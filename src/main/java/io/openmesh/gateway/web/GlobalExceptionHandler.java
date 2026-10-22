package io.openmesh.gateway.web;

import io.openmesh.gateway.model.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;
import java.util.concurrent.TimeoutException;

/** Maps controller exceptions to the shared {@link ApiError} JSON format. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, ServerWebExchange exchange) {
        return build(ex.getStatus(), ex.getMessage(), ex.getDetails(), exchange);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ApiError> handleValidation(WebExchangeBindException ex, ServerWebExchange exchange) {
        List<String> details = ex.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", details, exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiError> handleInput(ServerWebInputException ex, ServerWebExchange exchange) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body or parameters", null, exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        return build(ex.getStatusCode(), ex.getReason(), null, exchange);
    }

    /** Redis down or timing out: the admin API reports 503 instead of a generic 500. */
    @ExceptionHandler({DataAccessException.class, TimeoutException.class})
    public ResponseEntity<ApiError> handleStoreUnavailable(Exception ex, ServerWebExchange exchange) {
        log.warn("Backing store unavailable on {}: {}", exchange.getRequest().getPath(), ex.toString());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Backing store (Redis) is unavailable, try again shortly", null, exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled error on {} {} (trace {})", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), TraceContext.traceId(exchange), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", null, exchange);
    }

    private ResponseEntity<ApiError> build(HttpStatusCode code, String message, List<String> details,
                                           ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(code.value());
        ApiError body = ApiError.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .details(details)
                .path(exchange.getRequest().getPath().value())
                .traceId(TraceContext.traceId(exchange))
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
