package ke.hesabu.spike.etims

import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A stand-in for KRA's VSCU JAR so the spike runs before sandbox access is granted.
 *
 * It models the one rule that decides Hesabu's offline design, from the VSCU spec section 2.2 item 4:
 * "If there is no internet connection for 24 hours after VSCU issues a receipt signature and an internal
 * data, VSCU stops issuing a receipt number."
 *
 * Assumptions to check against the real JAR: the error code returned after the 24 hours (999 here),
 * whether the clock starts at the first unsent signature (as modelled) or at the last successful upload,
 * and what the signatures look like (random base32 here; the real ones are cryptographic).
 */
@Component
class FakeVscuState(private val clock: Clock) {
    val offlineLimit: Duration = Duration.ofHours(24)

    private var kraUp = true
    val kraReachable get() = kraUp
    private var firstUnsentAt: Instant? = null
    private val unsent = mutableListOf<Long>()
    private var rcptNo = 0L
    private var lastInvcNo = 0L
    private var uploaded = 0

    @Synchronized
    fun lastInvoiceNo() = lastInvcNo

    @Synchronized
    fun lastReceiptNo() = rcptNo

    @Synchronized
    fun setKraReachable(up: Boolean) {
        kraUp = up
        if (up) flush()
    }

    @Synchronized
    fun sign(req: TrnsSalesSaveReq): VscuResponse<SalesSignature> {
        val now = clock.instant()
        if (req.invcNo <= lastInvcNo) return error("994", "There is an overlapped Data")
        val since = firstUnsentAt
        if (!kraReachable && since != null && !now.isBefore(since.plus(offlineLimit))) {
            return error("999", "FAKE: no connection to KRA for 24 hours; receipt numbers stopped (VSCU spec 2.2.4)")
        }
        lastInvcNo = req.invcNo
        rcptNo += 1
        val sig = SalesSignature(
            rcptNo = rcptNo,
            intrlData = random(26),
            rcptSign = random(16),
            totRcptNo = rcptNo,
            vscuRcptPbctDate = STAMP.format(now.atZone(clock.zone)),
            sdcId = "KRACU0400000001",
            mrcNo = "WIS00000001",
        )
        if (kraReachable) {
            uploaded += 1
        } else {
            unsent += rcptNo
            if (firstUnsentAt == null) firstUnsentAt = now
        }
        return VscuResponse("000", "It is succeeded", STAMP.format(now.atZone(clock.zone)), sig)
    }

    @Synchronized
    fun status() = mapOf(
        "kraReachable" to kraReachable,
        "unsentReceipts" to unsent.size,
        "uploadedReceipts" to uploaded,
        "firstUnsentAt" to firstUnsentAt?.toString(),
        "receiptNumbersStopAt" to firstUnsentAt?.plus(offlineLimit)?.toString(),
    )

    private fun flush() {
        uploaded += unsent.size
        unsent.clear()
        firstUnsentAt = null
    }

    private fun error(cd: String, msg: String) =
        VscuResponse<SalesSignature>(cd, msg, STAMP.format(clock.instant().atZone(clock.zone)), null)

    private fun random(n: Int) = (1..n).map { B32[rng.nextInt(B32.length)] }.joinToString("")

    companion object {
        private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val rng = SecureRandom()
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

@RestController
@RequestMapping("/fake-vscu")
class FakeVscuController(private val state: FakeVscuState) {

    @PostMapping("/initializer/selectInitInfo")
    fun init(@RequestBody req: InitInfoReq) = VscuResponse(
        "000", "It is succeeded", null,
        InitInfoData(InitInfo(tin = req.tin, taxprNm = "HESABU SPIKE SHOP", bhfId = req.bhfId, bhfNm = "Headquarter",
            dvcId = "9990000000000001", sdcId = "KRACU0400000001", mrcNo = "WIS00000001",
            lastSaleInvcNo = state.lastInvoiceNo(), lastSaleRcptNo = state.lastReceiptNo())),
    )

    @PostMapping("/trnsSales/saveSales")
    fun save(@RequestBody req: TrnsSalesSaveReq) = state.sign(req)

    /** Spike control: cut or restore the VSCU's link to KRA. */
    @PostMapping("/_control/kra")
    fun kra(@RequestParam up: Boolean) = state.setKraReachable(up).let { state.status() }

    @GetMapping("/_control/status")
    fun status() = state.status()
}
