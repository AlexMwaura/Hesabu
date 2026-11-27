package ke.hesabu.spike.etims

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZonedDateTime

class SaleToVscuTest {
    private fun sale(vararg lines: SaleLine) = TillSale(
        tradeInvoiceNo = "HQ-T1-000001",
        lines = lines.toList(),
        payment = PaymentType.MOBILE_MONEY,
        cashierId = "C01", cashierName = "Wanjiku",
        soldAt = ZonedDateTime.parse("2026-11-27T12:30:05+03:00[Africa/Nairobi]"),
    )

    @Test
    fun `16 percent VAT is extracted from an inclusive shelf price`() {
        assertThat(SaleToVscu.includedTax(BigDecimal("116.00"), TaxType.B.rate)).isEqualByComparingTo("16.00")
        assertThat(SaleToVscu.includedTax(BigDecimal("350.00"), TaxType.B.rate)).isEqualByComparingTo("48.28")
        assertThat(SaleToVscu.includedTax(BigDecimal("108.00"), TaxType.E.rate)).isEqualByComparingTo("8.00")
        assertThat(SaleToVscu.includedTax(BigDecimal("180.00"), TaxType.C.rate)).isEqualByComparingTo("0.00")
    }

    @Test
    fun `mixed basket fills per-tax-type totals and invoice fields`() {
        val req = SaleToVscu.build(
            sale(
                SaleLine("KE2NTU0000001", "5020230601", "Cooking oil 1L", BigDecimal(2), BigDecimal("350"), TaxType.B),
                SaleLine("KE2NTU0000002", "5010150201", "Maize flour 2kg", BigDecimal.ONE, BigDecimal("180"), TaxType.C),
            ),
            invcNo = 7, tin = "P000000000A", bhfId = "00", tradeName = "Duka",
        )
        assertThat(req.taxblAmtB).isEqualByComparingTo("700.00")
        assertThat(req.taxAmtB).isEqualByComparingTo("96.55")
        assertThat(req.taxblAmtC).isEqualByComparingTo("180.00")
        assertThat(req.totTaxblAmt).isEqualByComparingTo("880.00")
        assertThat(req.totTaxAmt).isEqualByComparingTo("96.55")
        assertThat(req.totAmt).isEqualByComparingTo("880.00")
        assertThat(req.taxRtB).isEqualByComparingTo("16")
        assertThat(req.totItemCnt).isEqualTo(2)
        assertThat(req.salesTyCd).isEqualTo("N")
        assertThat(req.rcptTyCd).isEqualTo("S")
        assertThat(req.pmtTyCd).isEqualTo("06")
        assertThat(req.cfmDt).isEqualTo("20261127123005")
        assertThat(req.salesDt).isEqualTo("20261127")
        assertThat(req.itemList.map { it.itemSeq }).containsExactly(1, 2)
    }

    @Test
    fun `scu block groups signatures by four and builds the KRA QR payload`() {
        val block = ScuReceiptBlock.of(
            SalesSignature(27, "GZGGIZLYTJSSD7YLYLGIIG6FCY", "TQZMKL57AGBMSTPO", 32, "20211027162114", "CU010000005"),
            label = "NS",
        )
        assertThat(block.lines).contains(
            "Date: 27/10/2021 Time: 16:21:14",
            "CU Invoice No.: CU010000005/27",
            "Receipt counter: 27/32 NS",
            "Internal Data: GZGG-IZLY-TJSS-D7YL-YLGI-IG6F-CY",
            "Receipt Signature: TQZM-KL57-AGBM-STPO",
        )
        assertThat(block.qrPayload).isEqualTo("27102021#162114#CU010000005#27#GZGGIZLYTJSSD7YLYLGIIG6FCY#TQZMKL57AGBMSTPO")
    }
}
