package ke.hesabu.core.etims

/** Sales receipt types (VSCU spec 4.9) and the labels TIS spec 6.23 prints for originals and copies. */
enum class ReceiptType(val code: String, val originalLabel: String, val copyLabel: String) {
    SALE("S", "NS", "CS"),
    CREDIT_NOTE("R", "NC", "CC"),
}

/** Credit note reason codes (VSCU spec 4.16). */
enum class CreditNoteReason(val code: String) {
    MISSING_QUANTITY("01"),
    MISSING_ITEM("02"),
    DAMAGED("03"),
    WASTED("04"),
    RAW_MATERIAL_SHORTAGE("05"),
    REFUND("06"),
    WRONG_CUSTOMER_PIN("07"),
    WRONG_CUSTOMER_NAME("08"),
    WRONG_AMOUNT_OR_PRICE("09"),
    WRONG_QUANTITY("10"),
    WRONG_ITEMS("11"),
    WRONG_TAX_TYPE("12"),
    OTHER("13"),
}

/**
 * Item codes per VSCU spec 4.17: country of origin, product type, packaging unit, quantity unit,
 * then a 7-digit serial. Example: KE2NTBA0000012.
 */
object ItemCode {
    fun of(serial: Long, country: String = "KE", productType: Int = 2, pkgUnit: String = "NT", qtyUnit: String = "U"): String {
        require(serial in 1..9_999_999) { "Item serial must be 1 to 9,999,999" }
        return "$country$productType$pkgUnit$qtyUnit${serial.toString().padStart(7, '0')}"
    }
}
