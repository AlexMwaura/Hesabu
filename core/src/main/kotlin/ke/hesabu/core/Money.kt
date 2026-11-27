package ke.hesabu.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is held as whole cents in a Long everywhere in Hesabu (database, APIs, events), so sums never
 * drift. These helpers convert at the edges, for example to the two-decimal amounts eTIMS wants.
 */
object Money {
    fun toDecimal(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    fun ofDecimal(amount: BigDecimal): Long = amount.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact()

    fun ofShillings(shillings: Long): Long = Math.multiplyExact(shillings, 100L)

    /** M-Pesa only moves whole shillings. */
    fun isWholeShillings(cents: Long) = cents % 100 == 0L

    /** Tax contained in a VAT-inclusive amount: gross × rate ÷ (100 + rate), half-up to the cent. */
    fun includedTax(grossCents: Long, ratePercent: BigDecimal): Long {
        if (ratePercent.signum() == 0) return 0
        return BigDecimal.valueOf(grossCents).multiply(ratePercent)
            .divide(BigDecimal(100).add(ratePercent), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /** qty × unit price, half-up to the cent. */
    fun lineTotal(qty: BigDecimal, unitPriceCents: Long): Long =
        qty.multiply(BigDecimal.valueOf(unitPriceCents)).setScale(0, RoundingMode.HALF_UP).longValueExact()
}

/** Quantities are stored as thousandths (1.5 kg = 1500) so loose goods sold by weight stay exact. */
object Qty {
    fun toMilli(qty: BigDecimal): Long {
        require(qty.stripTrailingZeros().scale() <= 3) { "Quantities have at most 3 decimals, got $qty" }
        return qty.setScale(3, RoundingMode.UNNECESSARY).unscaledValue().longValueExact()
    }

    fun ofMilli(milli: Long): BigDecimal = BigDecimal.valueOf(milli, 3)
}
