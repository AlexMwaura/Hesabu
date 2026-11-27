package ke.hesabu.spike

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A clock tests can move forward, to simulate hours of outage in milliseconds. */
class MutableClock(private var now: Instant, private val zone: ZoneId = NAIROBI) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}
