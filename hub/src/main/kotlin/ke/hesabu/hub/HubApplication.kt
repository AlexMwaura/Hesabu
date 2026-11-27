package ke.hesabu.hub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.ZoneId

val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class HubApplication {
    @Bean
    fun clock(): Clock = Clock.system(NAIROBI)
}

object Hub {
    fun builder(): SpringApplicationBuilder =
        SpringApplicationBuilder(HubApplication::class.java).properties("spring.config.name=hub")
}

fun main(args: Array<String>) {
    Hub.builder().run(*args)
}

@ConfigurationProperties("hesabu.hub")
data class HubProperties(
    /** Short branch code used in the shop's own invoice numbers, e.g. HQ-000153. */
    val branchCode: String,
    val tradeName: String,
    val tillToken: String,
    val cloud: Cloud,
) {
    data class Cloud(
        val baseUrl: String,
        val hubKey: String,
        val syncEnabled: Boolean,
        val syncIntervalMs: Long,
        val timeoutMs: Int,
    )
}

@ConfigurationProperties("hesabu.vscu")
data class VscuProperties(
    val baseUrl: String,
    val tin: String,
    val bhfId: String,
    val deviceSerial: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)
