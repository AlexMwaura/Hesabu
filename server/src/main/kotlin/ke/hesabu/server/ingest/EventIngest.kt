package ke.hesabu.server.ingest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ke.hesabu.core.sync.CustomerUpdated
import ke.hesabu.core.sync.EventTypes
import ke.hesabu.core.sync.SaleRecorded
import ke.hesabu.core.sync.ShiftClosed
import ke.hesabu.core.sync.StockLevels
import ke.hesabu.server.web.Branch
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class IncomingEvent(val id: String, val type: String, val occurredAt: String, val payload: JsonNode)

/**
 * Stores what hubs send and projects it into reporting tables. Each event is stored and projected in
 * one transaction, keyed by its id, so a hub resending a batch changes nothing.
 */
@Service
class EventIngest(private val jdbc: JdbcClient, private val mapper: ObjectMapper, private val tx: TransactionTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(branch: Branch, events: List<IncomingEvent>): List<String> = events.mapNotNull { e ->
        try {
            tx.execute {
                val inserted = jdbc.sql(
                    "INSERT INTO hub_event (id, branch_id, type, occurred_at, payload) VALUES (:id, :b, :t, :at, CAST(:p AS jsonb)) ON CONFLICT (id) DO NOTHING",
                ).param("id", UUID.fromString(e.id)).param("b", branch.id).param("t", e.type).param("at", odt(e.occurredAt))
                    .param("p", mapper.writeValueAsString(e.payload)).update()
                if (inserted == 1) project(branch, e)
            }
            e.id
        } catch (ex: RuntimeException) {
            log.error("Could not ingest event {} ({}) from branch {}", e.id, e.type, branch.code, ex)
            null
        }
    }

    private fun project(branch: Branch, e: IncomingEvent) {
        when (e.type) {
            EventTypes.SALE_RECORDED -> sale(branch, mapper.treeToValue(e.payload, SaleRecorded::class.java))
            EventTypes.STOCK_LEVELS -> stock(branch, mapper.treeToValue(e.payload, StockLevels::class.java), odt(e.occurredAt))
            EventTypes.SHIFT_CLOSED -> shift(branch, mapper.treeToValue(e.payload, ShiftClosed::class.java))
            EventTypes.CUSTOMER_UPDATED -> customer(branch, mapper.treeToValue(e.payload, CustomerUpdated::class.java), odt(e.occurredAt))
            else -> log.warn("Unknown event type {} stored without projection", e.type)
        }
    }

    private fun sale(branch: Branch, s: SaleRecorded) {
        val id = UUID.fromString(s.saleId)
        jdbc.sql(
            """
            INSERT INTO sale (id, branch_id, receipt_type, original_sale_id, trade_invoice_no, invc_no, shift_id, till_id,
                              cashier_name, customer_id, total_cents, tax_cents, signed_at, sdc_id, scu_rcpt_no)
            VALUES (:id, :b, :rt, :orig, :trd, :n, :shift, :till, :cashier, :cust, :tot, :tax, :at, :sdc, :rcpt)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", id).param("b", branch.id).param("rt", s.receiptType).param("orig", s.originalSaleId?.let(UUID::fromString))
            .param("trd", s.tradeInvoiceNo).param("n", s.invcNo).param("shift", UUID.fromString(s.shiftId)).param("till", s.tillId)
            .param("cashier", s.cashierName).param("cust", s.customerId?.let(UUID::fromString)).param("tot", s.totalCents)
            .param("tax", s.taxCents).param("at", odt(s.signedAt)).param("sdc", s.scu.sdcId).param("rcpt", s.scu.rcptNo)
            .update()
        s.lines.forEachIndexed { i, l ->
            jdbc.sql(
                """
                INSERT INTO sale_line (sale_id, seq, product_code, name, qty_milli, unit_price_cents, total_cents, tax_type)
                VALUES (:id, :seq, :code, :name, :qty, :price, :tot, :tax) ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("id", id).param("seq", i + 1).param("code", l.productCode).param("name", l.name).param("qty", l.qtyMilli)
                .param("price", l.unitPriceCents).param("tot", l.totalCents).param("tax", l.taxType).update()
        }
        s.tenders.forEachIndexed { i, t ->
            jdbc.sql("INSERT INTO sale_tender (sale_id, seq, method, amount_cents, mpesa_code) VALUES (:id, :seq, :m, :a, :c) ON CONFLICT DO NOTHING")
                .param("id", id).param("seq", i + 1).param("m", t.method).param("a", t.amountCents).param("c", t.mpesaCode).update()
        }
    }

    private fun stock(branch: Branch, s: StockLevels, at: OffsetDateTime) = s.levels.forEach { l ->
        jdbc.sql(
            """
            INSERT INTO stock_level (branch_id, product_code, on_hand_milli, reorder_level_milli, as_of)
            VALUES (:b, :c, :on, :ro, :at)
            ON CONFLICT (branch_id, product_code) DO UPDATE SET on_hand_milli = excluded.on_hand_milli,
                reorder_level_milli = excluded.reorder_level_milli, as_of = excluded.as_of
            WHERE stock_level.as_of <= excluded.as_of
            """.trimIndent(),
        ).param("b", branch.id).param("c", l.productCode).param("on", l.onHandMilli).param("ro", l.reorderLevelMilli).param("at", at).update()
    }

    private fun shift(branch: Branch, s: ShiftClosed) {
        jdbc.sql(
            """
            INSERT INTO shift_close (shift_id, branch_id, till_id, cashier_name, opened_at, closed_at, float_cents,
                                     expected_cash_cents, counted_cash_cents, variance_cents, sales_count, sales_total_cents)
            VALUES (:id, :b, :till, :name, :o, :c, :f, :e, :cnt, :v, :n, :tot) ON CONFLICT (shift_id) DO NOTHING
            """.trimIndent(),
        ).param("id", UUID.fromString(s.shiftId)).param("b", branch.id).param("till", s.tillId).param("name", s.cashierName)
            .param("o", odt(s.openedAt)).param("c", odt(s.closedAt)).param("f", s.floatCents).param("e", s.expectedCashCents)
            .param("cnt", s.countedCashCents).param("v", s.varianceCents).param("n", s.salesCount).param("tot", s.salesTotalCents).update()
    }

    private fun customer(branch: Branch, c: CustomerUpdated, at: OffsetDateTime) {
        jdbc.sql(
            """
            INSERT INTO customer (id, branch_id, name, phone, credit_limit_cents, balance_cents, updated_at)
            VALUES (:id, :b, :n, :p, :l, :bal, :at)
            ON CONFLICT (id) DO UPDATE SET name = excluded.name, phone = excluded.phone, credit_limit_cents = excluded.credit_limit_cents,
                balance_cents = excluded.balance_cents, updated_at = excluded.updated_at
            WHERE customer.updated_at <= excluded.updated_at
            """.trimIndent(),
        ).param("id", UUID.fromString(c.customerId)).param("b", branch.id).param("n", c.name).param("p", c.phone)
            .param("l", c.creditLimitCents).param("bal", c.balanceCents).param("at", at).update()
    }

    companion object {
        private val VSCU_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

        /** Accepts ISO instants from the hub and yyyyMMddHHmmss stamps from the VSCU (Nairobi time). */
        fun odt(s: String): OffsetDateTime =
            if (s.length == 14 && s.all(Char::isDigit)) LocalDateTime.parse(s, VSCU_STAMP).atZone(NAIROBI).toOffsetDateTime()
            else OffsetDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC)
    }
}
