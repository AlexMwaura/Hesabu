package ke.hesabu.core.sale

import ke.hesabu.core.Money
import ke.hesabu.core.TaxType
import java.math.BigDecimal

enum class TenderMethod { CASH, MPESA_CODE, MPESA_STK, CREDIT }

/**
 * One way a sale was paid. [amountCents] is what this tender pays towards the sale; change given
 * back for cash is the till's business and is not recorded here.
 */
data class Tender(
    val method: TenderMethod,
    val amountCents: Long,
    /** The M-Pesa transaction code the cashier typed (MPESA_CODE) or the one STK returned (MPESA_STK). */
    val mpesaCode: String? = null,
    /** For MPESA_STK: the CheckoutRequestID of the prompt that was paid. */
    val checkoutRequestId: String? = null,
)

/** A sale line with its price fixed. Prices are shelf prices, VAT included, as Kenyan shops price goods. */
data class PricedLine(
    val productCode: String,
    val itemClassCode: String,
    val name: String,
    val barcode: String?,
    val qty: BigDecimal,
    val unitPriceCents: Long,
    val taxType: TaxType,
    val isService: Boolean,
    val qtyUnitCode: String,
    val pkgUnitCode: String,
) {
    val totalCents: Long get() = Money.lineTotal(qty, unitPriceCents)
    val taxCents: Long get() = Money.includedTax(totalCents, taxType.rate)
}

data class TaxBucket(val taxType: TaxType, val taxableCents: Long, val taxCents: Long)

data class SaleTotals(val totalCents: Long, val taxCents: Long, val buckets: Map<TaxType, TaxBucket>) {
    companion object {
        fun of(lines: List<PricedLine>): SaleTotals {
            val buckets = TaxType.entries.associateWith { t ->
                val ls = lines.filter { it.taxType == t }
                TaxBucket(t, ls.sumOf { it.totalCents }, ls.sumOf { it.taxCents })
            }
            return SaleTotals(lines.sumOf { it.totalCents }, lines.sumOf { it.taxCents }, buckets)
        }
    }
}
