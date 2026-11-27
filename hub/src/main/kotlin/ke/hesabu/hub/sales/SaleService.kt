package ke.hesabu.hub.sales

import ke.hesabu.core.Qty
import ke.hesabu.core.etims.EtimsInvoice
import ke.hesabu.core.etims.InvoiceHeader
import ke.hesabu.core.etims.SalesSignature
import ke.hesabu.core.etims.TrnsSalesSaveReq
import ke.hesabu.core.etims.VscuResult
import ke.hesabu.core.sale.PricedLine
import ke.hesabu.core.sale.SaleTotals
import ke.hesabu.core.sale.TenderMethod
import ke.hesabu.core.sale.TenderRules
import ke.hesabu.core.stock.StockMovementType
import ke.hesabu.core.stock.StockRules
import ke.hesabu.core.sync.EventTypes
import ke.hesabu.core.sync.SaleLineRecord
import ke.hesabu.core.sync.SaleRecorded
import ke.hesabu.core.sync.ScuRecord
import ke.hesabu.core.sync.TenderRecord
import ke.hesabu.hub.HubProperties
import ke.hesabu.hub.VscuProperties
import ke.hesabu.hub.catalogue.CatalogueRepo
import ke.hesabu.hub.credit.DeniService
import ke.hesabu.hub.fiscal.FiscalService
import ke.hesabu.hub.fiscal.VscuClient
import ke.hesabu.hub.fiscal.VscuNoAnswer
import ke.hesabu.hub.fiscal.VscuUnreachable
import ke.hesabu.hub.mpesa.HubMpesaService
import ke.hesabu.hub.shift.Shift
import ke.hesabu.hub.shift.ShiftService
import ke.hesabu.hub.stock.Movement
import ke.hesabu.hub.stock.StockService
import ke.hesabu.hub.sync.Outbox
import ke.hesabu.hub.web.HubException
import ke.hesabu.hub.web.notFound
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Sales and credit notes. The order of work follows KRA's rules:
 * validate → check stock (6.29) → write the attempt → VSCU signs → record stock, deni, M-Pesa and the
 * sync event in one transaction → return a printable receipt. No receipt without a signature (6.7, 10).
 */
@Service
class SaleService(
    private val store: SaleStore,
    private val catalogue: CatalogueRepo,
    private val stock: StockService,
    private val fiscal: FiscalService,
    private val vscu: VscuClient,
    private val shifts: ShiftService,
    private val deni: DeniService,
    private val mpesa: HubMpesaService,
    private val outbox: Outbox,
    private val receipts: ReceiptService,
    private val hub: HubProperties,
    private val vscuProps: VscuProperties,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {

    fun sell(tillId: String, req: SaleRequest): ReceiptView {
        require(req.clientRef.isNotBlank()) { "clientRef is required" }
        val existing = store.byClientRef(req.clientRef)
        alreadyDone(existing, "S")?.let { return it }

        val shift = shifts.requireOpen(tillId)
        val lines = price(req)
        val totals = SaleTotals.of(lines)
        val problems = TenderRules.problems(totals.totalCents, req.tenders, req.customerId)
        if (problems.isNotEmpty()) throw HubException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYMENT", problems.first(), problems)
        val customer = req.customerId?.let(deni::get)
        val creditCents = req.tenders.filter { it.method == TenderMethod.CREDIT }.sumOf { it.amountCents }
        val tenders = req.tenders.map { it.stored() }
        val saleId = existing?.id ?: UUID.randomUUID().toString()
        val codes = tenders.filter { it.isMpesa() }.mapNotNull { it.mpesaCode }

        val id = signing {
            resolveUncertain(existing)
            stock.locked {
                checkStock(lines)
                mpesa.ensureUnused(codes, saleId)
                if (creditCents > 0) deni.checkCanCharge(customer!!.id, creditCents)

                val invcNo = fiscal.lastInvcNo() + 1
                val at = clock.instant()
                val header = header(
                    invcNo, shift, at, TenderRules.etimsPaymentCode(req.tenders),
                    req.customerPin ?: customer?.kraPin, req.customerName ?: customer?.name, customer?.phone,
                )
                val request = EtimsInvoice.sale(header, lines, invcNo)
                tx.executeWithoutResult {
                    store.writeAttempt(
                        saleId, req.clientRef, "S", null, shift.id, tillId, invcNo, request, lines, tenders,
                        totals.totalCents, totals.taxCents, customer?.id, null, at, retry = existing != null,
                    )
                }
                val sig = sign(saleId, request)
                tx.executeWithoutResult {
                    store.markSigned(saleId, invcNo, sig, at)
                    fiscal.advanceTo(invcNo)
                    stock.apply(lines.goods().map { Movement(it.productCode, StockMovementType.SALE, Qty.toMilli(it.qty), ref = saleId) }, at)
                    if (creditCents > 0) deni.charge(customer!!.id, creditCents, saleId, shift.id, at)
                    tenders.filter { it.isMpesa() }.forEach { mpesa.recordClaim(it.mpesaCode!!, saleId, it.amountCents, at) }
                    outbox.enqueue(EventTypes.SALE_RECORDED, event(saleId), at)
                }
                saleId
            }
        }
        if (codes.isNotEmpty()) mpesa.trySubmitNow()
        return receipts.view(id, copy = false)
    }

    fun creditNote(tillId: String, originalSaleId: String, req: CreditNoteRequest): ReceiptView {
        require(req.clientRef.isNotBlank()) { "clientRef is required" }
        require(req.lines.isNotEmpty()) { "A credit note needs at least one line" }
        val existing = store.byClientRef(req.clientRef)
        alreadyDone(existing, "R")?.let { return it }

        val original = store.byId(originalSaleId) ?: notFound("Sale $originalSaleId")
        if (original.receiptType != "S" || original.status != SaleStatus.SIGNED) {
            throw HubException(HttpStatus.CONFLICT, "NOT_REFUNDABLE", "Only signed sales can be refunded")
        }
        val shift = shifts.requireOpen(tillId)
        val originalReq = store.request(original)
        val sold = store.lines(originalSaleId)

        val lines = req.lines.map { r ->
            val soldLines = sold.filter { it.productCode == r.productCode }
            if (soldLines.isEmpty()) throw HubException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ON_SALE", "${r.productCode} is not on the original sale")
            val item = originalReq.itemList.first { it.itemCd == r.productCode }
            val first = soldLines.first()
            PricedLine(
                r.productCode, item.itemClsCd, item.itemNm, item.bcd, r.qty, first.unitPriceCents, first.taxType,
                first.isService, item.qtyUnitCd, item.pkgUnitCd,
            )
        }
        val totals = SaleTotals.of(lines)
        val customerId = original.customerId
        if (req.refundMethod == RefundMethod.DENI) {
            if (customerId == null) throw HubException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CUSTOMER", "The original sale has no customer to credit")
            deni.checkCanRefund(customerId, totals.totalCents)
        }
        val tenderMethod = when (req.refundMethod) {
            RefundMethod.CASH -> "CASH"
            RefundMethod.DENI -> "CREDIT"
            RefundMethod.MPESA -> "MPESA"
        }
        val tenders = listOf(StoredTender(tenderMethod, totals.totalCents, null, null))
        val noteId = existing?.id ?: UUID.randomUUID().toString()

        val id = signing {
            resolveUncertain(existing)
            stock.locked {
                checkReturnable(originalSaleId, sold, lines)
                val invcNo = fiscal.lastInvcNo() + 1
                val at = clock.instant()
                val header = header(invcNo, shift, at, req.refundMethod.etimsPaymentCode, originalReq.custTin, originalReq.custNm, null)
                val request = EtimsInvoice.creditNote(header, lines, invcNo, original.invcNo!!, req.reason)
                tx.executeWithoutResult {
                    store.writeAttempt(
                        noteId, req.clientRef, "R", originalSaleId, shift.id, tillId, invcNo, request, lines, tenders,
                        totals.totalCents, totals.taxCents, customerId, req.reason.name, at, retry = existing != null,
                    )
                }
                val sig = sign(noteId, request)
                tx.executeWithoutResult {
                    store.markSigned(noteId, invcNo, sig, at)
                    fiscal.advanceTo(invcNo)
                    if (req.restock) {
                        stock.apply(lines.goods().map { Movement(it.productCode, StockMovementType.RETURN_IN, Qty.toMilli(it.qty), ref = noteId) }, at)
                    }
                    if (req.refundMethod == RefundMethod.DENI) deni.refund(customerId!!, totals.totalCents, noteId, shift.id, at)
                    outbox.enqueue(EventTypes.SALE_RECORDED, event(noteId), at)
                }
                noteId
            }
        }
        return receipts.view(id, copy = false)
    }

    /** A retry of something already finished returns the same receipt; a few states can't be retried. */
    private fun alreadyDone(existing: SaleRow?, receiptType: String): ReceiptView? {
        if (existing == null) return null
        if (existing.receiptType != receiptType) {
            throw HubException(HttpStatus.CONFLICT, "CLIENT_REF_REUSED", "clientRef ${existing.clientRef} was used for a different document")
        }
        return when (existing.status) {
            SaleStatus.SIGNED -> receipts.view(existing.id, copy = false)
            SaleStatus.NEEDS_ATTENTION -> throw needsAttention(existing)
            SaleStatus.SIGNING -> throw HubException(HttpStatus.CONFLICT, "IN_PROGRESS", "This sale is being signed; try again in a moment")
            SaleStatus.BLOCKED, SaleStatus.REJECTED, SaleStatus.UNCERTAIN -> null
        }
    }

    /**
     * A retry after the VSCU gave no answer. The signing lock has just resynced the number series with
     * the VSCU: if the VSCU has recorded the attempt's number and no signed sale here holds it, the VSCU
     * signed this sale and the signature was lost.
     */
    private fun resolveUncertain(existing: SaleRow?) {
        if (existing?.status != SaleStatus.UNCERTAIN) return
        val n = existing.attemptInvcNo ?: return
        fiscal.resync()
        if (fiscal.lastInvcNo() >= n && store.signedWithInvoice(n) == null) {
            store.setStatus(existing.id, SaleStatus.NEEDS_ATTENTION, null, "VSCU recorded invoice $n but the hub has no signature")
            throw needsAttention(existing)
        }
    }

    private fun needsAttention(row: SaleRow) = HubException(
        HttpStatus.CONFLICT, "NEEDS_ATTENTION",
        "The fiscal unit signed this sale but the hub did not get the receipt. Don't sell it again; call support.",
        mapOf("saleId" to row.id, "invoiceNo" to row.attemptInvcNo),
    )

    /** Holds the signing lock, turning VSCU connection problems during resync into till-facing errors. */
    private fun <T> signing(block: () -> T): T = try {
        fiscal.exclusive(block)
    } catch (e: VscuUnreachable) {
        throw vscuDown()
    } catch (e: VscuNoAnswer) {
        throw vscuDown()
    }

    private fun vscuDown() = HubException(
        HttpStatus.SERVICE_UNAVAILABLE, "VSCU_UNREACHABLE",
        "The fiscal unit can't be reached, so no receipt can be printed. Check the hub box, then try this sale again.",
    )

    private fun sign(saleId: String, request: TrnsSalesSaveReq): SalesSignature {
        val res = try {
            vscu.saveSales(request)
        } catch (e: VscuUnreachable) {
            store.setStatus(saleId, SaleStatus.BLOCKED, null, e.message)
            throw vscuDown()
        } catch (e: VscuNoAnswer) {
            store.setStatus(saleId, SaleStatus.UNCERTAIN, null, e.message)
            fiscal.markOutOfSync()
            throw HubException(
                HttpStatus.GATEWAY_TIMEOUT, "VSCU_NO_ANSWER",
                "The fiscal unit didn't answer. Try this sale again; Hesabu will check whether it was signed.",
            )
        }
        val sig = res.data
        if (!res.ok || sig == null) {
            store.setStatus(saleId, SaleStatus.REJECTED, res.resultCd, res.resultMsg)
            if (res.resultCd == VscuResult.OVERLAPPED) fiscal.markOutOfSync()
            throw HubException(
                HttpStatus.BAD_GATEWAY, "VSCU_REJECTED",
                "The fiscal unit refused to sign (${res.resultCd}): ${res.resultMsg}",
                mapOf("resultCd" to res.resultCd),
            )
        }
        return sig
    }

    private fun price(req: SaleRequest): List<PricedLine> {
        require(req.lines.isNotEmpty()) { "A sale needs at least one item" }
        return req.lines.map { l ->
            val p = when {
                l.productCode != null -> catalogue.find(l.productCode)
                l.barcode != null -> catalogue.findByBarcode(l.barcode)
                else -> throw IllegalArgumentException("Each line needs a productCode or a barcode")
            }
            if (p == null || !p.active) notFound("Product ${l.productCode ?: l.barcode}")
            require(l.qty.signum() > 0) { "Quantities must be positive" }
            Qty.toMilli(l.qty)
            PricedLine(p.code, p.itemClassCode, p.name, p.barcode, l.qty, p.priceCents, p.taxType, p.isService, p.qtyUnitCode, p.pkgUnitCode)
        }
    }

    private fun checkStock(lines: List<PricedLine>) {
        val requested = lines.goods().groupBy { it.productCode }.mapValues { (_, ls) -> ls.sumOf { Qty.toMilli(it.qty) } }
        val short = StockRules.shortfalls(requested, stock::onHandMilli)
        if (short.isNotEmpty()) {
            val s = short.first()
            throw HubException(
                HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Only ${Qty.ofMilli(s.onHandMilli)} of ${s.productCode} in stock; the sale needs ${Qty.ofMilli(s.requestedMilli)}",
                short,
            )
        }
    }

    private fun checkReturnable(originalSaleId: String, sold: List<StoredLine>, lines: List<PricedLine>) {
        val returned = store.returnedQty(originalSaleId)
        lines.groupBy { it.productCode }.forEach { (code, ls) ->
            val want = ls.sumOf { Qty.toMilli(it.qty) }
            val soldQty = sold.filter { it.productCode == code }.sumOf { it.qtyMilli }
            val left = soldQty - (returned[code] ?: 0)
            if (want <= 0) throw IllegalArgumentException("Return quantities must be positive")
            if (want > left) {
                throw HubException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "RETURN_EXCEEDS_SALE",
                    "Only ${Qty.ofMilli(left)} of $code can still be returned on this sale",
                )
            }
        }
    }

    private fun header(
        invcNo: Long, shift: Shift, at: Instant, paymentCode: String,
        customerPin: String?, customerName: String?, customerPhone: String?,
    ) = InvoiceHeader(
        tin = vscuProps.tin,
        bhfId = vscuProps.bhfId,
        tradeInvoiceNo = "%s-%06d".format(hub.branchCode, invcNo),
        cashierId = shift.cashierId,
        cashierName = shift.cashierName,
        at = at.atZone(clock.zone),
        paymentCode = paymentCode,
        tradeName = hub.tradeName,
        customerPin = customerPin,
        customerName = customerName,
        customerPhone = customerPhone,
    )

    private fun event(saleId: String): SaleRecorded {
        val row = store.byId(saleId)!!
        val sig = store.signature(row)
        return SaleRecorded(
            saleId = row.id,
            receiptType = row.receiptType,
            originalSaleId = row.originalSaleId,
            tradeInvoiceNo = row.tradeInvoiceNo!!,
            invcNo = row.invcNo!!,
            shiftId = row.shiftId,
            tillId = row.tillId,
            cashierId = store.request(row).regrId,
            cashierName = store.request(row).regrNm,
            signedAt = row.signedAt!!,
            customerId = row.customerId,
            totalCents = row.totalCents,
            taxCents = row.taxCents,
            lines = store.lines(saleId).map { SaleLineRecord(it.productCode, it.name, it.qtyMilli, it.unitPriceCents, it.totalCents, it.taxType.name) },
            tenders = store.tenders(saleId).map { TenderRecord(it.method, it.amountCents, it.mpesaCode) },
            scu = ScuRecord(sig.sdcId, sig.rcptNo, sig.totRcptNo, sig.intrlData, sig.rcptSign, sig.vscuRcptPbctDate),
        )
    }

    private fun List<PricedLine>.goods() = filterNot { it.isService }
}
