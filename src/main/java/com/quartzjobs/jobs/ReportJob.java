package com.quartzjobs.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ╔══════════════════════════════════════════════════════════════╗ ReportJob — Job genérico de
 * generación de reportes ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>Este Job sirve como plantilla base. Para crear tu propio job, copia esta clase y reemplaza el
 * contenido del método execute() con tu lógica.
 *
 * <p>Diferencia con EmailJob: ------------------------- EmailJob usa @Autowired para inyectar un
 * servicio externo. ReportJob muestra el patrón más simple: solo lógica propia, sin dependencias
 * inyectadas.
 *
 * <p>Cuando necesites usar servicios propios en este job: 1. Declara el campo: @Autowired private
 * MiServicio miServicio; 2. Úsalo en execute(): miServicio.generarReporte();
 *
 * <p>¿Qué hace @DisallowConcurrentExecution? ----------------------------------------- Sin esta
 * anotación, si el job tarda más que su intervalo, Quartz lanzaría una segunda instancia mientras
 * la primera aún está corriendo → dos ejecuciones en paralelo del mismo job.
 *
 * <p>Ejemplo del problema: - Job programado cada 30 segundos - La ejecución tarda 45 segundos -
 * Sin @DisallowConcurrentExecution → segunda instancia arranca a los 30s mientras la primera aún
 * corre (solapamiento) - Con @DisallowConcurrentExecution → Quartz espera a que termine la primera
 * antes de lanzar la segunda (seguro)
 *
 * <p>Regla general: ponlo siempre a menos que explícitamente necesites ejecuciones paralelas del
 * mismo job.
 */
@Component
@DisallowConcurrentExecution
public class ReportJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReportJob.class);

    // ══════════════════════════════════════════════════════════════
    //  execute() — lógica del job
    // ══════════════════════════════════════════════════════════════

    /**
     * Quartz llama a este método cuando llega el horario programado.
     *
     * <p>JobExecutionContext te da acceso a:
     *
     * <p>context.getJobDetail().getKey().getName() → nombre del job
     * context.getJobDetail().getKey().getGroup() → grupo del job context.getMergedJobDataMap() →
     * datos extra del job context.getFireTime() → cuándo se disparó context.getNextFireTime() →
     * próximo disparo context.getTrigger().getKey().getName() → nombre del trigger
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        // Obtenemos el nombre del job para identificarlo en los logs
        String jobName = context.getJobDetail().getKey().getName();
        log.info("▶ ReportJob [{}] iniciado", jobName);

        try {
            /*
             * ─────────────────────────────────────────────────────
             *  TODO: Reemplaza este bloque con tu lógica real
             * ─────────────────────────────────────────────────────
             * Ejemplos de lo que podrías hacer aquí:
             *
             *   reporteService.generarResumenDiario();
             *   excelService.exportarVentas(LocalDate.now());
             *   emailService.enviarReporteAGerencia();
             *   archivoService.limpiarTempsMasDe7Dias();
             * ─────────────────────────────────────────────────────
             */
            Thread.sleep(500); // simula tiempo de procesamiento

            log.info("✔ ReportJob [{}] completado", jobName);

        } catch (InterruptedException e) {
            /*
             * InterruptedException ocurre cuando el hilo que ejecuta
             * el job es interrumpido externamente (ej: al apagar el servidor).
             *
             * Buena práctica al capturarla:
             *   1. Thread.currentThread().interrupt() → restaura la bandera
             *      de interrupción del hilo (obligatorio en Java)
             *   2. Lanzar JobExecutionException → notifica a Quartz del fallo
             *
             * Si no haces el interrupt(), otros mecanismos de la JVM que
             * dependen de esa señal dejarían de funcionar correctamente.
             */
            Thread.currentThread().interrupt();
            throw new JobExecutionException(e);
        }
    }
}
