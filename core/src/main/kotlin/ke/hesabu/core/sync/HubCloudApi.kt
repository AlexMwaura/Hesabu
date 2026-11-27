package ke.hesabu.core.sync

import ke.hesabu.core.mpesa.ClaimStatus

/*
 * Messages between an in-shop hub and the cloud server. The hub is the source of truth for what was
 * sold and signed in its branch; the cloud is the source of truth for the catalogue and for M-Pesa
 * confirmations, which Safaricom can only deliver to a public HTTPS address.
 */

object EventTypes {
    const val SALE_RECORDED = "SaleRecorded"
    const val STOCK_LEVELS = "StockLevelsChanged"
    const val SHIFT_CLOSED = "ShiftClosed"
    const val CUSTOMER_UPDATED = "CustomerUpdated"
}

/** A signed sale or credit note. Amounts in cents, quantities in thousandths. */
data class SaleRecorded(
    val saleId: String,
    val receiptType: String,
    val originalSaleId: String?,
    val tradeInvoiceNo: String,
    val invcNo: Long,
    val shiftId: String,
    val tillId: String,
    val cashierId: String,
    val cashierName: String,
    val signedAt: String,
    val customerId: String?,
    val totalCents: Long,
    val taxCents: Long,
    val lines: List<SaleLineRecord>,
    val tenders: List<TenderRecord>,
    val scu: ScuRecord,
)

data class SaleLineRecord(
    val productCode: String,
    val name: String,
    val qtyMilli: Long,
    val unitPriceCents: Long,
    val totalCents: Long,
    val taxType: String,
)

data class TenderRecord(val method: String, val amountCents: Long, val mpesaCode: String?)

data class ScuRecord(val sdcId: String, val rcptNo: Long, val totRcptNo: Long, val intrlData: String, val rcptSign: String, val signedAt: String)

data class StockLevels(val levels: List<StockLevel>)

data class StockLevel(val productCode: String, val onHandMilli: Long, val reorderLevelMilli: Long)

data class ShiftClosed(
    val shiftId: String,
    val tillId: String,
    val cashierId: String,
    val cashierName: String,
    val openedAt: String,
    val closedAt: String,
    val floatCents: Long,
    val expectedCashCents: Long,
    val countedCashCents: Long,
    val varianceCents: Long,
    val salesCount: Int,
    val salesTotalCents: Long,
)

data class CustomerUpdated(
    val customerId: String,
    val name: String,
    val phone: String?,
    val creditLimitCents: Long,
    val balanceCents: Long,
)

data class EventBatchResult(val accepted: List<String>)

data class CatalogueItem(
    val code: String,
    val itemClassCode: String,
    val name: String,
    val barcode: String?,
    val priceCents: Long,
    val taxType: String,
    val isService: Boolean,
    val qtyUnitCode: String,
    val pkgUnitCode: String,
    val reorderLevelMilli: Long,
    val active: Boolean,
    val version: Long,
)

data class CataloguePage(val items: List<CatalogueItem>, val latestVersion: Long)

data class MpesaCheckRequest(val code: String, val amountCents: Long)

/** [ref] is the hub's id for what the money pays for: a sale, or a deni repayment. */
data class MpesaClaimRequest(val ref: String, val code: String, val amountCents: Long)

data class MpesaClaimResult(
    val code: String,
    val status: ClaimStatus,
    val confirmedAmountCents: Long? = null,
    val claimedByRef: String? = null,
)

data class MpesaStatusRequest(val codes: List<String>)

data class StkStartRequest(val ref: String, val phone: String, val amountCents: Long)

enum class StkState { WAITING, PAID, FAILED }

data class StkStatus(
    val checkoutRequestId: String,
    val state: StkState,
    val amountCents: Long,
    val mpesaCode: String? = null,
    val resultDesc: String? = null,
)
