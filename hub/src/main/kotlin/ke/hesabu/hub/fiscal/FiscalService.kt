package ke.hesabu.hub.fiscal

import ke.hesabu.core.etims.InitInfoReq
import ke.hesabu.hub.VscuProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the branch's invoice number series. Signing is serialised: the VSCU rejects a number it has
 * already seen (994), so two tills must never be given the same next number.
 */
@Service
class FiscalService(
    private val vscu: VscuClient,
    private val props: VscuProperties,
    private val jdbc: JdbcClient,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock(true)

    @Volatile private var inSync = false

    fun lastInvcNo(): Long =
        jdbc.sql("SELECT last_invc_no FROM fiscal_counter WHERE id = 1").query { rs, _ -> rs.getLong(1) }.single()

    /** Call inside the signing transaction once a number has been used. */
    fun advanceTo(invcNo: Long) {
        jdbc.sql("UPDATE fiscal_counter SET last_invc_no = :n WHERE id = 1 AND last_invc_no < :n").param("n", invcNo).update()
    }

    /** TIS spec 6.3: continue from the last invoice the VSCU recorded. Throws if the VSCU is unreachable. */
    fun resync(): Long {
        val res = vscu.initInfo(InitInfoReq(props.tin, props.bhfId, props.deviceSerial))
        val fromVscu = res.data?.info?.lastSaleInvcNo ?: 0
        advanceTo(fromVscu)
        inSync = true
        return lastInvcNo()
    }

    fun markOutOfSync() {
        inSync = false
    }

    val isInSync: Boolean get() = inSync

    /** Runs [block] holding the signing lock, resyncing the number series with the VSCU first if needed. */
    fun <T> exclusive(block: () -> T): T = lock.withLock {
        if (!inSync) resync()
        block()
    }

    override fun run(args: ApplicationArguments) {
        try {
            log.info("Invoice series restored from VSCU: last invoice {}", lock.withLock { resync() })
        } catch (e: RuntimeException) {
            log.warn("VSCU not reachable at start-up ({}); will retry before the first sale", e.message)
        }
    }
}
