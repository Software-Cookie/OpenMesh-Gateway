package io.openmesh.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openmesh.gateway.model.ApiError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/** Writes an {@link ApiError} JSON body from filters, where controller advice does not apply. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String reason, String message) {
        return write(exchange, status, reason, message, UnaryOperator.identity());
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String reason, String message,
                            UnaryOperator<ApiError.ApiErrorBuilder> customizer) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ApiError body = customizer.apply(ApiError.builder()
                        .status(status.value())
                        .error(status.getReasonPhrase())
                        .reason(reason)
                        .message(message)
                        .path(exchange.getRequest().getPath().value())
                        .traceId(TraceContext.traceId(exchange)))
                .build();
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize error response", e);
            byte[] fallback = ("{\"status\":" + status.value() + "}").getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(fallback)));
        }
    }
}
