package com.quartzjobs.jobs.woocommerce;

// Ubicación sugerida: src/main/java/com/quartzjobs/jobs/woocommerce/SyncErrorTracker.java

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Lleva la cuenta de errores CONSECUTIVOS por cada job (identificado por su nombre + grupo de
 * Quartz), para poder disparar una alerta por email cuando se supera el umbral configurado.
 *
 * <p>Nota: el contador vive en memoria (Spring singleton), por lo que se reinicia si la aplicación
 * se reinicia. Si en el futuro necesitas que sobreviva a reinicios, puede persistirse en una tabla
 * H2/MySQL en vez de este ConcurrentHashMap.
 */
@Component
public class SyncErrorTracker {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** Incrementa el contador de errores del job y devuelve el valor resultante. */
    public int incrementAndGet(String jobKey) {
        return counters.computeIfAbsent(jobKey, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** Resetea el contador (se llama tras una ejecución exitosa o tras enviar la alerta). */
    public void reset(String jobKey) {
        counters.remove(jobKey);
    }

    public int currentCount(String jobKey) {
        AtomicInteger counter = counters.get(jobKey);
        return counter != null ? counter.get() : 0;
    }
}
