// src/main/java/com/quartzjobs/config/StartupConfigPrinter.java
package com.quartzjobs.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

public class StartupConfigPrinter
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();

        // @Value NO funciona aquí: este listener se instancia manualmente
        // (SpringApplication.addListeners / spring.factories), fuera del
        // ciclo de vida de beans, y el evento se dispara ANTES de que
        // exista el ApplicationContext que resuelve @Value. Por eso hay
        // que leer el Environment directamente.
        String[] activeProfiles = env.getActiveProfiles();
        String activeProfile =
                activeProfiles.length > 0
                        ? String.join(",", activeProfiles)
                        : "No definido (usando default: dev)";

        String sep = "═".repeat(65);

        System.out.println("\n" + sep);
        System.out.println("  🚀 STARTUP CONFIG — quartz-jobs — variables cargadas");
        System.out.println(sep);

        // ── Perfil activo ────────────────────────────────────────────────
        System.out.println("\n  📌 PERFIL ACTIVO");
        System.out.printf("  %-55s = %s%n", "spring.profiles.active", activeProfile);

        // ── Info ─────────────────────────────────────────────────
        System.out.println("\n  📌 APP");
        printVar(env, "app.built-version");
        printVar(env, "app.internal-api.token");

        // ── Datasource (H2 en dev / MySQL en prod) ──────────────────────
        System.out.println("\n  🗄️  DATASOURCE");
        printVar(env, "spring.datasource.url");
        printVar(env, "spring.datasource.username");
        printSecret(env, "spring.datasource.password");
        printVar(env, "spring.datasource.driver-class-name");
        printVar(env, "spring.jpa.database-platform");
        printVar(env, "spring.jpa.hibernate.ddl-auto");
        printVar(env, "spring.jpa.show-sql");

        // ── H2 Console (solo perfil dev) ─────────────────────────────────
        System.out.println("\n  🧪 H2 CONSOLE");
        printVar(env, "spring.h2.console.enabled");
        printVar(env, "spring.h2.console.path");

        // ── Quartz ────────────────────────────────────────────────────────
        System.out.println("\n  ⏰ QUARTZ");
        printVar(env, "spring.quartz.job-store-type");
        printVar(env, "spring.quartz.jdbc.initialize-schema");
        printVar(env, "spring.quartz.properties.org.quartz.scheduler.instanceName");
        printVar(env, "spring.quartz.properties.org.quartz.scheduler.instanceId");
        printVar(env, "spring.quartz.properties.org.quartz.threadPool.threadCount");

        // ── Mail ──────────────────────────────────────────────────────────
        System.out.println("\n  📧 MAIL");
        printVar(env, "spring.mail.host");
        printVar(env, "spring.mail.port");
        printVar(env, "spring.mail.username");
        printSecret(env, "spring.mail.password");

        // ── Servidor ──────────────────────────────────────────────────────
        System.out.println("\n  🌐 SERVIDOR");
        printVar(env, "server.port");

        // ── Springdoc / Swagger ──────────────────────────────────────────
        System.out.println("\n  📘 SWAGGER / OPENAPI");
        printVar(env, "springdoc.swagger-ui.path");
        printVar(env, "springdoc.api-docs.path");

        // ── Actuator ──────────────────────────────────────────────────────
        System.out.println("\n  ❤️  ACTUATOR");
        printVar(env, "management.endpoints.web.exposure.include");

        // ── Logging ───────────────────────────────────────────────────────
        System.out.println("\n  🔧 LOGGING");
        printVar(env, "logging.level.com.quartzjobs");
        printVar(env, "logging.level.org.quartz");

        System.out.println("\n" + sep + "\n");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void printVar(Environment env, String key) {
        String value = env.getProperty(key);
        if (value != null) {
            System.out.printf("  %-55s = %s%n", key, value);
        } else {
            System.out.printf("  %-55s = ⚠️  NO DEFINIDA%n", key);
        }
    }

    private void printSecret(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            System.out.printf("  %-55s = ⚠️  NO DEFINIDA%n", key);
        } else if (value.length() <= 4) {
            System.out.printf("  %-55s = ****%n", key);
        } else {
            System.out.printf("  %-55s = %s****(oculto)%n", key, value.substring(0, 4));
        }
    }
}
