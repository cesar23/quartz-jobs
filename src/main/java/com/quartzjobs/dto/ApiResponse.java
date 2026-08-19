package com.quartzjobs.dto;

/**
 * Respuesta estándar de la API para operaciones sobre jobs (programar, pausar, reanudar, disparar,
 * eliminar).
 *
 * <p>Reemplaza los Map de String a Object sueltos que había antes en JobController: acá los campos
 * son miembros reales del record, con autocompletado y chequeo de tipos en compilación, no keys de
 * String armadas a mano en cada endpoint. Por eso ya no hacen falta constantes como STATUS/ERROR:
 * el nombre del campo lo da el propio record, Java se encarga de que no haya typos.
 *
 * <p>Los campos que no aplican a una respuesta puntual quedan null (y así se serializan explícitos
 * en el JSON, sin ocultarlos) — mismo criterio que ya usa el resto del proyecto en otras
 * respuestas.
 */
public record ApiResponse(String status, String message, String job, String error) {

    /** Respuesta de éxito al programar un job nuevo. */
    public static ApiResponse scheduled(String jobName) {
        return new ApiResponse("OK", "Job programado: " + jobName, null, null);
    }

    /**
     * Respuesta de éxito para pause/resume/trigger/delete: un status + el nombre del job afectado.
     */
    public static ApiResponse status(String status, String jobName) {
        return new ApiResponse(status, null, jobName, null);
    }

    /** Respuesta de error genérica. */
    public static ApiResponse error(String errorMessage) {
        return new ApiResponse(null, null, null, errorMessage);
    }
}
