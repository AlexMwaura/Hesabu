package ke.hesabu.server.mpesa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import ke.hesabu.core.Money
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/** Body Safaricom posts to the C2B validation and confirmation URLs. Amounts arrive as strings. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class C2bNotification(
    @JsonProperty("TransactionType") val transactionType: String? = null,
    @JsonProperty("TransID") val transId: String,
    @JsonProperty("TransTime") val transTime: String? = null,
    @JsonProperty("TransAmount") val transAmount: String,
    @JsonProperty("BusinessShortCode") val businessShortCode: String,
    @JsonProperty("BillRefNumber") val billRefNumber: String? = null,
    @JsonProperty("MSISDN") val msisdn: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallbackEnvelope(@JsonProperty("Body") val body: StkCallbackBody)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallbackBody(@JsonProperty("stkCallback") val stkCallback: StkCallback)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallback(
    @JsonProperty("CheckoutRequestID") val checkoutRequestId: String,
    @JsonProperty("ResultCode") val resultCode: Int,
    @JsonProperty("ResultDesc") val resultDesc: String? = null,
    @JsonProperty("CallbackMetadata") val callbackMetadata: CallbackMetadata? = null,
) {
    private fun item(name: String) = callbackMetadata?.items?.firstOrNull { it.name == name }?.value
    val receiptNumber: String? get() = item("MpesaReceiptNumber")?.toString()
    val amount: Long? get() = (item("Amount") as? Number)?.toLong()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CallbackMetadata(@JsonProperty("Item") val items: List<CallbackItem> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class CallbackItem(@JsonProperty("Name") val name: String, @JsonProperty("Value") val value: Any? = null)

data class DarajaAck(@JsonProperty("ResultCode") val resultCode: Int = 0, @JsonProperty("ResultDesc") val resultDesc: String = "Accepted")

/**
 * Endpoints Safaricom calls. Every handler answers ResultCode 0 quickly so Safaricom stops retrying;
 * the work behind them is idempotent.
 */
@RestController
@RequestMapping("/hooks")
class DarajaCallbacks(private val mpesa: MpesaService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/c2b/validate")
    fun validate(@RequestBody n: C2bNotification) = DarajaAck()

    @PostMapping("/c2b/confirm")
    fun confirm(@RequestBody n: C2bNotification): DarajaAck {
        val cents = Money.ofDecimal(BigDecimal(n.transAmount))
        val first = mpesa.onConfirmation(n.transId, cents, n.businessShortCode, ConfirmationSource.C2B, n.msisdn, n.billRefNumber, n.transTime)
        log.info("C2B {} KES {} to {} ({})", n.transId, n.transAmount, n.businessShortCode, if (first) "new" else "duplicate")
        return DarajaAck()
    }

    @PostMapping("/stk/result")
    fun stk(@RequestBody env: StkCallbackEnvelope): DarajaAck {
        val cb = env.body.stkCallback
        mpesa.onStkResult(cb.checkoutRequestId, cb.resultCode, cb.resultDesc, cb.receiptNumber, cb.amount)
        return DarajaAck()
    }
}
