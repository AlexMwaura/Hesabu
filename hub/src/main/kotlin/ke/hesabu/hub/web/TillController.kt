package ke.hesabu.hub.web

import ke.hesabu.hub.HubProperties
import ke.hesabu.hub.catalogue.CatalogueRepo
import ke.hesabu.hub.credit.CreditLimitChange
import ke.hesabu.hub.credit.DeniService
import ke.hesabu.hub.credit.NewCustomer
import ke.hesabu.hub.credit.Repayment
import ke.hesabu.hub.fiscal.FiscalService
import ke.hesabu.hub.mpesa.HubMpesaService
import ke.hesabu.hub.sales.CreditNoteRequest
import ke.hesabu.hub.sales.ReceiptService
import ke.hesabu.hub.sales.SaleRequest
import ke.hesabu.hub.sales.SaleService
import ke.hesabu.hub.sales.SaleStatus
import ke.hesabu.hub.sales.SaleStore
import ke.hesabu.hub.shift.CloseShift
import ke.hesabu.hub.shift.OpenShift
import ke.hesabu.hub.shift.ShiftService
import ke.hesabu.hub.stock.AdjustmentRequest
import ke.hesabu.hub.stock.GoodsReceivedRequest
import ke.hesabu.hub.stock.StockService
import ke.hesabu.hub.sync.Outbox
import ke.hesabu.hub.sync.SyncService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class MpesaCheck(val code: String, val amountCents: Long)
data class StkPrompt(val ref: String, val phone: String, val amountCents: Long)

/** The API the Android till calls over the shop's LAN. Till-specific calls carry an X-Till-Id header. */
@RestController
@RequestMapping("/api/till/v1")
class TillController(
    private val catalogue: CatalogueRepo,
    private val stock: StockService,
    private val sales: SaleService,
    private val receipts: ReceiptService,
    private val store: SaleStore,
    private val shifts: ShiftService,
    private val deni: DeniService,
    private val mpesa: HubMpesaService,
    private val fiscal: FiscalService,
    private val outbox: Outbox,
    private val sync: SyncService,
    private val props: HubProperties,
) {
    @GetMapping("/status")
    fun status() = mapOf(
        "branchCode" to props.branchCode,
        "tradeName" to props.tradeName,
        "fiscalInSync" to fiscal.isInSync,
        "lastInvoiceNo" to fiscal.lastInvcNo(),
        "unsyncedEvents" to outbox.unsentCount(),
        "lastSyncAt" to sync.lastSyncAt(),
        "salesNeedingAttention" to store.countByStatus(SaleStatus.NEEDS_ATTENTION),
    )

    @GetMapping("/catalogue")
    fun catalogue() = catalogue.all()

    @GetMapping("/stock")
    fun stock() = stock.levels()

    @PostMapping("/stock/receipts")
    fun receive(@RequestBody req: GoodsReceivedRequest) = stock.receive(req)

    @PostMapping("/stock/adjustments")
    fun adjust(@RequestBody req: AdjustmentRequest) = stock.adjust(req)

    @PostMapping("/shifts")
    fun openShift(@RequestHeader("X-Till-Id") tillId: String, @RequestBody req: OpenShift) =
        ResponseEntity.status(HttpStatus.CREATED).body(shifts.open(tillId, req))

    @GetMapping("/shifts/current")
    fun currentShift(@RequestHeader("X-Till-Id") tillId: String) = shifts.current(tillId) ?: notFound("Open shift on till $tillId")

    @PostMapping("/shifts/{id}/close")
    fun closeShift(@RequestHeader("X-Till-Id") tillId: String, @PathVariable id: String, @RequestBody req: CloseShift) =
        shifts.close(tillId, id, req)

    @GetMapping("/shifts/{id}/report")
    fun shiftReport(@PathVariable id: String) = shifts.report(id)

    @PostMapping("/sales")
    fun sell(@RequestHeader("X-Till-Id") tillId: String, @RequestBody req: SaleRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(sales.sell(tillId, req))

    @GetMapping("/sales/{id}/receipt")
    fun receipt(@PathVariable id: String, @RequestParam(defaultValue = "false") copy: Boolean) = receipts.view(id, copy)

    @PostMapping("/sales/{id}/credit-notes")
    fun creditNote(@RequestHeader("X-Till-Id") tillId: String, @PathVariable id: String, @RequestBody req: CreditNoteRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(sales.creditNote(tillId, id, req))

    @PostMapping("/customers")
    fun createCustomer(@RequestBody req: NewCustomer) = ResponseEntity.status(HttpStatus.CREATED).body(deni.create(req))

    @GetMapping("/customers")
    fun customers() = deni.list()

    @GetMapping("/customers/{id}")
    fun customer(@PathVariable id: String) = deni.statement(id)

    @PutMapping("/customers/{id}/credit-limit")
    fun creditLimit(@PathVariable id: String, @RequestBody req: CreditLimitChange) = deni.setLimit(id, req)

    @PostMapping("/customers/{id}/payments")
    fun repay(@RequestHeader("X-Till-Id") tillId: String, @PathVariable id: String, @RequestBody req: Repayment) = deni.repay(tillId, id, req)

    @PostMapping("/mpesa/check")
    fun checkMpesa(@RequestBody req: MpesaCheck) = mpesa.check(req.code, req.amountCents)

    @PostMapping("/mpesa/stk")
    fun stk(@RequestBody req: StkPrompt) = mpesa.startStk(req.ref, req.phone, req.amountCents)

    @GetMapping("/mpesa/stk/{checkoutRequestId}")
    fun stkStatus(@PathVariable checkoutRequestId: String) = mpesa.stkStatus(checkoutRequestId)

    @PostMapping("/sync")
    fun syncNow() = sync.runOnce()
}
