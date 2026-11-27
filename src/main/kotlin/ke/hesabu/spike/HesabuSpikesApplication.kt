package ke.hesabu.spike

import ke.hesabu.spike.daraja.DarajaProperties
import ke.hesabu.spike.etims.VscuProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.ZoneId

/** Zone used for every timestamp Safaricom and KRA expect. */
val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

@SpringBootApplication
@EnableConfigurationProperties(DarajaProperties::class, VscuProperties::class)
class HesabuSpikesApplication {
    @Bean
    fun clock(): Clock = Clock.system(NAIROBI)
}

fun main(args: Array<String>) {
    runApplication<HesabuSpikesApplication>(*args)
}
