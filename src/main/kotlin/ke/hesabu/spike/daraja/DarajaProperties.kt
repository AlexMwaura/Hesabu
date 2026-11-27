package ke.hesabu.spike.daraja

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "hesabu.daraja")
data class DarajaProperties(
    val baseUrl: String,
    val consumerKey: String,
    val consumerSecret: String,
    val stkShortCode: String,
    val stkPasskey: String,
    val c2bShortCode: String,
    val publicBaseUrl: String,
    val c2bSimulatePath: String,
) {
    val stkCallbackUrl get() = "$publicBaseUrl/hooks/stk/result"
    val c2bValidationUrl get() = "$publicBaseUrl/hooks/c2b/validate"
    val c2bConfirmationUrl get() = "$publicBaseUrl/hooks/c2b/confirm"
}
