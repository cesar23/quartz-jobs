package com.quartzjobs.dto;

import java.util.Map;

/** DTO para programar un job desde la API REST. */
public class ScheduleRequest {

    /** Nombre único del job (ej: "EnvioEmailDiario") */
    private String jobName;

    /** Grupo del job (ej: "EMAIL", "REPORTS") */
    private String jobGroup;

    /** Nombre completo de la clase del job (ej: "com.quartzjobs.jobs.EmailJob") */
    private String jobClass;

    /**
     * Expresión Cron (usar este O intervalMs, no los dos). Ejemplos: "0 0 8 * * ?" → todos los días
     * a las 8 AM "0 * * * * ?" → cada minuto
     */
    private String cronExpression;

    /** Intervalo en milisegundos para jobs simples repetitivos. Ejemplo: 3600000 → cada hora */
    private Long intervalMs;

    /** Datos extra que el job recibirá en el JobDataMap */
    private Map<String, String> data;

    // ─── Getters y Setters ────────────────────────────────────────

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String v) {
        this.jobName = v;
    }

    public String getJobGroup() {
        return jobGroup;
    }

    public void setJobGroup(String v) {
        this.jobGroup = v;
    }

    public String getJobClass() {
        return jobClass;
    }

    public void setJobClass(String v) {
        this.jobClass = v;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String v) {
        this.cronExpression = v;
    }

    public Long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(Long v) {
        this.intervalMs = v;
    }

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> v) {
        this.data = v;
    }
}
