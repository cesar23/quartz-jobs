# Tutorial — cómo correr quartz-jobs

Guía paso a paso con todos los comandos, para local (sin Docker) y con
Docker Compose, en los 3 perfiles (`dev`, `pre`, `prod`).

## 0. Prerrequisitos

- Java 21
- Maven (o usa el wrapper `./mvnw` si el proyecto lo trae)
- Docker Desktop (solo si vas a usar la Opción B)
- Los ficheros `.env.dev`, `.env.pre`, `.env.prod` ya completados con
  valores reales (no los placeholders) — ver sección "Antes de empezar".

## 1. Antes de empezar — variables que DEBES completar

En los 3 `.env.*` reemplaza estos placeholders por valores reales:

| Variable | Cómo generarla |
|---|---|
| `INTERNAL_API_TOKEN` | `openssl rand -hex 32` |
| `DB_PASSWORD` | La que quieras, pero no reutilices otra contraseña |
| `MARIADB_ROOT_PASSWORD` | Solo si vas a usar Docker (Opción B) — agrégala si no está |

```bash
# Generar un token random (Git Bash / WSL / Linux / Mac):
openssl rand -hex 32
```

---

## 2. Opción A — correr LOCAL, sin Docker (perfil `dev`, H2)

Para desarrollo día a día. Usa H2 embebido, no necesita nada externo —
no usa `.env.dev` (ese es para Docker, ver sección 3), las variables acá
las setea tu IDE o tu shell directamente.

```bash
# 1) Compilar
mvn clean package -DskipTests

# 2) Correr (perfil dev es el default si no seteas SPRING_PROFILES_ACTIVE)
mvn spring-boot:run

# — o, si ya tienes el jar compilado —
java -jar target/quartz-jobs-*.jar
```

Verifica que levantó bien:
```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

Para correr con perfil `pre` en local (necesitas MySQL/MariaDB corriendo
en tu máquina, ej. con Laragon/XAMPP):

```bash
# Windows (cmd)
set SPRING_PROFILES_ACTIVE=pre
mvn spring-boot:run

# Windows (PowerShell)
$env:SPRING_PROFILES_ACTIVE="pre"
mvn spring-boot:run

# Linux/Mac
SPRING_PROFILES_ACTIVE=pre mvn spring-boot:run
```

---

## 3. Opción B — correr con Docker Compose (perfiles `dev`/`pre`/`prod`)

`docker-compose.yml` no tiene ningún nombre de archivo `.env` quemado —
todas las variables del servicio `quartz-jobs` se resuelven con `${VAR}`,
tomando los valores de lo que le pases con `--env-file` al levantar. Por
eso el mismo `docker-compose.yml` sirve para los 3 perfiles, solo cambia
qué archivo le indicas en el comando.

Los 3 servicios (`quartz-jobs`, `mariadb`, `phpmyadmin`) están en la red
`quartz-net`, así que `mariadb` es el hostname que usa la app para
conectarse a la base de datos dentro de Docker (no `localhost`).

### 3.1. Primera vez con una BD nueva (contenedor recién creado)

La primera vez que levantas `mariadb` desde cero, la BD está vacía —
Quartz necesita crear sus tablas (`QRTZ_*`) una sola vez. `QUARTZ_INIT_SCHEMA`
se pasa como variable de shell (no hace falta tocar el `.env.*`):

```bash
# Perfil pre, bootstrap del esquema
QUARTZ_INIT_SCHEMA=always docker compose --env-file .env.pre up --build

# Windows PowerShell
$env:QUARTZ_INIT_SCHEMA="always"; docker compose --env-file .env.pre up --build
```

Espera a ver en los logs algo como:
```
Scheduler QuartzJobsScheduler_$_NON_CLUSTERED started.
Started JobsApplication in X seconds
```

Presiona `Ctrl+C` para bajarlo.

### 3.2. Arranques normales (de acá en adelante)

**Sin** `QUARTZ_INIT_SCHEMA`, para no perder los jobs/triggers
persistidos en cada reinicio (default `never`):

```bash
# Perfil dev (con MariaDB en Docker en vez de H2)
docker compose --env-file .env.dev up --build

# Perfil pre
docker compose --env-file .env.pre up --build

# Perfil prod
docker compose --env-file .env.prod up --build

# En segundo plano (no bloquea la terminal)
docker compose --env-file .env.pre up --build -d
```

### 3.3. Comandos útiles del día a día

```bash
# Ver logs en vivo de la app
docker compose logs -f quartz-jobs

# Ver logs de todos los servicios
docker compose logs -f

# Ver contenedores corriendo
docker compose ps

# Bajar todo (mantiene los datos/volúmenes)
docker compose down

# Bajar todo Y borrar volúmenes (BORRA los datos de MariaDB/Mongo)
docker compose down -v

# Reconstruir solo la imagen de la app (después de cambiar código)
docker compose --env-file .env.pre build quartz-jobs
docker compose --env-file .env.pre up -d quartz-jobs

# Entrar a una shell dentro del contenedor de la app (debug)
docker compose exec quartz-jobs sh

# Ver el estado de salud de un contenedor
docker inspect --format='{{json .State.Health}}' <nombre-del-contenedor>

# Ver la red y qué contenedores están conectados
docker network inspect <COMPOSE_PROJECT_NAME>-net
```

---

## 4. Verificar que todo funciona

```bash
# Health check
curl http://localhost:7080/actuator/health
# {"status":"UP"}   (7080 en pre, 8080 en dev/prod — según SERVER_PORT)

# Swagger UI (abrir en el navegador)
http://localhost:7080/swagger-ui.html

# Consultar logs vía la API interna (necesita el X-Internal-Token real)
curl -H "X-Internal-Token: TU_TOKEN_REAL" \
  "http://localhost:7080/internal/logs?limit=20"

# phpMyAdmin (para inspeccionar la BD directamente)
http://localhost:PUERTO_PHPMYADMIN
```

---

## 5. Problemas comunes (ya nos pasaron todos, ojo con estos)

| Síntoma | Causa | Solución |
|---|---|---|
| `Connection refused: getsockopt` a MySQL | No hay MySQL/MariaDB corriendo, o `DB_HOST` apunta mal | Local: levanta el servicio (Laragon, etc). Docker: confirma `DB_HOST: mariadb` en el `environment:` del servicio `quartz-jobs` |
| `Could not resolve placeholder 'INTERNAL_API_TOKEN'` | Falta la variable en el `.env.*` activo | Setéala con un valor real (`openssl rand -hex 32`) |
| App arranca pero `/internal/logs` da `503` | `INTERNAL_API_TOKEN` vacío | Igual que arriba |
| `/internal/logs` da `401` | El header `X-Internal-Token` no coincide con el valor real | Verifica que sea el mismo valor, sin espacios extra |
| `405 Method Not Allowed` en `/internal/logs` | Estás usando POST en vez de GET | Cambia el método a GET |
| Tablas `QRTZ_*` no existen (BD nueva) | Nunca se corrió el bootstrap del esquema | `QUARTZ_INIT_SCHEMA=always` en el primer arranque (ver 3.1), después no volver a pasarlo |
| Healthcheck del contenedor siempre "unhealthy" en `pre` | Puerto del healthcheck no coincide con `SERVER_PORT=7080` | Ya corregido en el `Dockerfile` actual (usa `${SERVER_PORT:-8080}`) |
| El contenedor de la app sigue usando variables de otro perfil | `docker compose up` sin `--env-file`, o le pasaste el archivo equivocado | Siempre especifica `--env-file .env.dev` / `.env.pre` / `.env.prod` explícitamente |
| WARN "No appenders present..." al arrancar | Cosmético, pasa antes de que Logback resuelva el perfil | Ignóralo, o aplica el fallback de `logback-spring.xml` (ver README de logging) |

---

## 6. Apagar todo y limpiar (para volver a empezar de cero)

```bash
# Baja contenedores y borra volúmenes (datos de MariaDB/Mongo se pierden)
docker compose down -v

# Borra también las imágenes construidas localmente
docker compose down -v --rmi local

# La próxima vez que levantes, vas a necesitar QUARTZ_INIT_SCHEMA=always
# de nuevo (ver 3.1), porque la BD vuelve a estar vacía.
```
