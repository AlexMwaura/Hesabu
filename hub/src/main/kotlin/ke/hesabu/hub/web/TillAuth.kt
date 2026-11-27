package ke.hesabu.hub.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import ke.hesabu.hub.HubProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest

/**
 * Tills authenticate to the hub with a bearer token.
 * TODO(pairing): one shared token per hub is a Phase 1 shortcut; give each till its own credential.
 */
@Configuration
class TillAuth(private val props: HubProperties) : WebMvcConfigurer, HandlerInterceptor {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).addPathPatterns("/api/till/**")
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val given = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        val ok = MessageDigest.isEqual(given.toByteArray(), props.tillToken.toByteArray())
        if (!ok) {
            response.status = 401
            response.contentType = "application/json"
            response.writer.write("""{"code":"UNAUTHORISED","message":"Till token missing or wrong"}""")
        }
        return ok
    }
}
