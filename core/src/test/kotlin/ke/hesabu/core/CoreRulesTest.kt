package ke.hesabu.core

import ke.hesabu.core.credit.CreditRules
import ke.hesabu.core.etims.CreditNoteReason
import ke.hesabu.core.etims.EtimsInvoice
import ke.hesabu.core.etims.InvoiceHeader
import ke.hesabu.core.etims.ItemCode
import ke.hesabu.core.etims.SalesSignature
import ke.hesabu.core.etims.ScuReceiptBlock
import ke.hesabu.core.mpesa.ClaimStatus
import ke.hesabu.core.mpesa.MpesaRules
import ke.hesabu.core.sale.PricedLine
import ke.hesabu.core.sale.Tender
import ke.hesabu.core.sale.TenderMethod
import ke.hesabu.core.sale.TenderRules
import ke.hesabu.core.shift.CashFlows
import ke.hesabu.core.stock.StockMovementType
import ke.hesabu.core.stock.StockRules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

class CoreRulesTest {
    private fun line(code: String, qty: String, priceCents: Long, tax: TaxType) =
        PricedLine(code, "5020230601", code, null, BigDecimal(qty), priceCents, tax, false, "U", "NT")

    private val header = InvoiceHeader(
        "P000000000A", "00", "HQ-000001", "C01", "Wanjiku",
        ZonedDateTime.of(2026, 11, 27, 9, 30, 0, 0, ZoneId.of("Africa/Nairobi")), "01", "Duka",
    )

    @Test fun `VAT is worked out of shelf prices`() {
        assertThat(Money.includedTax(35_000, TaxType.B.rate)).isEqualTo(4_828)
        assertThat(Money.includedTax(35_000, TaxType.C.rate)).isZero()
        assertThat(Money.lineTotal(BigDecimal("1.5"), 12_000)).isEqualTo(18_000)
        assertThat(Qty.toMilli(BigDecimal("1.5"))).isEqualTo(1_500)
    }

    @Test fun `sale invoice carries per tax type totals`() {
        val req = EtimsInvoice.sale(header, listOf(line("OIL", "2", 35_000, TaxType.B), line("UNGA", "1", 21_000, TaxType.C)), 7)
        assertThat(req.rcptTyCd).isEqualTo("S")
        assertThat(req.taxblAmtB).isEqualByComparingTo("700.00")
        assertThat(req.taxAmtB).isEqualByComparingTo("96.55")
        assertThat(req.taxblAmtC).isEqualByComparingTo("210.00")
        assertThat(req.totAmt).isEqualByComparingTo("910.00")
        assertThat(req.cfmDt).isEqualTo("20261127093000")
    }

    @Test fun `credit note references the original invoice`() {
        val req = EtimsInvoice.creditNote(header, listOf(line("OIL", "1", 35_000, TaxType.B)), 8, 7, CreditNoteReason.DAMAGED)
        assertThat(req.rcptTyCd).isEqualTo("R")
        assertThat(req.orgInvcNo).isEqualTo(7)
        assertThat(req.rfdRsnCd).isEqualTo("03")
    }

    @Test fun `receipt block dashes signatures and builds the QR`() {
        val sig = SalesSignature(12, "ABCDEFGHIJ", "WXYZ1234", 40, "20261127093005", "KRACU0400000001")
        val block = ScuReceiptBlock.of(sig, "NS")
        assertThat(block.lines).contains("Internal Data: ABCD-EFGH-IJ", "Receipt counter: 12/40 NS")
        assertThat(block.qrPayload).isEqualTo("27112026#093005#KRACU0400000001#12#ABCDEFGHIJ#WXYZ1234")
        assertThat(ItemCode.of(12, pkgUnit = "NT", qtyUnit = "BA")).isEqualTo("KE2NTBA0000012")
    }

    @Test fun `tenders must add up and follow method rules`() {
        val ok = listOf(Tender(TenderMethod.CASH, 50_000), Tender(TenderMethod.MPESA_CODE, 41_000, "qh12ab34cd"))
        assertThat(TenderRules.problems(91_000, ok, null)).isEmpty()
        assertThat(TenderRules.problems(90_000, ok, null)).anyMatch { it.startsWith("Payments add up") }
        assertThat(TenderRules.problems(1_000, listOf(Tender(TenderMethod.CREDIT, 1_000)), null)).hasSize(1)
        assertThat(TenderRules.problems(1_050, listOf(Tender(TenderMethod.MPESA_CODE, 1_050, "QH12AB34CD")), null))
            .anyMatch { it.contains("whole shillings") }
        assertThat(TenderRules.etimsPaymentCode(listOf(Tender(TenderMethod.CASH, 1), Tender(TenderMethod.CREDIT, 1)))).isEqualTo("03")
        assertThat(TenderRules.etimsPaymentCode(listOf(Tender(TenderMethod.MPESA_STK, 1)))).isEqualTo("06")
    }

    @Test fun `M-Pesa claims verify only for full amount on the right till`() {
        val conf = MpesaRules.Confirmation("QH12AB34CD", 41_000, "600000")
        assertThat(MpesaRules.evaluate(conf, 41_000, "600000")).isEqualTo(ClaimStatus.VERIFIED)
        assertThat(MpesaRules.evaluate(conf, 42_000, "600000")).isEqualTo(ClaimStatus.AMOUNT_SHORT)
        assertThat(MpesaRules.evaluate(conf, 41_000, "600111")).isEqualTo(ClaimStatus.WRONG_TILL)
        assertThat(MpesaRules.evaluate(null, 41_000, "600000")).isEqualTo(ClaimStatus.PENDING)
    }

    @Test fun `stock, deni and shift rules`() {
        assertThat(StockRules.shortfalls(mapOf("OIL" to 3_000, "UNGA" to 1_000)) { if (it == "OIL") 2_000 else 5_000 })
            .containsExactly(StockRules.Shortfall("OIL", 2_000, 3_000))
        assertThat(StockMovementType.SALE.delta(1_000)).isEqualTo(-1_000)
        assertThat(StockMovementType.PURCHASE.kraCode).isEqualTo("02")
        assertThatThrownBy { StockMovementType.SALE.delta(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CreditRules.canExtend(4_000_00, 5_000_00, 1_000_00)).isTrue()
        assertThat(CreditRules.canExtend(4_000_00, 5_000_00, 1_000_01)).isFalse()
        assertThat(CashFlows(2_000_00, 10_000_00, 500_00, 1_000_00).varianceCents(12_400_00)).isEqualTo(-100_00)
    }
}
