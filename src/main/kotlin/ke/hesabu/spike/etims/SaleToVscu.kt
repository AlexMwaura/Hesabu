package ke.hesabu.spike.etims

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One line on a till sale. [unitPrice] is the shelf price, VAT included, as Kenyan shops price goods. */
data class SaleLine(
    val itemCd: String,
    val itemClsCd: String,
    val name: String,
    val qty: BigDecimal,
    val unitPrice: BigDecimal,
    val taxType: TaxType,
    val barcode: String? = null,
    val isService: Boolean = false,
    val qtyUnitCd: String = "U",
    val pkgUnitCd: String = "NT",
)

data class TillSale(
    val tradeInvoiceNo: String,     // the till's own number, e.g. CBD-T1-000153
    val lines: List<SaleLine>,
    val payment: PaymentType,
    val cashierId: String,
    val cashierName: String,
    val soldAt: ZonedDateTime,
    val customerPin: String? = null,
    val customerName: String? = null,
)

/** Builds the VSCU sales request, including the per-tax-type totals KRA wants on every invoice. */
object SaleToVscu {
    private val DT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val D: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val ZERO = BigDecimal.ZERO.setScale(2)

    /** Tax contained in a VAT-inclusive amount: amount × rate ÷ (100 + rate). */
    fun includedTax(amount: BigDecimal, rate: BigDecimal): BigDecimal =
        if (rate.signum() == 0) ZERO
        else amount.multiply(rate).divide(BigDecimal(100).add(rate), 2, RoundingMode.HALF_UP)

    fun build(sale: TillSale, invcNo: Long, tin: String, bhfId: String, tradeName: String?): TrnsSalesSaveReq {
        require(sale.lines.isNotEmpty()) { "A receipt needs at least one item (TIS spec 6.15)" }

        val items = sale.lines.mapIndexed { i, l ->
            val supply = l.qty.multiply(l.unitPrice).setScale(2, RoundingMode.HALF_UP)
            val tax = includedTax(supply, l.taxType.rate)
            SalesItem(
                itemSeq = i + 1,
                itemCd = l.itemCd,
                itemClsCd = l.itemClsCd,
                itemNm = l.name,
                bcd = l.barcode,
                pkgUnitCd = l.pkgUnitCd,
                pkg = l.qty,
                qtyUnitCd = l.qtyUnitCd,
                qty = l.qty,
                prc = l.unitPrice.setScale(2, RoundingMode.HALF_UP),
                splyAmt = supply,
                dcRt = ZERO,
                dcAmt = ZERO,
                taxTyCd = l.taxType.name,
                taxblAmt = supply,
                taxAmt = tax,
                totAmt = supply,
            )
        }

        fun taxable(t: TaxType) = items.filter { it.taxTyCd == t.name }.fold(ZERO) { a, it -> a + it.taxblAmt }
        fun tax(t: TaxType) = items.filter { it.taxTyCd == t.name }.fold(ZERO) { a, it -> a + it.taxAmt }

        val totalTaxable = items.fold(ZERO) { a, it -> a + it.taxblAmt }
        val totalTax = items.fold(ZERO) { a, it -> a + it.taxAmt }
        val stamp = DT.format(sale.soldAt)

        return TrnsSalesSaveReq(
            tin = tin, bhfId = bhfId,
            trdInvcNo = sale.tradeInvoiceNo,
            invcNo = invcNo,
            orgInvcNo = 0,
            custTin = sale.customerPin, custNm = sale.customerName,
            salesTyCd = "N", rcptTyCd = "S",
            pmtTyCd = sale.payment.code,
            salesSttsCd = "02",
            cfmDt = stamp, salesDt = D.format(sale.soldAt), stockRlsDt = stamp,
            totItemCnt = items.size,
            taxblAmtA = taxable(TaxType.A), taxblAmtB = taxable(TaxType.B), taxblAmtC = taxable(TaxType.C),
            taxblAmtD = taxable(TaxType.D), taxblAmtE = taxable(TaxType.E),
            taxRtA = TaxType.A.rate, taxRtB = TaxType.B.rate, taxRtC = TaxType.C.rate, taxRtD = TaxType.D.rate, taxRtE = TaxType.E.rate,
            taxAmtA = tax(TaxType.A), taxAmtB = tax(TaxType.B), taxAmtC = tax(TaxType.C),
            taxAmtD = tax(TaxType.D), taxAmtE = tax(TaxType.E),
            totTaxblAmt = totalTaxable, totTaxAmt = totalTax, totAmt = totalTaxable,
            regrId = sale.cashierId, regrNm = sale.cashierName, modrId = sale.cashierId, modrNm = sale.cashierName,
            receipt = SalesReceipt(sale.customerPin, null, invcNo, tradeName, null, null, null),
            itemList = items,
        )
    }
}
