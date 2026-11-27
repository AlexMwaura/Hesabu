package ke.hesabu.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import ke.hesabu.fake.daraja.FakeDaraja
import ke.hesabu.fake.vscu.FakeVscu
import ke.hesabu.hub.Hub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

/**
 * The whole Phase 1 back end in one JVM: cloud server on embedded Postgres, a hub on SQLite, the fake
 * VSCU and the fake Daraja. Tests run in order and share one shop, like a trading day.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EndToEndTest {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val contexts = mutableListOf<ConfigurableApplicationContext>()
    private lateinit var pg: EmbeddedPostgres

    private lateinit var serverUrl: String
    private lateinit var hubUrl: String
    private lateinit var darajaUrl: String
    private lateinit var ownerKey: String
    private lateinit var branchId: String

    private val adminToken = "e2e-admin"
    private val tillToken = "e2e-till"
    private val c2bShortCode = "600111"
    private val stkShortCode = "174379"
    private val till = "T1"

    private val http = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError) { _, _ -> }.build()

    private fun port(ctx: ConfigurableApplicationContext) = (ctx as ServletWebServerApplicationContext).webServer.port
    private fun freePort() = ServerSocket(0).use { it.localPort }

    @BeforeAll
    fun startEverything() {
        pg = EmbeddedPostgres.start()

        val daraja = FakeDaraja.builder().run("--server.port=0", "--fake.daraja.stk-delay-ms=200").also(contexts::add)
        darajaUrl = "http://localhost:${port(daraja)}"

        val serverPort = freePort()
        serverUrl = "http://localhost:$serverPort"
        Server.builder().run(
            "--server.port=$serverPort",
            "--spring.datasource.url=${pg.getJdbcUrl("postgres", "postgres")}",
            "--spring.datasource.username=postgres",
            "--spring.datasource.password=postgres",
            "--hesabu.admin-token=$adminToken",
            "--hesabu.daraja.base-url=$darajaUrl",
            "--hesabu.daraja.public-base-url=$serverUrl",
        ).also(contexts::add)

        // Onboard a shop: business, branch (with C2B till and STK short code), catalogue.
        val business = call("POST", "$serverUrl/api/admin/v1/businesses", mapOf("name" to "Duka la Mama", "kraPin" to "P051234567X"), admin = true)
        ownerKey = business["ownerKey"].asText()
        val branch = call(
            "POST", "$serverUrl/api/admin/v1/businesses/${business["id"].asText()}/branches",
            mapOf(
                "name" to "Kawangware", "code" to "KWG", "mpesaShortCode" to c2bShortCode, "stkShortCode" to stkShortCode,
                "stkPasskey" to "bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919",
            ),
            admin = true,
        )
        branchId = branch["id"].asText()
        val hubKey = branch["hubKey"].asText()
        call("POST", "$serverUrl/api/admin/v1/branches/$branchId/c2b/register", null, admin = true)

        owner("PUT", "/products/OIL", mapOf("itemClassCode" to "5020230601", "name" to "Cooking oil 1L", "priceCents" to 35_000, "taxType" to "B", "reorderLevelMilli" to 18_000))
        owner("PUT", "/products/UNGA", mapOf("itemClassCode" to "5020230602", "name" to "Maize flour 2kg", "priceCents" to 21_000, "taxType" to "C"))

        val vscu = FakeVscu.builder().run("--server.port=0").also(contexts::add)
        Hub.builder().run(
            "--server.port=0",
            "--spring.datasource.url=jdbc:sqlite:${Files.createTempFile("hesabu-e2e-hub", ".db")}",
            "--hesabu.hub.branch-code=KWG",
            "--hesabu.hub.till-token=$tillToken",
            "--hesabu.hub.cloud.base-url=$serverUrl",
            "--hesabu.hub.cloud.hub-key=$hubKey",
            "--hesabu.hub.cloud.sync-enabled=false",
            "--hesabu.vscu.base-url=http://localhost:${port(vscu)}",
        ).also { contexts += it; hubUrl = "http://localhost:${port(it)}" }
    }

    @AfterAll
    fun stopEverything() {
        contexts.reversed().forEach { it.close() }
        pg.close()
    }

    // ---- helpers

    private fun call(method: String, url: String, body: Any?, admin: Boolean = false, bearer: String? = null, tillId: String? = null): JsonNode {
        val spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url).contentType(MediaType.APPLICATION_JSON)
        if (admin) spec.header("X-Admin-Token", adminToken)
        bearer?.let { spec.header("Authorization", "Bearer $it") }
        tillId?.let { spec.header("X-Till-Id", it) }
        if (body != null) spec.body(mapper.writeValueAsString(body))
        val text = spec.retrieve().body(String::class.java) ?: "{}"
        return mapper.readTree(text)
    }

    private fun owner(method: String, path: String, body: Any? = null) = call(method, "$serverUrl/api/owner/v1$path", body, bearer = ownerKey)
    private fun hub(method: String, path: String, body: Any? = null) = call(method, "$hubUrl/api/till/v1$path", body, bearer = tillToken, tillId = till)

    /** A customer pays the shop's till from their phone; returns the code from their SMS. */
    private fun customerPays(shillings: Long) =
        call("POST", "$darajaUrl/_fake/c2b/pay", mapOf("shortCode" to c2bShortCode, "amount" to shillings, "msisdn" to "254712345678"))["transId"].asText()

    private fun sale(lines: List<Pair<String, Int>>, tender: Map<String, Any?>) = hub(
        "POST", "/sales",
        mapOf("clientRef" to UUID.randomUUID().toString(), "lines" to lines.map { mapOf("productCode" to it.first, "qty" to it.second) }, "tenders" to listOf(tender)),
    )

    private fun waitForStk(checkoutId: String): JsonNode {
        repeat(50) {
            val s = hub("GET", "/mpesa/stk/$checkoutId")
            if (s["state"].asText() != "WAITING") return s
            Thread.sleep(100)
        }
        error("STK $checkoutId still waiting")
    }

    private fun summary() = owner("GET", "/branches/$branchId/summary")

    // ---- the day

    @Test @Order(1)
    fun `hub pulls the catalogue from the cloud and opens the till`() {
        val sync = hub("POST", "/sync")
        assertThat(sync["online"].asBoolean()).isTrue()
        assertThat(sync["productsUpdated"].asInt()).isEqualTo(2)
        assertThat(hub("GET", "/catalogue").map { it["code"].asText() }).containsExactlyInAnyOrder("OIL", "UNGA")

        hub("POST", "/stock/receipts", mapOf("lines" to listOf(mapOf("productCode" to "OIL", "qty" to 20), mapOf("productCode" to "UNGA", "qty" to 10))))
        assertThat(hub("POST", "/shifts", mapOf("cashierId" to "C01", "cashierName" to "Wanjiku", "floatCents" to 100_000))["id"]).isNotNull()
    }

    @Test @Order(2)
    fun `a typed M-Pesa code is verified against Safaricom's confirmation`() {
        val code = customerPays(350)
        val check = hub("POST", "/mpesa/check", mapOf("code" to code, "amountCents" to 35_000))
        assertThat(check["status"].asText()).isEqualTo("VERIFIED")
        assertThat(check["offline"].asBoolean()).isFalse()

        val receipt = sale(listOf("OIL" to 1), mapOf("method" to "MPESA_CODE", "amountCents" to 35_000, "mpesaCode" to code))
        assertThat(receipt["label"].asText()).isEqualTo("NS")
        assertThat(receipt["payments"][0]["mpesaStatus"].asText()).isEqualTo("VERIFIED")
    }

    @Test @Order(3)
    fun `a short payment is caught before the sale`() {
        val code = customerPays(300)
        assertThat(hub("POST", "/mpesa/check", mapOf("code" to code, "amountCents" to 35_000))["status"].asText()).isEqualTo("AMOUNT_SHORT")
    }

    @Test @Order(4)
    fun `an STK prompt is paid, and its code verifies the sale`() {
        val started = hub("POST", "/mpesa/stk", mapOf("ref" to "sale-stk-1", "phone" to "254708374149", "amountCents" to 70_000))
        assertThat(started["state"].asText()).isEqualTo("WAITING")
        val paid = waitForStk(started["checkoutRequestId"].asText())
        assertThat(paid["state"].asText()).isEqualTo("PAID")

        val receipt = sale(
            listOf("OIL" to 2),
            mapOf("method" to "MPESA_STK", "amountCents" to 70_000, "mpesaCode" to paid["mpesaCode"].asText(), "checkoutRequestId" to paid["checkoutRequestId"].asText()),
        )
        assertThat(receipt["payments"][0]["mpesaStatus"].asText()).isEqualTo("VERIFIED")
    }

    @Test @Order(5)
    fun `a cancelled STK prompt is reported as failed`() {
        val started = hub("POST", "/mpesa/stk", mapOf("ref" to "sale-stk-2", "phone" to "254700000000", "amountCents" to 10_000))
        val result = waitForStk(started["checkoutRequestId"].asText())
        assertThat(result["state"].asText()).isEqualTo("FAILED")
        assertThat(result["resultDesc"].asText()).contains("cancelled")
    }

    @Test @Order(6)
    fun `a confirmation that arrives after the sale verifies it on the next sync, and a retried callback counts once`() {
        val code = "QK" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val receipt = sale(listOf("UNGA" to 1), mapOf("method" to "MPESA_CODE", "amountCents" to 21_000, "mpesaCode" to code))
        assertThat(receipt["payments"][0]["mpesaStatus"].asText()).isEqualTo("PENDING")

        val confirmation = mapOf("TransID" to code, "TransAmount" to "210.00", "BusinessShortCode" to c2bShortCode, "TransactionType" to "Pay Bill")
        repeat(2) { assertThat(call("POST", "$serverUrl/hooks/c2b/confirm", confirmation)["ResultCode"].asInt()).isZero() }

        hub("POST", "/sync")
        val reprint = hub("GET", "/sales/${receipt["saleId"].asText()}/receipt?copy=true")
        assertThat(reprint["label"].asText()).isEqualTo("CS")
        assertThat(reprint["payments"][0]["mpesaStatus"].asText()).isEqualTo("VERIFIED")
    }

    @Test @Order(7)
    fun `the owner's daily summary reflects the hub's day, and resyncing changes nothing`() {
        val first = hub("POST", "/sync")
        assertThat(first["online"].asBoolean()).isTrue()
        hub("POST", "/sync")

        val s = summary()
        assertThat(s["sales"]["count"].asInt()).isEqualTo(3)
        assertThat(s["sales"]["grossCents"].asLong()).isEqualTo(126_000)
        assertThat(s["paymentsByMethod"]["MPESA_CODE"].asLong()).isEqualTo(56_000)
        assertThat(s["paymentsByMethod"]["MPESA_STK"].asLong()).isEqualTo(70_000)
        assertThat(s["mpesa"]["verifiedCents"].asLong()).isEqualTo(126_000)
        assertThat(s["mpesa"]["pendingCents"].asLong()).isZero()
        // The 300-shilling payment that was too short is money on the till that no sale claimed.
        assertThat(s["mpesa"]["unclaimedCents"].asLong()).isEqualTo(30_000)
        assertThat(s["lowStock"].map { it["productCode"].asText() }).containsExactly("OIL")
        assertThat(s["lastHubEventAt"].isNull).isFalse()
        assertThat(hub("GET", "/status")["unsyncedEvents"].asLong()).isZero()
    }
}
