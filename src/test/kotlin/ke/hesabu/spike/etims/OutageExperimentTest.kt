package ke.hesabu.spike.etims

import ke.hesabu.spike.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The eTIMS spike's experiment, run over real HTTP against the fake VSCU:
 * how long can a shop keep issuing fiscal receipts when its internet to KRA is down?
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutageExperimentTest(
    @Autowired private val clock: MutableClock,
    @Autowired private val fakeVscu: FakeVscuState,
) {
    @TestConfiguration
    class Clocks {
        @Bean @Primary
        fun testClock() = MutableClock(Instant.parse("2026-11-27T05:00:00Z"))
    }

    @LocalServerPort private var port: Int = 0
    private lateinit var stock: StockBook
    private lateinit var fiscal: FiscalReceiptService

    private fun props(baseUrl: String) = VscuProperties(baseUrl, "P000000000A", "00", "spike-1", 500, 2000)

    private fun service(baseUrl: String) =
        FiscalReceiptService(VscuClient(props(baseUrl), RestClient.builder()), props(baseUrl), stock)

    private var n = 0
    private fun sale(qty: Int = 1) = TillSale(
        tradeInvoiceNo = "HQ-T1-%06d".format(++n),
        lines = listOf(SaleLine("KE2NTU0000001", "5020230601", "Cooking oil 1L", BigDecimal(qty), BigDecimal("350"), TaxType.B)),
        payment = PaymentType.CASH, cashierId = "C01", cashierName = "Wanjiku",
        soldAt = ZonedDateTime.now(clock),
    )

    @BeforeEach
    fun setUp() {
        stock = StockBook().apply { set("KE2NTU0000001", BigDecimal(1000)) }
        fiscal = service("http://localhost:$port/fake-vscu")
        fakeVscu.setKraReachable(true)
        fiscal.restoreSequence()
    }

    @Test
    fun `receipts keep printing through a 4 hour outage, stop at 24 hours, and resume when KRA is back`() {
        val first = fiscal.sign(sale()) as FiscalResult.Printable

        fakeVscu.setKraReachable(false)
        // A trading day with no internet: one sale every 30 minutes for 4 hours.
        repeat(8) {
            clock.advance(Duration.ofMinutes(30))
            val r = fiscal.sign(sale())
            assertThat(r).isInstanceOf(FiscalResult.Printable::class.java)
        }
        assertThat(fakeVscu.status()["unsentReceipts"]).isEqualTo(8)

        // Still down the next morning: 24 hours after the first unsigned-to-KRA receipt, the VSCU stops.
        clock.advance(Duration.ofHours(20).plusMinutes(30))
        val stopped = fiscal.sign(sale())
        assertThat(stopped).isInstanceOf(FiscalResult.Rejected::class.java)
        assertThat((stopped as FiscalResult.Rejected).resultCd).isEqualTo("999")

        // Internet returns: the queue uploads and selling resumes with the next invoice number.
        fakeVscu.setKraReachable(true)
        assertThat(fakeVscu.status()["unsentReceipts"]).isEqualTo(0)
        val resumed = fiscal.sign(sale()) as FiscalResult.Printable
        // 1 online + 8 offline sales signed; the rejected one used no number.
        assertThat(resumed.request.invcNo).isEqualTo(first.request.invcNo + 9)
    }

    @Test
    fun `a till that restarts continues the VSCU's invoice numbering`() {
        val signed = fiscal.sign(sale()) as FiscalResult.Printable
        val restarted = service("http://localhost:$port/fake-vscu")
        assertThat(restarted.restoreSequence()).isEqualTo(signed.request.invcNo)
        val next = restarted.sign(sale()) as FiscalResult.Printable
        assertThat(next.request.invcNo).isEqualTo(signed.request.invcNo + 1)
    }

    @Test
    fun `no receipt when the VSCU itself is unreachable`() {
        val unreachable = service("http://localhost:1/fake-vscu")
        val r = unreachable.sign(sale())
        assertThat(r).isInstanceOf(FiscalResult.Blocked::class.java)
        assertThat(stock.onHand("KE2NTU0000001")).isEqualByComparingTo("1000")
    }

    @Test
    fun `goods beyond stock on hand are refused before the VSCU is called`() {
        stock.set("KE2NTU0000001", BigDecimal(2))
        val r = fiscal.sign(sale(qty = 3))
        assertThat(r).isEqualTo(FiscalResult.InsufficientStock("KE2NTU0000001", BigDecimal(2), BigDecimal(3)))
    }

    @Test
    fun `stock is released only for signed sales`() {
        stock.set("KE2NTU0000001", BigDecimal(5))
        assertThat(fiscal.sign(sale(qty = 2))).isInstanceOf(FiscalResult.Printable::class.java)
        assertThat(stock.onHand("KE2NTU0000001")).isEqualByComparingTo("3")
    }
}
