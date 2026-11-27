package ke.hesabu.hub.stock

import ke.hesabu.core.Qty
import ke.hesabu.core.stock.StockMovementType
import ke.hesabu.core.sync.EventTypes
import ke.hesabu.core.sync.StockLevel
import ke.hesabu.core.sync.StockLevels
import ke.hesabu.hub.catalogue.CatalogueRepo
import ke.hesabu.hub.sync.Outbox
import ke.hesabu.hub.web.HubException
import ke.hesabu.hub.web.notFound
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Movement(
    val productCode: String,
    val type: StockMovementType,
    val qtyMilli: Long,
    val unitCostCents: Long? = null,
    val ref: String? = null,
    val note: String? = null,
)

data class StockView(val productCode: String, val name: String, val onHand: BigDecimal, val reorderLevel: BigDecimal, val low: Boolean)

data class GoodsReceivedLine(val productCode: String, val qty: BigDecimal, val unitCostCents: Long? = null)
data class GoodsReceivedRequest(val supplier: String? = null, val ref: String? = null, val lines: List<GoodsReceivedLine>)

enum class AdjustmentReason(val incoming: StockMovementType, val outgoing: StockMovementType) {
    COUNT(StockMovementType.ADJUSTMENT_IN, StockMovementType.ADJUSTMENT_OUT),
    DAMAGED(StockMovementType.ADJUSTMENT_IN, StockMovementType.DISCARD),
    EXPIRED(StockMovementType.ADJUSTMENT_IN, StockMovementType.DISCARD),
}

/** [qtyChange] is signed: positive adds stock, negative removes it. */
data class AdjustmentRequest(val productCode: String, val qtyChange: BigDecimal, val reason: AdjustmentReason, val note: String? = null)

/** The branch stock ledger. Every change is a movement coded with KRA's stock in/out types. */
@Service
class StockService(
    private val jdbc: JdbcClient,
    private val catalogue: CatalogueRepo,
    private val outbox: Outbox,
    private val clock: Clock,
    private val tx: TransactionTemplate,
) {
    /**
     * Held by a sale from its stock check until its stock is released, and by receipts and adjustments,
     * so stock can't change between "enough on hand" and the signed receipt.
     */
    private val lock = ReentrantLock(true)

    fun <T> locked(block: () -> T): T = lock.withLock(block)

    fun onHandMilli(code: String): Long =
        jdbc.sql("SELECT on_hand_milli FROM stock WHERE product_code = :c").param("c", code)
            .query { rs, _ -> rs.getLong(1) }.optional().orElse(0L)

    fun levels(): List<StockView> =
        jdbc.sql(
            """
            SELECT p.code, p.name, COALESCE(s.on_hand_milli, 0) AS on_hand, p.reorder_level_milli
            FROM product p LEFT JOIN stock s ON s.product_code = p.code
            WHERE p.active = 1 AND p.is_service = 0 ORDER BY p.name
            """.trimIndent(),
        ).query { rs, _ ->
            val onHand = rs.getLong(3)
            val reorder = rs.getLong(4)
            StockView(rs.getString(1), rs.getString(2), Qty.ofMilli(onHand), Qty.ofMilli(reorder), onHand <= reorder)
        }.list()

    /**
     * Applies movements. Call inside a transaction. A movement that would take stock below zero
     * fails the whole transaction (TIS spec 6.29).
     */
    fun apply(movements: List<Movement>, at: Instant) {
        if (movements.isEmpty()) return
        movements.forEach { m ->
            val delta = m.type.delta(m.qtyMilli)
            jdbc.sql("INSERT INTO stock (product_code, on_hand_milli) VALUES (:c, 0) ON CONFLICT (product_code) DO NOTHING")
                .param("c", m.productCode).update()
            val changed = jdbc.sql("UPDATE stock SET on_hand_milli = on_hand_milli + :d WHERE product_code = :c AND on_hand_milli + :d >= 0")
                .param("d", delta).param("c", m.productCode).update()
            if (changed == 0) {
                throw HubException(
                    HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                    "Not enough ${m.productCode} in stock: ${Qty.ofMilli(onHandMilli(m.productCode))} on hand",
                    mapOf("productCode" to m.productCode, "onHand" to Qty.ofMilli(onHandMilli(m.productCode))),
                )
            }
            jdbc.sql(
                """
                INSERT INTO stock_movement (id, product_code, type, kra_code, qty_milli, unit_cost_cents, ref, note, at)
                VALUES (:id, :c, :type, :kra, :qty, :cost, :ref, :note, :at)
                """.trimIndent(),
            )
                .param("id", UUID.randomUUID().toString()).param("c", m.productCode).param("type", m.type.name)
                .param("kra", m.type.kraCode).param("qty", m.qtyMilli).param("cost", m.unitCostCents)
                .param("ref", m.ref).param("note", m.note).param("at", at.toString())
                .update()
        }
        val codes = movements.map { it.productCode }.distinct()
        outbox.enqueue(EventTypes.STOCK_LEVELS, StockLevels(codes.map(::level)), at)
    }

    fun receive(req: GoodsReceivedRequest): List<StockView> = locked { tx.execute { receiveTx(req) }!! }

    private fun receiveTx(req: GoodsReceivedRequest): List<StockView> {
        require(req.lines.isNotEmpty()) { "Goods received needs at least one line" }
        val movements = req.lines.map {
            val p = catalogue.find(it.productCode) ?: notFound("Product ${it.productCode}")
            require(!p.isService) { "${p.name} is a service and has no stock" }
            require(it.qty.signum() > 0) { "Received quantities must be positive" }
            Movement(p.code, StockMovementType.PURCHASE, Qty.toMilli(it.qty), it.unitCostCents, req.ref, req.supplier)
        }
        apply(movements, clock.instant())
        return levels().filter { v -> movements.any { it.productCode == v.productCode } }
    }

    fun adjust(req: AdjustmentRequest): StockView = locked { tx.execute { adjustTx(req) }!! }

    private fun adjustTx(req: AdjustmentRequest): StockView {
        val p = catalogue.find(req.productCode) ?: notFound("Product ${req.productCode}")
        require(!p.isService) { "${p.name} is a service and has no stock" }
        require(req.qtyChange.signum() != 0) { "Adjustment must change the quantity" }
        val milli = Qty.toMilli(req.qtyChange.abs())
        val type = if (req.qtyChange.signum() > 0) req.reason.incoming else req.reason.outgoing
        apply(listOf(Movement(p.code, type, milli, note = req.note ?: req.reason.name)), clock.instant())
        return levels().first { it.productCode == p.code }
    }

    private fun level(code: String): StockLevel {
        val reorder = catalogue.find(code)?.reorderLevelMilli ?: 0
        return StockLevel(code, onHandMilli(code), reorder)
    }
}
