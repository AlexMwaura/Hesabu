package ke.hesabu.spike.etims

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "hesabu.vscu")
data class VscuProperties(
    val baseUrl: String,
    val tin: String,
    val bhfId: String,
    val deviceSerial: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

/*
 * Field names and codes follow KRA's "Virtual Sales Control Unit Requirements & Communication Protocols"
 * v2.0 (April 2023), sections 3.3.1 (initialisation), 3.3.6 (sales) and 4 (code definitions).
 */

/** 4.1 Tax Type. Rates in percent. */
enum class TaxType(val rate: BigDecimal) {
    A(BigDecimal.ZERO),          // Exempt
    B(BigDecimal("16.00")),      // 16% VAT
    C(BigDecimal.ZERO),          // 0% (zero-rated)
    D(BigDecimal.ZERO),          // Non-VAT
    E(BigDecimal("8.00")),       // 8%
}

/** 4.10 Payment Method. */
enum class PaymentType(val code: String) { CASH("01"), CREDIT("02"), CASH_CREDIT("03"), BANK_CHECK("04"), CARD("05"), MOBILE_MONEY("06"), OTHER("07") }

data class InitInfoReq(val tin: String, val bhfId: String, val dvcSrlNo: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VscuResponse<T>(
    val resultCd: String,
    val resultMsg: String? = null,
    val resultDt: String? = null,
    val data: T? = null,
) {
    val ok get() = resultCd == "000"
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class InitInfoData(val info: InitInfo? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InitInfo(
    val tin: String? = null,
    val taxprNm: String? = null,
    val bhfId: String? = null,
    val bhfNm: String? = null,
    val dvcId: String? = null,
    val sdcId: String? = null,
    val mrcNo: String? = null,
    val lastSaleInvcNo: Long? = null,
    val lastSaleRcptNo: Long? = null,
)

/** TrnsSalesSaveWrReq. Amounts are VAT-inclusive, two decimals. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TrnsSalesSaveReq(
    val tin: String,
    val bhfId: String,
    val trdInvcNo: String,
    val invcNo: Long,
    val orgInvcNo: Long,
    val custTin: String?,
    val custNm: String?,
    val salesTyCd: String,          // 4.8: only "N" (Normal) is sent
    val rcptTyCd: String,           // 4.9: "S" sale, "R" credit note
    val pmtTyCd: String?,
    val salesSttsCd: String,        // 4.11: "02" approved
    val cfmDt: String,              // yyyyMMddHHmmss
    val salesDt: String,            // yyyyMMdd
    val stockRlsDt: String?,
    val cnclReqDt: String? = null,
    val cnclDt: String? = null,
    val rfdDt: String? = null,
    val rfdRsnCd: String? = null,
    val totItemCnt: Int,
    val taxblAmtA: BigDecimal, val taxblAmtB: BigDecimal, val taxblAmtC: BigDecimal, val taxblAmtD: BigDecimal, val taxblAmtE: BigDecimal,
    val taxRtA: BigDecimal, val taxRtB: BigDecimal, val taxRtC: BigDecimal, val taxRtD: BigDecimal, val taxRtE: BigDecimal,
    val taxAmtA: BigDecimal, val taxAmtB: BigDecimal, val taxAmtC: BigDecimal, val taxAmtD: BigDecimal, val taxAmtE: BigDecimal,
    val totTaxblAmt: BigDecimal,
    val totTaxAmt: BigDecimal,
    val totAmt: BigDecimal,
    val prchrAcptcYn: String = "N",
    val remark: String? = null,
    val regrId: String,
    val regrNm: String,
    val modrId: String,
    val modrNm: String,
    val receipt: SalesReceipt,
    val itemList: List<SalesItem>,
)

data class SalesReceipt(
    val custTin: String?,
    val custMblNo: String?,
    val rptNo: Long,
    val trdeNm: String?,
    val adrs: String?,
    val topMsg: String?,
    val btmMsg: String?,
    val prchrAcptcYn: String = "N",
)

/**
 * The spec's table labels itemCd "Item Classification Code" and itemClsCd "Item Code", but its JSON
 * sample uses itemCd for the trader's item code (KE1NTXU0000001) and itemClsCd for KRA's classification
 * (5059690800). The spike follows the sample. Likewise the item tax field is "taxAmt" in the sample
 * and "totTaxAmt" in the table; the sample is followed. Confirm both in the sandbox.
 */
data class SalesItem(
    val itemSeq: Int,
    val itemCd: String,
    val itemClsCd: String,
    val itemNm: String,
    val bcd: String?,
    val pkgUnitCd: String,
    val pkg: BigDecimal,
    val qtyUnitCd: String,
    val qty: BigDecimal,
    val prc: BigDecimal,
    val splyAmt: BigDecimal,
    val dcRt: BigDecimal,
    val dcAmt: BigDecimal,
    val isrccCd: String? = null,
    val isrccNm: String? = null,
    val isrcRt: BigDecimal? = null,
    val isrcAmt: BigDecimal? = null,
    val taxTyCd: String,
    val taxblAmt: BigDecimal,
    val taxAmt: BigDecimal,
    val totAmt: BigDecimal,
)

/** TrnsSalesSaveWrRes.data */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SalesSignature(
    val rcptNo: Long,
    val intrlData: String,
    val rcptSign: String,
    val totRcptNo: Long,
    @JsonProperty("VSCURcptPbctDate") val vscuRcptPbctDate: String,
    val sdcId: String,
    val mrcNo: String? = null,
)
