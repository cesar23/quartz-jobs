package com.quartzjobs.util.log.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representa una línea del log JSON generado por LogstashEncoder.
 * Solo mapea los campos que nos interesa exponer/filtrar — el resto de
 * campos de la línea original se ignoran (defensa en profundidad: aunque
 * el JSON tuviera algo inesperado, nunca se re-expone tal cual).
 *
 * Es normal y esperado que traceId/jobKey/source/etc. vengan null en
 * logs que no ocurren dentro de un request HTTP ni una ejecución de Job
 * (arranque de Spring, inicialización de Tomcat/Quartz, etc.) — esos
 * campos son opcionales por diseño, igual que en cualquier esquema de
 * logging estructurado (trace_id, span_id, request_id se documentan como
 * opcionales, presentes solo cuando hay un contexto de request activo).
 * La API los devuelve explícitos como "campo": null (en vez de omitirlos)
 * para que el consumidor siempre vea el mismo shape de objeto.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogEntry {

    // LogstashEncoder escribe el timestamp como "@timestamp" (con arroba),
    // no como "timestamp" — sin el @JsonProperty en el setter, Jackson
    // nunca lo matchea al deserializar el archivo y queda siempre null.
    // Se anota el setter (no el campo) para que la SALIDA de esta API siga
    // devolviendo "timestamp" (sin arroba), más limpio para el consumidor.
    private String timestamp;
    private String level;
    private String logger_name;
    private String message;
    private String traceId;
    private String jobKey;
    private String jobGroup;
    private String source;
    private String httpMethod;
    private String httpPath;

    // getters/setters (Jackson los necesita para deserializar)

    public String getTimestamp() { return timestamp; }
    @JsonProperty("@timestamp")
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getLogger() { return logger_name; }
    public void setLogger_name(String loggerName) { this.logger_name = loggerName; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getJobKey() { return jobKey; }
    public void setJobKey(String jobKey) { this.jobKey = jobKey; }

    public String getJobGroup() { return jobGroup; }
    public void setJobGroup(String jobGroup) { this.jobGroup = jobGroup; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getHttpPath() { return httpPath; }
    public void setHttpPath(String httpPath) { this.httpPath = httpPath; }
}
