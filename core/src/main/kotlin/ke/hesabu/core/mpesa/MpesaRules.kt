package ke.hesabu.core.mpesa

/** Status of an M-Pesa code a cashier attached to a sale. */
enum class ClaimStatus {
    /** Safaricom confirmed the money reached this business's till for at least the sale amount. */
    VERIFIED,
    /** No confirmation yet. Normal for a few seconds; worth checking if it lasts. */
    PENDING,
    AMOUNT_SHORT,
    /** The code is real but the money went to a different till or paybill. */
    WRONG_TILL,
    /** The code is already attached to another sale: one customer's message used twice. */
    CODE_ALREADY_USED,
}

object MpesaRules {
    private val CODE = Regex("^[A-Z0-9]{10}$")

    fun normalise(code: String) = code.trim().uppercase()

    fun isValidCode(code: String) = CODE.matches(normalise(code))

    data class Confirmation(val code: String, val amountCents: Long, val shortCode: String)

    fun evaluate(confirmation: Confirmation?, expectedAmountCents: Long, expectedShortCode: String): ClaimStatus = when {
        confirmation == null -> ClaimStatus.PENDING
        confirmation.shortCode != expectedShortCode -> ClaimStatus.WRONG_TILL
        confirmation.amountCents < expectedAmountCents -> ClaimStatus.AMOUNT_SHORT
        else -> ClaimStatus.VERIFIED
    }
}
