package com.quartzjobs.util.log.query;

/**
 * Agrupa los criterios crudos (tal como llegan del query string) para armar un LogFilter. Reemplaza
 * el método con 8 parámetros sueltos (LogFilter.of) y el constructor con 8 parámetros de LogFilter
 * -- ambos disparaban "too many parameters" en SonarQube (limite: 7).
 *
 * <p>Cada campo es opcional, tal como en LogFilter.
 */
public record LogFilterRequest(
        String traceId,
        String jobKey,
        String jobGroup,
        String level,
        String source,
        String message,
        String from,
        String to) {}
