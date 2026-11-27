package ke.hesabu.server.reports

import ke.hesabu.core.Qty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class SalesSummary(val count: Int, val grossCents: Long, val refundsCents: Long, val netCents: Long, val taxCents: Long)
data class MpesaTotals(val verifiedCents: Long, val pendingCents: Long, val problemCount: Int, val unclaimedCents: Long)
data class LowStock(val productCode: String, val name: String, val onHand: BigDecimal, val reorderLevel: BigDecimal)
data class ShiftLine(val tillId: String, val cashierName: String, val closedAt: String, val varianceCents: Long, val salesTotalCents: Long)

data class DailySummary(
    val branchId: UUID,
    val date: LocalDate,
    val sales: SalesSummary,
    val paymentsByMethod: Map<String, Long>,
    val mpesa: MpesaTotals,
    val deniOutstandingCents: Long,
    val lowStock: List<LowStock>,
    val shifts: List<ShiftLine>,
    /** When the hub last sent anything: how stale this page may be. */
    val lastHubEventAt: String?,
)

/** The owner's one-page view of a branch's day. */
@Service
class SummaryService(private val jdbc: JdbcClient) {
    private val nairobi = ZoneId.of("Africa/Nairobi")

    fun daily(branchId: UUID, businessId: UUID, date: LocalDate): DailySummary {
        val from = date.atStartOfDay(nairobi).toOffsetDateTime()
        val to = date.plusDays(1).atStartOfDay(nairobi).toOffsetDateTime()

        val sales = jdbc.sql(
            """
            SELECT COUNT(*) FILTER (WHERE receipt_type = 'S'),
                   COALESCE(SUM(total_cents) FILTER (WHERE receipt_type = 'S'), 0),
                   COALESCE(SUM(total_cents) FILTER (WHERE receipt_type = 'R'), 0),
                   COALESCE(SUM(tax_cents) FILTER (WHERE receipt_type = 'S'), 0) - COALESCE(SUM(tax_cents) FILTER (WHERE receipt_type = 'R'), 0)
            FROM sale WHERE branch_id = :b AND signed_at >= :from AND signed_at < :to
            """.trimIndent(),
        ).param("b", branchId).param("from", from).param("to", to)
            .query { rs, _ -> SalesSummary(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(2) - rs.getLong(3), rs.getLong(4)) }.single()

        val byMethod = jdbc.sql(
            """
            SELECT t.method, SUM(t.amount_cents) FROM sale_tender t JOIN sale s ON s.id = t.sale_id
            WHERE s.branch_id = :b AND s.receipt_type = 'S' AND s.signed_at >= :from AND s.signed_at < :to GROUP BY t.method
            """.trimIndent(),
        ).param("b", branchId).param("from", from).param("to", to).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

        val mpesa = jdbc.sql(
            """
            SELECT COALESCE(SUM(expected_amount_cents) FILTER (WHERE status = 'VERIFIED'), 0),
                   COALESCE(SUM(expected_amount_cents) FILTER (WHERE status = 'PENDING'), 0),
                   COUNT(*) FILTER (WHERE status NOT IN ('VERIFIED', 'PENDING'))
            FROM mpesa_claim WHERE branch_id = :b AND claimed_at >= :from AND claimed_at < :to
            """.trimIndent(),
        ).param("b", branchId).param("from", from).param("to", to).query { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getInt(3)) }.single()

        // Money that reached the branch's short codes but no sale or repayment claims.
        val unclaimed = jdbc.sql(
            """
            SELECT COALESCE(SUM(c.amount_cents), 0) FROM mpesa_confirmation c JOIN branch br ON br.id = :b
            WHERE c.short_code IN (br.mpesa_short_code, br.stk_short_code) AND c.received_at >= :from AND c.received_at < :to
              AND NOT EXISTS (SELECT 1 FROM mpesa_claim m WHERE m.code = c.trans_id)
            """.trimIndent(),
        ).param("b", branchId).param("from", from).param("to", to).query { rs, _ -> rs.getLong(1) }.single()

        val deni = jdbc.sql("SELECT COALESCE(SUM(balance_cents), 0) FROM customer WHERE branch_id = :b").param("b", branchId)
            .query { rs, _ -> rs.getLong(1) }.single()

        val low = jdbc.sql(
            """
            SELECT s.product_code, COALESCE(p.name, s.product_code), s.on_hand_milli, s.reorder_level_milli
            FROM stock_level s LEFT JOIN product p ON p.business_id = :biz AND p.code = s.product_code
            WHERE s.branch_id = :b AND s.reorder_level_milli > 0 AND s.on_hand_milli <= s.reorder_level_milli ORDER BY 2
            """.trimIndent(),
        ).param("b", branchId).param("biz", businessId)
            .query { rs, _ -> LowStock(rs.getString(1), rs.getString(2), Qty.ofMilli(rs.getLong(3)), Qty.ofMilli(rs.getLong(4))) }.list()

        val shifts = jdbc.sql(
            "SELECT till_id, cashier_name, closed_at, variance_cents, sales_total_cents FROM shift_close WHERE branch_id = :b AND closed_at >= :from AND closed_at < :to ORDER BY closed_at",
        ).param("b", branchId).param("from", from).param("to", to)
            .query { rs, _ -> ShiftLine(rs.getString(1), rs.getString(2), rs.getObject(3, java.time.OffsetDateTime::class.java).toString(), rs.getLong(4), rs.getLong(5)) }
            .list()

        val last = jdbc.sql("SELECT MAX(received_at) FROM hub_event WHERE branch_id = :b").param("b", branchId)
            .query { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java)?.toString() }.single()

        return DailySummary(branchId, date, sales, byMethod, MpesaTotals(mpesa.first, mpesa.second, mpesa.third, unclaimed), deni, low, shifts, last)
    }
}
