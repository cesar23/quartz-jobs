package com.quartzjobs.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;

/**
 * ╔══════════════════════════════════════════════════════════════╗ SchedulerService — Servicio
 * principal para gestionar Quartz ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>¿Qué hace este servicio? ------------------------- Es el puente entre tu código y el motor de
 * Quartz. Aquí es donde realmente se crean, configuran y controlan los jobs.
 *
 * <p>Flujo general para programar un job: --------------------------------------
 *
 * <p>1. Crear JobDetail → "qué ejecutar" (clase + nombre + datos) 2. Crear Trigger → "cuándo
 * ejecutarlo" (cron o intervalo) 3. Registrar en Quartz → scheduler().scheduleJob(jobDetail,
 * trigger) 4. Quartz persiste el job en la base de datos y se encarga del resto
 *
 * <p>Arquitectura de Quartz (simplificada): ----------------------------------------
 *
 * <p>[Tu código] ↓ llama a [SchedulerService] ← este archivo ↓ usa [Scheduler de Quartz] ← motor
 * interno ↓ persiste en [BD — tablas QRTZ_*] ← H2 en dev, MySQL en prod ↓ ejecuta [Tu Job
 * (EmailJob, etc.)] ← la tarea real
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    /**
     * SchedulerFactoryBean es el componente de Spring que envuelve al Scheduler nativo de Quartz y
     * lo integra con el contexto de Spring.
     *
     * <p>Spring Boot lo crea automáticamente a partir de la configuración en application.yml
     * (job-store-type, thread pool, etc.) y de la SpringBeanJobFactory definida en
     * QuartzConfig.java (para inyectar @Autowired dentro de los Jobs).
     */
    private final SchedulerFactoryBean schedulerFactory;

    public SchedulerService(SchedulerFactoryBean schedulerFactory) {
        this.schedulerFactory = schedulerFactory;
    }

    /**
     * Método auxiliar: devuelve el Scheduler activo de Quartz. Se llama en cada operación para
     * obtener la instancia en ejecución.
     */
    private Scheduler scheduler() {
        return schedulerFactory.getScheduler();
    }

    // ══════════════════════════════════════════════════════════════
    //  Programar con expresión CRON
    // ══════════════════════════════════════════════════════════════

    /**
     * Programa un Job usando una expresión Cron.
     *
     * <p>Úsalo para: horarios fijos y predecibles. Ejemplos: "todos los días a las 8 AM", "cada
     * lunes a las 9 AM"
     *
     * @param jobClass Clase del job a ejecutar (ej: EmailJob.class)
     * @param jobName Nombre único dentro del grupo (ej: "EmailDiario")
     * @param jobGroup Agrupación lógica de jobs (ej: "EMAIL", "REPORTS")
     * @param cronExpression Expresión cron de 6 campos (ej: "0 0 8 * * ?")
     * @param data Datos extra que el Job recibirá en su JobDataMap (ej: {"to": "user@mail.com",
     *     "subject": "Hola"})
     */
    public void scheduleCronJob(
            Class<? extends Job> jobClass,
            String jobName,
            String jobGroup,
            String cronExpression,
            Map<String, String> data)
            throws SchedulerException {

        /*
         * JobDataMap: mapa de datos que viaja con el job.
         * El Job los recupera dentro de execute() con:
         *   context.getMergedJobDataMap().getString("to")
         *
         * Estos datos se guardan en la base de datos junto con el job,
         * así que persisten entre reinicios del servidor.
         */
        JobDataMap dataMap = new JobDataMap();
        if (data != null) dataMap.putAll(data);

        /*
         * JobDetail: define "QUÉ" ejecutar.
         *
         * - newJob(jobClass)         → qué clase ejecutar
         * - withIdentity(name,group) → identificador único del job (JobKey)
         * - usingJobData(dataMap)    → datos que recibirá el job
         * - storeDurably()           → el job persiste en BD aunque no tenga trigger activo
         *                              (importante para poder dispararlos manualmente)
         */
        JobDetail jobDetail =
                JobBuilder.newJob(jobClass)
                        .withIdentity(jobName, jobGroup)
                        .usingJobData(dataMap)
                        .storeDurably()
                        .build();

        /*
         * Trigger: define "CUÁNDO" ejecutar.
         *
         * - withIdentity(...)   → nombre del trigger (convención: "trigger_" + jobName)
         * - forJob(jobDetail)   → asocia el trigger al job creado arriba
         * - startNow()          → empieza a contar desde este momento
         * - withSchedule(...)   → define el ritmo de ejecución (aquí: CronSchedule)
         *
         * withMisfireHandlingInstructionDoNothing():
         *   Si el servidor estuvo apagado y el job no se ejecutó en su horario
         *   (eso se llama "misfire"), Quartz NO lo ejecuta al reiniciar.
         *   Simplemente espera el próximo disparo normal.
         *   Alternativa: withMisfireHandlingInstructionFireAndProceed()
         *   → ejecutaría inmediatamente al detectar el misfire.
         */
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity("trigger_" + jobName, jobGroup)
                        .forJob(jobDetail)
                        .startNow()
                        .withSchedule(
                                CronScheduleBuilder.cronSchedule(cronExpression)
                                        .withMisfireHandlingInstructionDoNothing())
                        .build();

        /*
         * scheduleJob() registra el JobDetail + Trigger en Quartz.
         * Quartz persiste el job y el trigger en las tablas QRTZ_* de la base de datos y
         * a partir de aquí se encarga de ejecutarlo en el horario indicado.
         */
        scheduler().scheduleJob(jobDetail, trigger);
        log.info("✅ Job CRON programado: [{}/{}] cron={}", jobGroup, jobName, cronExpression);
    }

    // ══════════════════════════════════════════════════════════════
    //  Programar con intervalo simple (ms)
    // ══════════════════════════════════════════════════════════════

    /**
     * Programa un Job con repetición a intervalo fijo en milisegundos.
     *
     * <p>Úsalo para: repeticiones regulares sin importar la hora exacta. Ejemplos: "cada 30
     * minutos", "cada 2 horas", "cada 10 segundos"
     *
     * <p>Conversión rápida a milisegundos: 10 segundos = 10_000 ms 1 minuto = 60_000 ms 30 minutos
     * = 1_800_000 ms 1 hora = 3_600_000 ms 1 día = 86_400_000 ms
     *
     * @param jobClass Clase del job a ejecutar
     * @param jobName Nombre único dentro del grupo
     * @param jobGroup Agrupación lógica
     * @param intervalMs Intervalo en milisegundos entre cada ejecución
     * @param data Datos extra para el job
     */
    public void scheduleSimpleJob(
            Class<? extends Job> jobClass,
            String jobName,
            String jobGroup,
            long intervalMs,
            Map<String, String> data)
            throws SchedulerException {

        JobDataMap dataMap = new JobDataMap();
        if (data != null) dataMap.putAll(data);

        // JobDetail: igual que en CronJob — define QUÉ ejecutar
        JobDetail jobDetail =
                JobBuilder.newJob(jobClass)
                        .withIdentity(jobName, jobGroup)
                        .usingJobData(dataMap)
                        .storeDurably()
                        .build();

        /*
         * SimpleTrigger: repite el job cada intervalMs milisegundos.
         *
         * - withIntervalInMilliseconds(intervalMs) → cada cuánto se repite
         * - repeatForever()                        → sin fecha de fin, indefinidamente
         *
         * Alternativa a repeatForever():
         *   .withRepeatCount(5) → ejecuta exactamente 5 veces y para
         */
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity("trigger_" + jobName, jobGroup)
                        .forJob(jobDetail)
                        .startNow()
                        .withSchedule(
                                SimpleScheduleBuilder.simpleSchedule()
                                        .withIntervalInMilliseconds(intervalMs)
                                        .repeatForever())
                        .build();

        scheduler().scheduleJob(jobDetail, trigger);
        log.info("✅ Job SIMPLE programado: [{}/{}] intervalMs={}", jobGroup, jobName, intervalMs);
    }

    // ══════════════════════════════════════════════════════════════
    //  Control de Jobs
    // ══════════════════════════════════════════════════════════════

    /**
     * Pausa un job. Se detiene pero permanece guardado en la base de datos.
     *
     * <p>Internamente Quartz cambia el estado del trigger a "PAUSED" en la tabla QRTZ_TRIGGERS. No
     * borra nada.
     *
     * <p>JobKey.jobKey(name, group) → crea el identificador único del job Es equivalente a new
     * JobKey(name, group)
     */
    public void pauseJob(String jobName, String jobGroup) throws SchedulerException {
        scheduler().pauseJob(JobKey.jobKey(jobName, jobGroup));
        log.info("⏸ Job pausado: [{}/{}]", jobGroup, jobName);
    }

    /**
     * Reanuda un job pausado. Quartz cambia el estado del trigger de "PAUSED" a "WAITING" y vuelve
     * a dispararlo en el próximo horario.
     */
    public void resumeJob(String jobName, String jobGroup) throws SchedulerException {
        scheduler().resumeJob(JobKey.jobKey(jobName, jobGroup));
        log.info("▶ Job reanudado: [{}/{}]", jobGroup, jobName);
    }

    /**
     * Elimina el job y su trigger de Quartz permanentemente. Quartz borra los registros de las
     * tablas QRTZ_JOB_DETAILS y QRTZ_TRIGGERS de la base de datos. No se puede deshacer.
     */
    public void deleteJob(String jobName, String jobGroup) throws SchedulerException {
        scheduler().deleteJob(JobKey.jobKey(jobName, jobGroup));
        log.info("🗑 Job eliminado: [{}/{}]", jobGroup, jobName);
    }

    /**
     * Dispara el job ahora mismo, fuera de su horario programado.
     *
     * <p>IMPORTANTE: no altera el schedule original. Si el job estaba programado para las 8 AM,
     * seguirá ejecutándose a las 8 AM como siempre.
     *
     * <p>Útil para: pruebas, ejecuciones urgentes, recuperación manual.
     */
    public void triggerNow(String jobName, String jobGroup) throws SchedulerException {
        scheduler().triggerJob(JobKey.jobKey(jobName, jobGroup));
        log.info("[Job] disparado manualmente: [{}/{}]", jobGroup, jobName);
    }

    /**
     * Verifica si un job existe en Quartz (está registrado en la base de datos).
     *
     * <p>Útil para evitar duplicados antes de programar: if
     * (!schedulerService.jobExists("EmailDiario", "EMAIL")) {
     * schedulerService.scheduleCronJob(...); }
     *
     * @return true si el job existe, false si no
     */
    public boolean jobExists(String jobName, String jobGroup) throws SchedulerException {
        return scheduler().checkExists(JobKey.jobKey(jobName, jobGroup));
    }

    // ══════════════════════════════════════════════════════════════
    //  Obtener todos los jobs
    // ══════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getAllJobs() throws SchedulerException {
        List<Map<String, Object>> result = new ArrayList<>();

        for (String group : scheduler().getJobGroupNames()) {
            for (JobKey key : scheduler().getJobKeys(GroupMatcher.jobGroupEquals(group))) {
                result.add(buildJobInfo(key));
            }
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════
    //  Obtener un job específico
    // ══════════════════════════════════════════════════════════════

    public Map<String, Object> getJob(String name, String group) throws SchedulerException {
        JobKey key = JobKey.jobKey(name, group);
        if (!scheduler().checkExists(key)) return Map.of();
        return buildJobInfo(key);
    }

    // ══════════════════════════════════════════════════════════════
    //  Método privado: construye el mapa de info de un job
    // ══════════════════════════════════════════════════════════════

    private Map<String, Object> buildJobInfo(JobKey key) throws SchedulerException {
        JobDetail detail = scheduler().getJobDetail(key);
        List<? extends Trigger> triggers = scheduler().getTriggersOfJob(key);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", key.getName());
        info.put("group", key.getGroup());
        info.put("jobClass", detail.getJobClass().getName());
        info.put("data", detail.getJobDataMap().getWrappedMap());

        if (!triggers.isEmpty()) {
            Trigger trigger = triggers.get(0);
            Trigger.TriggerState state = scheduler().getTriggerState(trigger.getKey());
            info.put("status", state.name());
            info.put("nextFire", trigger.getNextFireTime());
            info.put("prevFire", trigger.getPreviousFireTime());

            // Si es CronTrigger, agrega la expresión cron
            if (trigger instanceof CronTrigger cronTrigger) {
                info.put("cronExpr", cronTrigger.getCronExpression());
            }

            // Si es SimpleTrigger, agrega el intervalo
            if (trigger instanceof SimpleTrigger simpleTrigger) {
                info.put("intervalMs", simpleTrigger.getRepeatInterval());
                info.put("timesExecuted", simpleTrigger.getTimesTriggered());
            }
        } else {
            info.put("status", "SIN_TRIGGER");
        }

        return info;
    }
}
