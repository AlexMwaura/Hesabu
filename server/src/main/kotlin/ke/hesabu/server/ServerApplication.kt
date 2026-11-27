package ke.hesabu.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.ZoneId

val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

@SpringBootApplication
@ConfigurationPropertiesScan
class ServerApplication {
    @Bean
    fun clock(): Clock = Clock.system(NAIROBI)
}

object Server {
    fun builder(): SpringApplicationBuilder =
        SpringApplicationBuilder(ServerApplication::class.java).properties("spring.config.name=server")
}

fun main(args: Array<String>) {
    Server.builder().run(*args)
}

@ConfigurationProperties("hesabu")
data class ServerProperties(val adminToken: String, val daraja: Daraja) {
    data class Daraja(
        val baseUrl: String,
        val consumerKey: String,
        val consumerSecret: String,
        val publicBaseUrl: String,
    ) {
        val stkCallbackUrl: String get() = "$publicBaseUrl/hooks/stk/result"
        val c2bConfirmationUrl: String get() = "$publicBaseUrl/hooks/c2b/confirm"
        val c2bValidationUrl: String get() = "$publicBaseUrl/hooks/c2b/validate"
    }
}
