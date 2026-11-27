package ke.hesabu.spike.etims

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient

/** Thrown when the VSCU itself cannot be reached. Per TIS spec 6.7 and 10, no receipt may be printed then. */
class VscuUnavailable(cause: Throwable) : RuntimeException("VSCU not reachable: ${cause.message}", cause)

/**
 * Talks to a VSCU over its local REST API. The VSCU is KRA's JAR running on a machine the business
 * controls; it signs receipts itself and forwards them to KRA's eTIMS server.
 */
@Component
class VscuClient(props: VscuProperties, builder: RestClient.Builder) {
    private val http: RestClient = builder
        .baseUrl(props.baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(props.connectTimeoutMs)
            setReadTimeout(props.readTimeoutMs)
        })
        .build()

    fun initInfo(req: InitInfoReq): VscuResponse<InitInfoData> =
        post("/initializer/selectInitInfo", req, object : ParameterizedTypeReference<VscuResponse<InitInfoData>>() {})

    fun saveSales(req: TrnsSalesSaveReq): VscuResponse<SalesSignature> =
        post("/trnsSales/saveSales", req, object : ParameterizedTypeReference<VscuResponse<SalesSignature>>() {})

    private fun <T : Any> post(path: String, body: Any, type: ParameterizedTypeReference<T>): T = try {
        http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type)
            ?: error("Empty VSCU response for $path")
    } catch (e: ResourceAccessException) {
        throw VscuUnavailable(e)
    }
}
