package ke.hesabu.spike.daraja

import ke.hesabu.spike.MutableClock
import ke.hesabu.spike.daraja.PaymentMatcher.Source
import ke.hesabu.spike.daraja.PaymentMatcher.Status
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

class PaymentMatcherTest {
    private val clock = MutableClock(Instant.parse("2026-11-27T09:00:00Z"))
    private val matcher = PaymentMatcher(clock)
    private val till = "600000"

    @Test
    fun `code typed offline becomes verified when the confirmation arrives`() {
        assertThat(matcher.claim("sale-1", "skr4abc123", 500, till).status).isEqualTo(Status.PENDING)
        matcher.onConfirmation("SKR4ABC123", 500, till, Source.C2B)
        assertThat(matcher.claimFor("sale-1")!!.status).isEqualTo(Status.VERIFIED)
    }

    @Test
    fun `confirmation that arrived first verifies the code immediately`() {
        matcher.onConfirmation("SKR4ABC123", 500, till, Source.C2B)
        assertThat(matcher.claim("sale-1", "SKR4ABC123", 500, till).status).isEqualTo(Status.VERIFIED)
    }

    @Test
    fun `duplicate callbacks from Safaricom are ignored`() {
        assertThat(matcher.onConfirmation("SKR4ABC123", 500, till, Source.C2B)).isTrue()
        assertThat(matcher.onConfirmation("SKR4ABC123", 500, till, Source.C2B)).isFalse()
        assertThat(matcher.shiftReport().unclaimed).hasSize(1)
    }

    @Test
    fun `less money than the sale total is flagged`() {
        matcher.onConfirmation("SKR4ABC123", 450, till, Source.C2B)
        val claim = matcher.claim("sale-1", "SKR4ABC123", 500, till)
        assertThat(claim.status).isEqualTo(Status.AMOUNT_SHORT)
        assertThat(claim.confirmedAmount).isEqualTo(450)
    }

    @Test
    fun `payment to a different till is flagged`() {
        matcher.onConfirmation("SKR4ABC123", 500, "999999", Source.C2B)
        assertThat(matcher.claim("sale-1", "SKR4ABC123", 500, till).status).isEqualTo(Status.WRONG_TILL)
    }

    @Test
    fun `one customer message cannot pay for two sales`() {
        matcher.onConfirmation("SKR4ABC123", 500, till, Source.C2B)
        matcher.claim("sale-1", "SKR4ABC123", 500, till)
        assertThat(matcher.claim("sale-2", "SKR4ABC123", 500, till).status).isEqualTo(Status.CODE_ALREADY_USED)
        assertThat(matcher.claimFor("sale-1")!!.status).isEqualTo(Status.VERIFIED)
    }

    @Test
    fun `a code that never arrives stays pending and shows as stale`() {
        matcher.claim("sale-1", "FAKE123456", 500, till)
        clock.advance(Duration.ofMinutes(10))
        assertThat(matcher.stalePending(Duration.ofMinutes(5))).extracting<String> { it.saleId }.containsExactly("sale-1")
        assertThat(matcher.shiftReport().pendingTotal).isEqualTo(500)
    }

    @Test
    fun `shift report separates verified, pending, problems and unclaimed money`() {
        matcher.onConfirmation("AAAAAAAAA1", 100, till, Source.C2B)
        matcher.claim("s1", "AAAAAAAAA1", 100, till)
        matcher.claim("s2", "BBBBBBBBB2", 200, till)
        matcher.onConfirmation("CCCCCCCCC3", 50, till, Source.C2B)
        matcher.claim("s3", "CCCCCCCCC3", 80, till)
        matcher.onConfirmation("DDDDDDDDD4", 300, till, Source.C2B)

        val r = matcher.shiftReport()
        assertThat(r.verified.map { it.saleId }).containsExactly("s1")
        assertThat(r.pending.map { it.saleId }).containsExactly("s2")
        assertThat(r.problems.map { it.saleId }).containsExactly("s3")
        assertThat(r.unclaimed.map { it.code }).containsExactly("DDDDDDDDD4")
        assertThat(r.verifiedTotal).isEqualTo(100)
    }

    @Test
    fun `malformed codes are refused at the till`() {
        assertThatThrownBy { matcher.claim("s1", "12345", 100, till) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `stk password is base64 of shortcode, passkey and timestamp`() {
        val pw = StkPassword.of("174379", "passkey", "20261127120000")
        assertThat(String(Base64.getDecoder().decode(pw))).isEqualTo("174379passkey20261127120000")
        assertThat(StkPassword.timestamp(clock)).isEqualTo("20261127120000") // 09:00Z is 12:00 in Nairobi
    }
}
