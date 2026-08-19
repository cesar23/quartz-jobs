package com.quartzjobs.jobs.woocommerce;

// Ubicación: src/main/java/com/quartzjobs/jobs/WooCommerceAuthJob.java

import com.fasterxml.jackson.databind.JsonNode;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Primer job del flujo (cada 2h, por ejemplo): obtiene el access_token y lo deja cacheado en {@link
 * WooCommerceTokenCache} para que {@link WooCommerceDataSyncJob} (cada 15 min) lo reutilice sin
 * loguearse de nuevo.
 *
 * <p>1) POST {loginUrl} -> obtiene access_token 2) POST {syncLoginUrl} -> valida el token contra
 * WooCommerce 3) GET {skusUrl} -> obtiene los SKUs (solo como verificación)
 *
 * <p>JobDataMap esperado: - loginUrl (obligatorio) - syncLoginUrl (obligatorio) - skusUrl
 * (obligatorio) - username (obligatorio) - password (obligatorio) - adminEmail (obligatorio, para
 * alertas) - tokenKey (opcional, default "woocommerce-default" — DEBE coincidir con el tokenKey del
 * WooCommerceDataSyncJob asociado) - maxErrors (opcional, default "5") - logFilePath (opcional,
 * default "logs/woocommerce-auth.txt")
 */
@Component
@DisallowConcurrentExecution
public class WooCommerceAuthJob extends AbstractWooCommerceJob {

    private static final Logger log = LoggerFactory.getLogger(WooCommerceAuthJob.class);
    private static final String DEFAULT_LOG_PATH = "logs/woocommerce-auth.txt";
    private static final String DEFAULT_TOKEN_KEY = "woocommerce-default";
    private static final int DEFAULT_MAX_ERRORS = 5;

    @Autowired private WooCommerceTokenCache tokenCache;

    public WooCommerceAuthJob(RestTemplate restTemplate) {
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

        String loginUrl = data.getString("loginUrl");
        String syncLoginUrl = data.getString("syncLoginUrl");
        String skusUrl = data.getString("skusUrl");
        String username = data.getString("username");
        String password = data.getString("password");
        String adminEmail = data.getString("adminEmail");
        String tokenKey = orDefault(data.getString("tokenKey"), DEFAULT_TOKEN_KEY);
        String logFilePath = orDefault(data.getString("logFilePath"), DEFAULT_LOG_PATH);
        int maxErrors = parseIntOrDefault(data.getString("maxErrors"), DEFAULT_MAX_ERRORS);

        if (isBlank(loginUrl)
                || isBlank(syncLoginUrl)
                || isBlank(skusUrl)
                || isBlank(username)
                || isBlank(password)
                || isBlank(adminEmail)) {
            String msg =
                    "Faltan parámetros obligatorios "
                            + "(loginUrl/syncLoginUrl/skusUrl/username/password/adminEmail)";
            log.error("✘ [{}] {}", jobKey, msg);
            writeLog(logFilePath, "ERROR [" + jobKey + "] " + msg);
            throw new JobExecutionException(msg);
        }

        log.info("▶ [{}] WooCommerceAuthJob iniciado", jobKey);
        writeLog(logFilePath, "INFO [" + jobKey + "] Job iniciado");

        try {
            // 1) Login -> obtener access_token
            String token = login(loginUrl, username, password);
            log.info("token:", token);
            writeLog(logFilePath, "INFO [" + jobKey + "] Token obtenido correctamente");

            // 2) sync/login -> validar token contra WooCommerce
            boolean syncOk = syncLogin(syncLoginUrl, token);
            writeLog(logFilePath, "INFO [" + jobKey + "] sync/login -> isValid=" + syncOk);

            // 3) sync/get-skus -> obtener SKUs (verificación de que el token sirve)
            int skusCount = getSkus(skusUrl, token);
            writeLog(
                    logFilePath,
                    "INFO [" + jobKey + "] sync/get-skus -> " + skusCount + " SKUs obtenidos");

            // Deja el token disponible para WooCommerceDataSyncJob
            tokenCache.put(tokenKey, token);
            writeLog(
                    logFilePath,
                    "INFO [" + jobKey + "] Token cacheado con tokenKey='" + tokenKey + "'");

            log.info("✔ [{}] WooCommerceAuthJob completado ({} SKUs)", jobKey, skusCount);
            errorTracker.reset(jobKey);

        } catch (Exception e) {
            handleFailure(jobKey, e, logFilePath, adminEmail, maxErrors);
            throw new JobExecutionException(e);
        }
    }

    // ── Paso 1: login ────────────────────────────────────────────────────

    private String login(String loginUrl, String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body =
                String.format(
                        "{\"email\":\"%s\",\"password\":\"%s\"}",
                        escapeJson(username), escapeJson(password));

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response =
                restTemplate.postForEntity(loginUrl, request, String.class);

        JsonNode root = parseJson(response.getBody());
        JsonNode tokenNode = root.path("data").path("access_token");

        if (tokenNode.isMissingNode() || tokenNode.asText().isBlank()) {
            throw new IllegalStateException(
                    "La respuesta de login no contiene data.access_token: " + response.getBody());
        }
        return tokenNode.asText();
    }

    // ── Paso 2: sync/login ───────────────────────────────────────────────

    private boolean syncLogin(String syncLoginUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(syncLoginUrl, HttpMethod.POST, request, String.class);

        JsonNode root = parseJson(response.getBody());
        return root.path("data").path("isValid").asBoolean(false);
    }

    // ── Paso 3: sync/get-skus ────────────────────────────────────────────

    private int getSkus(String skusUrl, String token) {
        HttpEntity<Void> request = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> response =
                restTemplate.exchange(skusUrl, HttpMethod.GET, request, String.class);

        JsonNode root = parseJson(response.getBody());
        JsonNode skusArray = root.path("data").path("skus_filtrados");

        return skusArray.isArray() ? skusArray.size() : 0;
    }
}
