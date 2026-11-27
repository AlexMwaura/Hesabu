package ke.hesabu.spike.daraja

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Base64

/** STK Push password: base64(shortcode + passkey + timestamp), timestamp yyyyMMddHHmmss in Nairobi time. */
object StkPassword {
    val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun timestamp(clock: Clock): String = TIMESTAMP.format(clock.instant().atZone(clock.zone))

    fun of(shortCode: String, passkey: String, timestamp: String): String =
        Base64.getEncoder().encodeToString("$shortCode$passkey$timestamp".toByteArray())
}

data class StkPushRequest(
    @JsonProperty("BusinessShortCode") val businessShortCode: String,
    @JsonProperty("Password") val password: String,
    @JsonProperty("Timestamp") val timestamp: String,
    @JsonProperty("TransactionType") val transactionType: String,
    @JsonProperty("Amount") val amount: Long,
    @JsonProperty("PartyA") val partyA: String,
    @JsonProperty("PartyB") val partyB: String,
    @JsonProperty("PhoneNumber") val phoneNumber: String,
    @JsonProperty("CallBackURL") val callBackUrl: String,
    @JsonProperty("AccountReference") val accountReference: String,
    @JsonProperty("TransactionDesc") val transactionDesc: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkPushAck(
    @JsonProperty("MerchantRequestID") val merchantRequestId: String? = null,
    @JsonProperty("CheckoutRequestID") val checkoutRequestId: String? = null,
    @JsonProperty("ResponseCode") val responseCode: String? = null,
    @JsonProperty("ResponseDescription") val responseDescription: String? = null,
    @JsonProperty("CustomerMessage") val customerMessage: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallbackEnvelope(@JsonProperty("Body") val body: StkCallbackBody)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallbackBody(@JsonProperty("stkCallback") val stkCallback: StkCallback)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StkCallback(
    @JsonProperty("MerchantRequestID") val merchantRequestId: String,
    @JsonProperty("CheckoutRequestID") val checkoutRequestId: String,
    @JsonProperty("ResultCode") val resultCode: Int,
    @JsonProperty("ResultDesc") val resultDesc: String,
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

data class RegisterUrlRequest(
    @JsonProperty("ShortCode") val shortCode: String,
    @JsonProperty("ResponseType") val responseType: String,
    @JsonProperty("ConfirmationURL") val confirmationUrl: String,
    @JsonProperty("ValidationURL") val validationUrl: String,
)

data class C2bSimulateRequest(
    @JsonProperty("ShortCode") val shortCode: String,
    @JsonProperty("CommandID") val commandId: String,
    @JsonProperty("Amount") val amount: Long,
    @JsonProperty("Msisdn") val msisdn: String,
    @JsonProperty("BillRefNumber") val billRefNumber: String,
)

/** Body Safaricom posts to the C2B validation and confirmation URLs. All amounts arrive as strings. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class C2bNotification(
    @JsonProperty("TransactionType") val transactionType: String? = null,
    @JsonProperty("TransID") val transId: String,
    @JsonProperty("TransTime") val transTime: String? = null,
    @JsonProperty("TransAmount") val transAmount: String,
    @JsonProperty("BusinessShortCode") val businessShortCode: String,
    @JsonProperty("BillRefNumber") val billRefNumber: String? = null,
    @JsonProperty("MSISDN") val msisdn: String? = null,
    @JsonProperty("FirstName") val firstName: String? = null,
)

data class DarajaAck(
    @JsonProperty("ResultCode") val resultCode: Int = 0,
    @JsonProperty("ResultDesc") val resultDesc: String = "Accepted",
)
