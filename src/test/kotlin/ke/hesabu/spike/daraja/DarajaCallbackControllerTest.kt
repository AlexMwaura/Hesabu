package ke.hesabu.spike.daraja

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class DarajaCallbackControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val matcher: PaymentMatcher,
    @Autowired private val stkSales: StkSaleRegistry,
) {
    // Shape of a Daraja C2B v2 confirmation (MSISDN arrives masked or hashed in v2).
    private val confirmation = """
        {"TransactionType":"Pay Bill","TransID":"TKR1XYZ001","TransTime":"20261127101500","TransAmount":"500.00",
         "BusinessShortCode":"600000","BillRefNumber":"HQ-T1-000001","InvoiceNumber":"","OrgAccountBalance":"",
         "ThirdPartyTransID":"","MSISDN":"2547 ***** 149","FirstName":"JOHN"}
    """.trimIndent()

    @Test
    fun `c2b confirmation is acknowledged and stored once even when retried`() {
        repeat(2) {
            mvc.post("/hooks/c2b/confirm") { contentType = MediaType.APPLICATION_JSON; content = confirmation }
                .andExpect { status { isOk() }; jsonPath("$.ResultCode") { value(0) } }
        }
        assertThat(matcher.shiftReport().unclaimed.count { it.code == "TKR1XYZ001" }).isEqualTo(1)
        assertThat(matcher.claim("sale-c2b", "TKR1XYZ001", 500, "600000").status).isEqualTo(PaymentMatcher.Status.VERIFIED)
    }

    @Test
    fun `successful stk callback verifies the sale that sent the prompt`() {
        stkSales.remember("ws_CO_27112026101500001", "sale-stk")
        val body = """
            {"Body":{"stkCallback":{"MerchantRequestID":"29115-34620561-1","CheckoutRequestID":"ws_CO_27112026101500001",
             "ResultCode":0,"ResultDesc":"The service request is processed successfully.",
             "CallbackMetadata":{"Item":[{"Name":"Amount","Value":350},{"Name":"MpesaReceiptNumber","Value":"TKR1STK001"},
             {"Name":"TransactionDate","Value":20261127101530},{"Name":"PhoneNumber","Value":254708374149}]}}}}
        """.trimIndent()
        mvc.post("/hooks/stk/result") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isOk() } }
        assertThat(matcher.claimFor("sale-stk")!!.status).isEqualTo(PaymentMatcher.Status.VERIFIED)
    }

    @Test
    fun `cancelled stk prompt leaves the sale unpaid`() {
        stkSales.remember("ws_CO_cancelled", "sale-cancel")
        val body = """{"Body":{"stkCallback":{"MerchantRequestID":"1","CheckoutRequestID":"ws_CO_cancelled","ResultCode":1032,"ResultDesc":"Request cancelled by user"}}}"""
        mvc.post("/hooks/stk/result") { contentType = MediaType.APPLICATION_JSON; content = body }
            .andExpect { status { isOk() } }
        assertThat(matcher.claimFor("sale-cancel")).isNull()
    }
}
