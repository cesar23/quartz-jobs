package com.quartzjobs.util.log.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protección mínima para los endpoints internos (ej: {@code /internal/logs}) cuando el proyecto NO
 * tiene spring-security instalado.
 *
 * <p>Exige un header "X-Internal-Token" que coincida con la variable de entorno INTERNAL_API_TOKEN,
 * para cualquier ruta que empiece con "/internal/".
 *
 * <p>⚠️ Esto es una traba básica, NO un reemplazo de spring-security. Si en algún momento este
 * proyecto crece y necesita roles, usuarios, OAuth, etc., migra a spring-boot-starter-security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // después de CorrelationIdFilter, antes del resto
public class InternalApiTokenFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Token";
    private static final String PROTECTED_PREFIX = "/internal/";

    @Value("${app.internal-api.token:}")
    private String expectedToken;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (!path.startsWith(PROTECTED_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (expectedToken == null || expectedToken.isBlank()) {
            // Sin token configurado, por seguridad BLOQUEA en vez de dejar pasar.
            response.sendError(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Token interno no configurado (app.internal-api.token)");
            return;
        }

        String providedToken = request.getHeader(HEADER_NAME);
        if (expectedToken.equals(providedToken)) {
            filterChain.doFilter(request, response);
        } else {
            response.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Falta o es inválido el header " + HEADER_NAME);
        }
    }
}
