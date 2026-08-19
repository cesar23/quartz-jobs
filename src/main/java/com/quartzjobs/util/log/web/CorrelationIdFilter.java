package com.quartzjobs.util.log.web;

import com.quartzjobs.util.log.core.LogContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADAPTER de {@link LogContext} para requests HTTP ENTRANTES (Spring MVC).
 *
 * <p>Úsalo SOLO si este proyecto tiene controllers REST. Si copias el paquete {@code util.log.core}
 * a un proyecto sin Spring Web, este archivo no aplica — no lo copies.
 *
 * <p>Traza tanto el REQUEST (método, path, query string) como el RESPONSE (status, duración,
 * content-type) de cada llamada entrante, con un traceId que amarra ambos logs — reusando el header
 * "X-Request-Id" si el cliente ya lo mandó (útil si el cliente es otro servicio tuyo y quieres
 * encadenar trazas entre servicios).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = request.getHeader(HEADER_NAME);
        if (traceId == null || traceId.isBlank()) {
            traceId = LogContext.newTraceId();
        }
        response.setHeader(HEADER_NAME, traceId);

        long start = System.currentTimeMillis();
        try {
            LogContext.put(LogContext.TRACE_ID, traceId);
            LogContext.put(LogContext.SOURCE, "http");
            LogContext.put("httpMethod", request.getMethod());
            LogContext.put("httpPath", request.getRequestURI());

            String query = request.getQueryString();
            log.info("→ Request iniciado{}", (query != null ? " query=" + query : ""));

            filterChain.doFilter(request, response);

            // Log de RESPONSE — separado del catch de abajo porque acá no
            // hubo excepción no controlada (puede igual ser un 4xx/5xx
            // manejado por un @ExceptionHandler, por eso se evalúa el status).
            logResponse(response, start, null);

        } catch (Exception ex) {
            logResponse(response, start, ex);
            throw ex;
        } finally {
            LogContext.clear();
        }
    }

    private void logResponse(HttpServletResponse response, long startMillis, Exception ex) {
        long durationMs = System.currentTimeMillis() - startMillis;
        int status = response.getStatus();

        if (ex != null) {
            log.error(
                    "← Request finalizado con excepción status={} durationMs={}",
                    status,
                    durationMs,
                    ex);
        } else if (status >= 500) {
            log.error(
                    "← Request finalizado status={} durationMs={} contentType={}",
                    status,
                    durationMs,
                    response.getContentType());
        } else if (status >= 400) {
            log.warn(
                    "← Request finalizado status={} durationMs={} contentType={}",
                    status,
                    durationMs,
                    response.getContentType());
        } else {
            log.info(
                    "← Request finalizado status={} durationMs={} contentType={}",
                    status,
                    durationMs,
                    response.getContentType());
        }
    }
}
