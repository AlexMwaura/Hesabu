package ke.hesabu.spike.etims

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Lets you ring up a sample sale by hand while toggling the fake VSCU's link to KRA. */
@RestController
@RequestMapping("/spike/etims")
class EtimsSpikeController(
    private val fiscal: FiscalReceiptService,
    private val stock: StockBook,
    private val vscu: VscuClient,
    private val props: VscuProperties,
    private val clock: Clock,
) {
    private val seq = AtomicInteger(0)

    @PostMapping("/init")
    fun init() = vscu.initInfo(InitInfoReq(props.tin, props.bhfId, props.deviceSerial))

    @PostMapping("/demo-sale")
    fun demoSale(): FiscalResult {
        listOf("KE2NTU0000001", "KE2NTU0000002").forEach {
            if (stock.onHand(it) < BigDecimal.ONE) stock.set(it, BigDecimal(100))
        }
        val n = seq.incrementAndGet()
        val sale = TillSale(
            tradeInvoiceNo = "HQ-T1-%06d".format(n),
            lines = listOf(
                SaleLine("KE2NTU0000001", "5020230601", "Cooking oil 1L", BigDecimal.ONE, BigDecimal("350"), TaxType.B),
                SaleLine("KE2NTU0000002", "5010150201", "Maize flour 2kg (zero-rated)", BigDecimal.ONE, BigDecimal("180"), TaxType.C),
            ),
            payment = PaymentType.MOBILE_MONEY,
            cashierId = "C01",
            cashierName = "Demo Cashier",
            soldAt = ZonedDateTime.now(clock),
        )
        return fiscal.sign(sale, tradeName = "Hesabu Spike Shop")
    }
}
