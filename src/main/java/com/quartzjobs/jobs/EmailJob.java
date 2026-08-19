package com.quartzjobs.jobs;

import com.quartzjobs.service.EmailService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ╔══════════════════════════════════════════════════════════════╗ EmailJob — Job que envía correos
 * electrónicos con Quartz ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>¿Qué es un Job en Quartz? -------------------------- Un Job es simplemente una clase Java que
 * implementa la interfaz Job de Quartz. Solo tiene un método obligatorio: execute(). Quartz llama a
 * ese método cuando llega el momento programado.
 *
 * <p>¿Cómo llegan los datos al Job? -------------------------------- Los datos (destinatario,
 * asunto, cuerpo) se pasan al programar el job desde el controlador REST en el campo "data":
 *
 * <p>POST /api/jobs { "jobClass": "com.quartzjobs.jobs.EmailJob", "data": { "to":
 * "cliente@empresa.com", "subject": "Reporte diario", "body": "Aquí está tu reporte" } }
 *
 * <p>Quartz guarda ese mapa de datos en la base de datos y lo recupera cada vez que dispara el job,
 * disponible via JobDataMap.
 *
 * <p>Anotaciones en esta clase: --------------------------- @Component → Spring gestiona esta clase
 * como un Bean @DisallowConcurrentExecution → evita ejecuciones solapadas (ver abajo)
 */
@Component
@DisallowConcurrentExecution
public class EmailJob implements Job {

    /*
     * Logger: registra mensajes en la consola/archivo de log.
     * Se recomienda un logger por clase para identificar el origen.
     *
     * Niveles de log (de menos a más severo):
     *   DEBUG → detalles de desarrollo (desactivado en producción)
     *   INFO  → eventos normales del flujo
     *   WARN  → algo inesperado pero no crítico
     *   ERROR → fallos que necesitan atención
     */
    private static final Logger log = LoggerFactory.getLogger(EmailJob.class);

    /*
     * @Autowired: Spring inyecta el EmailService automáticamente.
     *
     * Esto funciona gracias a SpringBeanJobFactory configurado
     * en QuartzConfig. Sin esa configuración, emailService
     * sería null y lanzaría NullPointerException al ejecutarse.
     */
    @Autowired private EmailService emailService;

    // ══════════════════════════════════════════════════════════════
    //  execute() — punto de entrada del Job
    // ══════════════════════════════════════════════════════════════

    /**
     * Quartz llama a este método automáticamente cuando llega el momento programado (cron o
     * intervalo).
     *
     * @param context Contiene toda la información de la ejecución: - JobDataMap con los datos del
     *     job - JobDetail con nombre, grupo, clase - Trigger que disparó esta ejecución - Fechas:
     *     cuándo se disparó, próximo disparo, etc.
     * @throws JobExecutionException Si lanza esta excepción, Quartz la registra como fallo del job.
     *     Puedes configurar si reintenta automáticamente o no.
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        /*
         * getMergedJobDataMap(): obtiene el mapa de datos del job.
         * "Merged" significa que combina los datos del JobDetail
         * y del Trigger si ambos tienen datos (el Trigger tiene prioridad).
         *
         * Para la mayoría de casos puedes usar simplemente:
         *   context.getJobDetail().getJobDataMap()
         */
        JobDataMap data = context.getMergedJobDataMap();

        // Recuperamos los valores guardados al programar el job
        String to = data.getString("to"); // destinatario del email
        String subject = data.getString("subject"); // asunto
        String body = data.getString("body"); // cuerpo del mensaje

        log.info("▶ EmailJob disparado — destinatario: {}", to);

        try {
            // Delegamos el envío real al EmailService
            emailService.send(to, subject, body);
            log.info("✔ Email enviado a: {}", to);

        } catch (Exception e) {
            log.error("✘ Error enviando email: {}", e.getMessage(), e);

            /*
             * JobExecutionException: la excepción estándar de Quartz
             * para reportar fallos en un Job.
             *
             * Opciones al lanzarla:
             *
             * 1) Sin reintento (comportamiento actual):
             *    throw new JobExecutionException(e);
             *    → Quartz registra el fallo y continúa con el schedule normal.
             *
             * 2) Con reintento inmediato:
             *    JobExecutionException ex = new JobExecutionException(e);
             *    ex.setRefireImmediately(true);
             *    throw ex;
             *    → Quartz vuelve a ejecutar el job de inmediato.
             *    ⚠️ Cuidado: puede generar bucles infinitos si el error persiste.
             *
             * 3) Desactivar el trigger tras el fallo:
             *    JobExecutionException ex = new JobExecutionException(e);
             *    ex.setUnscheduleAllTriggers(true);
             *    throw ex;
             *    → Quartz cancela todos los triggers del job.
             */
            throw new JobExecutionException(e);
        }
    }
}
