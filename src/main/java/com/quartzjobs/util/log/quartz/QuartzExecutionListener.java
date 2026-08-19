package com.quartzjobs.util.log.quartz;

import com.quartzjobs.util.log.core.LogContext;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ADAPTER de {@link LogContext} para Quartz — el equivalente, para Jobs, de lo que {@code
 * CorrelationIdFilter} hace para requests HTTP.
 *
 * <p>Úsalo SOLO si el proyecto usa Quartz. Se registra una vez (ver {@link QuartzLoggingConfig}) y
 * aplica automáticamente a cada job sin que cada clase de Job tenga que hacer nada al respecto: ya
 * no necesitas construir manualmente "[" + jobKey + "]" en cada mensaje.
 */
@Component
public class QuartzExecutionListener implements JobListener {

    private static final Logger log = LoggerFactory.getLogger(QuartzExecutionListener.class);

    @Override
    public String getName() {
        return "quartzExecutionLoggingListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        LogContext.put(LogContext.TRACE_ID, context.getFireInstanceId());
        LogContext.put(LogContext.SOURCE, "job");
        LogContext.put("jobKey", context.getJobDetail().getKey().getName());
        LogContext.put("jobGroup", context.getJobDetail().getKey().getGroup());
        LogContext.put("triggerKey", context.getTrigger().getKey().toString());

        log.info("▶ Job disparado");
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        log.warn("⚠ Job vetado (no se ejecutó, ej: @DisallowConcurrentExecution)");
        LogContext.clear();
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            long durationMs = context.getJobRunTime();
            if (jobException != null) {
                log.error("✘ Job finalizado con error durationMs={}", durationMs, jobException);
            } else {
                log.info("✔ Job finalizado OK durationMs={}", durationMs);
            }
        } finally {
            // Quartz reutiliza hilos de su thread pool: si no limpias, el
            // siguiente job en ese mismo hilo heredaría este contexto.
            LogContext.clear();
        }
    }
}
