package ke.hesabu.hub.fiscal

import ke.hesabu.core.etims.InitInfoData
import ke.hesabu.core.etims.InitInfoReq
import ke.hesabu.core.etims.SalesSignature
import ke.hesabu.core.etims.TrnsSalesSaveReq
import ke.hesabu.core.etims.VscuResponse
import ke.hesabu.hub.VscuProperties
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The request never reached the VSCU, so nothing was signed. Safe to retry with the same number. */
class VscuUnreachable(cause: Throwable) : RuntimeException("VSCU not reachable: ${cause.message}", cause)

/** The request may have reached the VSCU but no answer came back. It may or may not have signed. */
class VscuNoAnswer(cause: Throwable) : RuntimeException("No answer from the VSCU: ${cause.message}", cause)

/**
 * Talks to the VSCU's local REST API. The VSCU is KRA's JAR running on the hub box (or the fake VSCU
 * during development); it signs receipts itself and forwards them to KRA.
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
            ?: throw VscuNoAnswer(IllegalStateException("empty body from $path"))
    } catch (e: ResourceAccessException) {
        throw classify(e)
    }

    private fun classify(e: ResourceAccessException): RuntimeException {
        val cause = e.cause
        val neverSent = cause is ConnectException || cause is UnknownHostException || cause is NoRouteToHostException ||
            (cause is SocketTimeoutException && cause.message?.contains("connect", ignoreCase = true) == true)
        return if (neverSent) VscuUnreachable(cause ?: e) else VscuNoAnswer(cause ?: e)
    }
}
