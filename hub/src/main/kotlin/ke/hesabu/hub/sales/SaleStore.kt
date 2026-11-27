package ke.hesabu.hub.sales

import com.fasterxml.jackson.databind.ObjectMapper
import ke.hesabu.core.Qty
import ke.hesabu.core.TaxType
import ke.hesabu.core.etims.SalesSignature
import ke.hesabu.core.etims.TrnsSalesSaveReq
import ke.hesabu.core.sale.PricedLine
import ke.hesabu.core.sale.Tender
import ke.hesabu.core.sale.TenderMethod
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

data class SaleRow(
    val id: String,
    val clientRef: String,
    val receiptType: String,
    val originalSaleId: String?,
    val shiftId: String,
    val tillId: String,
    val status: SaleStatus,
    val attemptInvcNo: Long?,
    val invcNo: Long?,
    val tradeInvoiceNo: String?,
    val totalCents: Long,
    val taxCents: Long,
    val customerId: String?,
    val signedAt: String?,
    val requestJson: String?,
    val signatureJson: String?,
)

data class StoredLine(val seq: Int, val productCode: String, val name: String, val qtyMilli: Long, val unitPriceCents: Long, val totalCents: Long, val taxType: TaxType, val isService: Boolean)
data class StoredTender(val method: String, val amountCents: Long, val mpesaCode: String?, val checkoutRequestId: String?)

/** Persistence for sales and credit notes. */
@Repository
class SaleStore(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    fun byClientRef(ref: String): SaleRow? =
        jdbc.sql("SELECT * FROM sale WHERE client_ref = :r").param("r", ref).query(::row).optional().orElse(null)

    fun byId(id: String): SaleRow? = jdbc.sql("SELECT * FROM sale WHERE id = :id").param("id", id).query(::row).optional().orElse(null)

    fun signedWithInvoice(invcNo: Long): SaleRow? =
        jdbc.sql("SELECT * FROM sale WHERE invc_no = :n AND status = 'SIGNED'").param("n", invcNo).query(::row).optional().orElse(null)

    /** Writes (or rewrites, on retry) the attempt before the VSCU is called. */
    fun writeAttempt(
        id: String, clientRef: String, receiptType: String, originalSaleId: String?, shiftId: String, tillId: String,
        invcNo: Long, request: TrnsSalesSaveReq, lines: List<PricedLine>, tenders: List<StoredTender>,
        totalCents: Long, taxCents: Long, customerId: String?, reason: String?, at: Instant, retry: Boolean,
    ) {
        if (retry) {
            jdbc.sql("DELETE FROM sale_line WHERE sale_id = :id").param("id", id).update()
            jdbc.sql("DELETE FROM tender WHERE sale_id = :id").param("id", id).update()
            jdbc.sql(
                """
                UPDATE sale SET status = 'SIGNING', attempt_invc_no = :n, trade_invoice_no = :trd, total_cents = :tot,
                    tax_cents = :tax, customer_id = :cust, credit_note_reason = :rsn, shift_id = :shift, till_id = :till,
                    request_json = :req, vscu_result_cd = NULL, vscu_result_msg = NULL
                WHERE id = :id
                """.trimIndent(),
            )
                .param("n", invcNo).param("trd", request.trdInvcNo).param("tot", totalCents).param("tax", taxCents)
                .param("cust", customerId).param("rsn", reason).param("shift", shiftId).param("till", tillId)
                .param("req", mapper.writeValueAsString(request)).param("id", id).update()
        } else {
            jdbc.sql(
                """
                INSERT INTO sale (id, client_ref, receipt_type, original_sale_id, shift_id, till_id, status, attempt_invc_no,
                                  trade_invoice_no, total_cents, tax_cents, customer_id, credit_note_reason, created_at, request_json)
                VALUES (:id, :ref, :rt, :orig, :shift, :till, 'SIGNING', :n, :trd, :tot, :tax, :cust, :rsn, :at, :req)
                """.trimIndent(),
            )
                .param("id", id).param("ref", clientRef).param("rt", receiptType).param("orig", originalSaleId)
                .param("shift", shiftId).param("till", tillId).param("n", invcNo).param("trd", request.trdInvcNo)
                .param("tot", totalCents).param("tax", taxCents).param("cust", customerId).param("rsn", reason)
                .param("at", at.toString()).param("req", mapper.writeValueAsString(request)).update()
        }
        lines.forEachIndexed { i, l ->
            jdbc.sql(
                """
                INSERT INTO sale_line (sale_id, seq, product_code, name, qty_milli, unit_price_cents, total_cents, tax_type, is_service)
                VALUES (:id, :seq, :code, :name, :qty, :price, :total, :tax, :svc)
                """.trimIndent(),
            )
                .param("id", id).param("seq", i + 1).param("code", l.productCode).param("name", l.name)
                .param("qty", Qty.toMilli(l.qty)).param("price", l.unitPriceCents).param("total", l.totalCents)
                .param("tax", l.taxType.name).param("svc", if (l.isService) 1 else 0).update()
        }
        tenders.forEachIndexed { i, t ->
            jdbc.sql("INSERT INTO tender (sale_id, seq, method, amount_cents, mpesa_code, checkout_request_id) VALUES (:id, :seq, :m, :a, :c, :co)")
                .param("id", id).param("seq", i + 1).param("m", t.method).param("a", t.amountCents)
                .param("c", t.mpesaCode).param("co", t.checkoutRequestId).update()
        }
    }

    fun setStatus(id: String, status: SaleStatus, resultCd: String? = null, resultMsg: String? = null) {
        jdbc.sql("UPDATE sale SET status = :s, vscu_result_cd = :cd, vscu_result_msg = :msg WHERE id = :id")
            .param("s", status.name).param("cd", resultCd).param("msg", resultMsg?.take(500)).param("id", id).update()
    }

    fun markSigned(id: String, invcNo: Long, sig: SalesSignature, at: Instant) {
        jdbc.sql("UPDATE sale SET status = 'SIGNED', invc_no = :n, signed_at = :at, signature_json = :sig, vscu_result_cd = '000' WHERE id = :id")
            .param("n", invcNo).param("at", at.toString()).param("sig", mapper.writeValueAsString(sig)).param("id", id).update()
    }

    fun lines(saleId: String): List<StoredLine> =
        jdbc.sql("SELECT seq, product_code, name, qty_milli, unit_price_cents, total_cents, tax_type, is_service FROM sale_line WHERE sale_id = :id ORDER BY seq")
            .param("id", saleId)
            .query { rs, _ ->
                StoredLine(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                    TaxType.valueOf(rs.getString(7)), rs.getInt(8) == 1)
            }.list()

    fun tenders(saleId: String): List<StoredTender> =
        jdbc.sql("SELECT method, amount_cents, mpesa_code, checkout_request_id FROM tender WHERE sale_id = :id ORDER BY seq")
            .param("id", saleId).query { rs, _ -> StoredTender(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)) }.list()

    /** Quantity of each product already returned against [originalSaleId] on signed credit notes. */
    fun returnedQty(originalSaleId: String): Map<String, Long> =
        jdbc.sql(
            """
            SELECT l.product_code, SUM(l.qty_milli) FROM sale_line l JOIN sale s ON s.id = l.sale_id
            WHERE s.original_sale_id = :id AND s.status = 'SIGNED' AND s.receipt_type = 'R' GROUP BY l.product_code
            """.trimIndent(),
        ).param("id", originalSaleId).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

    fun request(row: SaleRow): TrnsSalesSaveReq = mapper.readValue(row.requestJson, TrnsSalesSaveReq::class.java)
    fun signature(row: SaleRow): SalesSignature = mapper.readValue(row.signatureJson, SalesSignature::class.java)

    fun countByStatus(status: SaleStatus): Long =
        jdbc.sql("SELECT COUNT(*) FROM sale WHERE status = :s").param("s", status.name).query { rs, _ -> rs.getLong(1) }.single()

    private fun row(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = SaleRow(
        id = rs.getString("id"),
        clientRef = rs.getString("client_ref"),
        receiptType = rs.getString("receipt_type"),
        originalSaleId = rs.getString("original_sale_id"),
        shiftId = rs.getString("shift_id"),
        tillId = rs.getString("till_id"),
        status = SaleStatus.valueOf(rs.getString("status")),
        attemptInvcNo = rs.getLong("attempt_invc_no").takeUnless { rs.wasNull() },
        invcNo = rs.getLong("invc_no").takeUnless { rs.wasNull() },
        tradeInvoiceNo = rs.getString("trade_invoice_no"),
        totalCents = rs.getLong("total_cents"),
        taxCents = rs.getLong("tax_cents"),
        customerId = rs.getString("customer_id"),
        signedAt = rs.getString("signed_at"),
        requestJson = rs.getString("request_json"),
        signatureJson = rs.getString("signature_json"),
    )
}

fun Tender.stored() = StoredTender(method.name, amountCents, mpesaCode?.trim()?.uppercase(), checkoutRequestId)
fun StoredTender.isMpesa() = method == TenderMethod.MPESA_CODE.name || method == TenderMethod.MPESA_STK.name
