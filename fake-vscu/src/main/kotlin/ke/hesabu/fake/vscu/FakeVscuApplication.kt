package ke.hesabu.fake.vscu

import ke.hesabu.core.etims.InitInfo
import ke.hesabu.core.etims.InitInfoData
import ke.hesabu.core.etims.InitInfoReq
import ke.hesabu.core.etims.SalesSignature
import ke.hesabu.core.etims.TrnsSalesSaveReq
import ke.hesabu.core.etims.VscuResponse
import ke.hesabu.core.etims.VscuResult
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A stand-in for KRA's VSCU JAR, so Hesabu can be built and tested before sandbox access.
 * It listens on the same port and paths as the real JAR, so switching is a URL change.
 *
 * TODO(kra-sandbox): replace with KRA's test VSCU JAR once the eTIMS sandbox registration is done,
 * then check the assumptions marked FAKE below against it.
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
class FakeVscuApplication

object FakeVscu {
    fun builder(): SpringApplicationBuilder =
        SpringApplicationBuilder(FakeVscuApplication::class.java).properties("spring.config.name=fake-vscu")
}

fun main(args: Array<String>) {
    FakeVscu.builder().run(*args)
}

/**
 * Models VSCU spec 2.2 item 4: "If there is no internet connection for 24 hours after VSCU issues a
 * receipt signature and an internal data, VSCU stops issuing a receipt number."
 *
 * FAKE assumptions: the error code after 24 hours (999), that the 24 hours start at the first receipt
 * not yet sent to KRA, and the signature format (random base32; the real ones are cryptographic).
 */
@Component
class FakeVscuState {
    val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    val offlineLimit: Duration = Duration.ofHours(24)

    private var offset: Duration = Duration.ZERO
    private var kraUp = true
    private var firstUnsentAt: Instant? = null
    private var unsent = 0
    private var uploaded = 0
    private var rcptNo = 0L
    private var lastInvcNo = 0L

    @Synchronized fun now(): Instant = Instant.now().plus(offset)
    @Synchronized fun lastInvoiceNo() = lastInvcNo
    @Synchronized fun lastReceiptNo() = rcptNo

    @Synchronized
    fun setKraReachable(up: Boolean) {
        kraUp = up
        if (up) {
            uploaded += unsent
            unsent = 0
            firstUnsentAt = null
        }
    }

    @Synchronized
    fun advance(d: Duration) { offset = offset.plus(d) }

    @Synchronized
    fun sign(req: TrnsSalesSaveReq): VscuResponse<SalesSignature> {
        val now = now()
        if (req.invcNo <= lastInvcNo) return error(VscuResult.OVERLAPPED, "There is an overlapped Data")
        val since = firstUnsentAt
        if (!kraUp && since != null && !now.isBefore(since.plus(offlineLimit))) {
            return error(VscuResult.UNKNOWN, "FAKE: no connection to KRA for 24 hours; receipt numbers stopped (VSCU spec 2.2.4)")
        }
        lastInvcNo = req.invcNo
        rcptNo += 1
        if (kraUp) uploaded += 1 else {
            unsent += 1
            if (firstUnsentAt == null) firstUnsentAt = now
        }
        val sig = SalesSignature(
            rcptNo = rcptNo,
            intrlData = random(26),
            rcptSign = random(16),
            totRcptNo = rcptNo,
            vscuRcptPbctDate = STAMP.format(now.atZone(zone)),
            sdcId = SDC_ID,
            mrcNo = "WIS00000001",
        )
        return VscuResponse(VscuResult.OK, "It is succeeded", STAMP.format(now.atZone(zone)), sig)
    }

    @Synchronized
    fun status() = mapOf(
        "kraReachable" to kraUp,
        "unsentReceipts" to unsent,
        "uploadedReceipts" to uploaded,
        "lastInvcNo" to lastInvcNo,
        "lastRcptNo" to rcptNo,
        "clock" to now().toString(),
        "receiptNumbersStopAt" to firstUnsentAt?.plus(offlineLimit)?.toString(),
    )

    private fun error(cd: String, msg: String) = VscuResponse<SalesSignature>(cd, msg, STAMP.format(now().atZone(zone)), null)

    private fun random(n: Int) = (1..n).map { B32[rng.nextInt(B32.length)] }.joinToString("")

    companion object {
        const val SDC_ID = "KRACU0400000001"
        private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val rng = SecureRandom()
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

@RestController
class FakeVscuController(private val state: FakeVscuState) {

    @PostMapping("/initializer/selectInitInfo")
    fun init(@RequestBody req: InitInfoReq) = VscuResponse(
        VscuResult.OK, "It is succeeded", null,
        InitInfoData(
            InitInfo(
                tin = req.tin, taxprNm = "HESABU TEST SHOP", bhfId = req.bhfId, bhfNm = "Headquarter",
                dvcId = "9990000000000001", sdcId = FakeVscuState.SDC_ID, mrcNo = "WIS00000001",
                lastSaleInvcNo = state.lastInvoiceNo(), lastSaleRcptNo = state.lastReceiptNo(),
            ),
        ),
    )

    @PostMapping("/trnsSales/saveSales")
    fun save(@RequestBody req: TrnsSalesSaveReq) = state.sign(req)

    /** Test control: cut or restore the VSCU's link to KRA. Not part of the real VSCU. */
    @PostMapping("/_fake/kra")
    fun kra(@RequestParam up: Boolean): Map<String, Any?> {
        state.setKraReachable(up)
        return state.status()
    }

    /** Test control: move the fake's clock forward. Not part of the real VSCU. */
    @PostMapping("/_fake/clock/advance")
    fun advance(@RequestParam minutes: Long): Map<String, Any?> {
        state.advance(Duration.ofMinutes(minutes))
        return state.status()
    }

    @GetMapping("/_fake/status")
    fun status() = state.status()
}
