# Tutorial de despliegue — quartz-jobs

Guía completa: correr local (con y sin Docker), y el pipeline real de
`dev` → `pre` → `prod` vía GitHub Actions ("build once, promote everywhere").

---

## 0. Prerrequisitos

- Java 21 (JDK) — ver `Instalacion_java_jdk_21.md` si necesitas instalarlo
- Docker + Docker Compose (para las opciones con contenedor)
- Los ficheros `.env.dev`, `.env.pre`, `.env.prod` completados con valores
  reales (copia `.env.example` como base) — ver sección 1

---

## 1. Antes de empezar — variables que DEBES completar

En los 3 `.env.*` reemplaza estos placeholders por valores reales:

| Variable | Cómo generarla |
|---|---|
| `INTERNAL_API_TOKEN` | `openssl rand -hex 32` |
| `MARIADB_ROOT_PASSWORD` | `openssl rand -hex 16` — **distinta** en cada ambiente |
| `DB_PASSWORD` | La que quieras, pero **distinta por ambiente**, nunca reutilizada |

```bash
openssl rand -hex 32   # para tokens largos
openssl rand -hex 16   # para contraseñas
```

---

## 2. Opción A — correr LOCAL, sin Docker (perfil `dev`, H2)

Para desarrollo día a día. Usa H2 embebido, no necesita nada externo.

```bash
# 1) Dar permisos al Maven Wrapper (una sola vez, si aún no lo hiciste)
chmod +x mvnw

# 2) Compilar
./mvnw clean package -DskipTests

# 3) Correr (perfil dev es el default si no seteas SPRING_PROFILES_ACTIVE)
./mvnw spring-boot:run

# — o, con el jar ya compilado —
java -jar target/quartz-jobs-*.jar
```

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

Para correr con perfil `pre`/`prod` en local (necesitas MariaDB/MySQL
corriendo en tu máquina), setea **ambas** variables — `spring.profiles.active`
NO elige el `.env` por sí solo (ver README, sección 6):

```bash
# Linux/Mac
SPRING_PROFILES_ACTIVE=pre SPRINGDOTENV_FILENAME=.env.pre ./mvnw spring-boot:run

# Windows PowerShell
$env:SPRING_PROFILES_ACTIVE="pre"; $env:SPRINGDOTENV_FILENAME=".env.pre"; ./mvnw spring-boot:run
```

---

## 3. Opción B — Docker Compose LOCAL (build desde tu Dockerfile)

`docker-compose.yml` no tiene ningún nombre de archivo quemado — todas las
variables se resuelven con `${VAR}` desde el `.env.*` que le pases con
`--env-file`. El servicio `quartz-jobs` tiene `image:` **y** `build:` a la
vez: con `build` + `up`, construye local desde tu `Dockerfile`.

### 3.1. Primera vez con una BD nueva (contenedor recién creado)

Un contenedor de MariaDB recién creado no tiene las tablas `QRTZ_*` de
Quartz. Corre **una sola vez** con el bootstrap del esquema activado:

```bash
mkdir logs
docker compose --env-file .env.pre build
QUARTZ_INIT_SCHEMA=always docker compose --env-file .env.pre up
```

Espera a ver `Started JobsApplication` en los logs, sin `exited with code 1`.
`Ctrl+C` para bajarlo.

### 3.2. Arranques normales (de acá en adelante)

**Sin** `QUARTZ_INIT_SCHEMA=always` — dejarlo en `always` de forma
permanente **dropea y recrea** las tablas en cada reinicio, perdiendo
todos los jobs/triggers persistidos:

```bash
docker compose --env-file .env.dev up --build

# Perfil pre / prod, igual patrón:
docker compose --env-file .env.pre up --build
docker compose --env-file .env.prod up --build

# En segundo plano
docker compose --env-file .env.dev up --build -d
```

### 3.3. Comandos útiles del día a día

```bash
# Logs en vivo de la app
docker compose logs -f quartz-jobs

# Logs de todos los servicios
docker compose logs -f

# Contenedores corriendo
docker compose ps

# Bajar (mantiene los datos/volúmenes)
docker compose down

# Bajar Y borrar volúmenes (BORRA los datos de MariaDB)
docker compose down -v

# Reconstruir solo la imagen de la app tras cambiar código
docker compose --env-file .env.dev build quartz-jobs
docker compose --env-file .env.dev up -d quartz-jobs

# Shell dentro del contenedor de la app
docker compose exec quartz-jobs sh

# Estado de salud de un contenedor
docker inspect --format='{{json .State.Health}}' jobs-dev-app

# Ver la red y qué contenedores están conectados
docker network inspect jobs-dev-net
```

---

## 4. Verificar que todo funciona

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP"} (7080 en pre local)
```

```bash
# Swagger UI
http://localhost:8080/swagger-ui.html

# API interna de logs (necesita el X-Internal-Token real)
curl -H "X-Internal-Token: TU_TOKEN_REAL" \
  "http://localhost:8080/internal/logs?limit=20"

# phpMyAdmin
http://localhost:${HOST_MACHINE_PMA_PORT}   # 19090 en dev, 19091 en pre, 19092 en prod
```

---

## 5. El pipeline real: `dev` → `pre` → `prod` (GitHub Actions)

Esto reemplaza construir la imagen a mano en el VPS — la imagen se
compila **una sola vez** en CI, y `pre`/`prod` solo la re-etiquetan
(mismo digest, cero recompilación). Ver README sección 9 para el detalle
de cada workflow.

### 5.1. Publicar una nueva imagen `dev`

```bash
git push origin develop
```

Esto dispara `build.yml`: compila, testea, corre MegaLinter, construye y
publica la imagen en GHCR con tags `dev-<sha corto>` y `dev`, la escanea
con Trivy, y hace un integration test real (levanta la app + MariaDB y le
pega a un endpoint). El SHA corto que necesitas para el siguiente paso
sale en los logs del job "Set short SHA".

### 5.2. Promover esa imagen a `pre`

En GitHub → **Actions** → `Promote to PRE and Deploy` → **Run workflow**,
con el SHA corto de 5.1.

Esto re-etiqueta `dev-<sha>` como `pre` y `pre-<sha>` en GHCR — **sin
recompilar nada**.

### 5.3. Promover de `pre` a `prod`

En GitHub → **Actions** → `Promote to PROD and Deploy` → **Run workflow**,
con el SHA ya validado en `pre` y una versión semántica (ej. `v1.4.2`).

Re-etiqueta `pre-<sha>` como `prod` y `<version>` — tampoco recompila.

---

## 6. Deploy en el VPS (`pre`/`prod` reales) — usar `pull`, nunca `build`

En el VPS, `/opt/app_pre` y `/opt/app_prod` solo necesitan
`docker-compose.yml` + el `.env.*` correspondiente — **no** necesitan el
código fuente ni el `Dockerfile`, porque nunca construyen ahí:

```bash
cd /opt/app_pre    # o /opt/app_prod

docker compose -f docker-compose.yml --env-file .env.pre pull
docker compose -f docker-compose.yml --env-file .env.pre up -d --remove-orphans
docker image prune -f
```

`pull` descarga exactamente la imagen `ghcr.io/.../quartz-jobs:pre` (o
`:prod`) que armó el paso de promoción — jamás compila local.

Si el paquete de GHCR es **privado**, el VPS necesita loguearse primero:
```bash
echo "TU_TOKEN" | docker login ghcr.io -u TU_USUARIO --password-stdin
```
(el token necesita permiso `read:packages`)

### Primera vez en un VPS nuevo (BD vacía)

Igual que en local — un solo arranque con el bootstrap, después nunca más:
```bash
QUARTZ_INIT_SCHEMA=always docker compose -f docker-compose.yml --env-file .env.pre up -d
```

---

## 7. Limpieza — volúmenes e imágenes viejas

```bash
# Ver volúmenes que ya no usa ningún contenedor
docker volume ls -f dangling=true

# Borrar solo los que empiezan con "jobs" (de este proyecto)
docker volume rm $(docker volume ls -f dangling=true -q | grep -E '^jobs')

# Imágenes viejas sin usar (cuidado en un servidor compartido con otros proyectos)
docker image prune -f
```

⚠️ En un servidor compartido con otros proyectos, revisa la lista antes de
un `docker volume prune -a`/`docker system prune -a` a lo bruto — puede
borrar datos de otro proyecto que no es tuyo.

---

## 8. Problemas comunes (todos reales, ya nos pasaron)

| Síntoma | Causa | Solución |
|---|---|---|
| `Connection refused: getsockopt` a MySQL | No hay MariaDB/MySQL corriendo, o `DB_HOST` apunta mal | Local sin Docker: levanta el servicio (Laragon, etc). Con Docker: confirma `DB_HOST: mariadb` en `docker-compose.yml` |
| `UnknownHostException: mariadb` corriendo **desde el IDE** | `DB_HOST=mariadb` en el `.env.*` — ese hostname solo existe dentro de la red de Docker | `DB_HOST=localhost` en el `.env.*` para correr sin Docker (docker-compose lo sobreescribe a `mariadb` solo para el contenedor) |
| `Could not resolve placeholder 'INTERNAL_API_TOKEN'` | Falta la variable, o `application.yml` no tiene `:` de default | Setéala con un valor real; `${INTERNAL_API_TOKEN:}` en el yml evita que tumbe el arranque completo |
| `405 Method Not Allowed` en `/internal/logs` | Estás usando POST en vez de GET | Cambiar a GET |
| Tablas `QRTZ_*` no existen / `Table 'QRTZ_LOCKS' doesn't exist` | BD nueva, nunca se corrió el bootstrap | `QUARTZ_INIT_SCHEMA=always` en el primer arranque (sección 3.1/6), después volver a `never` |
| El mismo error de `QRTZ_LOCKS` **después de un `docker compose down`** con bootstrap ya hecho | `mariadb` no tenía volumen persistente — `down` borra el contenedor y con él los datos | Confirma que `docker-compose.yml` tenga `volumes: - mariadb_data:/var/lib/mysql` en el servicio `mariadb` |
| WARN `"No appenders present..."` al arrancar | Cosmético, pasa antes de que Logback resuelva el perfil | Ignorar, o agregar un `<root>` fallback fuera de los `<springProfile>` en `logback-spring.xml` |
| Logback falla con `contains an integer token converter, i.e. %i` | Usaste `TimeBasedRollingPolicy` con `%i`/`maxFileSize` — solo `SizeAndTimeBasedRollingPolicy` soporta eso | Cambiar la clase de la política en `logback-spring.xml` |
| `./mvnw: cannot open .mvn/wrapper/maven-wrapper.properties` en CI o local | Falta la carpeta `.mvn/wrapper/` (o está en `.gitignore` por error) | `mkdir -p .mvn/wrapper` + crear `maven-wrapper.properties`, o `mvn -N wrapper:wrapper -Dmaven=3.9.9`, y commitear |
| `error: release version 21 not supported` aunque `java -version` muestre 21 | `javac` (no `java`) apunta a otro JDK | `sudo update-alternatives --config javac`, no solo `--config java` |
| `spotless:check` falla en archivos que "no toqué" | Primera vez que corre sobre esos archivos | `mvn spotless:apply` una vez, commitear |
| `Cannot load ruleset ...pmd-ruleset.xml: can't parse argument number` | XML inválido: un comentario `<!-- -->` con `--` en el medio del texto | Quitar cualquier `--` dentro de comentarios XML |
| Contenedores de 2 ambientes chocan (`container already exists`, puertos ocupados) | `COMPOSE_PROJECT_NAME`/`HOST_PORT`/`HOST_MACHINE_PMA_PORT` iguales en distintos `.env.*` | Cada ambiente necesita valores **distintos** en esas 3 variables |
| `docker compose pull` no trae nada nuevo en el VPS | El servicio no tiene `image:`, solo `build:` — `pull` no hace nada con un servicio build-only | Agregar `image: ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}` junto a `build:` (ver sección 6) |
| Deploy en el VPS falla en un paso que referencia `docker-compose.pre.yml`/`docker-compose.prod.yml` | Overlay fantasma de una versión anterior del pipeline, ya no existe | Usar solo `docker-compose.yml` + `--env-file`, sin overlay |
