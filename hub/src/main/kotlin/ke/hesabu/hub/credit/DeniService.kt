package ke.hesabu.hub.credit

import ke.hesabu.core.Money
import ke.hesabu.core.credit.CreditRules
import ke.hesabu.core.mpesa.MpesaRules
import ke.hesabu.core.sync.CustomerUpdated
import ke.hesabu.core.sync.EventTypes
import ke.hesabu.hub.mpesa.HubMpesaService
import ke.hesabu.hub.shift.ShiftService
import ke.hesabu.hub.sync.Outbox
import ke.hesabu.hub.web.HubException
import ke.hesabu.hub.web.notFound
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class Customer(
    val id: String,
    val name: String,
    val phone: String?,
    val kraPin: String?,
    val creditLimitCents: Long,
    val balanceCents: Long,
) {
    val headroomCents: Long get() = CreditRules.headroom(balanceCents, creditLimitCents)
}

data class NewCustomer(val name: String, val phone: String? = null, val kraPin: String? = null, val creditLimitCents: Long = 0)
data class CreditLimitChange(val creditLimitCents: Long)

enum class RepaymentMethod { CASH, MPESA_CODE }
data class Repayment(val amountCents: Long, val method: RepaymentMethod, val mpesaCode: String? = null)

data class DeniEntry(val id: String, val kind: String, val amountCents: Long, val method: String?, val mpesaCode: String?, val ref: String?, val at: String)
data class Statement(val customer: Customer, val entries: List<DeniEntry>)

/** The deni book: who owes the shop what, with a limit per customer. */
@Service
class DeniService(
    private val jdbc: JdbcClient,
    private val outbox: Outbox,
    private val shifts: ShiftService,
    private val mpesa: HubMpesaService,
    private val clock: Clock,
) {
    fun find(id: String): Customer? =
        jdbc.sql("SELECT id, name, phone, kra_pin, credit_limit_cents, balance_cents FROM customer WHERE id = :id")
            .param("id", id).query(::row).optional().orElse(null)

    fun get(id: String): Customer = find(id) ?: notFound("Customer $id")

    fun list(): List<Customer> =
        jdbc.sql("SELECT id, name, phone, kra_pin, credit_limit_cents, balance_cents FROM customer ORDER BY name").query(::row).list()

    @Transactional
    fun create(req: NewCustomer): Customer {
        require(req.name.isNotBlank()) { "Customer name is required" }
        require(req.creditLimitCents >= 0) { "Credit limit can't be negative" }
        val id = UUID.randomUUID().toString()
        val now = clock.instant()
        jdbc.sql("INSERT INTO customer (id, name, phone, kra_pin, credit_limit_cents, created_at) VALUES (:id, :n, :p, :k, :l, :at)")
            .param("id", id).param("n", req.name.trim()).param("p", req.phone).param("k", req.kraPin)
            .param("l", req.creditLimitCents).param("at", now.toString()).update()
        return get(id).also { publish(it, now) }
    }

    @Transactional
    fun setLimit(id: String, req: CreditLimitChange): Customer {
        require(req.creditLimitCents >= 0) { "Credit limit can't be negative" }
        get(id)
        jdbc.sql("UPDATE customer SET credit_limit_cents = :l WHERE id = :id").param("l", req.creditLimitCents).param("id", id).update()
        return get(id).also { publish(it, clock.instant()) }
    }

    fun checkCanCharge(customerId: String, amountCents: Long) {
        val c = get(customerId)
        if (!CreditRules.canExtend(c.balanceCents, c.creditLimitCents, amountCents)) {
            throw HubException(
                HttpStatus.CONFLICT, "CREDIT_LIMIT",
                "${c.name} can take at most ${Money.toDecimal(c.headroomCents)} more on credit",
                mapOf("headroomCents" to c.headroomCents),
            )
        }
    }

    /**
     * A credit sale. Call inside the sale's transaction, after the receipt is signed: the limit was
     * checked before signing, and a signed receipt must always be recorded.
     */
    fun charge(customerId: String, amountCents: Long, saleId: String, shiftId: String, at: Instant) {
        entry(customerId, "CHARGE", amountCents, null, null, saleId, shiftId, at)
        jdbc.sql("UPDATE customer SET balance_cents = balance_cents + :a WHERE id = :id").param("a", amountCents).param("id", customerId).update()
        publish(get(customerId), at)
    }

    /** A credit note refunded against deni. Call inside the credit note's transaction. */
    fun checkCanRefund(customerId: String, amountCents: Long) {
        val c = get(customerId)
        if (amountCents > c.balanceCents) {
            throw HubException(HttpStatus.CONFLICT, "REFUND_EXCEEDS_DENI", "${c.name} only owes ${Money.toDecimal(c.balanceCents)}; refund the rest in cash")
        }
    }

    /** Call after the credit note is signed; [checkCanRefund] runs before signing. Never goes below zero. */
    fun refund(customerId: String, amountCents: Long, saleId: String, shiftId: String, at: Instant) {
        val c = get(customerId)
        val applied = minOf(amountCents, c.balanceCents)
        if (applied == 0L) return
        entry(customerId, "REFUND", applied, null, null, saleId, shiftId, at)
        jdbc.sql("UPDATE customer SET balance_cents = balance_cents - :a WHERE id = :id").param("a", applied).param("id", customerId).update()
        publish(get(customerId), at)
    }

    @Transactional
    fun repay(tillId: String, customerId: String, req: Repayment): Customer {
        val c = get(customerId)
        require(req.amountCents > 0) { "Repayment must be positive" }
        if (req.amountCents > c.balanceCents) {
            throw HubException(HttpStatus.CONFLICT, "OVERPAYMENT", "${c.name} only owes ${Money.toDecimal(c.balanceCents)}")
        }
        val shift = shifts.requireOpen(tillId)
        val now = clock.instant()
        val entryId = UUID.randomUUID().toString()
        val code = when (req.method) {
            RepaymentMethod.CASH -> null
            RepaymentMethod.MPESA_CODE -> {
                require(req.mpesaCode != null && MpesaRules.isValidCode(req.mpesaCode)) { "M-Pesa code must be 10 letters and digits" }
                require(Money.isWholeShillings(req.amountCents)) { "M-Pesa amounts are whole shillings" }
                MpesaRules.normalise(req.mpesaCode).also {
                    mpesa.ensureUnused(listOf(it), entryId)
                    mpesa.recordClaim(it, entryId, req.amountCents, now)
                }
            }
        }
        entry(customerId, "PAYMENT", req.amountCents, req.method.name, code, entryId, shift.id, now, id = entryId)
        jdbc.sql("UPDATE customer SET balance_cents = balance_cents - :a WHERE id = :id").param("a", req.amountCents).param("id", customerId).update()
        return get(customerId).also { publish(it, now) }
    }

    fun statement(id: String): Statement = Statement(
        get(id),
        jdbc.sql("SELECT id, kind, amount_cents, method, mpesa_code, ref, at FROM deni_entry WHERE customer_id = :id ORDER BY at, rowid")
            .param("id", id)
            .query { rs, _ -> DeniEntry(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)) }
            .list(),
    )

    private fun entry(
        customerId: String, kind: String, amountCents: Long, method: String?, code: String?, ref: String?, shiftId: String?, at: Instant,
        id: String = UUID.randomUUID().toString(),
    ) {
        jdbc.sql(
            """
            INSERT INTO deni_entry (id, customer_id, kind, amount_cents, method, mpesa_code, ref, shift_id, at)
            VALUES (:id, :c, :k, :a, :m, :code, :ref, :s, :at)
            """.trimIndent(),
        ).param("id", id).param("c", customerId).param("k", kind).param("a", amountCents).param("m", method)
            .param("code", code).param("ref", ref).param("s", shiftId).param("at", at.toString()).update()
    }

    private fun publish(c: Customer, at: Instant) =
        outbox.enqueue(EventTypes.CUSTOMER_UPDATED, CustomerUpdated(c.id, c.name, c.phone, c.creditLimitCents, c.balanceCents), at)

    private fun row(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) =
        Customer(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6))
}
