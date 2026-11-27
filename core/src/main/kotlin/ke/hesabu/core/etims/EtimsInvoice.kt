package ke.hesabu.core.etims

import ke.hesabu.core.Money
import ke.hesabu.core.TaxType
import ke.hesabu.core.sale.PricedLine
import ke.hesabu.core.sale.SaleTotals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Who and what an invoice is for, apart from its lines. */
data class InvoiceHeader(
    val tin: String,
    val bhfId: String,
    val tradeInvoiceNo: String,
    val cashierId: String,
    val cashierName: String,
    val at: ZonedDateTime,
    val paymentCode: String,
    val tradeName: String?,
    val customerPin: String? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
)

/** Builds the VSCU sales request, including the per-tax-type totals KRA wants on every invoice. */
object EtimsInvoice {
    private val DT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val D: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun sale(h: InvoiceHeader, lines: List<PricedLine>, invcNo: Long): TrnsSalesSaveReq =
        build(h, lines, invcNo, ReceiptType.SALE, originalInvcNo = 0, reason = null)

    /**
     * A credit note for (part of) an earlier sale. Amounts are positive; the receipt type says it is a refund.
     * TODO(kra-sandbox): confirm the VSCU wants positive amounts and the original's invcNo in orgInvcNo.
     */
    fun creditNote(h: InvoiceHeader, lines: List<PricedLine>, invcNo: Long, originalInvcNo: Long, reason: CreditNoteReason): TrnsSalesSaveReq =
        build(h, lines, invcNo, ReceiptType.CREDIT_NOTE, originalInvcNo, reason)

    private fun build(
        h: InvoiceHeader,
        lines: List<PricedLine>,
        invcNo: Long,
        type: ReceiptType,
        originalInvcNo: Long,
        reason: CreditNoteReason?,
    ): TrnsSalesSaveReq {
        require(lines.isNotEmpty()) { "A receipt needs at least one item (TIS spec 6.15)" }
        val totals = SaleTotals.of(lines)
        val stamp = DT.format(h.at)

        val items = lines.mapIndexed { i, l ->
            val total = Money.toDecimal(l.totalCents)
            SalesItem(
                itemSeq = i + 1,
                itemCd = l.productCode,
                itemClsCd = l.itemClassCode,
                itemNm = l.name,
                bcd = l.barcode,
                pkgUnitCd = l.pkgUnitCode,
                pkg = l.qty,
                qtyUnitCd = l.qtyUnitCode,
                qty = l.qty,
                prc = Money.toDecimal(l.unitPriceCents),
                splyAmt = total,
                dcRt = ZERO,
                dcAmt = ZERO,
                taxTyCd = l.taxType.name,
                taxblAmt = total,
                taxAmt = Money.toDecimal(l.taxCents),
                totAmt = total,
            )
        }

        fun taxable(t: TaxType) = Money.toDecimal(totals.buckets.getValue(t).taxableCents)
        fun tax(t: TaxType) = Money.toDecimal(totals.buckets.getValue(t).taxCents)

        return TrnsSalesSaveReq(
            tin = h.tin,
            bhfId = h.bhfId,
            trdInvcNo = h.tradeInvoiceNo,
            invcNo = invcNo,
            orgInvcNo = originalInvcNo,
            custTin = h.customerPin,
            custNm = h.customerName,
            salesTyCd = "N",
            rcptTyCd = type.code,
            pmtTyCd = h.paymentCode,
            salesSttsCd = "02",
            cfmDt = stamp,
            salesDt = D.format(h.at),
            stockRlsDt = stamp,
            rfdDt = if (type == ReceiptType.CREDIT_NOTE) stamp else null,
            rfdRsnCd = reason?.code,
            totItemCnt = items.size,
            taxblAmtA = taxable(TaxType.A), taxblAmtB = taxable(TaxType.B), taxblAmtC = taxable(TaxType.C),
            taxblAmtD = taxable(TaxType.D), taxblAmtE = taxable(TaxType.E),
            taxRtA = TaxType.A.rate, taxRtB = TaxType.B.rate, taxRtC = TaxType.C.rate,
            taxRtD = TaxType.D.rate, taxRtE = TaxType.E.rate,
            taxAmtA = tax(TaxType.A), taxAmtB = tax(TaxType.B), taxAmtC = tax(TaxType.C),
            taxAmtD = tax(TaxType.D), taxAmtE = tax(TaxType.E),
            totTaxblAmt = Money.toDecimal(totals.totalCents),
            totTaxAmt = Money.toDecimal(totals.taxCents),
            totAmt = Money.toDecimal(totals.totalCents),
            regrId = h.cashierId, regrNm = h.cashierName,
            modrId = h.cashierId, modrNm = h.cashierName,
            receipt = SalesReceipt(h.customerPin, h.customerPhone, invcNo, h.tradeName, null, null, null),
            itemList = items,
        )
    }

    private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)
}
