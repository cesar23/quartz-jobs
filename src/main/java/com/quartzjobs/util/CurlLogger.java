package com.quartzjobs.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;

/**
 * 🔍 Utilidad para generar comandos cURL equivalentes de peticiones HTTP.
 *
 * <p>Útil para debugging y documentación de APIs. Trabaja con {@link HttpHeaders} de Spring MVC /
 * RestTemplate — NO depende de WebFlux/reactive.
 *
 * @author Sistema de Sincronización
 * @version 2.0
 */
public final class CurlLogger {

    private static final Logger log = LoggerFactory.getLogger(CurlLogger.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CURL_LOCATION = "curl --location '";
    private static final String REQUEST_FLAG = " \\\n--request ";
    private static final String HEADER_FLAG = " \\\n--header '";
    private static final String DATA_FLAG = " \\\n--data '";
    private static final String QUOTE = "'";
    private static final String SEPARATOR = "━".repeat(53);

    private CurlLogger() {}

    /**
     * Genera un comando cURL completo, enmascarando headers sensibles (Authorization, tokens,
     * api-key, etc.). Es el modo recomendado para cualquier log que pueda llegar a producción.
     */
    public static String generateCurl(String method, String url, HttpHeaders headers, Object body) {
        return build(method, url, headers, body, true);
    }

    /**
     * Genera un comando cURL SIN enmascarar headers — tokens y passwords visibles en texto plano.
     * SOLO usar en desarrollo local, nunca en logs que puedan llegar a un entorno compartido
     * (Grafana, archivos, etc.).
     */
    public static String generateCurlUnsafe(
            String method, String url, HttpHeaders headers, Object body) {
        return build(method, url, headers, body, false);
    }

    /**
     * Genera un comando cURL simplificado a partir de un Map plano de headers (siempre
     * enmascarado).
     */
    public static String generateSimpleCurl(
            String method, String url, Map<String, String> headers, Object body) {
        StringBuilder curl = new StringBuilder(CURL_LOCATION).append(url).append(QUOTE);

        if (!"GET".equalsIgnoreCase(method)) {
            curl.append(REQUEST_FLAG).append(method.toUpperCase());
        }
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(
                    (name, value) ->
                            curl.append(HEADER_FLAG)
                                    .append(name)
                                    .append(": ")
                                    .append(
                                            isSensitiveHeader(name)
                                                    ? maskSensitiveValue(value)
                                                    : value)
                                    .append(QUOTE));
        }
        if (body != null) {
            curl.append(DATA_FLAG).append(convertBodyToString(body)).append(QUOTE);
        }
        return curl.toString();
    }

    /**
     * Genera un comando cURL a partir de un {@link HttpRequest} de Spring (el tipo que recibe un
     * {@code ClientHttpRequestInterceptor} de RestTemplate — ver {@code
     * LoggingClientHttpRequestInterceptor}). Este es el reemplazo del antiguo {@code
     * generateCurlFromClientRequest} que usaba {@code ClientRequest} de WebFlux.
     *
     * @param request objeto HttpRequest recibido en el interceptor
     * @param body bytes del body, tal como llegan al interceptor (puede ser null/vacío)
     */
    public static String generateCurlFromHttpRequest(HttpRequest request, byte[] body) {
        String method = request.getMethod() != null ? request.getMethod().name() : "GET";
        String url = request.getURI().toString();
        String bodyText =
                (body != null && body.length > 0) ? new String(body, StandardCharsets.UTF_8) : null;
        return generateCurl(method, url, request.getHeaders(), bodyText);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static String build(
            String method, String url, HttpHeaders headers, Object body, boolean mask) {
        StringBuilder curl = new StringBuilder(CURL_LOCATION).append(url).append(QUOTE);

        if (!"GET".equalsIgnoreCase(method)) {
            curl.append(REQUEST_FLAG).append(method.toUpperCase());
        }

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(
                    (name, values) -> {
                        String value = values.isEmpty() ? "" : values.getFirst();
                        if (mask && isSensitiveHeader(name)) {
                            value = maskSensitiveValue(value);
                        }
                        curl.append(HEADER_FLAG)
                                .append(name)
                                .append(": ")
                                .append(value)
                                .append(QUOTE);
                    });
        }

        if (body != null) {
            curl.append(DATA_FLAG).append(convertBodyToString(body)).append(QUOTE);
        }

        return curl.toString();
    }

    private static String convertBodyToString(Object body) {
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            log.error("Error al convertir body a JSON: {}", e.getMessage());
            return body.toString();
        }
    }

    private static boolean isSensitiveHeader(String headerName) {
        String lower = headerName.toLowerCase();
        return lower.contains("authorization")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("api-key");
    }

    private static String maskSensitiveValue(String value) {
        if (value == null || value.length() < 20) {
            return "***MASKED***";
        }
        return value.substring(0, 10) + "..." + value.substring(value.length() - 10);
    }

    /** Registra el cURL en los logs con formato legible (modo SAFE, headers enmascarados). */
    public static void logCurl(String method, String url, HttpHeaders headers, Object body) {
        logCurlInternal(generateCurl(method, url, headers, body), false);
    }

    /** Registra el cURL en los logs, permitiendo elegir modo unsafe (SOLO desarrollo local). */
    public static void logCurl(
            String method, String url, HttpHeaders headers, Object body, boolean unsafe) {
        String curl =
                unsafe
                        ? generateCurlUnsafe(method, url, headers, body)
                        : generateCurl(method, url, headers, body);
        logCurlInternal(curl, unsafe);
    }

    private static void logCurlInternal(String curl, boolean unsafe) {
        log.info(SEPARATOR);
        log.info("🔧 CURL EQUIVALENT COMMAND {}", unsafe ? "(⚠️ UNSAFE MODE)" : "");
        log.info(SEPARATOR);
        log.info("\n{}\n", curl);
        log.info(SEPARATOR);
    }

    /**
     * Copia el cURL al portapapeles. Solo funciona en un entorno con GUI (desarrollo local) — en un
     * servidor headless, falla silenciosamente y queda logueado el warning.
     */
    public static void copyCurlToClipboard(String curl) {
        try {
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(curl), null);
            log.info("✅ Comando cURL copiado al portapapeles");
        } catch (Exception e) {
            log.warn(
                    "⚠️ No se pudo copiar al portapapeles (esperado en servidor headless): {}",
                    e.getMessage());
        }
    }

    /**
     * 🧪 Ejemplos de uso — placeholders, NO son credenciales reales. Ejecutar con: {@code mvn
     * exec:java -Dexec.mainClass="com.quartzjobs.util.CurlLogger"}
     */
    public static void main(String[] args) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("REEMPLAZA_CON_TOKEN_DE_PRUEBA");
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body =
                """
            {
                "user": "usuario_de_ejemplo",
                "password": "***"
            }
            """;

        System.out.println("SAFE (headers enmascarados):");
        System.out.println(generateCurl("POST", "https://api.ejemplo.com/login", headers, body));

        System.out.println("\nUNSAFE (solo desarrollo local, NUNCA en logs compartidos):");
        System.out.println(
                generateCurlUnsafe("POST", "https://api.ejemplo.com/login", headers, body));
    }
}
