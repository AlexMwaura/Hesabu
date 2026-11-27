package ke.hesabu.hub.sales

import ke.hesabu.core.etims.CreditNoteReason
import ke.hesabu.core.sale.Tender
import java.math.BigDecimal

/** A line as the till sends it: by product code or by scanned barcode. */
data class SaleLineRequest(val productCode: String? = null, val barcode: String? = null, val qty: BigDecimal)

data class SaleRequest(
    /** The till's own id for this sale. Retrying with the same value never creates a second sale. */
    val clientRef: String,
    val lines: List<SaleLineRequest>,
    val tenders: List<Tender>,
    /** Needed for credit (deni) tenders; also puts the customer on the receipt. */
    val customerId: String? = null,
    /** For business buyers who want their KRA PIN on the invoice. */
    val customerPin: String? = null,
    val customerName: String? = null,
)

enum class RefundMethod(val etimsPaymentCode: String) { CASH("01"), DENI("02"), MPESA("06") }

data class ReturnLine(val productCode: String, val qty: BigDecimal)

data class CreditNoteRequest(
    val clientRef: String,
    val lines: List<ReturnLine>,
    val reason: CreditNoteReason,
    /** How the customer gets the money back. MPESA means the shop sends it back by M-Pesa outside Hesabu. */
    val refundMethod: RefundMethod,
    /** Put the goods back on the shelf. False for damaged or expired returns. */
    val restock: Boolean = true,
)

enum class SaleStatus {
    /** Written before the VSCU call. */
    SIGNING,
    SIGNED,
    /** The VSCU could not be reached; nothing was signed. The till may retry. */
    BLOCKED,
    /** The VSCU refused, for example after 24 hours without reaching KRA. The till may retry later. */
    REJECTED,
    /** The VSCU may have signed but no answer came back. Resolved on retry. */
    UNCERTAIN,
    /** The VSCU signed it but the hub never got the signature. Needs support. */
    NEEDS_ATTENTION,
}

data class ReceiptLine(val productCode: String, val name: String, val qty: BigDecimal, val unitPriceCents: Long, val totalCents: Long, val taxType: String)
data class ReceiptTax(val taxType: String, val label: String, val ratePercent: BigDecimal, val taxableCents: Long, val taxCents: Long)
data class ReceiptPayment(val method: String, val amountCents: Long, val mpesaCode: String?, val mpesaStatus: String?)

/** Everything the till needs to print a fiscal receipt. */
data class ReceiptView(
    val saleId: String,
    val clientRef: String,
    val receiptType: String,
    /** NS, NC, or CS/CC for a reprint (TIS spec 6.23). */
    val label: String,
    val copy: Boolean,
    val tradeName: String?,
    val kraPin: String,
    val bhfId: String,
    val tradeInvoiceNo: String,
    val invoiceNo: Long,
    val originalInvoiceNo: Long?,
    val issuedAt: String,
    val cashierName: String,
    val customerName: String?,
    val customerPin: String?,
    val lines: List<ReceiptLine>,
    val taxes: List<ReceiptTax>,
    val totalCents: Long,
    val taxCents: Long,
    val payments: List<ReceiptPayment>,
    val scuLines: List<String>,
    val qrPayload: String,
)
