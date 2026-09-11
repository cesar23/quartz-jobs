# =========================================================================
# Dockerfile — demo-crud (Spring Boot)
# Multi-stage build: compila en una imagen pesada, corre en una liviana.
# =========================================================================

# ---- ETAPA 1: BUILD ------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache de dependencias: solo se re-descarga si cambia el pom.xml
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Codigo fuente (invalida cache solo cuando cambia src/)
COPY src ./src

# Compila y empaqueta. Los tests deberian correr en un paso previo del
# pipeline de CI, no dentro del build de la imagen.
# El perfil Spring "prod" (application-prod.yml) NO se elige aqui: se
# activa en runtime con la variable SPRING_PROFILES_ACTIVE (ver docker-compose).
RUN mvn clean package -DskipTests -B


# ---- ETAPA 2: RUNTIME (imagen final) -------------------------------------
FROM eclipse-temurin:25-jre-alpine

# Usuario no-root: evita correr el proceso de la app como root dentro
# del contenedor, buena practica de seguridad en produccion.
RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

# Wildcard: no depende del nombre exacto del jar (artifactId/version)
COPY --from=build /build/target/*.jar app.jar

RUN chown -R spring:spring /app
USER spring

EXPOSE 8080

# Healthcheck: usa el endpoint de Actuator para que Docker/orquestador
# sepan si el contenedor esta realmente sano, no solo "corriendo".
# Puerto FIJO a proposito -- ver docker-compose.yml: SERVER_PORT dentro
# del contenedor siempre es 8080 en los 3 ambientes (dev/pre/prod). El
# "puerto bonito" que varia por ambiente (7080 en pre) es un mapeo de
# host, no algo que la app o este healthcheck necesiten saber.
#
# interval=2m (no 30s): cada chequeo genera 2 lineas de log via
# CorrelationIdFilter -- 30s satura el log sin aportar nada, esto es solo
# la RED de seguridad para orquestadores que ignoren docker-compose.yml
# (ese SI define su propio healthcheck con este mismo intervalo, y es el
# que manda cuando corres con "docker compose up").
HEALTHCHECK --interval=2m --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Forma exec: Java es PID 1, recibe SIGTERM directamente (shutdown graceful).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
