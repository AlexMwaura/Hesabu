package ke.hesabu.core.stock

/** Stock movements, coded as KRA's Stock In/Out types (VSCU spec 4.15). */
enum class StockMovementType(val kraCode: String, val incoming: Boolean) {
    IMPORT("01", true),
    PURCHASE("02", true),
    RETURN_IN("03", true),
    TRANSFER_IN("04", true),
    PROCESSING_IN("05", true),
    ADJUSTMENT_IN("06", true),
    SALE("11", false),
    RETURN_OUT("12", false),
    TRANSFER_OUT("13", false),
    PROCESSING_OUT("14", false),
    DISCARD("15", false),
    ADJUSTMENT_OUT("16", false);

    /** The change to stock on hand for a movement of [qtyMilli] (always given as a positive number). */
    fun delta(qtyMilli: Long): Long {
        require(qtyMilli > 0) { "Movement quantities are positive; the type gives the direction" }
        return if (incoming) qtyMilli else -qtyMilli
    }
}

object StockRules {
    data class Shortfall(val productCode: String, val onHandMilli: Long, val requestedMilli: Long)

    /**
     * TIS spec 6.29: a receipt may not be issued for goods beyond the stock on hand.
     * [requested] maps product code to quantity in thousandths; services must be left out.
     */
    fun shortfalls(requested: Map<String, Long>, onHand: (String) -> Long): List<Shortfall> =
        requested.mapNotNull { (code, qty) ->
            val have = onHand(code)
            if (have < qty) Shortfall(code, have, qty) else null
        }
}
