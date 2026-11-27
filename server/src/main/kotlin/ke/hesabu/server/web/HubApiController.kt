package ke.hesabu.server.web

import jakarta.servlet.http.HttpServletRequest
import ke.hesabu.core.sync.CataloguePage
import ke.hesabu.core.sync.EventBatchResult
import ke.hesabu.core.sync.MpesaCheckRequest
import ke.hesabu.core.sync.MpesaClaimRequest
import ke.hesabu.core.sync.MpesaStatusRequest
import ke.hesabu.core.sync.StkStartRequest
import ke.hesabu.server.catalogue.CatalogueService
import ke.hesabu.server.ingest.EventIngest
import ke.hesabu.server.ingest.IncomingEvent
import ke.hesabu.server.mpesa.MpesaService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** What in-shop hubs call. The hub key identifies the branch. */
@RestController
@RequestMapping("/api/hub/v1")
class HubApiController(
    private val ingest: EventIngest,
    private val catalogue: CatalogueService,
    private val mpesa: MpesaService,
) {
    @PostMapping("/events")
    fun events(req: HttpServletRequest, @RequestBody events: List<IncomingEvent>) = EventBatchResult(ingest.ingest(req.branch(), events))

    @GetMapping("/catalogue")
    fun catalogue(req: HttpServletRequest, @RequestParam(defaultValue = "0") since: Long): CataloguePage =
        catalogue.since(req.branch().businessId, since)

    @PostMapping("/mpesa/check")
    fun check(req: HttpServletRequest, @RequestBody body: MpesaCheckRequest) = mpesa.check(req.branch(), body.code, body.amountCents)

    @PostMapping("/mpesa/claims")
    fun claim(req: HttpServletRequest, @RequestBody body: MpesaClaimRequest) = mpesa.claim(req.branch(), body.ref, body.code, body.amountCents)

    @PostMapping("/mpesa/claims/status")
    fun statuses(req: HttpServletRequest, @RequestBody body: MpesaStatusRequest) = mpesa.statuses(req.branch(), body.codes)

    @PostMapping("/mpesa/stk")
    fun stk(req: HttpServletRequest, @RequestBody body: StkStartRequest) = mpesa.startStk(req.branch(), body.ref, body.phone, body.amountCents)

    @GetMapping("/mpesa/stk/{id}")
    fun stkStatus(req: HttpServletRequest, @PathVariable id: String) = mpesa.stkStatus(req.branch(), id)
}
