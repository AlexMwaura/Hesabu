package ke.hesabu.server.admin

import ke.hesabu.server.mpesa.DarajaClient
import ke.hesabu.server.web.ApiException
import ke.hesabu.server.web.Directory
import ke.hesabu.server.web.Keys
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class NewBusiness(val name: String, val kraPin: String? = null)
data class NewBranch(
    val name: String,
    val code: String,
    val bhfId: String = "00",
    val mpesaShortCode: String? = null,
    val stkShortCode: String? = null,
    val stkPasskey: String? = null,
)

/** Keys are shown once, at creation; only their hashes are stored. */
data class CreatedBusiness(val id: UUID, val ownerKey: String)
data class CreatedBranch(val id: UUID, val hubKey: String)

/** Onboarding, used by Hesabu staff. */
@RestController
@RequestMapping("/api/admin/v1")
class AdminController(private val jdbc: JdbcClient, private val directory: Directory, private val daraja: DarajaClient) {

    @PostMapping("/businesses")
    @Transactional
    fun business(@RequestBody req: NewBusiness): ResponseEntity<CreatedBusiness> {
        require(req.name.isNotBlank()) { "Business name is required" }
        val id = UUID.randomUUID()
        val key = Keys.newKey("own_")
        jdbc.sql("INSERT INTO business (id, name, kra_pin, owner_key_hash) VALUES (:id, :n, :p, :h)")
            .param("id", id).param("n", req.name).param("p", req.kraPin).param("h", Keys.hash(key)).update()
        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedBusiness(id, key))
    }

    @PostMapping("/businesses/{businessId}/branches")
    @Transactional
    fun branch(@PathVariable businessId: UUID, @RequestBody req: NewBranch): ResponseEntity<CreatedBranch> {
        require(req.name.isNotBlank() && req.code.isNotBlank()) { "Branch name and code are required" }
        val id = UUID.randomUUID()
        val key = Keys.newKey("hub_")
        jdbc.sql(
            """
            INSERT INTO branch (id, business_id, name, code, bhf_id, hub_key_hash, mpesa_short_code, stk_short_code, stk_passkey)
            VALUES (:id, :b, :n, :c, :bhf, :h, :m, :s, :pk)
            """.trimIndent(),
        ).param("id", id).param("b", businessId).param("n", req.name).param("c", req.code).param("bhf", req.bhfId)
            .param("h", Keys.hash(key)).param("m", req.mpesaShortCode).param("s", req.stkShortCode).param("pk", req.stkPasskey).update()
        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedBranch(id, key))
    }

    /** Tells Safaricom where to send C2B confirmations for the branch's till or paybill. */
    @PostMapping("/branches/{branchId}/c2b/register")
    fun registerC2b(@PathVariable branchId: UUID): Map<String, Any?> {
        val branch = directory.branch(branchId) ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such branch")
        val shortCode = branch.mpesaShortCode ?: throw ApiException(HttpStatus.CONFLICT, "NO_SHORT_CODE", "Branch has no M-Pesa short code")
        return daraja.registerC2b(shortCode)
    }
}
