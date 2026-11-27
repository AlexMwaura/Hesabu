package ke.hesabu.server.mpesa

import ke.hesabu.core.Money
import ke.hesabu.core.mpesa.ClaimStatus
import ke.hesabu.core.mpesa.MpesaRules
import ke.hesabu.core.sync.MpesaClaimResult
import ke.hesabu.core.sync.StkState
import ke.hesabu.core.sync.StkStatus
import ke.hesabu.server.web.ApiException
import ke.hesabu.server.web.Branch
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClientException

enum class ConfirmationSource { C2B, STK }

/**
 * Matches M-Pesa codes that hubs attach to sales against the confirmations Safaricom sends here.
 * A claim is VERIFIED only when the money reached one of the branch's own short codes, in full.
 */
@Service
class MpesaService(private val jdbc: JdbcClient, private val daraja: DarajaClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Confirmation(val code: String, val amountCents: Long, val shortCode: String)
    private data class Claim(val code: String, val branchId: java.util.UUID, val ref: String, val expectedCents: Long)

    /** Stores a confirmation once (Safaricom retries callbacks) and re-evaluates any claim waiting for it. */
    @Transactional
    fun onConfirmation(transId: String, amountCents: Long, shortCode: String, source: ConfirmationSource, msisdn: String?, billRef: String?, transTime: String?): Boolean {
        val code = MpesaRules.normalise(transId)
        val inserted = jdbc.sql(
            """
            INSERT INTO mpesa_confirmation (trans_id, short_code, amount_cents, source, msisdn, bill_ref, trans_time)
            VALUES (:c, :sc, :a, :src, :m, :br, :tt) ON CONFLICT (trans_id) DO NOTHING
            """.trimIndent(),
        ).param("c", code).param("sc", shortCode).param("a", amountCents).param("src", source.name)
            .param("m", msisdn).param("br", billRef).param("tt", transTime).update() == 1
        if (inserted) claim(code)?.let(::reevaluate)
        return inserted
    }

    /** Is there money for this code? Does not claim it. */
    fun check(branch: Branch, rawCode: String, amountCents: Long): MpesaClaimResult {
        val code = MpesaRules.normalise(rawCode)
        require(MpesaRules.isValidCode(code)) { "M-Pesa codes are 10 letters and digits" }
        if (claim(code) != null) return MpesaClaimResult(code, ClaimStatus.CODE_ALREADY_USED)
        val conf = confirmation(code)
        return MpesaClaimResult(code, evaluate(branch, conf, amountCents), conf?.amountCents)
    }

    /** Attaches a code to a sale or repayment. Repeating the same claim is harmless. */
    @Transactional
    fun claim(branch: Branch, ref: String, rawCode: String, amountCents: Long): MpesaClaimResult {
        val code = MpesaRules.normalise(rawCode)
        require(MpesaRules.isValidCode(code)) { "M-Pesa codes are 10 letters and digits" }
        require(amountCents > 0 && Money.isWholeShillings(amountCents)) { "M-Pesa amounts are whole shillings" }
        jdbc.sql(
            "INSERT INTO mpesa_claim (code, branch_id, ref, expected_amount_cents, status) VALUES (:c, :b, :r, :a, 'PENDING') ON CONFLICT (code) DO NOTHING",
        ).param("c", code).param("b", branch.id).param("r", ref).param("a", amountCents).update()
        val claim = claim(code)!!
        if (claim.branchId != branch.id || claim.ref != ref) {
            return MpesaClaimResult(code, ClaimStatus.CODE_ALREADY_USED, claimedByRef = claim.ref.takeIf { claim.branchId == branch.id })
        }
        return reevaluate(claim)
    }

    fun statuses(branch: Branch, codes: List<String>): List<MpesaClaimResult> = codes.map(MpesaRules::normalise).mapNotNull { code ->
        claim(code)?.takeIf { it.branchId == branch.id }?.let { c ->
            val conf = confirmation(code)
            MpesaClaimResult(code, evaluate(branch, conf, c.expectedCents), conf?.amountCents, c.ref)
        }
    }

    fun startStk(branch: Branch, ref: String, phone: String, amountCents: Long): StkStatus {
        val shortCode = branch.stkShortCode
        val passkey = branch.stkPasskey
        if (shortCode == null || passkey == null) throw ApiException(HttpStatus.CONFLICT, "STK_NOT_SET_UP", "M-Pesa prompts are not set up for this branch")
        require(amountCents > 0 && Money.isWholeShillings(amountCents)) { "M-Pesa amounts are whole shillings" }
        val ack = try {
            daraja.stkPush(shortCode, passkey, phone, amountCents / 100, ref)
        } catch (e: RestClientException) {
            log.warn("STK push failed for branch {}: {}", branch.code, e.message)
            throw ApiException(HttpStatus.BAD_GATEWAY, "DARAJA_ERROR", "Safaricom did not accept the prompt")
        }
        val checkoutId = ack.checkoutRequestId
        if (ack.responseCode != "0" || checkoutId == null) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "STK_REFUSED", ack.responseDescription ?: ack.errorMessage ?: "STK refused")
        }
        jdbc.sql(
            """
            INSERT INTO stk_request (checkout_request_id, branch_id, ref, phone, amount_cents, short_code, state)
            VALUES (:id, :b, :r, :p, :a, :sc, 'WAITING')
            """.trimIndent(),
        ).param("id", checkoutId).param("b", branch.id).param("r", ref).param("p", phone).param("a", amountCents).param("sc", shortCode).update()
        return StkStatus(checkoutId, StkState.WAITING, amountCents)
    }

    fun stkStatus(branch: Branch, checkoutRequestId: String): StkStatus =
        jdbc.sql("SELECT checkout_request_id, state, amount_cents, mpesa_code, result_desc FROM stk_request WHERE checkout_request_id = :id AND branch_id = :b")
            .param("id", checkoutRequestId).param("b", branch.id)
            .query { rs, _ -> StkStatus(rs.getString(1), StkState.valueOf(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getString(5)) }
            .optional().orElseThrow { ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such M-Pesa prompt") }

    /** Safaricom's STK result. Success also counts as a confirmation of the code it carries. */
    @Transactional
    fun onStkResult(checkoutRequestId: String, resultCode: Int, resultDesc: String?, code: String?, amountShillings: Long?) {
        val req = jdbc.sql("SELECT short_code, state FROM stk_request WHERE checkout_request_id = :id").param("id", checkoutRequestId)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
        if (req == null) {
            log.warn("STK result for unknown CheckoutRequestID {}", checkoutRequestId)
            return
        }
        if (req.second != StkState.WAITING.name) return
        val paid = resultCode == 0 && code != null && amountShillings != null
        if (paid) onConfirmation(code!!, Money.ofShillings(amountShillings!!), req.first, ConfirmationSource.STK, null, null, null)
        jdbc.sql("UPDATE stk_request SET state = :s, mpesa_code = :c, result_code = :rc, result_desc = :rd, updated_at = now() WHERE checkout_request_id = :id")
            .param("s", if (paid) StkState.PAID.name else StkState.FAILED.name).param("c", code?.let(MpesaRules::normalise))
            .param("rc", resultCode).param("rd", resultDesc).param("id", checkoutRequestId).update()
    }

    private fun reevaluate(claim: Claim): MpesaClaimResult {
        val branch = jdbc.sql("SELECT mpesa_short_code, stk_short_code FROM branch WHERE id = :b").param("b", claim.branchId)
            .query { rs, _ -> setOfNotNull(rs.getString(1), rs.getString(2)) }.single()
        val conf = confirmation(claim.code)
        val status = evaluate(branch, conf, claim.expectedCents)
        jdbc.sql("UPDATE mpesa_claim SET status = :s, confirmed_amount_cents = :a, updated_at = now() WHERE code = :c")
            .param("s", status.name).param("a", conf?.amountCents).param("c", claim.code).update()
        return MpesaClaimResult(claim.code, status, conf?.amountCents, claim.ref)
    }

    private fun evaluate(branch: Branch, conf: Confirmation?, expectedCents: Long) = evaluate(branch.shortCodes, conf, expectedCents)

    private fun evaluate(shortCodes: Set<String>, conf: Confirmation?, expectedCents: Long): ClaimStatus {
        val expected = conf?.shortCode?.takeIf { it in shortCodes } ?: shortCodes.firstOrNull() ?: ""
        return MpesaRules.evaluate(conf?.let { MpesaRules.Confirmation(it.code, it.amountCents, it.shortCode) }, expectedCents, expected)
    }

    private fun confirmation(code: String): Confirmation? =
        jdbc.sql("SELECT trans_id, amount_cents, short_code FROM mpesa_confirmation WHERE trans_id = :c").param("c", code)
            .query { rs, _ -> Confirmation(rs.getString(1), rs.getLong(2), rs.getString(3)) }.optional().orElse(null)

    private fun claim(code: String): Claim? =
        jdbc.sql("SELECT code, branch_id, ref, expected_amount_cents FROM mpesa_claim WHERE code = :c").param("c", code)
            .query { rs, _ -> Claim(rs.getString(1), rs.getObject(2, java.util.UUID::class.java), rs.getString(3), rs.getLong(4)) }
            .optional().orElse(null)
}
