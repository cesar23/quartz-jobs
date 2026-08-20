package com.quartzjobs.util.log.query;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agrupa todos los criterios de filtrado de una consulta de logs.
 *
 * <p>Cada campo es opcional (Set vacío = "no filtrar por esto"). Dentro de un mismo campo, varios
 * valores separados por coma se combinan con OR (ej: level=ERROR,WARN -> ERROR u WARN). Entre
 * campos distintos se combina con AND (ej: jobGroup=SYNC & level=ERROR,WARN -> jobGroup=SYNC Y
 * (level=ERROR O level=WARN)).
 */
public final class LogFilter {

    private static final Logger log = LoggerFactory.getLogger(LogFilter.class);

    private final Set<String> traceIds;
    private final Set<String> jobKeys;
    private final Set<String> jobGroups;
    private final Set<String> levels;
    private final Set<String> sources;
    private final String messageContains;
    private final OffsetDateTime from;
    private final OffsetDateTime to;

    // Un solo parámetro (el DTO), en vez de 8 sueltos -- evita el
    // "Constructor has 8 parameters" de SonarQube.
    private LogFilter(LogFilterRequest req) {
        this.traceIds = toUpperSet(req.traceId(), false);
        this.jobKeys = toUpperSet(req.jobKey(), false);
        this.jobGroups = toUpperSet(req.jobGroup(), false);
        this.levels = toUpperSet(req.level(), true);
        this.sources = toUpperSet(req.source(), false);
        this.messageContains =
                (req.message() == null || req.message().isBlank())
                        ? null
                        : req.message().toLowerCase();
        this.from = parseTimestamp(req.from(), "from");
        this.to = parseTimestamp(req.to(), "to");
    }

    public static LogFilter of(LogFilterRequest req) {
        return new LogFilter(req);
    }

    /**
     * Divide "A,B,C" en un Set. Si uppercase=true, normaliza a mayúsculas (útil para "level"). Set
     * vacío = sin valores.
     */
    private static Set<String> toUpperSet(String raw, boolean upperCase) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        String[] parts = raw.split(",");
        Set<String> set = new HashSet<>();
        for (String part : parts) {
            String v = part.trim();
            if (!v.isEmpty()) {
                set.add(upperCase ? v.toUpperCase() : v);
            }
        }
        return set;
    }

    /**
     * Parsea un timestamp ISO-8601 con offset, igual al formato que escribe LogstashEncoder en
     * "@timestamp" (ej: "2026-08-10T16:11:02.949-05:00"). Si el valor no es parseable, se ignora
     * ese filtro (no rompe la consulta) y se deja constancia en el log.
     */
    private static OffsetDateTime parseTimestamp(String raw, String paramName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn(
                    "Parámetro '{}' no es un timestamp ISO-8601 válido: '{}' — se ignora este filtro",
                    paramName,
                    raw);
            return null;
        }
    }

    // matches() delega cada chequeo a un metodo pequeño (excludedBySet /
    // levelExcluded / messageExcluded / hasTimeRange) en vez de anidar
    // condiciones -- asi baja la complejidad cognitiva reportada por
    // SonarQube (era 17, el limite es 15). Cada helper es trivial de leer
    // por separado.
    public boolean matches(LogEntry entry) {
        if (excludedBySet(traceIds, entry.getTraceId())) {
            return false;
        }
        if (excludedBySet(jobKeys, entry.getJobKey())) {
            return false;
        }
        if (excludedBySet(jobGroups, entry.getJobGroup())) {
            return false;
        }
        if (levelExcluded(entry)) {
            return false;
        }
        if (excludedBySet(sources, entry.getSource())) {
            return false;
        }
        if (messageExcluded(entry)) {
            return false;
        }
        return !hasTimeRange() || matchesTimeRange(entry);
    }

    /**
     * true si "allowed" tiene valores y "value" no está entre ellos. Set vacío = no filtra, nunca
     * excluye.
     */
    private static boolean excludedBySet(Set<String> allowed, String value) {
        return !allowed.isEmpty() && !allowed.contains(value);
    }

    private boolean levelExcluded(LogEntry entry) {
        if (levels.isEmpty()) {
            return false;
        }
        String level = entry.getLevel();
        return level == null || !levels.contains(level.toUpperCase());
    }

    private boolean messageExcluded(LogEntry entry) {
        if (messageContains == null) {
            return false;
        }
        String message = entry.getMessage();
        return message == null || !message.toLowerCase().contains(messageContains);
    }

    private boolean hasTimeRange() {
        return from != null || to != null;
    }

    private boolean matchesTimeRange(LogEntry entry) {
        if (entry.getTimestamp() == null) {
            return false; // sin timestamp no se puede evaluar el rango -> se excluye
        }
        OffsetDateTime entryTime;
        try {
            entryTime = OffsetDateTime.parse(entry.getTimestamp());
        } catch (DateTimeParseException e) {
            return false; // línea con timestamp corrupto -> se excluye, no rompe la consulta
        }
        boolean beforeStart = from != null && entryTime.isBefore(from);
        boolean afterEnd = to != null && entryTime.isAfter(to);
        return !beforeStart && !afterEnd;
    }
}
