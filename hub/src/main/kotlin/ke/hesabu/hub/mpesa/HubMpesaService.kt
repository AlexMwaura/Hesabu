package ke.hesabu.hub.mpesa

import ke.hesabu.core.Money
import ke.hesabu.core.mpesa.ClaimStatus
import ke.hesabu.core.mpesa.MpesaRules
import ke.hesabu.core.sync.MpesaCheckRequest
import ke.hesabu.core.sync.MpesaClaimRequest
import ke.hesabu.core.sync.StkStartRequest
import ke.hesabu.core.sync.StkStatus
import ke.hesabu.hub.sync.CloudClient
import ke.hesabu.hub.sync.CloudRefused
import ke.hesabu.hub.sync.CloudUnavailable
import ke.hesabu.hub.web.HubException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

data class ClaimView(val code: String, val ref: String, val amountCents: Long, val status: ClaimStatus, val confirmedAmountCents: Long?)

/** Result of checking a code at the till. [offline] means the cloud could not be asked: treat as unverified. */
data class MpesaCheckResult(val code: String, val status: ClaimStatus, val offline: Boolean, val confirmedAmountCents: Long? = null)

/**
 * M-Pesa at the hub. Safaricom only talks to the cloud, so verification needs the internet; what the
 * hub can do offline is refuse a code it has already accepted for another sale.
 */
@Service
class HubMpesaService(
    private val jdbc: JdbcClient,
    private val cloud: CloudClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun claimOf(code: String): ClaimView? =
        jdbc.sql("SELECT code, ref, amount_cents, status, confirmed_amount_cents FROM mpesa_claim WHERE code = :c")
            .param("c", MpesaRules.normalise(code)).query(::row).optional().orElse(null)

    fun claimsFor(ref: String): List<ClaimView> =
        jdbc.sql("SELECT code, ref, amount_cents, status, confirmed_amount_cents FROM mpesa_claim WHERE ref = :r")
            .param("r", ref).query(::row).list()

    /** Refuses codes this branch already accepted for something else. Works offline. */
    fun ensureUnused(codes: Collection<String>, ref: String) {
        codes.map(MpesaRules::normalise).forEach { code ->
            val existing = claimOf(code)
            if (existing != null && existing.ref != ref) {
                throw HubException(
                    HttpStatus.CONFLICT, "MPESA_CODE_ALREADY_USED",
                    "M-Pesa code $code was already used for another sale", mapOf("code" to code),
                )
            }
        }
    }

    /** Records a claim. Call inside the transaction that records the sale or repayment. */
    fun recordClaim(code: String, ref: String, amountCents: Long, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO mpesa_claim (code, ref, amount_cents, status, submitted, updated_at)
            VALUES (:c, :r, :a, 'PENDING', 0, :at) ON CONFLICT (code) DO NOTHING
            """.trimIndent(),
        ).param("c", MpesaRules.normalise(code)).param("r", ref).param("a", amountCents).param("at", at.toString()).update()
    }

    /** Asks the cloud whether money for [code] has arrived, before the cashier completes the sale. */
    fun check(rawCode: String, amountCents: Long): MpesaCheckResult {
        require(MpesaRules.isValidCode(rawCode)) { "M-Pesa codes are 10 letters and digits" }
        require(Money.isWholeShillings(amountCents)) { "M-Pesa amounts are whole shillings" }
        val code = MpesaRules.normalise(rawCode)
        if (claimOf(code) != null) return MpesaCheckResult(code, ClaimStatus.CODE_ALREADY_USED, offline = false)
        return try {
            val r = cloud.checkMpesa(MpesaCheckRequest(code, amountCents))
            MpesaCheckResult(code, r.status, offline = false, confirmedAmountCents = r.confirmedAmountCents)
        } catch (e: CloudUnavailable) {
            MpesaCheckResult(code, ClaimStatus.PENDING, offline = true)
        }
    }

    /** Sends claims the cloud has not seen yet. Returns how many were sent. */
    fun submitPending(): Int {
        val pending = jdbc.sql("SELECT code, ref, amount_cents, status, confirmed_amount_cents FROM mpesa_claim WHERE submitted = 0")
            .query(::row).list()
        pending.forEach { c ->
            val r = cloud.claimMpesa(MpesaClaimRequest(c.ref, c.code, c.amountCents))
            update(c.code, r.status, r.confirmedAmountCents, submitted = true)
        }
        return pending.size
    }

    /** Refreshes claims still waiting for Safaricom's confirmation. */
    fun refreshPending(): Int {
        val codes = jdbc.sql("SELECT code FROM mpesa_claim WHERE submitted = 1 AND status = 'PENDING'")
            .query { rs, _ -> rs.getString(1) }.list()
        if (codes.isEmpty()) return 0
        cloud.mpesaStatuses(codes).forEach { update(it.code, it.status, it.confirmedAmountCents, submitted = true) }
        return codes.size
    }

    /** Best effort, right after a sale: try to get a definite status while the customer is still there. */
    fun trySubmitNow() {
        try {
            submitPending()
        } catch (e: CloudUnavailable) {
            log.info("Cloud unreachable; M-Pesa claims will be sent on the next sync")
        } catch (e: CloudRefused) {
            log.warn("Cloud refused an M-Pesa claim: {}", e.message)
        }
    }

    fun startStk(ref: String, phone: String, amountCents: Long): StkStatus {
        require(Money.isWholeShillings(amountCents) && amountCents > 0) { "M-Pesa amounts are whole shillings" }
        require(Regex("^2547\\d{8}$|^2541\\d{8}$").matches(phone)) { "Phone must look like 2547XXXXXXXX" }
        return stkCall { cloud.startStk(StkStartRequest(ref, phone, amountCents)) }
    }

    fun stkStatus(checkoutRequestId: String): StkStatus = stkCall { cloud.stkStatus(checkoutRequestId) }

    private fun stkCall(block: () -> StkStatus): StkStatus = try {
        block()
    } catch (e: CloudUnavailable) {
        throw HubException(
            HttpStatus.SERVICE_UNAVAILABLE, "OFFLINE",
            "No internet, so the M-Pesa prompt can't be sent. Ask the customer to pay to the till and type the code.",
        )
    } catch (e: CloudRefused) {
        throw HubException(HttpStatus.BAD_GATEWAY, "STK_REFUSED", "M-Pesa prompt failed: ${e.body}")
    }

    private fun update(code: String, status: ClaimStatus, confirmed: Long?, submitted: Boolean) {
        jdbc.sql("UPDATE mpesa_claim SET status = :s, confirmed_amount_cents = :a, submitted = :sub, updated_at = :at WHERE code = :c")
            .param("s", status.name).param("a", confirmed).param("sub", if (submitted) 1 else 0)
            .param("at", clock.instant().toString()).param("c", code).update()
    }

    private fun row(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = ClaimView(
        rs.getString(1), rs.getString(2), rs.getLong(3), ClaimStatus.valueOf(rs.getString(4)),
        rs.getLong(5).takeUnless { rs.wasNull() },
    )
}
