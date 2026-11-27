package ke.hesabu.hub.sales

import ke.hesabu.core.Money
import ke.hesabu.core.Qty
import ke.hesabu.core.etims.ReceiptType
import ke.hesabu.core.etims.ScuReceiptBlock
import ke.hesabu.hub.HubProperties
import ke.hesabu.hub.mpesa.HubMpesaService
import ke.hesabu.hub.web.HubException
import ke.hesabu.hub.web.notFound
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/** Builds the printable receipt for a signed sale or credit note, original or reprint. */
@Service
class ReceiptService(
    private val store: SaleStore,
    private val mpesa: HubMpesaService,
    private val hub: HubProperties,
) {
    fun view(saleId: String, copy: Boolean): ReceiptView {
        val row = store.byId(saleId) ?: notFound("Sale $saleId")
        if (row.status != SaleStatus.SIGNED) {
            throw HubException(HttpStatus.CONFLICT, "NOT_SIGNED", "This sale has no signed receipt (status ${row.status})")
        }
        val req = store.request(row)
        val sig = store.signature(row)
        val type = if (row.receiptType == "S") ReceiptType.SALE else ReceiptType.CREDIT_NOTE
        val label = if (copy) type.copyLabel else type.originalLabel
        val block = ScuReceiptBlock.of(sig, label)
        val lines = store.lines(saleId)

        val taxes = lines.groupBy { it.taxType }.toSortedMap().map { (t, ls) ->
            val taxable = ls.sumOf { it.totalCents }
            ReceiptTax(t.name, t.label, t.rate, taxable, ls.sumOf { Money.includedTax(it.totalCents, t.rate) })
        }

        return ReceiptView(
            saleId = row.id,
            clientRef = row.clientRef,
            receiptType = type.name,
            label = label,
            copy = copy,
            tradeName = hub.tradeName,
            kraPin = req.tin,
            bhfId = req.bhfId,
            tradeInvoiceNo = req.trdInvcNo,
            invoiceNo = req.invcNo,
            originalInvoiceNo = req.orgInvcNo.takeIf { it > 0 },
            issuedAt = row.signedAt!!,
            cashierName = req.regrNm,
            customerName = req.custNm,
            customerPin = req.custTin,
            lines = lines.map { ReceiptLine(it.productCode, it.name, Qty.ofMilli(it.qtyMilli), it.unitPriceCents, it.totalCents, it.taxType.name) },
            taxes = taxes,
            totalCents = row.totalCents,
            taxCents = row.taxCents,
            payments = store.tenders(saleId).map { t ->
                ReceiptPayment(t.method, t.amountCents, t.mpesaCode, t.mpesaCode?.let { mpesa.claimOf(it)?.status?.name })
            },
            scuLines = block.lines,
            qrPayload = block.qrPayload,
        )
    }
}
