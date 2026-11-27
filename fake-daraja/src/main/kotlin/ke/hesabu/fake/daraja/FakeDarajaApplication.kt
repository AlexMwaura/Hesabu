package ke.hesabu.fake.daraja

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A stand-in for Safaricom's Daraja API so Hesabu's M-Pesa flows run before a sandbox app exists.
 * Same paths and JSON shapes as Daraja; it calls the registered callback URLs like Safaricom does.
 *
 * TODO(sandbox): register a Daraja sandbox app, point hesabu.daraja.base-url at
 * https://sandbox.safaricom.co.ke, and compare real callbacks with what this fake sends.
 *
 * Test hooks (not in Daraja):
 * - an STK prompt to a phone ending in 0000 is "cancelled by the user" (ResultCode 1032);
 * - POST /_fake/c2b/pay makes a customer pay a till and returns the M-Pesa code, so tests can type it.
 */
@SpringBootApplication(
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
class FakeDarajaApplication

object FakeDaraja {
    fun builder(): SpringApplicationBuilder =
        SpringApplicationBuilder(FakeDarajaApplication::class.java).properties("spring.config.name=fake-daraja")
}

fun main(args: Array<String>) {
    FakeDaraja.builder().run(*args)
}

@Component
class FakeDarajaState {
    /** Registered C2B URLs by short code. */
    val c2bUrls = ConcurrentHashMap<String, Map<String, Any?>>()
    val payments = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
    val stkPrompts = java.util.Collections.synchronizedList(mutableListOf<Map<String, Any?>>())

    fun newCode(): String {
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val chars = letters + "0123456789"
        return "T" + letters[rng.nextInt(letters.length)] + (1..8).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private val rng = SecureRandom()
    }
}

@RestController
class FakeDarajaController(
    private val state: FakeDarajaState,
    builder: RestClient.Builder,
    @Value("\${fake.daraja.stk-delay-ms:800}") private val stkDelayMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = builder.build()
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val nairobi = ZoneId.of("Africa/Nairobi")

    @GetMapping("/oauth/v1/generate")
    fun token(@RequestHeader("Authorization", required = false) auth: String?): ResponseEntity<Any> {
        if (auth == null || !auth.startsWith("Basic ")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("errorMessage" to "Invalid Authentication passed"))
        }
        return ResponseEntity.ok(mapOf("access_token" to "fake-" + UUID.randomUUID(), "expires_in" to "3599"))
    }

    @PostMapping("/mpesa/stkpush/v1/processrequest")
    fun stkPush(@RequestBody req: Map<String, Any?>): Map<String, Any?> {
        val checkoutId = "ws_CO_" + LocalDateTime.now(nairobi).format(stamp) + (1000..9999).random()
        val merchantId = UUID.randomUUID().toString().take(18)
        val phone = req["PhoneNumber"].toString()
        val amount = (req["Amount"] as Number).toLong()
        val callback = req["CallBackURL"].toString()
        state.stkPrompts += mapOf("checkoutRequestId" to checkoutId, "phone" to phone, "amount" to amount, "shortCode" to req["BusinessShortCode"])

        scheduler.schedule({
            val cb = if (phone.endsWith("0000")) {
                mapOf("MerchantRequestID" to merchantId, "CheckoutRequestID" to checkoutId, "ResultCode" to 1032, "ResultDesc" to "Request cancelled by user")
            } else {
                val code = state.newCode()
                state.payments += mapOf("transId" to code, "shortCode" to req["BusinessShortCode"], "amount" to amount, "source" to "STK")
                mapOf(
                    "MerchantRequestID" to merchantId, "CheckoutRequestID" to checkoutId, "ResultCode" to 0,
                    "ResultDesc" to "The service request is processed successfully.",
                    "CallbackMetadata" to mapOf(
                        "Item" to listOf(
                            mapOf("Name" to "Amount", "Value" to amount),
                            mapOf("Name" to "MpesaReceiptNumber", "Value" to code),
                            mapOf("Name" to "TransactionDate", "Value" to LocalDateTime.now(nairobi).format(stamp).toLong()),
                            mapOf("Name" to "PhoneNumber", "Value" to phone.toLong()),
                        ),
                    ),
                )
            }
            send(callback, mapOf("Body" to mapOf("stkCallback" to cb)))
        }, stkDelayMs, TimeUnit.MILLISECONDS)

        return mapOf(
            "MerchantRequestID" to merchantId, "CheckoutRequestID" to checkoutId, "ResponseCode" to "0",
            "ResponseDescription" to "Success. Request accepted for processing", "CustomerMessage" to "Success. Request accepted for processing",
        )
    }

    @PostMapping("/mpesa/c2b/v2/registerurl")
    fun register(@RequestBody req: Map<String, Any?>): Map<String, Any?> {
        state.c2bUrls[req["ShortCode"].toString()] = req
        return mapOf("OriginatorCoversationID" to UUID.randomUUID().toString(), "ResponseCode" to "0", "ResponseDescription" to "Success")
    }

    /** Like Daraja's sandbox simulator: the confirmation goes to the registered URL. */
    @PostMapping("/mpesa/c2b/v1/simulate")
    fun simulate(@RequestBody req: Map<String, Any?>): Map<String, Any?> {
        pay(req["ShortCode"].toString(), (req["Amount"] as Number).toLong(), req["Msisdn"]?.toString(), req["BillRefNumber"]?.toString())
        return mapOf("OriginatorCoversationID" to UUID.randomUUID().toString(), "ResponseCode" to "0", "ResponseDescription" to "Accept the service request successfully.")
    }

    data class Pay(val shortCode: String, val amount: Long, val msisdn: String? = null, val billRef: String? = null)

    /** Test hook: a customer pays [shortCode]; returns the M-Pesa code they would see in their SMS. */
    @PostMapping("/_fake/c2b/pay", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun fakePay(@RequestBody req: Pay): Map<String, Any?> = mapOf("transId" to pay(req.shortCode, req.amount, req.msisdn, req.billRef))

    @GetMapping("/_fake/state")
    fun fakeState() = mapOf("c2bUrls" to state.c2bUrls, "payments" to state.payments, "stkPrompts" to state.stkPrompts)

    private fun pay(shortCode: String, amount: Long, msisdn: String?, billRef: String?): String {
        val code = state.newCode()
        state.payments += mapOf("transId" to code, "shortCode" to shortCode, "amount" to amount, "source" to "C2B")
        val urls = state.c2bUrls[shortCode]
        if (urls == null) {
            log.warn("No C2B URLs registered for {}; confirmation for {} not sent", shortCode, code)
            return code
        }
        // Daraja v2 masks the payer's number; the fake does the same.
        send(
            urls["ConfirmationURL"].toString(),
            mapOf(
                "TransactionType" to "Pay Bill", "TransID" to code, "TransTime" to LocalDateTime.now(nairobi).format(stamp),
                "TransAmount" to "%d.00".format(amount), "BusinessShortCode" to shortCode, "BillRefNumber" to (billRef ?: ""),
                "MSISDN" to (msisdn?.let { it.take(6) + "***" + it.takeLast(3) } ?: "2547***149"), "FirstName" to "John",
            ),
        )
        return code
    }

    private fun send(url: String, body: Any) {
        try {
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity()
        } catch (e: Exception) {
            log.warn("Callback to {} failed: {}", url, e.message)
        }
    }
}
