package ke.hesabu.spike.daraja

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The core of the Daraja spike: match M-Pesa codes a cashier types at the till against the
 * confirmations Safaricom sends, so a sale is only "verified" when the money really arrived.
 *
 * In-memory only. A real build keeps both sides in Postgres with unique keys on the M-Pesa code.
 */
@Component
class PaymentMatcher(private val clock: Clock) {

    enum class Source { C2B, STK }

    data class Confirmation(
        val code: String,
        val amount: Long,
        val shortCode: String,
        val source: Source,
        val receivedAt: Instant,
    )

    enum class Status { VERIFIED, PENDING, AMOUNT_SHORT, WRONG_TILL, CODE_ALREADY_USED }

    data class Claim(
        val saleId: String,
        val code: String,
        val expectedAmount: Long,
        val expectedShortCode: String,
        val claimedAt: Instant,
        val status: Status,
        val confirmedAmount: Long? = null,
    )

    data class ShiftReport(
        val verified: List<Claim>,
        val pending: List<Claim>,
        val problems: List<Claim>,
        /** Money that arrived on the till but is attached to no sale. */
        val unclaimed: List<Confirmation>,
        val verifiedTotal: Long,
        val pendingTotal: Long,
    )

    private val confirmations = ConcurrentHashMap<String, Confirmation>()
    private val claimsBySale = ConcurrentHashMap<String, Claim>()
    private val saleByCode = ConcurrentHashMap<String, String>()

    /**
     * Records a confirmation from Safaricom. Safaricom retries callbacks, so the same code may arrive
     * more than once; the first one wins and later copies are ignored.
     * @return true if this was the first time the code was seen.
     */
    @Synchronized
    fun onConfirmation(code: String, amount: Long, shortCode: String, source: Source): Boolean {
        val key = normalise(code)
        if (confirmations.containsKey(key)) return false
        confirmations[key] = Confirmation(key, amount, shortCode, source, clock.instant())
        saleByCode[key]?.let { saleId -> claimsBySale[saleId]?.let { claimsBySale[saleId] = evaluate(it) } }
        return true
    }

    /** STK payments are already tied to a sale through the CheckoutRequestID, so they verify directly. */
    @Synchronized
    fun onStkSuccess(saleId: String, code: String, amount: Long, shortCode: String) {
        onConfirmation(code, amount, shortCode, Source.STK)
        claim(saleId, code, amount, shortCode)
    }

    /** The cashier typed an M-Pesa code against a sale. Works offline; verification happens when the confirmation arrives. */
    @Synchronized
    fun claim(saleId: String, rawCode: String, expectedAmount: Long, expectedShortCode: String): Claim {
        val code = normalise(rawCode)
        require(CODE.matches(code)) { "M-Pesa codes are 10 letters and digits, got '$rawCode'" }

        val owner = saleByCode[code]
        if (owner != null && owner != saleId) {
            // The same code typed on a second sale: classic reuse of one customer's message.
            return Claim(saleId, code, expectedAmount, expectedShortCode, clock.instant(), Status.CODE_ALREADY_USED)
                .also { claimsBySale[saleId] = it }
        }
        saleByCode[code] = saleId
        val claim = evaluate(Claim(saleId, code, expectedAmount, expectedShortCode, clock.instant(), Status.PENDING))
        claimsBySale[saleId] = claim
        return claim
    }

    fun claimFor(saleId: String): Claim? = claimsBySale[saleId]

    fun shiftReport(): ShiftReport {
        val claims = claimsBySale.values.sortedBy { it.claimedAt }
        val claimedCodes = claims.filter { it.status != Status.CODE_ALREADY_USED }.map { it.code }.toSet()
        val verified = claims.filter { it.status == Status.VERIFIED }
        val pending = claims.filter { it.status == Status.PENDING }
        return ShiftReport(
            verified = verified,
            pending = pending,
            problems = claims.filter { it.status !in setOf(Status.VERIFIED, Status.PENDING) },
            unclaimed = confirmations.values.filter { it.code !in claimedCodes }.sortedBy { it.receivedAt },
            verifiedTotal = verified.sumOf { it.expectedAmount },
            pendingTotal = pending.sumOf { it.expectedAmount },
        )
    }

    /** Pending claims older than [age]: the cashier should check these before the customer leaves. */
    fun stalePending(age: Duration): List<Claim> =
        claimsBySale.values.filter { it.status == Status.PENDING && it.claimedAt.plus(age).isBefore(clock.instant()) }

    private fun evaluate(claim: Claim): Claim {
        val conf = confirmations[claim.code] ?: return claim.copy(status = Status.PENDING)
        val status = when {
            conf.shortCode != claim.expectedShortCode -> Status.WRONG_TILL
            conf.amount < claim.expectedAmount -> Status.AMOUNT_SHORT
            else -> Status.VERIFIED
        }
        return claim.copy(status = status, confirmedAmount = conf.amount)
    }

    companion object {
        private val CODE = Regex("^[A-Z0-9]{10}$")
        fun normalise(code: String) = code.trim().uppercase()
    }
}
