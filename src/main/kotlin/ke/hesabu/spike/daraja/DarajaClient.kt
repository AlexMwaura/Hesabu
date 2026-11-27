package ke.hesabu.spike.daraja

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Minimal Daraja client for the spike: OAuth token, STK Push, C2B URL registration and the sandbox C2B
 * simulator. Not production code: no retries, no circuit breaking, token cached in memory only.
 */
@Component
class DarajaClient(
    private val props: DarajaProperties,
    private val clock: Clock,
    builder: RestClient.Builder,
) {
    private val http: RestClient = builder.baseUrl(props.baseUrl).build()

    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAt: Instant = Instant.EPOCH

    fun accessToken(): String {
        val now = clock.instant()
        token?.let { if (now.isBefore(tokenExpiresAt)) return it }
        val basic = Base64.getEncoder().encodeToString("${props.consumerKey}:${props.consumerSecret}".toByteArray())
        val res = http.get()
            .uri("/oauth/v1/generate?grant_type=client_credentials")
            .header(HttpHeaders.AUTHORIZATION, "Basic $basic")
            .retrieve()
            .body<TokenResponse>() ?: error("Empty token response from Daraja")
        // Refresh a minute early so a token never expires mid-request.
        tokenExpiresAt = now.plusSeconds(res.expiresIn.toLong() - 60)
        token = res.accessToken
        return res.accessToken
    }

    /** Sends an M-Pesa prompt to [phone] (2547XXXXXXXX). The ack only means "queued"; the result comes on the callback. */
    fun stkPush(phone: String, amount: Long, accountReference: String, buyGoods: Boolean = false): StkPushAck {
        val ts = StkPassword.timestamp(clock)
        val req = StkPushRequest(
            businessShortCode = props.stkShortCode,
            password = StkPassword.of(props.stkShortCode, props.stkPasskey, ts),
            timestamp = ts,
            transactionType = if (buyGoods) "CustomerBuyGoodsOnline" else "CustomerPayBillOnline",
            amount = amount,
            partyA = phone,
            partyB = props.stkShortCode,
            phoneNumber = phone,
            callBackUrl = props.stkCallbackUrl,
            accountReference = accountReference.take(12),
            transactionDesc = "Hesabu sale".take(13),
        )
        return post("/mpesa/stkpush/v1/processrequest", req)
    }

    fun registerC2bUrls(): Map<*, *> = post(
        "/mpesa/c2b/v2/registerurl",
        RegisterUrlRequest(
            shortCode = props.c2bShortCode,
            // "Completed": if our validation URL is unreachable, Safaricom completes the payment anyway.
            responseType = "Completed",
            confirmationUrl = props.c2bConfirmationUrl,
            validationUrl = props.c2bValidationUrl,
        ),
    )

    /** Sandbox only: makes Safaricom send a C2B confirmation to our registered URL. */
    fun simulateC2b(amount: Long, msisdn: String, billRef: String): Map<*, *> = post(
        props.c2bSimulatePath,
        C2bSimulateRequest(props.c2bShortCode, "CustomerPayBillOnline", amount, msisdn, billRef),
    )

    private inline fun <reified T : Any> post(path: String, body: Any): T =
        http.post()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body<T>() ?: error("Empty response from Daraja $path")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("expires_in") val expiresIn: String,
    )
}
