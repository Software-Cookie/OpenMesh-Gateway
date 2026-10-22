package io.openmesh.gateway.web;

import org.springframework.web.server.ServerWebExchange;

/** Names shared by the telemetry filter and everything that reports a trace id. */
public final class TraceContext {

    public static final String TRACE_ID_ATTR = "openmesh.traceId";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private TraceContext() {
    }

    public static String traceId(ServerWebExchange exchange) {
        return exchange.getAttribute(TRACE_ID_ATTR);
    }
}
