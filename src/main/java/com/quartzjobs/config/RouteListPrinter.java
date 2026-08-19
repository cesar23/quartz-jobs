package com.quartzjobs.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Prints all registered Spring MVC routes to the console on startup. Inspired by Laravel's "php
 * artisan route:list" command.
 */
@Component
@Order(Integer.MAX_VALUE) // run last, once everything else is initialized
public class RouteListPrinter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RouteListPrinter.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final Environment environment;

    public RouteListPrinter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            Environment environment) {
        this.handlerMapping = handlerMapping;
        this.environment = environment;
    }

    // ==== ANSI colors ====
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";
    private static final String WHITE_BOLD = "\u001B[1;97m";

    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_PATCH = "PATCH";
    private static final String METHOD_DELETE = "DELETE";
    private static final String METHOD_ANY = "ANY";

    private static final List<String> HTTP_METHODS_ORDER =
            List.of(METHOD_GET, METHOD_POST, METHOD_PUT, METHOD_PATCH, METHOD_DELETE);

    private static final String NL = System.lineSeparator();

    private record RouteRow(String method, String path, String controller, String action) {}

    @Override
    public void run(String... args) {
        List<RouteRow> rows = collectRoutes();

        rows.sort(Comparator.comparing(RouteRow::path).thenComparing(r -> methodOrder(r.method())));

        if (isDevProfile()) {
            // dev: tabla ASCII con colores, legible directo en la terminal.
            String table = buildTable(rows);
            String summary = buildSummary(rows);
            log.info("{}{}{}", table, NL, summary);
        } else {
            // pre/prod: salida JSON estructurada. Una tabla ANSI dentro de un
            // solo campo "message" se ve horrible en un log JSON y no se
            // puede filtrar. En su lugar, cada ruta es su propio log, con
            // httpMethod/httpPath/controller/action como campos reales del
            // JSON (via StructuredArguments) — consultable desde Grafana o
            // desde /internal/logs igual que cualquier otro log.
            logStructured(rows);
        }
    }

    /**
     * true si el perfil activo es "dev" (o si no hay perfil activo, ya que application.yml usa
     * "dev" como default cuando falta SPRING_PROFILES_ACTIVE). Cualquier otro perfil (pre, prod,
     * etc.) se trata como "salida JSON".
     */
    private boolean isDevProfile() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            return true;
        }
        for (String profile : active) {
            if ("dev".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void logStructured(List<RouteRow> rows) {
        for (RouteRow r : rows) {
            log.info(
                    "Ruta registrada {} {} -> {}",
                    StructuredArguments.value("httpMethod", r.method()),
                    StructuredArguments.value("httpPath", r.path()),
                    StructuredArguments.value("handler", r.controller() + "::" + r.action()),
                    StructuredArguments.kv("controller", r.controller()),
                    StructuredArguments.kv("action", r.action()));
        }

        Map<String, Long> byMethod =
                rows.stream()
                        .collect(Collectors.groupingBy(RouteRow::method, Collectors.counting()));

        log.info(
                "Rutas registradas: {} totales",
                StructuredArguments.value("routeCount", rows.size()),
                StructuredArguments.kv("routesByMethod", byMethod));
    }

    private List<RouteRow> collectRoutes() {
        List<RouteRow> rows = new ArrayList<>();
        Map<RequestMappingInfo, HandlerMethod> map = handlerMapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : map.entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();

            Set<String> patterns = extractPatterns(info);
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();

            String controller = handler.getBeanType().getSimpleName();
            String action = handler.getMethod().getName();

            if (methods.isEmpty()) {
                for (String pattern : patterns) {
                    rows.add(new RouteRow(METHOD_ANY, pattern, controller, action));
                }
            } else {
                for (RequestMethod m : methods) {
                    for (String pattern : patterns) {
                        rows.add(new RouteRow(m.name(), pattern, controller, action));
                    }
                }
            }
        }
        return rows;
    }

    private Set<String> extractPatterns(RequestMappingInfo info) {
        PathPatternsRequestCondition pathPatterns = info.getPathPatternsCondition();
        if (pathPatterns != null) {
            return pathPatterns.getPatternValues();
        }

        PatternsRequestCondition patterns = info.getPatternsCondition();
        if (patterns != null) {
            return patterns.getPatterns();
        }

        return Set.of("/");
    }

    private int methodOrder(String method) {
        return switch (method) {
            case METHOD_GET -> 0;
            case METHOD_POST -> 1;
            case METHOD_PUT -> 2;
            case METHOD_PATCH -> 3;
            case METHOD_DELETE -> 4;
            default -> 5;
        };
    }

    private String colorFor(String method) {
        return switch (method) {
            case METHOD_GET -> GREEN;
            case METHOD_POST -> YELLOW;
            case METHOD_PUT -> BLUE;
            case METHOD_PATCH -> MAGENTA;
            case METHOD_DELETE -> RED;
            default -> GRAY;
        };
    }

    /** Pads plain text (no ANSI codes) to a fixed visible width, then wraps it in color. */
    private String cell(String text, int width, String color) {
        int pad = Math.max(width - text.length(), 0);
        return color + text + RESET + " ".repeat(pad);
    }

    private String buildTable(List<RouteRow> rows) {
        int wMethod = Math.max(6, rows.stream().mapToInt(r -> r.method().length()).max().orElse(6));
        int wPath = Math.max(4, rows.stream().mapToInt(r -> r.path().length()).max().orElse(4));
        int wHandler =
                Math.max(
                        9,
                        rows.stream()
                                .mapToInt(r -> (r.controller() + "::" + r.action()).length())
                                .max()
                                .orElse(9));

        String border =
                "+"
                        + "-".repeat(wMethod + 2)
                        + "+"
                        + "-".repeat(wPath + 2)
                        + "+"
                        + "-".repeat(wHandler + 2)
                        + "+";

        StringBuilder out = new StringBuilder();
        out.append(NL);
        out.append(WHITE_BOLD).append("  >> RUTAS REGISTRADAS").append(RESET).append(NL);
        out.append(border).append(NL);
        out.append("| ")
                .append(cell("METODO", wMethod, BOLD))
                .append(" | ")
                .append(cell("RUTA", wPath, BOLD))
                .append(" | ")
                .append(cell("HANDLER", wHandler, BOLD))
                .append(" |")
                .append(NL);
        out.append(border).append(NL);

        for (RouteRow r : rows) {
            String color = colorFor(r.method());
            String handlerPlain = r.controller() + "::" + r.action();
            int pad = Math.max(wHandler - handlerPlain.length(), 0);
            String handler = r.controller() + DIM + "::" + RESET + r.action() + " ".repeat(pad);

            out.append("| ")
                    .append(cell(r.method(), wMethod, color + BOLD))
                    .append(" | ")
                    .append(cell(r.path(), wPath, CYAN))
                    .append(" | ")
                    .append(handler)
                    .append(" |")
                    .append(NL);
        }

        out.append(border);
        return out.toString();
    }

    private String buildSummary(List<RouteRow> rows) {
        Map<String, Long> byMethod =
                rows.stream()
                        .collect(Collectors.groupingBy(RouteRow::method, Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        sb.append(GRAY)
                .append("  Total: ")
                .append(RESET)
                .append(BOLD)
                .append(rows.size())
                .append(RESET)
                .append(GRAY)
                .append(" rutas")
                .append(RESET)
                .append("   ");

        for (String m : HTTP_METHODS_ORDER) {
            long count = byMethod.getOrDefault(m, 0L);
            if (count > 0) {
                sb.append(colorFor(m))
                        .append(BOLD)
                        .append(m)
                        .append(RESET)
                        .append(GRAY)
                        .append(":")
                        .append(RESET)
                        .append(count)
                        .append("  ");
            }
        }

        return sb.toString();
    }
}
