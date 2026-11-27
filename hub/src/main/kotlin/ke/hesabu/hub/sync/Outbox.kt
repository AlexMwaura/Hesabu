package ke.hesabu.hub.sync

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class OutboxRow(val seq: Long, val id: String, val type: String, val payload: String, val createdAt: String)

/** Events written in the same transaction as the change they describe; [SyncService] sends them. */
@Component
class Outbox(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    fun enqueue(type: String, payload: Any, at: Instant) {
        jdbc.sql("INSERT INTO outbox (id, type, payload, created_at) VALUES (:id, :type, :payload, :at)")
            .param("id", UUID.randomUUID().toString())
            .param("type", type)
            .param("payload", mapper.writeValueAsString(payload))
            .param("at", at.toString())
            .update()
    }

    fun nextBatch(limit: Int): List<OutboxRow> =
        jdbc.sql("SELECT seq, id, type, payload, created_at FROM outbox WHERE sent_at IS NULL ORDER BY seq LIMIT :n")
            .param("n", limit)
            .query { rs, _ -> OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
            .list()

    fun markSent(ids: Collection<String>, at: Instant) = ids.forEach {
        jdbc.sql("UPDATE outbox SET sent_at = :at WHERE id = :id").param("at", at.toString()).param("id", it).update()
    }

    fun markFailed(ids: Collection<String>, error: String) = ids.forEach {
        jdbc.sql("UPDATE outbox SET attempts = attempts + 1, last_error = :e WHERE id = :id").param("e", error.take(500)).param("id", it).update()
    }

    fun unsentCount(): Long = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL").query { rs, _ -> rs.getLong(1) }.single()

    fun unsentOfType(type: String): List<OutboxRow> =
        jdbc.sql("SELECT seq, id, type, payload, created_at FROM outbox WHERE sent_at IS NULL AND type = :t ORDER BY seq")
            .param("t", type)
            .query { rs, _ -> OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
            .list()
}
