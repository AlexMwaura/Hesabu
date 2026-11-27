package ke.hesabu.core.sale

import ke.hesabu.core.Money
import ke.hesabu.core.mpesa.MpesaRules

object TenderRules {

    /** Problems that stop a sale before it is sent for signing. Empty means the tenders are fine. */
    fun problems(totalCents: Long, tenders: List<Tender>, customerId: String?): List<String> = buildList {
        if (tenders.isEmpty()) add("A sale needs at least one payment")
        tenders.forEachIndexed { i, t ->
            val n = i + 1
            if (t.amountCents <= 0) add("Payment $n: amount must be positive")
            when (t.method) {
                TenderMethod.CASH -> {}
                TenderMethod.MPESA_CODE -> {
                    if (!Money.isWholeShillings(t.amountCents)) add("Payment $n: M-Pesa amounts are whole shillings")
                    if (t.mpesaCode == null || !MpesaRules.isValidCode(t.mpesaCode)) add("Payment $n: M-Pesa code must be 10 letters and digits")
                }
                TenderMethod.MPESA_STK -> {
                    if (!Money.isWholeShillings(t.amountCents)) add("Payment $n: M-Pesa amounts are whole shillings")
                    if (t.checkoutRequestId.isNullOrBlank()) add("Payment $n: STK payment needs its CheckoutRequestID")
                    if (t.mpesaCode == null || !MpesaRules.isValidCode(t.mpesaCode)) add("Payment $n: STK payment needs the M-Pesa code it returned")
                }
                TenderMethod.CREDIT -> if (customerId == null) add("Payment $n: credit (deni) needs a customer")
            }
        }
        val paid = tenders.sumOf { it.amountCents }
        if (tenders.isNotEmpty() && paid != totalCents) {
            add("Payments add up to ${Money.toDecimal(paid)} but the sale is ${Money.toDecimal(totalCents)}")
        }
        val codes = tenders.mapNotNull { it.mpesaCode?.let(MpesaRules::normalise) }
        if (codes.size != codes.toSet().size) add("The same M-Pesa code appears twice on this sale")
    }

    /**
     * eTIMS payment type (VSCU spec 4.10) for the whole receipt. The protocol takes one code per
     * receipt, so mixed payments are mapped: cash + credit is "03"; any other mix is "07" (other).
     * TODO(kra-confirm): ask KRA or an integrator how split cash/M-Pesa receipts should be coded.
     */
    fun etimsPaymentCode(tenders: List<Tender>): String {
        val kinds = tenders.map {
            when (it.method) {
                TenderMethod.CASH -> "cash"
                TenderMethod.MPESA_CODE, TenderMethod.MPESA_STK -> "mobile"
                TenderMethod.CREDIT -> "credit"
            }
        }.toSet()
        return when (kinds) {
            setOf("cash") -> "01"
            setOf("credit") -> "02"
            setOf("cash", "credit") -> "03"
            setOf("mobile") -> "06"
            else -> "07"
        }
    }
}
