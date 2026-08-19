package com.quartzjobs.jobs.woocommerce;

// Ubicación: src/main/java/com/quartzjobs/jobs/woocommerce/WooCommerceDataSyncJob.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Segundo job del flujo (cada 15 min, por ejemplo): reutiliza el token que dejó {@link
 * WooCommerceAuthJob} en {@link WooCommerceTokenCache} — NO hace login por su cuenta.
 *
 * <p>1) GET {syncMongoUrl} -> sincroniza productos (Mongo) 2) GET {sendWebUrl} -> envía pendientes
 * a la web 3) GET {sendWebExchangeRateUrl} -> actualiza tipo de cambio 4) GET {sendCleanCacheUrl}
 * -> limpia caché de la web
 *
 * <p>JobDataMap esperado: - syncMongoUrl (obligatorio) - sendWebUrl (obligatorio) -
 * sendWebExchangeRateUrl (obligatorio) - sendCleanCacheUrl (obligatorio) - adminEmail (obligatorio,
 * para alertas) - tokenKey (opcional, default "woocommerce-default" — DEBE coincidir con el
 * tokenKey del WooCommerceAuthJob) - tokenTtlMinutes (opcional, default "150" — cuánto se considera
 * válido el token cacheado antes de rechazarlo) - maxErrors (opcional, default "5") - logFilePath
 * (opcional, default "logs/woocommerce-datasync.txt")
 *
 * <p>Si no hay token cacheado (o ya venció el tokenTtlMinutes), el job falla de inmediato con un
 * mensaje claro — no intenta loguearse por su cuenta.
 */
@Component
@DisallowConcurrentExecution
public class WooCommerceDataSyncJob extends AbstractWooCommerceJob {

    private static final Logger log = LoggerFactory.getLogger(WooCommerceDataSyncJob.class);
    private static final String DEFAULT_LOG_PATH = "logs/woocommerce-datasync.txt";
    private static final String DEFAULT_TOKEN_KEY = "woocommerce-default";
    private static final int DEFAULT_MAX_ERRORS = 5;
    // El access_token real dura 24h (1440 min). Dejamos 1380 min (23h) como
    // límite de caché: dan 60 min de margen de seguridad antes de que el
    // token real expire, sin necesidad de tocar nada si el reloj del
    // WooCommerceAuthJob se atrasa un poco en algún ciclo.
    private static final long DEFAULT_TOKEN_TTL_MINUTES = 1380;

    @Autowired private WooCommerceTokenCache tokenCache;

    public WooCommerceDataSyncJob(RestTemplate restTemplate) {
        super(restTemplate);
    }

    @Override
    protected Logger log() {
        return log;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        JobDataMap data = context.getMergedJobDataMap();
        String jobKey = context.getJobDetail().getKey().toString();

        String syncMongoUrl = data.getString("syncMongoUrl");
        String sendWebUrl = data.getString("sendWebUrl");
        String sendWebExchangeRateUrl = data.getString("sendWebExchangeRateUrl");
        String sendCleanCacheUrl = data.getString("sendCleanCacheUrl");
        String adminEmail = data.getString("adminEmail");
        String tokenKey = orDefault(data.getString("tokenKey"), DEFAULT_TOKEN_KEY);
        String logFilePath = orDefault(data.getString("logFilePath"), DEFAULT_LOG_PATH);
        int maxErrors = parseIntOrDefault(data.getString("maxErrors"), DEFAULT_MAX_ERRORS);
        long tokenTtlMinutes =
                parseLongOrDefault(data.getString("tokenTtlMinutes"), DEFAULT_TOKEN_TTL_MINUTES);

        if (isBlank(syncMongoUrl)
                || isBlank(sendWebUrl)
                || isBlank(sendWebExchangeRateUrl)
                || isBlank(sendCleanCacheUrl)
                || isBlank(adminEmail)) {
            String msg =
                    "Faltan parámetros obligatorios "
                            + "(syncMongoUrl/sendWebUrl/sendWebExchangeRateUrl/sendCleanCacheUrl/adminEmail)";
            log.error("✘ [{}] {}", jobKey, msg);
            writeLog(logFilePath, "ERROR [" + jobKey + "] " + msg);
            throw new JobExecutionException(msg);
        }

        Optional<String> cachedToken =
                tokenCache.get(tokenKey, Duration.ofMinutes(tokenTtlMinutes));

        if (cachedToken.isEmpty()) {
            String msg =
                    "No hay token válido en caché para tokenKey='"
                            + tokenKey
                            + "'. ¿Ya corrió WooCommerceAuthJob con el mismo tokenKey? "
                            + "¿Superó el token los "
                            + tokenTtlMinutes
                            + " minutos de vigencia?";
            log.error("✘ [{}] {}", jobKey, msg);
            writeLog(logFilePath, "ERROR [" + jobKey + "] " + msg);
            handleFailure(
                    jobKey, new IllegalStateException(msg), logFilePath, adminEmail, maxErrors);
            throw new JobExecutionException(msg);
        }
        String token = cachedToken.get();

        log.info(
                "▶ [{}] WooCommerceDataSyncJob iniciado (token cacheado, tokenKey='{}')",
                jobKey,
                tokenKey);
        writeLog(logFilePath, "INFO [" + jobKey + "] Job iniciado con token cacheado");

        try {
            // 1) sync/syncMongo -> sincroniza productos (Mongo)
            JsonNode mongoSummary = syncMongo(syncMongoUrl, token);
            writeLog(logFilePath, "INFO [" + jobKey + "] sync/syncMongo -> " + mongoSummary);
            int totalSendPendingWeb = mongoSummary.path("totalPendingSendWeb").asInt();

            if (totalSendPendingWeb > 0) {
                // 2) sync/sendWeb -> envía pendientes a la web
                String sendWebSummary = sendWeb(sendWebUrl, token);
                writeLog(logFilePath, "INFO [" + jobKey + "] sync/sendWeb -> " + sendWebSummary);

                // 3) sync/sendWebExchangeRate -> actualiza tipo de cambio
                String exchangeSummary = sendWebExchangeRate(sendWebExchangeRateUrl, token);
                writeLog(
                        logFilePath,
                        "INFO [" + jobKey + "] sync/sendWebExchangeRate -> " + exchangeSummary);

                // 4) sync/sendCleanCache -> limpia caché de la web
                String cleanCacheSummary = sendCleanCache(sendCleanCacheUrl, token);
                writeLog(
                        logFilePath,
                        "INFO [" + jobKey + "] sync/sendCleanCache -> " + cleanCacheSummary);
            }

            log.info(
                    "✔ [{}] WooCommerceDataSyncJob completado - Pendiente Envio [{}]",
                    jobKey,
                    totalSendPendingWeb);
            errorTracker.reset(jobKey);

        } catch (Exception e) {
            handleFailure(jobKey, e, logFilePath, adminEmail, maxErrors);
            throw new JobExecutionException(e);
        }
    }

    // ── syncMongo ────────────────────────────────────────────────────────

    private JsonNode syncMongo(String syncMongoUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(syncMongoUrl, HttpMethod.GET, request, String.class);

        JsonNode total = parseJson(response.getBody()).path("data").path("total");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode nodo = mapper.createObjectNode();
        nodo.put("totalCreados", total.path("totalCreados").asText("?"));
        nodo.put("totalActualizados", total.path("totalActualizados").asText("?"));
        nodo.put("totalProductApi", total.path("totalProductApi").asText("?"));
        nodo.put("totalPendingSendWeb", total.path("totalPendingSendWeb").asInt(0));
        nodo.put("total-sin-SKU", total.path("total-sin-SKU").asText("?"));
        return nodo;
    }

    // ── sendWeb ──────────────────────────────────────────────────────────

    private String sendWeb(String sendWebUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(sendWebUrl, HttpMethod.GET, request, String.class);

        JsonNode d = parseJson(response.getBody()).path("data");

        return String.format(
                "procesados=%s, exitosos=%s, fallidos=%s, errores=%s",
                d.path("total_processed").asText("?"),
                d.path("total_success").asText("?"),
                d.path("total_failures").asText("?"),
                d.path("errors").asText("?"));
    }

    // ── sendWebExchangeRate ──────────────────────────────────────────────

    private String sendWebExchangeRate(String sendWebExchangeRateUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(
                        sendWebExchangeRateUrl, HttpMethod.GET, request, String.class);

        JsonNode d = parseJson(response.getBody()).path("data");
        return "total=" + d.path("total").asText("?");
    }

    // ── sendCleanCache ───────────────────────────────────────────────────

    private String sendCleanCache(String sendCleanCacheUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(sendCleanCacheUrl, HttpMethod.GET, request, String.class);

        JsonNode root = parseJson(response.getBody());
        return root.path("message").asText("(sin mensaje)")
                + ", total="
                + root.path("data").path("total").asText("?");
    }
}
