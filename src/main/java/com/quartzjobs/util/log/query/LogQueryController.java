package com.quartzjobs.util.log.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint de consulta de logs con filtros, para que OTRAS aplicaciones
 * puedan consultar la trazabilidad de esta sin acceso directo al servidor.
 *
 * Protegido por {@link com.quartzjobs.util.log.web.InternalApiTokenFilter}
 * (requiere header "X-Internal-Token") — la ruta empieza con "/internal/"
 * a propósito, para que el filtro la reconozca automáticamente.
 *
 * MÉTODO: GET (es una consulta de solo lectura — no uses POST).
 *
 * Cada parámetro acepta varios valores separados por coma (OR dentro del
 * campo). Distintos campos se combinan con AND. Ejemplos:
 * <pre>
 * GET /internal/logs?jobKey=woocommerce.dataSyncJob&level=ERROR&limit=50
 * GET /internal/logs?jobGroup=SYNC&level=ERROR,WARN&limit=20
 * GET /internal/logs?traceId=a1b2c3d4-...
 * GET /internal/logs?source=job&message=timeout
 * GET /internal/logs?from=2026-08-10T00:00:00-05:00&to=2026-08-10T23:59:59-05:00
 * </pre>
 * "from"/"to" deben ir en formato ISO-8601 con offset, igual al que trae
 * el campo "timestamp" de la respuesta (ej: 2026-08-10T16:11:02.949-05:00).
 */
@RestController
public class LogQueryController {

    private final LogQueryService logQueryService;

    public LogQueryController(LogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    @GetMapping("/internal/logs")
    public List<LogEntry> query(
        @RequestParam(required = false) String traceId,
        @RequestParam(required = false) String jobKey,
        @RequestParam(required = false) String jobGroup,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String message,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "100") int limit) {

        LogFilterRequest req = new LogFilterRequest(traceId, jobKey, jobGroup, level, source, message, from, to);
        LogFilter filter = LogFilter.of(req);
        return logQueryService.query(filter, limit);
    }
}
