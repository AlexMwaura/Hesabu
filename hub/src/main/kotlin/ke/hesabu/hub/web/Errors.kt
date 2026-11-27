package ke.hesabu.hub.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** An error the till can show to the cashier. [code] is stable for the app; [message] is for people. */
class HubException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val details: Any? = null,
) : RuntimeException(message)

data class ErrorBody(val code: String, val message: String, val details: Any? = null)

@RestControllerAdvice
class ErrorAdvice {
    @ExceptionHandler(HubException::class)
    fun hub(e: HubException) = ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message ?: e.code, e.details))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(e: IllegalArgumentException) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorBody("INVALID", e.message ?: "Invalid request"))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody("BAD_REQUEST", e.mostSpecificCause.message ?: "Unreadable request"))
}

fun notFound(what: String): Nothing = throw HubException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what not found")
