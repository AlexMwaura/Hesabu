package ke.hesabu.server.mpesa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import ke.hesabu.server.ServerProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkPushAck(
    @JsonProperty("MerchantRequestID") val merchantRequestId: String? = null,
    @JsonProperty("CheckoutRequestID") val checkoutRequestId: String? = null,
    @JsonProperty("ResponseCode") val responseCode: String? = null,
    @JsonProperty("ResponseDescription") val responseDescription: String? = null,
    @JsonProperty("errorMessage") val errorMessage: String? = null,
)

/**
 * Safaricom Daraja: OAuth token, M-Pesa Express (STK Push) and C2B URL registration.
 * TODO(sandbox): run against the Daraja sandbox; add retries and a circuit breaker before production.
 */
@Component
class DarajaClient(private val props: ServerProperties, private val clock: Clock, builder: RestClient.Builder) {
    private val http: RestClient = builder.baseUrl(props.daraja.baseUrl).build()

    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Instant = Instant.EPOCH

    @Synchronized
    fun accessToken(): String {
        val now = clock.instant()
        token?.let { if (now.isBefore(tokenExpiresAt)) return it }
        val basic = Base64.getEncoder().encodeToString("${props.daraja.consumerKey}:${props.daraja.consumerSecret}".toByteArray())
        val res = http.get().uri("/oauth/v1/generate?grant_type=client_credentials")
            .header(HttpHeaders.AUTHORIZATION, "Basic $basic").retrieve().body<Map<String, Any?>>()
            ?: error("Empty token response from Daraja")
        val expiresIn = res["expires_in"].toString().toLong()
        tokenExpiresAt = now.plusSeconds(expiresIn - 60)
        return res["access_token"].toString().also { token = it }
    }

    /** Sends an M-Pesa prompt. The ack only means "queued"; the result arrives on the callback. */
    fun stkPush(shortCode: String, passkey: String, phone: String, amountShillings: Long, accountRef: String): StkPushAck {
        val ts = TIMESTAMP.format(clock.instant().atZone(clock.zone))
        val body = mapOf(
            "BusinessShortCode" to shortCode,
            "Password" to password(shortCode, passkey, ts),
            "Timestamp" to ts,
            // TODO(sandbox): Buy Goods tills use CustomerBuyGoodsOnline and PartyB = till number; confirm per branch.
            "TransactionType" to "CustomerPayBillOnline",
            "Amount" to amountShillings,
            "PartyA" to phone,
            "PartyB" to shortCode,
            "PhoneNumber" to phone,
            "CallBackURL" to props.daraja.stkCallbackUrl,
            "AccountReference" to accountRef.take(12),
            "TransactionDesc" to "Hesabu sale",
        )
        return post("/mpesa/stkpush/v1/processrequest", body).body<StkPushAck>() ?: error("Empty STK response")
    }

    fun registerC2b(shortCode: String): Map<String, Any?> = post(
        "/mpesa/c2b/v2/registerurl",
        mapOf(
            "ShortCode" to shortCode,
            // "Completed": if our validation URL is down, Safaricom completes the payment anyway.
            "ResponseType" to "Completed",
            "ConfirmationURL" to props.daraja.c2bConfirmationUrl,
            "ValidationURL" to props.daraja.c2bValidationUrl,
        ),
    ).body<Map<String, Any?>>() ?: emptyMap()

    private fun post(path: String, body: Any) = http.post().uri(path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}")
        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()

    companion object {
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** base64(shortcode + passkey + timestamp), timestamp in Nairobi time. */
        fun password(shortCode: String, passkey: String, timestamp: String): String =
            Base64.getEncoder().encodeToString("$shortCode$passkey$timestamp".toByteArray())
    }
}
