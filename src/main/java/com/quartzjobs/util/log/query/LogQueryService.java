package com.quartzjobs.util.log.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);
    private static final int HARD_MAX_LIMIT = 500; // nunca se puede pedir más que esto por request

    private final ObjectMapper objectMapper = new ObjectMapper();

    // IMPORTANTE: la ruta viene de configuración, NUNCA del request del
    // cliente — así se evita cualquier posibilidad de path traversal
    // (ej: alguien mandando ?file=../../.env.dev).
    @Value("${app.internal-api.log-file:logs/quartz-jobs.log}")
    private String logFilePath;

    /**
     * Lee el archivo de log activo y devuelve, como máximo, las últimas {@code limit} entradas que
     * matcheen el {@link LogFilter} dado. Lee línea por línea (sin cargar el archivo entero en
     * memoria) y mantiene solo una ventana acotada de resultados.
     */
    public List<LogEntry> query(LogFilter filter, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), HARD_MAX_LIMIT);
        Path path = Path.of(logFilePath);

        if (!Files.exists(path)) {
            log.warn("Archivo de log no encontrado: {}", path);
            return List.of();
        }

        // Deque acotado: al llenarse, descarta el más antiguo -> nos
        // quedamos siempre con los últimos N que matchean, sin acumular
        // resultados sin límite en memoria.
        Deque<LogEntry> window = new ArrayDeque<>(effectiveLimit);

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                LogEntry entry = parseLine(line);
                if (entry == null) {
                    continue; // línea corrupta/no-JSON, se ignora sin romper la consulta
                }
                if (!filter.matches(entry)) {
                    continue;
                }
                if (window.size() == effectiveLimit) {
                    window.removeFirst();
                }
                window.addLast(entry);
            }
        } catch (IOException e) {
            log.error("Error leyendo el archivo de log", e);
            return List.of();
        }

        return window.stream().toList();
    }

    private LogEntry parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(line, LogEntry.class);
        } catch (Exception e) {
            return null; // línea no-JSON (ej: un stack trace multi-línea) -> se ignora
        }
    }
}
