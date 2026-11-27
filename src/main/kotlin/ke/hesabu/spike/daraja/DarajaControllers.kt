package ke.hesabu.spike.daraja

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Endpoints Safaricom calls. Paths avoid the words Daraja refuses in callback URLs ("mpesa", "safaricom").
 * Every handler answers ResultCode 0 quickly so Safaricom stops retrying; work is idempotent.
 */
@RestController
@RequestMapping("/hooks")
class DarajaCallbackController(
    private val matcher: PaymentMatcher,
    private val stkSales: StkSaleRegistry,
    private val props: DarajaProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/c2b/validate")
    fun validate(@RequestBody n: C2bNotification): DarajaAck {
        log.info("C2B validation {} KES {} to {}", n.transId, n.transAmount, n.businessShortCode)
        return DarajaAck()
    }

    @PostMapping("/c2b/confirm")
    fun confirm(@RequestBody n: C2bNotification): DarajaAck {
        val first = matcher.onConfirmation(n.transId, toShillings(n.transAmount), n.businessShortCode, PaymentMatcher.Source.C2B)
        log.info("C2B confirmation {} KES {} ({})", n.transId, n.transAmount, if (first) "new" else "duplicate ignored")
        return DarajaAck()
    }

    @PostMapping("/stk/result")
    fun stkResult(@RequestBody env: StkCallbackEnvelope): DarajaAck {
        val cb = env.body.stkCallback
        val saleId = stkSales.saleFor(cb.checkoutRequestId)
        val code = cb.receiptNumber
        val amount = cb.amount
        when {
            cb.resultCode != 0 -> log.info("STK {} failed or cancelled: {} {}", cb.checkoutRequestId, cb.resultCode, cb.resultDesc)
            saleId == null -> log.warn("STK {} succeeded but no sale is waiting for it", cb.checkoutRequestId)
            code == null || amount == null -> log.warn("STK {} succeeded without receipt metadata", cb.checkoutRequestId)
            else -> matcher.onStkSuccess(saleId, code, amount, props.stkShortCode)
        }
        return DarajaAck()
    }

    // Daraja sends "TransAmount":"10.00"; the till works in whole shillings.
    private fun toShillings(amount: String): Long = BigDecimal(amount).toLong()
}

/** Maps an STK CheckoutRequestID back to the sale that sent the prompt. */
@org.springframework.stereotype.Component
class StkSaleRegistry {
    private val byCheckout = ConcurrentHashMap<String, String>()
    fun remember(checkoutRequestId: String, saleId: String) { byCheckout[checkoutRequestId] = saleId }
    fun saleFor(checkoutRequestId: String): String? = byCheckout[checkoutRequestId]
}

/** What the till calls in the spike. In the product this is the sync API. */
@RestController
@RequestMapping("/spike")
class TillPaymentController(
    private val matcher: PaymentMatcher,
    private val daraja: DarajaClient,
    private val stkSales: StkSaleRegistry,
    private val props: DarajaProperties,
) {
    data class TypedCode(val code: String, val amount: Long)
    data class StkPrompt(val phone: String, val amount: Long)

    @PostMapping("/sales/{saleId}/mpesa-code")
    fun typedCode(@PathVariable saleId: String, @RequestBody body: TypedCode) =
        matcher.claim(saleId, body.code, body.amount, props.c2bShortCode)

    @PostMapping("/sales/{saleId}/stk")
    fun prompt(@PathVariable saleId: String, @RequestBody body: StkPrompt): StkPushAck {
        val ack = daraja.stkPush(body.phone, body.amount, saleId)
        ack.checkoutRequestId?.let { stkSales.remember(it, saleId) }
        return ack
    }

    @GetMapping("/sales/{saleId}/payment")
    fun payment(@PathVariable saleId: String) = matcher.claimFor(saleId)

    @GetMapping("/shift-report")
    fun shiftReport() = matcher.shiftReport()

    @PostMapping("/c2b/register")
    fun register() = daraja.registerC2bUrls()

    @PostMapping("/c2b/simulate")
    fun simulate(@RequestBody body: TypedCode) = daraja.simulateC2b(body.amount, "254708374149", body.code)
}
