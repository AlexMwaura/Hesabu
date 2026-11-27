package ke.hesabu.hub

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ke.hesabu.core.TaxType
import ke.hesabu.core.etims.InitInfoReq
import ke.hesabu.fake.vscu.FakeVscu
import ke.hesabu.hub.catalogue.CatalogueRepo
import ke.hesabu.hub.catalogue.Product
import ke.hesabu.hub.fiscal.VscuClient
import ke.hesabu.hub.fiscal.VscuUnreachable
import ke.hesabu.hub.sync.Outbox
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import java.nio.file.Files
import java.util.UUID

/** The hub's till API end to end, against the fake VSCU over real HTTP, with the cloud unreachable. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.config.name=hub",
        "hesabu.hub.till-token=test-token",
        "hesabu.hub.cloud.base-url=http://localhost:1",
        "hesabu.hub.cloud.hub-key=unused",
        "hesabu.hub.cloud.sync-enabled=false",
    ],
)
class HubFlowTest(
    @Autowired private val rest: TestRestTemplate,
    @Autowired private val catalogue: CatalogueRepo,
    @Autowired private val outbox: Outbox,
    @Autowired private val mapper: ObjectMapper,
) {
    companion object {
        private val vscu = FakeVscu.builder().run("--server.port=0") as ServletWebServerApplicationContext
        private val vscuUrl = "http://localhost:${vscu.webServer.port}"
        private val db = Files.createTempFile("hesabu-hub-test", ".db")

        @JvmStatic
        @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) {
            r.add("hesabu.vscu.base-url") { vscuUrl }
            r.add("spring.datasource.url") { "jdbc:sqlite:$db" }
        }
    }

    private val fake = RestTemplate()
    private lateinit var till: String

    @BeforeEach
    fun setUp() {
        listOf(
            Product("OIL", "5020230601", "Cooking oil 1L", "6161100000011", 35_000, TaxType.B, false, "U", "NT", 3_000, true, 1),
            Product("UNGA", "5020230602", "Maize flour 2kg", null, 21_000, TaxType.C, false, "U", "NT", 0, true, 1),
            Product("DELIVERY", "8510100000", "Delivery", null, 10_000, TaxType.B, true, "U", "NT", 0, true, 1),
        ).forEach(catalogue::upsert)
        till = "T-" + UUID.randomUUID().toString().take(8)
    }

    // ---- helpers

    private fun call(method: HttpMethod, path: String, body: Any? = null, token: String = "test-token"): Pair<Int, JsonNode> {
        val h = HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
            set("X-Till-Id", till)
        }
        val r = rest.exchange("/api/till/v1$path", method, HttpEntity(body?.let(mapper::writeValueAsString), h), String::class.java)
        return r.statusCode.value() to mapper.readTree(r.body ?: "{}")
    }

    private fun post(path: String, body: Any) = call(HttpMethod.POST, path, body)
    private fun get(path: String) = call(HttpMethod.GET, path).second

    private fun openShift(floatCents: Long = 0): String =
        post("/shifts", mapOf("cashierId" to "C01", "cashierName" to "Wanjiku", "floatCents" to floatCents)).second["id"].asText()

    private fun receive(code: String, qty: Int) =
        post("/stock/receipts", mapOf("lines" to listOf(mapOf("productCode" to code, "qty" to qty))))

    private fun onHand(code: String) = get("/stock").first { it["productCode"].asText() == code }["onHand"].decimalValue().toInt()

    private fun sale(ref: String, lines: List<Pair<String, Int>>, vararg tenders: Map<String, Any?>, customerId: String? = null) =
        post(
            "/sales",
            mapOf(
                "clientRef" to ref,
                "lines" to lines.map { mapOf("productCode" to it.first, "qty" to it.second) },
                "tenders" to tenders.toList(),
                "customerId" to customerId,
            ),
        )

    private fun cash(cents: Long) = mapOf("method" to "CASH", "amountCents" to cents)
    private fun mpesaCode(code: String, cents: Long) = mapOf("method" to "MPESA_CODE", "amountCents" to cents, "mpesaCode" to code)
    private fun credit(cents: Long) = mapOf("method" to "CREDIT", "amountCents" to cents)
    private fun fakeStatus() = mapper.readTree(fake.getForObject("$vscuUrl/_fake/status", String::class.java))
    private fun ref() = "R-" + UUID.randomUUID()

    // ---- tests

    @Test
    fun `cash sale is signed, prints the SCU block, moves stock, and retries return the same receipt`() {
        openShift()
        receive("OIL", 10)
        receive("UNGA", 5)
        val before = onHand("OIL")
        val ref = ref()

        val (status, receipt) = sale(ref, listOf("OIL" to 2, "UNGA" to 1, "DELIVERY" to 1), cash(101_000))

        assertThat(status).isEqualTo(201)
        assertThat(receipt["label"].asText()).isEqualTo("NS")
        assertThat(receipt["totalCents"].asLong()).isEqualTo(101_000)
        val vat = receipt["taxes"].first { it["taxType"].asText() == "B" }
        assertThat(vat["taxableCents"].asLong()).isEqualTo(80_000)
        assertThat(vat["taxCents"].asLong()).isEqualTo(11_034)
        assertThat(receipt["scuLines"].map { it.asText() }).anyMatch { it.startsWith("Receipt Signature: ") }
        assertThat(receipt["qrPayload"].asText().split("#")).hasSize(6)
        assertThat(onHand("OIL")).isEqualTo(before - 2)

        val (again, repeat) = sale(ref, listOf("OIL" to 2), cash(70_000))
        assertThat(again).isEqualTo(201)
        assertThat(repeat["invoiceNo"].asLong()).isEqualTo(receipt["invoiceNo"].asLong())
        assertThat(onHand("OIL")).isEqualTo(before - 2)

        val copy = get("/sales/${receipt["saleId"].asText()}/receipt?copy=true")
        assertThat(copy["label"].asText()).isEqualTo("CS")
        assertThat(outbox.unsentOfType("SaleRecorded").map { mapper.readTree(it.payload)["saleId"].asText() })
            .contains(receipt["saleId"].asText())
    }

    @Test
    fun `goods beyond stock on hand are refused before the VSCU is asked`() {
        openShift()
        val have = onHand("UNGA")
        val signedBefore = fakeStatus()["lastInvcNo"].asLong()

        val (status, body) = sale(ref(), listOf("UNGA" to have + 1), cash(21_000L * (have + 1)))

        assertThat(status).isEqualTo(409)
        assertThat(body["code"].asText()).isEqualTo("INSUFFICIENT_STOCK")
        assertThat(fakeStatus()["lastInvcNo"].asLong()).isEqualTo(signedBefore)
    }

    @Test
    fun `selling needs an open shift and a till token`() {
        assertThat(sale(ref(), listOf("DELIVERY" to 1), cash(10_000)).second["code"].asText()).isEqualTo("NO_OPEN_SHIFT")
        assertThat(call(HttpMethod.GET, "/status", token = "wrong").first).isEqualTo(401)
    }

    @Test
    fun `after 24 hours without KRA the VSCU refuses, and the same sale signs once KRA is back`() {
        openShift()
        receive("OIL", 5)
        try {
            fake.postForObject("$vscuUrl/_fake/kra?up=false", null, String::class.java)
            assertThat(sale(ref(), listOf("OIL" to 1), cash(35_000)).first).isEqualTo(201)
            fake.postForObject("$vscuUrl/_fake/clock/advance?minutes=${24 * 60 + 1}", null, String::class.java)

            val stuck = ref()
            val before = onHand("OIL")
            val (status, body) = sale(stuck, listOf("OIL" to 1), cash(35_000))
            assertThat(status).isEqualTo(502)
            assertThat(body["code"].asText()).isEqualTo("VSCU_REJECTED")
            assertThat(body["details"]["resultCd"].asText()).isEqualTo("999")
            assertThat(onHand("OIL")).isEqualTo(before)

            fake.postForObject("$vscuUrl/_fake/kra?up=true", null, String::class.java)
            val (retry, receipt) = sale(stuck, listOf("OIL" to 1), cash(35_000))
            assertThat(retry).isEqualTo(201)
            assertThat(receipt["label"].asText()).isEqualTo("NS")
            assertThat(onHand("OIL")).isEqualTo(before - 1)
        } finally {
            fake.postForObject("$vscuUrl/_fake/kra?up=true", null, String::class.java)
        }
    }

    @Test
    fun `credit note returns stock, refunds cash, and can't return more than was sold`() {
        val shiftId = openShift(floatCents = 100_000)
        receive("OIL", 5)
        val (_, sold) = sale(ref(), listOf("OIL" to 2), cash(70_000))
        val stockAfterSale = onHand("OIL")
        val saleId = sold["saleId"].asText()

        val (status, note) = post(
            "/sales/$saleId/credit-notes",
            mapOf("clientRef" to ref(), "lines" to listOf(mapOf("productCode" to "OIL", "qty" to 1)), "reason" to "DAMAGED", "refundMethod" to "CASH"),
        )
        assertThat(status).isEqualTo(201)
        assertThat(note["label"].asText()).isEqualTo("NC")
        assertThat(note["originalInvoiceNo"].asLong()).isEqualTo(sold["invoiceNo"].asLong())
        assertThat(onHand("OIL")).isEqualTo(stockAfterSale + 1)

        val (tooMany, err) = post(
            "/sales/$saleId/credit-notes",
            mapOf("clientRef" to ref(), "lines" to listOf(mapOf("productCode" to "OIL", "qty" to 2)), "reason" to "REFUND", "refundMethod" to "CASH"),
        )
        assertThat(tooMany).isEqualTo(422)
        assertThat(err["code"].asText()).isEqualTo("RETURN_EXCEEDS_SALE")

        val (_, closed) = post("/shifts/$shiftId/close", mapOf("countedCashCents" to 135_000))
        assertThat(closed["cash"]["expectedCents"].asLong()).isEqualTo(135_000)
        assertThat(closed["cash"]["varianceCents"].asLong()).isZero()
    }

    @Test
    fun `deni is allowed within the limit, refused beyond it, and repaid in cash`() {
        val shiftId = openShift()
        receive("OIL", 5)
        val customer = post("/customers", mapOf("name" to "Mama Njeri", "phone" to "254712345678", "creditLimitCents" to 50_000)).second
        val id = customer["id"].asText()

        assertThat(sale(ref(), listOf("OIL" to 1), credit(35_000), customerId = id).first).isEqualTo(201)
        val (refused, err) = sale(ref(), listOf("OIL" to 1), credit(35_000), customerId = id)
        assertThat(refused).isEqualTo(409)
        assertThat(err["code"].asText()).isEqualTo("CREDIT_LIMIT")

        val after = post("/customers/$id/payments", mapOf("amountCents" to 20_000, "method" to "CASH")).second
        assertThat(after["balanceCents"].asLong()).isEqualTo(15_000)
        assertThat(get("/customers/$id")["entries"].map { it["kind"].asText() }).containsExactly("CHARGE", "PAYMENT")
        assertThat(get("/shifts/$shiftId/report")["cash"]["cashDeniRepaymentsCents"].asLong()).isEqualTo(20_000)
    }

    @Test
    fun `an M-Pesa code can't pay for two sales, even with the cloud unreachable`() {
        openShift()
        receive("OIL", 5)
        val code = "QH" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

        val (status, receipt) = sale(ref(), listOf("OIL" to 1), mpesaCode(code, 35_000))
        assertThat(status).isEqualTo(201)
        assertThat(receipt["payments"][0]["mpesaStatus"].asText()).isEqualTo("PENDING")

        assertThat(post("/mpesa/check", mapOf("code" to code, "amountCents" to 35_000)).second["status"].asText()).isEqualTo("CODE_ALREADY_USED")
        val other = post("/mpesa/check", mapOf("code" to "ZZ99ZZ99ZZ", "amountCents" to 35_000)).second
        assertThat(other["status"].asText()).isEqualTo("PENDING")
        assertThat(other["offline"].asBoolean()).isTrue()

        val (reused, err) = sale(ref(), listOf("OIL" to 1), mpesaCode(code.lowercase(), 35_000))
        assertThat(reused).isEqualTo(409)
        assertThat(err["code"].asText()).isEqualTo("MPESA_CODE_ALREADY_USED")
    }

    @Test
    fun `a VSCU that can't be reached is reported as unreachable, not as a lost answer`() {
        val client = VscuClient(VscuProperties("http://localhost:1", "P000000000A", "00", "x", 500, 500), RestClient.builder())
        assertThatThrownBy { client.initInfo(InitInfoReq("P000000000A", "00", "x")) }.isInstanceOf(VscuUnreachable::class.java)
    }
}
