package ke.hesabu.hub.shift

import ke.hesabu.core.mpesa.ClaimStatus
import ke.hesabu.core.shift.CashFlows
import ke.hesabu.core.sync.EventTypes
import ke.hesabu.core.sync.ShiftClosed
import ke.hesabu.hub.mpesa.ClaimView
import ke.hesabu.hub.mpesa.HubMpesaService
import ke.hesabu.hub.sync.Outbox
import ke.hesabu.hub.web.HubException
import ke.hesabu.hub.web.notFound
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class Shift(
    val id: String,
    val tillId: String,
    val cashierId: String,
    val cashierName: String,
    val floatCents: Long,
    val openedAt: String,
    val closedAt: String?,
    val expectedCashCents: Long?,
    val countedCashCents: Long?,
)

data class OpenShift(val cashierId: String, val cashierName: String, val floatCents: Long)
data class CloseShift(val countedCashCents: Long)

data class CashSummary(
    val floatCents: Long,
    val cashSalesCents: Long,
    val cashRefundsCents: Long,
    val cashDeniRepaymentsCents: Long,
    val expectedCents: Long,
    val countedCents: Long?,
    val varianceCents: Long?,
)

data class MpesaSummary(val verifiedCents: Long, val pendingCents: Long, val problems: List<ClaimView>)

data class ShiftReport(
    val shift: Shift,
    val salesCount: Int,
    val salesTotalCents: Long,
    val refundsTotalCents: Long,
    val paymentsByMethod: Map<String, Long>,
    val cash: CashSummary,
    val mpesa: MpesaSummary,
)

/** Shifts: open with a float, close with a cash count, and see the variance. One open shift per till. */
@Service
class ShiftService(
    private val jdbc: JdbcClient,
    private val outbox: Outbox,
    private val mpesa: HubMpesaService,
    private val clock: Clock,
) {
    fun current(tillId: String): Shift? =
        jdbc.sql("SELECT * FROM shift WHERE till_id = :t AND closed_at IS NULL").param("t", tillId).query(::row).optional().orElse(null)

    fun requireOpen(tillId: String): Shift = current(tillId)
        ?: throw HubException(HttpStatus.CONFLICT, "NO_OPEN_SHIFT", "Open a shift on till $tillId first")

    fun get(id: String): Shift = jdbc.sql("SELECT * FROM shift WHERE id = :id").param("id", id).query(::row).optional().orElse(null)
        ?: notFound("Shift $id")

    @Transactional
    fun open(tillId: String, req: OpenShift): Shift {
        require(req.floatCents >= 0) { "Float can't be negative" }
        require(req.cashierId.isNotBlank() && req.cashierName.isNotBlank()) { "Cashier id and name are required" }
        if (current(tillId) != null) throw HubException(HttpStatus.CONFLICT, "SHIFT_ALREADY_OPEN", "Till $tillId already has an open shift")
        val id = UUID.randomUUID().toString()
        try {
            jdbc.sql("INSERT INTO shift (id, till_id, cashier_id, cashier_name, float_cents, opened_at) VALUES (:id, :t, :c, :n, :f, :at)")
                .param("id", id).param("t", tillId).param("c", req.cashierId).param("n", req.cashierName)
                .param("f", req.floatCents).param("at", clock.instant().toString()).update()
        } catch (e: DataAccessException) {
            throw HubException(HttpStatus.CONFLICT, "SHIFT_ALREADY_OPEN", "Till $tillId already has an open shift")
        }
        return get(id)
    }

    @Transactional
    fun close(tillId: String, shiftId: String, req: CloseShift): ShiftReport {
        val shift = get(shiftId)
        if (shift.tillId != tillId) throw HubException(HttpStatus.CONFLICT, "WRONG_TILL", "Shift $shiftId belongs to till ${shift.tillId}")
        if (shift.closedAt != null) throw HubException(HttpStatus.CONFLICT, "SHIFT_CLOSED", "Shift $shiftId is already closed")
        require(req.countedCashCents >= 0) { "Counted cash can't be negative" }
        val flows = flows(shift)
        val now = clock.instant()
        jdbc.sql("UPDATE shift SET closed_at = :at, expected_cash_cents = :e, counted_cash_cents = :c WHERE id = :id")
            .param("at", now.toString()).param("e", flows.expectedCents).param("c", req.countedCashCents).param("id", shiftId).update()
        val report = report(shiftId)
        val closed = report.shift
        outbox.enqueue(
            EventTypes.SHIFT_CLOSED,
            ShiftClosed(
                closed.id, closed.tillId, closed.cashierId, closed.cashierName, closed.openedAt, closed.closedAt!!,
                closed.floatCents, flows.expectedCents, req.countedCashCents, flows.varianceCents(req.countedCashCents),
                report.salesCount, report.salesTotalCents,
            ),
            now,
        )
        return report
    }

    fun report(shiftId: String): ShiftReport {
        val shift = get(shiftId)
        val flows = flows(shift)
        val sales = jdbc.sql("SELECT COUNT(*), COALESCE(SUM(total_cents), 0) FROM sale WHERE shift_id = :s AND status = 'SIGNED' AND receipt_type = 'S'")
            .param("s", shiftId).query { rs, _ -> rs.getInt(1) to rs.getLong(2) }.single()
        val refunds = jdbc.sql("SELECT COALESCE(SUM(total_cents), 0) FROM sale WHERE shift_id = :s AND status = 'SIGNED' AND receipt_type = 'R'")
            .param("s", shiftId).query { rs, _ -> rs.getLong(1) }.single()
        val byMethod = jdbc.sql(
            """
            SELECT t.method, SUM(t.amount_cents) FROM tender t JOIN sale s ON s.id = t.sale_id
            WHERE s.shift_id = :s AND s.status = 'SIGNED' AND s.receipt_type = 'S' GROUP BY t.method
            """.trimIndent(),
        ).param("s", shiftId).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

        val saleIds = jdbc.sql("SELECT id FROM sale WHERE shift_id = :s AND status = 'SIGNED'").param("s", shiftId)
            .query { rs, _ -> rs.getString(1) }.list()
        val repaymentIds = jdbc.sql("SELECT id FROM deni_entry WHERE shift_id = :s AND kind = 'PAYMENT'").param("s", shiftId)
            .query { rs, _ -> rs.getString(1) }.list()
        val claims = (saleIds + repaymentIds).flatMap { mpesa.claimsFor(it) }

        val counted = shift.countedCashCents
        return ShiftReport(
            shift = shift,
            salesCount = sales.first,
            salesTotalCents = sales.second,
            refundsTotalCents = refunds,
            paymentsByMethod = byMethod,
            cash = CashSummary(
                flows.floatCents, flows.cashSalesCents, flows.cashRefundsCents, flows.cashDeniRepaymentsCents,
                flows.expectedCents, counted, counted?.let(flows::varianceCents),
            ),
            mpesa = MpesaSummary(
                verifiedCents = claims.filter { it.status == ClaimStatus.VERIFIED }.sumOf { it.amountCents },
                pendingCents = claims.filter { it.status == ClaimStatus.PENDING }.sumOf { it.amountCents },
                problems = claims.filter { it.status !in setOf(ClaimStatus.VERIFIED, ClaimStatus.PENDING) },
            ),
        )
    }

    private fun flows(shift: Shift): CashFlows {
        fun cash(receiptType: String) = jdbc.sql(
            """
            SELECT COALESCE(SUM(t.amount_cents), 0) FROM tender t JOIN sale s ON s.id = t.sale_id
            WHERE s.shift_id = :s AND s.status = 'SIGNED' AND s.receipt_type = :rt AND t.method = 'CASH'
            """.trimIndent(),
        ).param("s", shift.id).param("rt", receiptType).query { rs, _ -> rs.getLong(1) }.single()
        val deniCash = jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM deni_entry WHERE shift_id = :s AND kind = 'PAYMENT' AND method = 'CASH'")
            .param("s", shift.id).query { rs, _ -> rs.getLong(1) }.single()
        return CashFlows(shift.floatCents, cash("S"), cash("R"), deniCash)
    }

    private fun row(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Shift(
        id = rs.getString("id"),
        tillId = rs.getString("till_id"),
        cashierId = rs.getString("cashier_id"),
        cashierName = rs.getString("cashier_name"),
        floatCents = rs.getLong("float_cents"),
        openedAt = rs.getString("opened_at"),
        closedAt = rs.getString("closed_at"),
        expectedCashCents = rs.getLong("expected_cash_cents").takeUnless { rs.wasNull() },
        countedCashCents = rs.getLong("counted_cash_cents").takeUnless { rs.wasNull() },
    )
}
