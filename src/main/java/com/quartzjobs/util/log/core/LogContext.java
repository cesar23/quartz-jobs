package com.quartzjobs.util.log.core;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Núcleo de logging estructurado — PORTABLE, sin dependencias de Spring ni de Quartz (solo
 * slf4j-api, que ya tiene prácticamente cualquier proyecto Java). Si necesitas esto en otro
 * proyecto, copia SOLO esta clase.
 *
 * <p>Qué hace: pone/quita valores en el MDC (Mapped Diagnostic Context) de SLF4J. El MDC es un mapa
 * "thread-local" — cualquier valor que pongas aquí aparece automáticamente en los logs de ese hilo,
 * sin tener que pasar parámetros manualmente por cada método/servicio.
 *
 * <p>Esta clase NO decide cuándo se llena el MDC — eso lo hace cada "adapter" (ver paquetes {@code
 * util.log.web}, {@code util.log.quartz}, {@code util.log.httpclient}) según el tipo de unidad de
 * trabajo (request HTTP entrante, ejecución de job, llamada HTTP saliente, etc.).
 *
 * <p>El resto del código (controllers, services, use cases, jobs) solo usa su Logger normal:
 *
 * <pre>
 *   private static final Logger log = LoggerFactory.getLogger(MiClase.class);
 *   log.info("Producto sincronizado");
 * </pre>
 *
 * y el traceId/jobKey/lo-que-sea aparece solo en el log.
 *
 * <h2>Ejemplo básico</h2>
 *
 * <pre>
 * LogContext.put(LogContext.TRACE_ID, LogContext.newTraceId());
 * try {
 *     log.info("Procesando");
 * } finally {
 *     LogContext.clear();
 * }
 * </pre>
 *
 * <h2>Ejemplo anidado (agregar un campo temporal DENTRO de un job/request ya en curso, sin perder
 * el contexto que puso el adapter)</h2>
 *
 * Útil en un use case o service que procesa varios ítems dentro de un job:
 *
 * <pre>
 * for (Sku sku : skus) {
 *     try (AutoCloseable ignored = LogContext.scope("sku", sku.getCode())) {
 *         log.info("Procesando SKU"); // sale con traceId+jobKey+sku, los 3 juntos
 *     }
 *     // acá "sku" ya no está, pero traceId/jobKey del job siguen intactos
 * }
 * </pre>
 */
public final class LogContext {

    private LogContext() {}

    /**
     * Identificador único que amarra todos los logs de un mismo flujo (request, job, llamada
     * HTTP...).
     */
    public static final String TRACE_ID = "traceId";

    /**
     * Tipo de origen del flujo (ej: "http", "job", "http-client", "kafka"). Útil para filtrar en
     * dashboards.
     */
    public static final String SOURCE = "source";

    /** Genera un id único y corto, para usar como traceId cuando el caller no trae uno externo. */
    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    public static void putAll(Map<String, String> values) {
        values.forEach(LogContext::put);
    }

    public static String get(String key) {
        return MDC.get(key);
    }

    /**
     * Agrega un valor temporal al MDC y devuelve un {@link AutoCloseable} que, al cerrarse
     * (try-with-resources), restaura el valor anterior de esa clave (o lo elimina si no existía). A
     * diferencia de {@link #clear()}, esto NO borra el resto del contexto — es seguro usarlo dentro
     * de un job/request ya en curso.
     */
    public static AutoCloseable scope(String key, String value) {
        String previous = MDC.get(key);
        put(key, value);
        return () -> {
            if (previous != null) {
                MDC.put(key, previous);
            } else {
                MDC.remove(key);
            }
        };
    }

    /**
     * Vacía por completo el MDC del hilo actual. Debe usarse SOLO en el punto de entrada de una
     * unidad de trabajo completa (adapter de request HTTP, de job, etc.), SIEMPRE en un finally —
     * los thread pools reutilizan hilos, así que si no limpias, el siguiente request/job que caiga
     * en ese mismo hilo heredaría tu contexto por error.
     *
     * <p>Para agregar/quitar campos puntuales DENTRO de un flujo ya en curso, usa {@link
     * #scope(String, String)} en vez de este método.
     */
    public static void clear() {
        MDC.clear();
    }
}
