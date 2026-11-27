package ke.hesabu.core

import java.math.BigDecimal

/** eTIMS tax types (VSCU spec 4.1). Rates in percent. */
enum class TaxType(val rate: BigDecimal, val label: String) {
    A(BigDecimal("0.00"), "Exempt"),
    B(BigDecimal("16.00"), "VAT 16%"),
    C(BigDecimal("0.00"), "Zero-rated"),
    D(BigDecimal("0.00"), "Non-VAT"),
    E(BigDecimal("8.00"), "VAT 8%"),
}
