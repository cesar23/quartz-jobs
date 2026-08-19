package com.quartzjobs.util.log.httpclient;

import com.quartzjobs.util.log.core.LogContext;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * ADAPTER de {@link LogContext} para llamadas HTTP SALIENTES hechas con {@link
 * org.springframework.web.client.RestTemplate}.
 *
 * <p>Úsalo SOLO si el proyecto hace llamadas salientes vía RestTemplate (como {@code
 * AbstractWooCommerceJob}, que llama a login/sync/skus de la API externa). Complementa a {@code
 * CorrelationIdFilter} (que traza el request ENTRANTE): con este interceptor, cada llamada SALIENTE
 * queda también en el log — con el MISMO traceId del job/request que la originó (si existe uno
 * activo en el MDC), y además lo propaga como header "X-Request-Id" al servicio externo, para poder
 * correlacionar logs entre ambos sistemas si el otro lado también lo respeta.
 *
 * <p>No requiere registro manual como {@code QuartzLoggingConfig}: al ser {@code @Component}, basta
 * con inyectarlo en el {@code @Bean RestTemplate} (ver {@code RestTemplateConfig} — ejemplo en el
 * README).
 */
@Component
public class LoggingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(LoggingClientHttpRequestInterceptor.class);
    private static final String HEADER_NAME = "X-Request-Id";

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        // Reusa el traceId del job/request en curso si existe; si no, genera uno
        // (ej: si algún día se llama fuera de un job/request, como en un @PostConstruct).
        String traceId = LogContext.get(LogContext.TRACE_ID);
        if (traceId == null) {
            traceId = LogContext.newTraceId();
        }
        request.getHeaders().add(HEADER_NAME, traceId);

        String method = request.getMethod() != null ? request.getMethod().name() : "?";
        String uri = request.getURI().toString();

        long start = System.currentTimeMillis();
        log.info("→ HTTP saliente {} {}", method, uri);

        try {
            ClientHttpResponse response = execution.execute(request, body);
            long durationMs = System.currentTimeMillis() - start;

            int status = response.getStatusCode().value();
            if (status >= 500) {
                log.error(
                        "← HTTP saliente {} {} status={} durationMs={}",
                        method,
                        uri,
                        status,
                        durationMs);
            } else if (status >= 400) {
                log.warn(
                        "← HTTP saliente {} {} status={} durationMs={}",
                        method,
                        uri,
                        status,
                        durationMs);
            } else {
                log.info(
                        "← HTTP saliente {} {} status={} durationMs={}",
                        method,
                        uri,
                        status,
                        durationMs);
            }
            return response;

        } catch (IOException ex) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("← HTTP saliente {} {} FALLÓ durationMs={}", method, uri, durationMs, ex);
            throw ex;
        }
    }
}
