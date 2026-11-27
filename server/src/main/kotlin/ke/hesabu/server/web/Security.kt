package ke.hesabu.server.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import ke.hesabu.server.ServerProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

object Keys {
    private val rng = SecureRandom()

    fun newKey(prefix: String): String {
        val bytes = ByteArray(32).also(rng::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(key: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
}

data class Branch(
    val id: UUID,
    val businessId: UUID,
    val name: String,
    val code: String,
    val mpesaShortCode: String?,
    val stkShortCode: String?,
    val stkPasskey: String?,
) {
    /** Short codes whose money counts as paid to this branch. */
    val shortCodes: Set<String> get() = setOfNotNull(mpesaShortCode, stkShortCode)
}

@Component
class Directory(private val jdbc: JdbcClient) {
    fun branchByHubKey(key: String): Branch? = jdbc.sql("SELECT * FROM branch WHERE hub_key_hash = :h").param("h", Keys.hash(key))
        .query(::branch).optional().orElse(null)

    fun branch(id: UUID): Branch? = jdbc.sql("SELECT * FROM branch WHERE id = :id").param("id", id).query(::branch).optional().orElse(null)

    fun branchesOf(businessId: UUID): List<Branch> =
        jdbc.sql("SELECT * FROM branch WHERE business_id = :b ORDER BY name").param("b", businessId).query(::branch).list()

    fun businessByOwnerKey(key: String): UUID? = jdbc.sql("SELECT id FROM business WHERE owner_key_hash = :h").param("h", Keys.hash(key))
        .query { rs, _ -> rs.getObject(1, UUID::class.java) }.optional().orElse(null)

    private fun branch(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Branch(
        rs.getObject("id", UUID::class.java), rs.getObject("business_id", UUID::class.java), rs.getString("name"), rs.getString("code"),
        rs.getString("mpesa_short_code"), rs.getString("stk_short_code"), rs.getString("stk_passkey"),
    )
}

class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

data class ErrorBody(val code: String, val message: String)

@RestControllerAdvice
class ErrorAdvice {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message ?: e.code))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException) = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorBody("INVALID", e.message ?: "Invalid"))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody("BAD_REQUEST", e.mostSpecificCause.message ?: "Unreadable"))
}

const val BRANCH_ATTR = "hesabu.branch"
const val BUSINESS_ATTR = "hesabu.business"

fun HttpServletRequest.branch(): Branch = getAttribute(BRANCH_ATTR) as Branch
fun HttpServletRequest.businessId(): UUID = getAttribute(BUSINESS_ATTR) as UUID

/**
 * Three kinds of caller: hubs (one key per branch), owners (one key per business) and Hesabu staff
 * (admin token). Safaricom's callbacks under /hooks are open, as Daraja requires.
 * TODO(auth): owner keys are a Phase 1 shortcut; owners should sign in by phone with an OTP.
 * TODO(sandbox): restrict /hooks to Safaricom's published callback IP ranges in production.
 */
@Configuration
class Security(private val directory: Directory, private val props: ServerProperties) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(guard { req ->
            directory.branchByHubKey(bearer(req) ?: return@guard false)?.also { req.setAttribute(BRANCH_ATTR, it) } != null
        }).addPathPatterns("/api/hub/**")
        registry.addInterceptor(guard { req ->
            directory.businessByOwnerKey(bearer(req) ?: return@guard false)?.also { req.setAttribute(BUSINESS_ATTR, it) } != null
        }).addPathPatterns("/api/owner/**")
        registry.addInterceptor(guard { req ->
            val given = req.getHeader("X-Admin-Token").orEmpty()
            MessageDigest.isEqual(given.toByteArray(), props.adminToken.toByteArray())
        }).addPathPatterns("/api/admin/**")
    }

    private fun bearer(req: HttpServletRequest) = req.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

    private fun guard(check: (HttpServletRequest) -> Boolean) = object : HandlerInterceptor {
        override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
            if (check(request)) return true
            response.status = 401
            response.contentType = "application/json"
            response.writer.write("""{"code":"UNAUTHORISED","message":"Missing or wrong credentials"}""")
            return false
        }
    }
}
