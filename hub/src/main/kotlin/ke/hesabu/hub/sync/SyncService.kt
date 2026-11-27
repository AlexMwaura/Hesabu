package ke.hesabu.hub.sync

import com.fasterxml.jackson.databind.ObjectMapper
import ke.hesabu.hub.HubProperties
import ke.hesabu.hub.catalogue.CatalogueRepo
import ke.hesabu.hub.catalogue.Product
import ke.hesabu.hub.mpesa.HubMpesaService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock

data class SyncReport(
    val online: Boolean,
    val eventsSent: Int,
    val productsUpdated: Int,
    val claimsSent: Int,
    val claimsRefreshed: Int,
    val error: String? = null,
)

/** Moves data between the hub and the cloud. Safe to run at any time; every step is idempotent. */
@Service
class SyncService(
    private val outbox: Outbox,
    private val cloud: CloudClient,
    private val catalogue: CatalogueRepo,
    private val mpesa: HubMpesaService,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Synchronized
    fun runOnce(): SyncReport {
        var sent = 0
        var products = 0
        var claimsSent = 0
        var refreshed = 0
        return try {
            sent = pushEvents()
            products = pullCatalogue()
            claimsSent = mpesa.submitPending()
            refreshed = mpesa.refreshPending()
            setState("lastSyncAt", clock.instant().toString())
            SyncReport(true, sent, products, claimsSent, refreshed)
        } catch (e: CloudUnavailable) {
            SyncReport(false, sent, products, claimsSent, refreshed, e.message)
        } catch (e: CloudRefused) {
            log.warn("Cloud refused sync: {}", e.message)
            SyncReport(true, sent, products, claimsSent, refreshed, e.message)
        }
    }

    fun lastSyncAt(): String? = state("lastSyncAt")

    private fun pushEvents(): Int {
        var total = 0
        while (true) {
            val batch = outbox.nextBatch(100)
            if (batch.isEmpty()) return total
            val events = batch.map { OutgoingEvent(it.id, it.type, it.createdAt, mapper.readTree(it.payload)) }
            try {
                val accepted = cloud.pushEvents(events).accepted
                outbox.markSent(accepted, clock.instant())
                total += accepted.size
                if (accepted.size < batch.size) {
                    outbox.markFailed(batch.map { it.id } - accepted.toSet(), "not accepted by cloud")
                    return total
                }
            } catch (e: CloudUnavailable) {
                outbox.markFailed(batch.map { it.id }, e.message ?: "cloud unavailable")
                throw e
            }
        }
    }

    private fun pullCatalogue(): Int {
        val page = cloud.catalogue(catalogue.maxVersion())
        page.items.forEach { catalogue.upsert(Product.of(it)) }
        return page.items.size
    }

    private fun state(key: String): String? =
        jdbc.sql("SELECT value FROM sync_state WHERE key = :k").param("k", key).query { rs, _ -> rs.getString(1) }.optional().orElse(null)

    private fun setState(key: String, value: String) {
        jdbc.sql("INSERT INTO sync_state (key, value) VALUES (:k, :v) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
            .param("k", key).param("v", value).update()
    }
}

@Component
@ConditionalOnProperty("hesabu.hub.cloud.sync-enabled", havingValue = "true", matchIfMissing = true)
class SyncScheduler(private val sync: SyncService, private val props: HubProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 2000, fixedDelayString = "\${hesabu.hub.cloud.sync-interval-ms:5000}")
    fun tick() {
        if (props.cloud.hubKey.isBlank()) return
        val r = sync.runOnce()
        if (!r.online) log.debug("Offline: {}", r.error)
    }
}
