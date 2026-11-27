package ke.hesabu.core.etims

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/*
 * Wire models for the VSCU's local REST API, from KRA's "Virtual Sales Control Unit Requirements &
 * Communication Protocols" v2.0 (April 2023): 3.3.1 initialisation, 3.3.6 sales, 4 code tables.
 *
 * TODO(kra-sandbox): every field here was written from the PDF. Replay real requests against KRA's test
 * VSCU JAR and adjust names and number formats to what it actually accepts.
 */

object VscuResult {
    const val OK = "000"
    const val NO_RESULT = "001"
    const val OVERLAPPED = "994"
    const val UNKNOWN = "999"
}

data class InitInfoReq(val tin: String, val bhfId: String, val dvcSrlNo: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VscuResponse<T>(
    val resultCd: String,
    val resultMsg: String? = null,
    val resultDt: String? = null,
    val data: T? = null,
) {
    val ok: Boolean get() = resultCd == VscuResult.OK
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

/** TrnsSalesSaveWrReq. Amounts are VAT-inclusive with two decimals. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class TrnsSalesSaveReq(
    val tin: String,
    val bhfId: String,
    val trdInvcNo: String,
    val invcNo: Long,
    val orgInvcNo: Long,
    val custTin: String?,
    val custNm: String?,
    val salesTyCd: String,
    val rcptTyCd: String,
    val pmtTyCd: String?,
    val salesSttsCd: String,
    val cfmDt: String,
    val salesDt: String,
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
 * The spec's field table labels itemCd "Item Classification Code" and itemClsCd "Item Code", but its
 * JSON sample uses itemCd for the trader's item code and itemClsCd for KRA's classification. The sample
 * is followed, as is its "taxAmt" (the table says "totTaxAmt").
 * TODO(kra-sandbox): confirm both against the real VSCU.
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

/** TrnsSalesSaveWrRes.data: what the VSCU returns when it signs a receipt. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SalesSignature(
    val rcptNo: Long,
    val intrlData: String,
    val rcptSign: String,
    val totRcptNo: Long,
    @get:JsonProperty("VSCURcptPbctDate") @param:JsonProperty("VSCURcptPbctDate") val vscuRcptPbctDate: String,
    val sdcId: String,
    val mrcNo: String? = null,
)
