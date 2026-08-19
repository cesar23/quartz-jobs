package com.quartzjobs.jobs.woocommerce;

// src/main/java/com/quartzjobs/jobs/woocommerce/AbstractWooCommerceJob.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quartzjobs.service.EmailService;
import com.quartzjobs.util.FileManagerUtil;
import org.quartz.Job;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Helpers compartidos entre WooCommerceAuthJob y WooCommerceDataSyncJob, para no duplicar el mismo
 * código dos veces. No es un Job en sí mismo (por eso no lleva @Component
 * ni @DisallowConcurrentExecution) — cada subclase concreta sí los lleva.
 */
public abstract class AbstractWooCommerceJob implements Job {

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final RestTemplate restTemplate;

    @Autowired protected EmailService emailService;

    @Autowired protected SyncErrorTracker errorTracker;

    protected AbstractWooCommerceJob(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    protected abstract Logger log();

    protected HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Respuesta no es JSON válido: " + body, e);
        }
    }

    protected String extractErrorDetail(Exception e) {
        if (e instanceof HttpStatusCodeException httpEx) {
            return "HTTP " + httpEx.getStatusCode() + " - " + httpEx.getResponseBodyAsString();
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    protected void writeLog(String logFilePath, String line) {
        FileManagerUtil.appendLineWithRotation(logFilePath, line);
    }

    /** Cuenta el error, escribe log y avisa por email si se supera maxErrors. */
    protected void handleFailure(
            String jobKey, Exception e, String logFilePath, String adminEmail, int maxErrors) {

        String detail = extractErrorDetail(e);
        log().error("✘ [{}] Error: {}", jobKey, detail, e);
        writeLog(logFilePath, "ERROR [" + jobKey + "] " + detail);

        int errorCount = errorTracker.incrementAndGet(jobKey);
        writeLog(
                logFilePath,
                "INFO [" + jobKey + "] Errores consecutivos: " + errorCount + "/" + maxErrors);

        if (errorCount >= maxErrors) {
            try {
                String subject =
                        "[quartz-jobs] Alerta: "
                                + jobKey
                                + " superó "
                                + maxErrors
                                + " errores consecutivos";
                String bodyMsg =
                        "El job '"
                                + jobKey
                                + "' ha fallado "
                                + errorCount
                                + " veces seguidas.\n\nÚltimo error:\n"
                                + detail
                                + "\n\nRevisa el log en: "
                                + logFilePath;

                emailService.send(adminEmail, subject, bodyMsg);
                writeLog(
                        logFilePath,
                        "INFO [" + jobKey + "] Email de alerta enviado a " + adminEmail);
                errorTracker.reset(jobKey);

            } catch (Exception mailEx) {
                log().error(
                                "✘ [{}] No se pudo enviar el email de alerta: {}",
                                jobKey,
                                mailEx.getMessage(),
                                mailEx);
                writeLog(
                        logFilePath,
                        "ERROR ["
                                + jobKey
                                + "] Falló el envío del email de alerta: "
                                + mailEx.getMessage());
            }
        }
    }

    protected boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    protected String orDefault(String value, String def) {
        return (value != null && !value.isBlank()) ? value : def;
    }

    protected int parseIntOrDefault(String value, int def) {
        try {
            return (value != null && !value.isBlank()) ? Integer.parseInt(value.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected long parseLongOrDefault(String value, long def) {
        try {
            return (value != null && !value.isBlank()) ? Long.parseLong(value.trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    protected String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
