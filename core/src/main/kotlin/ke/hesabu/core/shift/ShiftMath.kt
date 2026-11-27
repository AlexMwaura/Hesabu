package ke.hesabu.core.shift

/** Cash a drawer should hold at close, from what happened during the shift. All in cents. */
data class CashFlows(
    val floatCents: Long,
    val cashSalesCents: Long,
    val cashRefundsCents: Long,
    val cashDeniRepaymentsCents: Long,
) {
    val expectedCents: Long get() = floatCents + cashSalesCents - cashRefundsCents + cashDeniRepaymentsCents

    /** Positive: more cash than expected. Negative: cash is short. */
    fun varianceCents(countedCents: Long): Long = countedCents - expectedCents
}
