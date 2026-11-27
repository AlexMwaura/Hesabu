package ke.hesabu.spike.etims

import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the till may do with a sale after asking the VSCU to sign it. */
sealed interface FiscalResult {
    data class Printable(val request: TrnsSalesSaveReq, val signature: SalesSignature, val receipt: ScuReceiptBlock) : FiscalResult
    /** VSCU unreachable: TIS spec 6.7/10 forbid printing a receipt. The sale must not complete. */
    data class Blocked(val reason: String) : FiscalResult
    /** VSCU answered with an error, e.g. after 24 hours without reaching KRA. */
    data class Rejected(val resultCd: String, val resultMsg: String?) : FiscalResult
    /** TIS spec 6.29: goods may not be sold beyond the stock on hand. */
    data class InsufficientStock(val itemCd: String, val onHand: BigDecimal, val requested: BigDecimal) : FiscalResult
}

/** Stock on hand per item, enough to enforce spec 6.29 in the spike. */
@Service
class StockBook {
    private val onHand = ConcurrentHashMap<String, BigDecimal>()
    fun set(itemCd: String, qty: BigDecimal) { onHand[itemCd] = qty }
    fun onHand(itemCd: String): BigDecimal = onHand[itemCd] ?: BigDecimal.ZERO
    fun release(itemCd: String, qty: BigDecimal) { onHand.merge(itemCd, qty.negate(), BigDecimal::add) }
}

/**
 * The till side (KRA calls it the Trader Invoicing System): check stock, ask the VSCU to sign,
 * and only then produce the SCU block that goes on the printed receipt.
 */
@Service
class FiscalReceiptService(
    private val vscu: VscuClient,
    private val props: VscuProperties,
    private val stock: StockBook,
) {
    // Spec 6.3: one ascending number series per receipt type.
    private val lastInvoiceNo = AtomicLong(0)

    /** Spec 6.3: after a reset, continue from the last invoice the VSCU transmitted. Call at till start-up. */
    fun restoreSequence(): Long {
        val res = vscu.initInfo(InitInfoReq(props.tin, props.bhfId, props.deviceSerial))
        val last = res.data?.info?.lastSaleInvcNo ?: 0
        lastInvoiceNo.set(last)
        return last
    }

    @Synchronized
    fun sign(sale: TillSale, tradeName: String? = null): FiscalResult {
        sale.lines.filterNot { it.isService }
            .groupBy { it.itemCd }
            .forEach { (itemCd, lines) ->
                val requested = lines.fold(BigDecimal.ZERO) { a, l -> a + l.qty }
                val have = stock.onHand(itemCd)
                if (have < requested) return FiscalResult.InsufficientStock(itemCd, have, requested)
            }

        val invcNo = lastInvoiceNo.get() + 1
        val req = SaleToVscu.build(sale, invcNo, props.tin, props.bhfId, tradeName)
        val res = try {
            vscu.saveSales(req)
        } catch (e: VscuUnavailable) {
            return FiscalResult.Blocked(e.message ?: "VSCU not reachable")
        }
        if (!res.ok || res.data == null) return FiscalResult.Rejected(res.resultCd, res.resultMsg)

        lastInvoiceNo.set(invcNo)
        sale.lines.filterNot { it.isService }.forEach { stock.release(it.itemCd, it.qty) }
        return FiscalResult.Printable(req, res.data, ScuReceiptBlock.of(res.data, label = "NS"))
    }
}

/** The "SCU Information" block a receipt must print, per TIS spec 6.23. */
data class ScuReceiptBlock(val lines: List<String>, val qrPayload: String) {
    companion object {
        private val IN = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** "ABCD-EFGH-IJ": a dash after every 4th character (6.23.6, 6.23.7). */
        fun dashed(s: String) = s.chunked(4).joinToString("-")

        fun of(sig: SalesSignature, label: String): ScuReceiptBlock {
            val at = LocalDateTime.parse(sig.vscuRcptPbctDate, IN)
            val date = at.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            val time = at.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val lines = listOf(
                "SCU Information",
                "Date: $date Time: $time",
                "CU ID: ${sig.sdcId}",
                "CU Invoice No.: ${sig.sdcId}/${sig.rcptNo}",
                "Receipt counter: ${sig.rcptNo}/${sig.totRcptNo} $label",
                "Internal Data: ${dashed(sig.intrlData)}",
                "Receipt Signature: ${dashed(sig.rcptSign)}",
            )
            // 6.23.8: invoice_date(ddmmyyyy)#time(hhmmss)#cu number#cu_receipt_number#internal_data#receipt_signature
            val qr = listOf(
                at.format(DateTimeFormatter.ofPattern("ddMMyyyy")),
                at.format(DateTimeFormatter.ofPattern("HHmmss")),
                sig.sdcId,
                sig.rcptNo.toString(),
                sig.intrlData,
                sig.rcptSign,
            ).joinToString("#")
            return ScuReceiptBlock(lines, qr)
        }
    }
}
