package ke.hesabu.server.web

import jakarta.servlet.http.HttpServletRequest
import ke.hesabu.server.NAIROBI
import ke.hesabu.server.catalogue.CatalogueService
import ke.hesabu.server.catalogue.ProductInput
import ke.hesabu.server.reports.SummaryService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class BranchView(val id: UUID, val name: String, val code: String, val mpesaShortCode: String?, val stkEnabled: Boolean)

/** What the owner dashboard (web, and owner mode on the phone) calls. */
@RestController
@RequestMapping("/api/owner/v1")
class OwnerController(
    private val directory: Directory,
    private val catalogue: CatalogueService,
    private val summaries: SummaryService,
) {
    @GetMapping("/branches")
    fun branches(req: HttpServletRequest) = directory.branchesOf(req.businessId()).map {
        BranchView(it.id, it.name, it.code, it.mpesaShortCode, it.stkShortCode != null && it.stkPasskey != null)
    }

    @GetMapping("/products")
    fun products(req: HttpServletRequest) = catalogue.since(req.businessId(), 0).items

    @PostMapping("/products")
    fun create(req: HttpServletRequest, @RequestBody body: ProductInput) =
        org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(catalogue.upsert(req.businessId(), body.copy(code = null)))

    @PutMapping("/products/{code}")
    fun upsert(req: HttpServletRequest, @PathVariable code: String, @RequestBody body: ProductInput) =
        catalogue.upsert(req.businessId(), body.copy(code = code))

    @GetMapping("/branches/{branchId}/summary")
    fun summary(
        req: HttpServletRequest,
        @PathVariable branchId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): Any {
        val branch = directory.branch(branchId)?.takeIf { it.businessId == req.businessId() }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such branch")
        return summaries.daily(branch.id, branch.businessId, date ?: LocalDate.now(NAIROBI))
    }
}
