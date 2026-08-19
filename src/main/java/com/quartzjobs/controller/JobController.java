package com.quartzjobs.controller;

import com.quartzjobs.dto.ApiResponse;
import com.quartzjobs.dto.ScheduleRequest;
import com.quartzjobs.service.SchedulerService;
import java.util.List;
import java.util.Map;
import org.quartz.Job;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JobController — Controlador REST para gestionar Jobs de Quartz.
 *
 * <p>¿Qué es un Job en Quartz? Un "Job" es una tarea programada. Por ejemplo: enviar un email todos
 * los días a las 8 AM, generar un reporte cada hora, limpiar registros viejos cada domingo.
 *
 * <p>¿Qué hace este controlador? Expone una API REST para que puedas: POST /api/jobs -&gt;
 * programar un nuevo job POST /api/jobs/{group}/{name}/pause -&gt; pausar un job POST
 * /api/jobs/{group}/{name}/resume -&gt; reanudar un job pausado POST
 * /api/jobs/{group}/{name}/trigger -&gt; ejecutar un job ahora mismo DELETE
 * /api/jobs/{group}/{name} -&gt; eliminar un job
 *
 * <p>Conceptos clave de Quartz: Job: la tarea en sí (la clase que tiene el código a ejecutar).
 * Trigger: el "cuándo" se ejecuta (Cron o intervalo fijo). JobKey: identificador único compuesto
 * por nombre + grupo. Ejemplo: nombre="EmailDiario", grupo="EMAIL".
 *
 * <p>Tipos de Trigger: CronTrigger: usa expresión cron, ej. "0 0 8 * * ?" (8 AM cada día).
 * SimpleTrigger: se repite cada X ms, ej. cada 30 minutos = 1_800_000 ms.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    // Logger: registra mensajes en la consola para depuración
    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    /**
     * SchedulerService es quien realmente habla con Quartz. El controlador solo recibe la petición
     * HTTP y delega la lógica al servicio — esto es buena práctica: "controlador delgado, servicio
     * con la lógica".
     */
    private final SchedulerService schedulerService;

    // Inyección por constructor (recomendada sobre @Autowired en campos)
    public JobController(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    // ══════════════════════════════════════════════════════════════
    //  POST /api/jobs  →  Programar un nuevo job
    // ══════════════════════════════════════════════════════════════

    /**
     * Crea y programa un Job en Quartz.
     *
     * <p>IMPORTANTE - Dos formas de programar un job:
     *
     * <p>1) Con expresión CRON (para horarios fijos): úsalo cuando necesitas que algo ocurra en un
     * horario específico. Ejemplo: jobName="EmailDiario", jobGroup="EMAIL",
     * jobClass="com.quartzjobs.jobs.EmailJob", cronExpression="0 0 8 * * ?", data={to:
     * "usuario@correo.com", subject: "Reporte diario", body: "..."}.
     *
     * <p>2) Con intervalo fijo en milisegundos (para repetición regular): úsalo cuando necesitas
     * que algo ocurra cada N tiempo. Ejemplo: jobName="ReporteCadaHora", jobGroup="REPORTS",
     * jobClass="com.quartzjobs.jobs.ReportJob", intervalMs=3600000.
     *
     * <p>Referencia de expresiones Cron más comunes: "0 * * * * ?" cada minuto. "0 0 * * * ?" cada
     * hora en punto. "0 0 8 * * ?" todos los días a las 8 AM. "0 0 8 * * MON-FRI" lunes a viernes a
     * las 8 AM. "0 0 0 1 * ?" primer día de cada mes a medianoche. "0 0/30 9-17 * * ?" cada 30 min
     * entre 9 AM y 5 PM.
     *
     * <p>Formato Cron en Quartz, 6 campos (distinto al cron de Linux): segundos minutos horas
     * diaMes mes diaSemana, ej: "0 0 8 * * ?". El "?" en diaSemana o diaMes significa "no importa /
     * cualquiera".
     */
    @PostMapping
    public ResponseEntity<ApiResponse> schedule(@RequestBody ScheduleRequest req) {
        try {
            /*
             * Class.forName() carga dinámicamente la clase del Job en tiempo de ejecución.
             * Ventaja: no necesitas modificar el controlador cada vez que creas un Job nuevo.
             * Solo pasas el nombre completo de la clase en el JSON del request.
             *
             * Ejemplo: "jobClass": "com.quartzjobs.jobs.EmailJob"
             *
             * Si la clase no existe → lanza ClassNotFoundException (capturado abajo)
             */
            @SuppressWarnings("unchecked")
            Class<? extends Job> jobClass = (Class<? extends Job>) Class.forName(req.getJobClass());

            /*
             * Decidimos qué tipo de Trigger crear:
             *
             *  ¿Viene cronExpression? → CronTrigger  (horario fijo tipo "8 AM cada día")
             *  ¿Viene intervalMs?     → SimpleTrigger (repetir cada N milisegundos)
             *  ¿No viene ninguno?     → SimpleTrigger con 60 segundos por defecto
             */
            if (req.getCronExpression() != null && !req.getCronExpression().isBlank()) {
                schedulerService.scheduleCronJob(
                        jobClass,
                        req.getJobName(),
                        req.getJobGroup(),
                        req.getCronExpression(),
                        req.getData());
            } else {
                long interval = req.getIntervalMs() != null ? req.getIntervalMs() : 60_000L;
                schedulerService.scheduleSimpleJob(
                        jobClass, req.getJobName(), req.getJobGroup(), interval, req.getData());
            }

            // HTTP 200 — job creado correctamente
            return ResponseEntity.ok(ApiResponse.scheduled(req.getJobName()));

        } catch (ClassNotFoundException e) {
            /*
             * La clase del Job no existe en el classpath.
             *
             * Causas comunes:
             *   - Nombre de paquete o clase escrito mal en el JSON
             *   - Olvidaste poner @Component en el Job
             *   - La clase está en otro módulo no incluido
             */
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Clase no encontrada: " + req.getJobClass()));

        } catch (SchedulerException e) {
            /*
             * Error interno de Quartz al intentar registrar el job.
             *
             * Causas comunes:
             *   - Ya existe un job con ese nombre + grupo (JobKey duplicado)
             *   - La expresión cron tiene formato incorrecto
             *   - Problemas de conexión con la base de datos MySQL
             */
            log.error("Error al programar job", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  POST /api/jobs/{group}/{name}/pause  →  Pausar un job
    // ══════════════════════════════════════════════════════════════

    /**
     * Pausa un job. Deja de ejecutarse pero NO se elimina de Quartz. Su configuración y schedule
     * quedan guardados en la base de datos.
     *
     * <p>Ejemplo de uso: POST /api/jobs/EMAIL/EmailDiario/pause
     *
     * <p>¿Cuándo usar pause en lugar de delete? Mantenimiento temporal del sistema, quieres
     * deshabilitar el job sin perder su configuración, planeas reanudarlo después con /resume.
     *
     * @param group grupo del job, ej: "EMAIL"
     * @param name nombre del job, ej: "EmailDiario"
     */
    @PostMapping("/{group}/{name}/pause")
    public ResponseEntity<ApiResponse> pause(
            @PathVariable String group, @PathVariable String name) {
        try {
            schedulerService.pauseJob(name, group);
            return ResponseEntity.ok(ApiResponse.status("PAUSADO", name));
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  POST /api/jobs/{group}/{name}/resume  →  Reanudar un job
    // ══════════════════════════════════════════════════════════════

    /**
     * Reanuda un job que estaba pausado. Quartz retoma el schedule original desde ese momento.
     *
     * <p>Ejemplo de uso: POST /api/jobs/EMAIL/EmailDiario/resume
     *
     * <p>¿Qué pasa con los disparos perdidos mientras estuvo pausado? Depende de la política de
     * "misfire" configurada en el trigger: DoNothing ignora los disparos perdidos y espera el
     * siguiente; FireNow ejecuta inmediatamente al reanudar. En nuestro SchedulerService usamos
     * DoNothing (lo más seguro).
     */
    @PostMapping("/{group}/{name}/resume")
    public ResponseEntity<ApiResponse> resume(
            @PathVariable String group, @PathVariable String name) {
        try {
            schedulerService.resumeJob(name, group);
            return ResponseEntity.ok(ApiResponse.status("REANUDADO", name));
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  POST /api/jobs/{group}/{name}/trigger  →  Ejecutar ahora
    // ══════════════════════════════════════════════════════════════

    /**
     * Dispara el job INMEDIATAMENTE sin esperar al próximo horario. El schedule original NO se
     * altera — si estaba programado para las 8 AM, seguirá ejecutándose a las 8 AM con normalidad.
     *
     * <p>Ejemplo de uso: POST /api/jobs/REPORTS/ReporteCadaHora/trigger
     *
     * <p>¿Cuándo usar triggerNow? Para probar que el job funciona correctamente sin esperar, ante
     * una necesidad urgente fuera del horario programado, o para recuperación manual tras un error
     * previo.
     */
    @PostMapping("/{group}/{name}/trigger")
    public ResponseEntity<ApiResponse> triggerNow(
            @PathVariable String group, @PathVariable String name) {
        try {
            schedulerService.triggerNow(name, group);
            return ResponseEntity.ok(ApiResponse.status("DISPARADO", name));
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DELETE /api/jobs/{group}/{name}  →  Eliminar un job
    // ══════════════════════════════════════════════════════════════

    /**
     * Elimina un job y su trigger de Quartz de forma permanente. Quartz borra el registro
     * completamente de la base de datos.
     *
     * <p>Ejemplo de uso: DELETE /api/jobs/EMAIL/EmailDiario
     *
     * <p>ADVERTENCIA: esta acción no se puede deshacer. Si solo quieres detenerlo temporalmente,
     * usa /pause.
     *
     * <p>¿Cuándo usar delete? Cuando el job ya no es necesario definitivamente, o cuando quieres
     * cambiarlo de configuración: primero DELETE el job actual, después POST con la nueva
     * configuración.
     */
    @DeleteMapping("/{group}/{name}")
    public ResponseEntity<ApiResponse> delete(
            @PathVariable String group, @PathVariable String name) {
        try {
            schedulerService.deleteJob(name, group);
            return ResponseEntity.ok(ApiResponse.status("ELIMINADO", name));
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GET /api/jobs  →  Listar todos los jobs
    // ══════════════════════════════════════════════════════════════

    /**
     * Retorna todos los jobs registrados en Quartz con su estado actual.
     *
     * <p>Ejemplo de respuesta: una lista de objetos con name, group, jobClass, status (NORMAL,
     * PAUSED, BLOCKED o ERROR), nextFire, prevFire, y cronExpr (solo si es CronTrigger).
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllJobs() {
        try {
            log.info("Buscando producto id={}", 11111);
            List<Map<String, Object>> jobs = schedulerService.getAllJobs();
            return ResponseEntity.ok(jobs);
        } catch (SchedulerException e) {
            log.error("Error al listar jobs", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GET /api/jobs/{group}/{name}  →  Detalle de un job
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{group}/{name}")
    public ResponseEntity<Object> getJob(@PathVariable String group, @PathVariable String name) {
        try {
            Map<String, Object> job = schedulerService.getJob(name, group);
            if (job == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(job);
        } catch (SchedulerException e) {
            return ResponseEntity.internalServerError().body(ApiResponse.error(e.getMessage()));
        }
    }
}
