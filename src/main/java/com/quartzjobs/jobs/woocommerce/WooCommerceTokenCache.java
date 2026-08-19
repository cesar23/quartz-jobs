package com.quartzjobs.jobs.woocommerce;

// Ubicación: src/main/java/com/quartzjobs/jobs/woocommerce/WooCommerceTokenCache.java

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Cache en memoria del access_token obtenido por {@link WooCommerceAuthJob}, para que {@link
 * WooCommerceDataSyncJob} pueda reutilizarlo sin tener que hacer login de nuevo en cada ejecución.
 *
 * <p>Clave = "tokenKey" (el mismo valor que pones en el JobDataMap de AMBOS jobs). Así puedes tener
 * varios pares auth/datasync corriendo en paralelo (ej. distintos ambientes o distintas tiendas)
 * sin que se pisen el token.
 *
 * <p>Es un bean @Component (singleton de Spring), por eso el estado sobrevive entre ejecuciones de
 * distintos jobs — vive mientras la app esté arriba.
 */
@Component
public class WooCommerceTokenCache {

    private static final class Entry {
        final String token;
        final Instant obtainedAt;

        Entry(String token, Instant obtainedAt) {
            this.token = token;
            this.obtainedAt = obtainedAt;
        }
    }

    private final ConcurrentHashMap<String, AtomicReference<Entry>> cache =
            new ConcurrentHashMap<>();

    /** Guarda/reemplaza el token vigente para esta key. Llamado por WooCommerceAuthJob. */
    public void put(String tokenKey, String token) {
        cache.computeIfAbsent(tokenKey, k -> new AtomicReference<>())
                .set(new Entry(token, Instant.now()));
    }

    /**
     * Devuelve el token vigente si existe y no ha superado maxAge desde que se guardó. Si no hay
     * token, o ya expiró, devuelve Optional.empty(). Llamado por WooCommerceDataSyncJob antes de
     * cada ciclo.
     */
    public Optional<String> get(String tokenKey, Duration maxAge) {
        AtomicReference<Entry> ref = cache.get(tokenKey);
        if (ref == null) {
            return Optional.empty();
        }
        Entry entry = ref.get();
        if (entry == null) {
            return Optional.empty();
        }
        boolean expired = Duration.between(entry.obtainedAt, Instant.now()).compareTo(maxAge) > 0;
        return expired ? Optional.empty() : Optional.of(entry.token);
    }

    /** Útil para debug/monitoreo: hace cuánto se obtuvo el token actual (si existe). */
    public Optional<Duration> ageOf(String tokenKey) {
        AtomicReference<Entry> ref = cache.get(tokenKey);
        Entry entry = (ref != null) ? ref.get() : null;
        return entry == null
                ? Optional.empty()
                : Optional.of(Duration.between(entry.obtainedAt, Instant.now()));
    }
}
