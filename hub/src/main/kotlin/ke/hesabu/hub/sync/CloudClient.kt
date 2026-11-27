package ke.hesabu.hub.sync

import com.fasterxml.jackson.databind.JsonNode
import ke.hesabu.core.sync.CataloguePage
import ke.hesabu.core.sync.EventBatchResult
import ke.hesabu.core.sync.MpesaCheckRequest
import ke.hesabu.core.sync.MpesaClaimRequest
import ke.hesabu.core.sync.MpesaClaimResult
import ke.hesabu.core.sync.MpesaStatusRequest
import ke.hesabu.core.sync.StkStartRequest
import ke.hesabu.core.sync.StkStatus
import ke.hesabu.hub.HubProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/** The shop's internet is down or the cloud is unreachable. The hub keeps trading and retries later. */
class CloudUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The cloud answered with an error the hub should pass on (for example, STK refused). */
class CloudRefused(val status: Int, val body: String) : RuntimeException("Cloud refused ($status): $body")

data class OutgoingEvent(val id: String, val type: String, val occurredAt: String, val payload: JsonNode)

@Component
class CloudClient(props: HubProperties, builder: RestClient.Builder) {
    private val http: RestClient = builder
        .baseUrl(props.cloud.baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.cloud.hubKey}")
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.cloud.timeoutMs)
            setReadTimeout(props.cloud.timeoutMs)
        })
        .build()

    fun pushEvents(events: List<OutgoingEvent>): EventBatchResult = call { post("/api/hub/v1/events", events).body<EventBatchResult>()!! }

    fun catalogue(sinceVersion: Long): CataloguePage =
        call { http.get().uri("/api/hub/v1/catalogue?since={v}", sinceVersion).retrieve().body<CataloguePage>()!! }

    fun checkMpesa(req: MpesaCheckRequest): MpesaClaimResult = call { post("/api/hub/v1/mpesa/check", req).body<MpesaClaimResult>()!! }

    fun claimMpesa(req: MpesaClaimRequest): MpesaClaimResult = call { post("/api/hub/v1/mpesa/claims", req).body<MpesaClaimResult>()!! }

    fun mpesaStatuses(codes: List<String>): List<MpesaClaimResult> = call {
        post("/api/hub/v1/mpesa/claims/status", MpesaStatusRequest(codes)).body(object : ParameterizedTypeReference<List<MpesaClaimResult>>() {})!!
    }

    fun startStk(req: StkStartRequest): StkStatus = call { post("/api/hub/v1/mpesa/stk", req).body<StkStatus>()!! }

    fun stkStatus(checkoutRequestId: String): StkStatus =
        call { http.get().uri("/api/hub/v1/mpesa/stk/{id}", checkoutRequestId).retrieve().body<StkStatus>()!! }

    private fun post(path: String, body: Any) =
        http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: ResourceAccessException) {
        throw CloudUnavailable("Cloud not reachable: ${e.message}", e)
    } catch (e: RestClientResponseException) {
        if (e.statusCode.is5xxServerError) throw CloudUnavailable("Cloud error ${e.statusCode.value()}", e)
        throw CloudRefused(e.statusCode.value(), e.responseBodyAsString)
    }
}
