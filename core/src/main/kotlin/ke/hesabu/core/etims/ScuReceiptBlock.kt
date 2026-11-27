package ke.hesabu.core.etims

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The "SCU Information" block every receipt must print (TIS spec 6.23), plus the QR code content. */
data class ScuReceiptBlock(val lines: List<String>, val qrPayload: String) {
    companion object {
        private val IN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** "ABCD-EFGH-IJ": a dash after every 4th character (6.23.6, 6.23.7). */
        fun dashed(s: String) = s.chunked(4).joinToString("-")

        fun of(sig: SalesSignature, label: String): ScuReceiptBlock {
            val at = LocalDateTime.parse(sig.vscuRcptPbctDate, IN)
            val lines = listOf(
                "SCU Information",
                "Date: ${at.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} Time: ${at.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}",
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
