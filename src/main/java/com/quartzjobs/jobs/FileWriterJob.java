package com.quartzjobs.jobs;

import com.quartzjobs.service.FileWriterService;
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
 * ╔══════════════════════════════════════════════════════════════╗ FileWriterJob — Job que escribe
 * fecha/hora en un fichero ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>¿Qué hace? ----------- Cada vez que Quartz lo dispara, añade una línea al fichero con:
 * [2026-03-10 19:30:00] Registro automático — ejecución #N
 *
 * <p>¿Cómo programarlo cada 3 minutos? ----------------------------------- Llama al endpoint con
 * este JSON:
 *
 * <p>POST http://localhost:8080/api/jobs Content-Type: application/json
 *
 * <p>{ "jobName": "RegistroFecha", "jobGroup": "FILE", "jobClass":
 * "com.quartzjobs.jobs.FileWriterJob", "intervalMs": 180000, "data": { "filePath":
 * "logs/registro.txt", "extra": "Registro automático" } }
 *
 * <p>Parámetros en "data": ---------------------- filePath → ruta del fichero (opcional, default:
 * "logs/registro.txt") extra → texto adicional en la línea (opcional)
 *
 * <p>Conversión: 3 minutos = 3 × 60 × 1000 = 180_000 ms
 */
@Component
@DisallowConcurrentExecution
public class FileWriterJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(FileWriterJob.class);

    /*
     * Spring inyecta FileWriterService gracias a SpringBeanJobFactory
     * configurado en QuartzConfig. Sin esa config, esto sería null.
     */
    @Autowired private FileWriterService fileWriterService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        // Recuperamos los datos que se pasaron al crear el job
        JobDataMap data = context.getMergedJobDataMap();
        String filePath = data.getString("filePath"); // puede ser null → usará el default
        String extra = data.getString("extra"); // puede ser null → texto por defecto

        log.info(
                "▶ FileWriterJob disparado — fichero: {}",
                filePath != null ? filePath : "logs/registro.txt");

        try {
            fileWriterService.appendLine(filePath, extra);
            log.info("✔ FileWriterJob completado");

        } catch (Exception e) {
            log.error("✘ Error escribiendo en fichero: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
