package com.quartzjobs.config;

import com.quartzjobs.util.log.httpclient.LoggingClientHttpRequestInterceptor;
import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * ============================================================ RestTemplateConfig
 * ============================================================ Configura y registra el bean
 * RestTemplate que usarán los distintos Jobs/Services de la app (ej: WooCommerceSyncJob) para hacer
 * llamadas HTTP salientes.
 *
 * <p>NOTA (Spring Boot 3.5.11): desde la versión 3.4.0, los métodos setConnectTimeout(Duration) y
 * setReadTimeout(Duration) de RestTemplateBuilder están DEPRECADOS (serán eliminados en la 4.0.0).
 * Se reemplazan por connectTimeout(Duration) y readTimeout(Duration) — mismo comportamiento, solo
 * cambia el nombre del método.
 */
@Configuration
public class RestTemplateConfig {

    private final LoggingClientHttpRequestInterceptor loggingInterceptor;

    public RestTemplateConfig(LoggingClientHttpRequestInterceptor loggingInterceptor) {
        this.loggingInterceptor = loggingInterceptor;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                // Tiempo máximo para ESTABLECER la conexión TCP.
                // API nueva (Spring Boot 3.4+): connectTimeout(Duration)
                // API vieja (deprecada): setConnectTimeout(Duration)
                .connectTimeout(Duration.ofSeconds(8))

                // Tiempo máximo para RECIBIR la respuesta una vez
                // conectado.
                // API nueva (Spring Boot 3.4+): readTimeout(Duration)
                // API vieja (deprecada): setReadTimeout(Duration)
                .readTimeout(Duration.ofSeconds(60))
                .additionalInterceptors(loggingInterceptor) // ← esta línea
                .build();
    }
}
