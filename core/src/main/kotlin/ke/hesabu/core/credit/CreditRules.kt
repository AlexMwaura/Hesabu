package ke.hesabu.core.credit

/** Deni (customer credit) rules. Balances are what the customer owes the shop, in cents. */
object CreditRules {
    fun canExtend(balanceCents: Long, limitCents: Long, amountCents: Long): Boolean =
        amountCents > 0 && balanceCents + amountCents <= limitCents

    fun headroom(balanceCents: Long, limitCents: Long): Long = maxOf(0, limitCents - balanceCents)
}
