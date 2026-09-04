# 📚 Tutorial completo — Quartz Jobs

> Guía de arquitectura y flujo de código para cualquier programador que se una al proyecto.
> Explica **qué hace la aplicación**, **cómo están organizados los ficheros**, **cómo viaja una petición** desde que entra por HTTP hasta que Quartz ejecuta un job real, y **cómo se compila/despliega** (Docker + CI/CD).

---

## 1. ¿Qué es esta aplicación?

**Quartz Jobs** es un servicio Spring Boot que expone una **API REST para crear, pausar, reanudar, disparar y eliminar tareas programadas (jobs)**, usando [Quartz Scheduler](https://www.quartz-scheduler.org/) como motor de programación.

En lugar de tener jobs "fijos" definidos en código (como haría `@Scheduled`), esta aplicación permite **crear jobs dinámicamente en tiempo de ejecución**, vía HTTP:

- Puedes decir "ejecuta la clase `EmailJob` todos los días a las 8 AM" sin recompilar nada.
- Los jobs y sus horarios se guardan en una base de datos (tablas `QRTZ_*`), así que **sobreviven a un reinicio del servidor**.
- Puedes pausar, reanudar, disparar manualmente o borrar cualquier job en caliente.

### Jobs que ya trae el proyecto

| Job | Qué hace | Servicio(s)/colaboradores que usa |
|---|---|---|
| `EmailJob` | Envía un correo (asunto + cuerpo) | `EmailService` |
| `FileWriterJob` | Escribe una línea con fecha/hora en un fichero | `FileWriterService` |
| `ReportJob` | Plantilla vacía de ejemplo (espera 500ms y loguea) | — |
| `WooCommerceAuthJob` | **Primer job del flujo** (ej. cada 2h): hace login contra la API externa, valida el token con `sync/login` y verifica que sirve trayendo los SKUs. Deja el token cacheado para el siguiente job | `RestTemplate`, `WooCommerceTokenCache`, `EmailService` |
| `WooCommerceDataSyncJob` | **Segundo job del flujo** (ej. cada 15 min): reutiliza el token que dejó `WooCommerceAuthJob` — **nunca hace login por su cuenta**. Sincroniza productos, envía pendientes a la web, actualiza tipo de cambio y limpia caché | `RestTemplate`, `WooCommerceTokenCache`, `EmailService` |

Componentes auxiliares (no son Jobs):
- **`SyncErrorTracker`** — contador en memoria (thread-safe) de errores consecutivos por job, usado para decidir cuándo avisar por correo.
- **`WooCommerceTokenCache`** — cachea el `access_token` obtenido por `WooCommerceAuthJob`, con control de vigencia (TTL), para que `WooCommerceDataSyncJob` lo reutilice sin loguearse de nuevo en cada ejecución.

---

## 2. Conceptos clave de Quartz (antes de leer código)

| Concepto | Qué es | Analogía |
|---|---|---|
| **Job** | La tarea en sí: una clase que implementa `org.quartz.Job` y tiene un método `execute()` | La "receta" |
| **JobDetail** | Define QUÉ ejecutar: clase + nombre + grupo + datos extra | El pedido en cocina |
| **Trigger** | Define CUÁNDO ejecutar: cron (`"0 0 8 * * ?"`) o intervalo fijo en ms | El reloj despertador |
| **JobKey** | Identificador único = `nombre + grupo` (ej: `EmailDiario` / `EMAIL`) | El DNI del job |
| **Scheduler** | El motor interno de Quartz que guarda y dispara todo | El "cerebro" |
| **JobStore JDBC** | Persistencia de todo lo anterior en tablas `QRTZ_*` de la BD | La libreta donde queda todo anotado |

Como el `JobStore` es JDBC, **todos los jobs y triggers se guardan en la base de datos** (H2 en `dev`, MariaDB/MySQL en `pre`/`prod`), no solo en memoria. Por eso, si reinicias la app, los jobs programados siguen ahí — pero ojo: en una base de datos **nueva** (por ejemplo, un contenedor de MariaDB recién creado), las tablas `QRTZ_*` no existen todavía. Ver sección 9 (`QUARTZ_INIT_SCHEMA`) para el bootstrap inicial.

---

## 3. Mapa de ficheros del proyecto

```
quartz-jobs/
├── pom.xml                          # Dependencias Maven + Spotless (formato de código)
├── mvnw, mvnw.cmd, .mvn/wrapper/     # Maven Wrapper: compila sin necesitar Maven instalado
├── Dockerfile                       # Build multi-stage (compila en Temurin, corre en JRE liviano)
├── docker-compose.yml               # app + MariaDB + phpMyAdmin, un solo fichero para dev/pre/prod
├── .env.dev / .env.pre / .env.prod  # Variables por ambiente (NUNCA se suben al repo)
├── .env.example                     # Plantilla sin secretos, sí se versiona
├── .gitignore
├── .github/
│   ├── workflows/
│   │   ├── build.yml                # CI: compila, testea, linter, build+push imagen a GHCR, integration test
│   │   ├── promote_pre.yml          # Re-etiqueta dev-<sha> → pre (sin recompilar)
│   │   └── promote_prod.yml         # Re-etiqueta pre-<sha> → prod (sin recompilar)
│   └── linters/
│       ├── checkstyle.xml           # Reglas de Checkstyle
│       ├── pmd-ruleset.xml          # Reglas de PMD
│       └── suppressions.xml         # Excepciones de Checkstyle
├── .mega-linter.yml                 # Config de MegaLinter (local y CI comparten este único archivo)
├── .yamllint.yml                    # Reglas de yamllint
├── .editorconfig
└── src/main/
    ├── resources/
    │   ├── application.yml          # Config común a todos los perfiles
    │   ├── application-dev.yml      # Perfil dev  → H2 (AUTO_SERVER=TRUE)
    │   ├── application-pre.yml      # Perfil pre  → MariaDB/MySQL (staging)
    │   ├── application-prod.yml     # Perfil prod → MariaDB/MySQL (producción)
    │   └── logback-spring.xml       # dev = consola legible; pre/prod = JSON estructurado
    └── java/com/quartzjobs/
        ├── JobsApplication.java          # Punto de entrada (main)
        │
        ├── config/
        │   ├── QuartzConfig.java         # Conecta Quartz con el contenedor de Spring
        │   ├── RestTemplateConfig.java    # RestTemplate con timeouts, con el interceptor de logging enganchado
        │   ├── StartupConfigPrinter.java  # Imprime en consola, al arrancar, qué config/perfil quedó activa
        │   ├── RouteListPrinter.java      # Lista rutas registradas al arrancar (tabla ANSI en dev, JSON en pre/prod)
        │   └── ShutdownBannerPrinter.java # Mensaje al apagar la app
        │
        ├── controller/
        │   └── JobController.java   # API REST: /api/jobs/**
        │
        ├── dto/
        │   ├── ScheduleRequest.java     # "Formulario" que llega en el body del POST
        │   ├── ApiResponse.java         # Record de respuesta estándar (status/message/job/error)
        │   └── LogFilterRequest.java    # Agrupa los criterios crudos de /internal/logs
        │
        ├── jobs/
        │   ├── EmailJob.java             # Tarea: enviar email
        │   ├── FileWriterJob.java        # Tarea: escribir en fichero
        │   ├── ReportJob.java            # Tarea: plantilla/ejemplo
        │   └── woocommerce/
        │       ├── AbstractWooCommerceJob.java  # Helpers compartidos entre los 2 jobs de WooCommerce
        │       ├── WooCommerceAuthJob.java       # 1er job: login + validación + cachea token
        │       ├── WooCommerceDataSyncJob.java   # 2do job: reutiliza el token, sincroniza
        │       ├── WooCommerceTokenCache.java    # Cache del access_token con TTL
        │       └── SyncErrorTracker.java         # Contador de errores en memoria (no es un Job)
        │
        ├── service/
        │   ├── SchedulerService.java    # Habla directamente con el Scheduler de Quartz
        │   ├── EmailService.java        # Envío de correo: texto plano, HTML y HTML con adjuntos
        │   └── FileWriterService.java   # Lógica de escritura en disco (uso general, no de negocio)
        │
        └── util/
            ├── DateUtils.java, StringUtil.java, NumberUtils.java, ...  # Utilidades generales
            ├── FileManagerUtil.java     # cat/tail/rm + escritura de logs de negocio CON ROTACIÓN
            ├── CurlLogger.java          # Genera el cURL equivalente de un request (debug)
            └── log/                     # Sistema de logging estructurado y trazabilidad — ver sección 7
                ├── core/LogContext.java
                ├── web/CorrelationIdFilter.java, InternalApiTokenFilter.java
                ├── quartz/QuartzExecutionListener.java, QuartzLoggingConfig.java
                ├── httpclient/LoggingClientHttpRequestInterceptor.java
                └── query/LogEntry.java, LogFilter.java, LogFilterRequest.java,
                         LogQueryService.java, LogQueryController.java
```

### Capas y su responsabilidad (arquitectura en capas clásica de Spring)

```
┌─────────────────────┐
│   JobController      │  ← Capa web: recibe HTTP, valida, delega. NO tiene lógica de negocio.
└──────────┬───────────┘
           │ usa
┌──────────▼───────────┐
│  SchedulerService     │  ← Orquesta Quartz: crea JobDetail + Trigger, pausa/reanuda/borra.
└──────────┬───────────┘
           │ programa
┌──────────▼───────────┐
│  Scheduler (Quartz)   │  ← Motor externo. Persiste en BD y dispara los Jobs en su horario.
└──────────┬───────────┘
           │ ejecuta
┌──────────▼───────────┐
│  Jobs (EmailJob, ...)  │  ← La tarea real. Usa @Autowired para llamar a un Service.
└──────────┬───────────┘
           │ delega en
┌──────────▼───────────┐
│  EmailService / etc.  │  ← Lógica de negocio pura, reutilizable, testeable sin Quartz.
└───────────────────────┘
```

**Regla de oro del proyecto:** el controlador nunca habla directamente con Quartz, y los Jobs nunca contienen lógica de negocio compleja — siempre delegan en un `Service`. Esto permite testear `EmailService` o `FileWriterService` sin necesidad de programar un job real.

---

## 4. Flujo completo: "quiero que se envíe un email todos los días a las 8 AM"

Sigamos una petición real, paso a paso, por todos los ficheros:

### Paso 1 — La petición HTTP

```http
POST /api/jobs
Content-Type: application/json

{
    "jobName": "EmailDiario",
    "jobGroup": "EMAIL",
    "jobClass": "com.quartzjobs.jobs.EmailJob",
    "cronExpression": "0 0 8 * * ?",
    "data": {
        "to": "cliente@empresa.com",
        "subject": "Reporte diario",
        "body": "Aquí está tu reporte"
    }
}
```

### Paso 2 — `JobController.schedule(...)`

- Deserializa el JSON en un `ScheduleRequest` (el DTO).
- Carga la clase del job dinámicamente: `Class.forName("com.quartzjobs.jobs.EmailJob")`.
  Esto es lo que permite añadir jobs nuevos **sin tocar el controlador**: solo creas la clase y la referencias por nombre completo en el JSON.
- Si viene `cronExpression`, llama a `schedulerService.scheduleCronJob(...)`; si no, usa `scheduleSimpleJob(...)` con el `intervalMs` (o 60s por defecto).
- Si algo falla (clase no existe, cron mal formado, etc.) devuelve un `400`/`500` con un `ApiResponse.error(...)` — nunca deja que la excepción rompa la respuesta.

### Paso 3 — `SchedulerService.scheduleCronJob(...)`

- Construye un `JobDetail`: qué clase ejecutar (`EmailJob`), con qué nombre/grupo (`EmailDiario`/`EMAIL`) y qué datos lleva (`to`, `subject`, `body` empaquetados en un `JobDataMap`).
- Construye un `Trigger` tipo `CronTrigger` con la expresión `"0 0 8 * * ?"`.
- Llama a `scheduler().scheduleJob(jobDetail, trigger)` → esto es lo que **realmente registra el job en Quartz**, que a su vez lo persiste en las tablas `QRTZ_JOB_DETAILS` y `QRTZ_TRIGGERS` de la base de datos.

### Paso 4 — Espera... y llega la hora programada

Quartz, por su cuenta (sin que tu código haga nada), detecta que son las 8:00 AM y:

1. Instancia `EmailJob` — pero no con `new EmailJob()`, sino a través de `SpringBeanJobFactory` (definida en `QuartzConfig`), que le permite a Spring inyectar los `@Autowired` dentro del job.
2. Antes de ejecutar, `QuartzExecutionListener` (ver sección 7) mete `jobKey`/`traceId` en el contexto de logging.
3. Llama a `emailJob.execute(context)`.

### Paso 5 — `EmailJob.execute(...)`

- Extrae `to`, `subject`, `body` del `JobDataMap` que viajó desde el `SchedulerService`.
- Llama a `emailService.send(to, subject, body)`.
- Si algo falla, lanza `JobExecutionException` para que Quartz registre el fallo (y decida si reintenta, según cómo la lances).

### Paso 6 — `EmailService.send(...)`

- Usa el `JavaMailSender` (autoconfigurado por Spring Boot con los datos de `application.yml` / `.env.*`) para mandar el correo real por SMTP. `EmailService` también expone `sendHtml(...)` y `sendHtmlWithAttachment(...)` para correos en HTML y con adjuntos.

**Resumen visual del recorrido:**

```
HTTP POST /api/jobs
   → JobController.schedule()
      → SchedulerService.scheduleCronJob()
         → Quartz guarda JobDetail + Trigger en la BD (QRTZ_*)
            → [... llega la hora programada ...]
               → QuartzExecutionListener mete jobKey/traceId en el log
                  → Quartz instancia EmailJob (vía SpringBeanJobFactory)
                     → EmailJob.execute()
                        → EmailService.send()
                           → Email enviado por SMTP ✅
```

### Caso real: `WooCommerceAuthJob` + `WooCommerceDataSyncJob`

Son **dos jobs separados, encadenados por el token cacheado**, no uno solo:

**1) `WooCommerceAuthJob`** (corre cada 2h, por ejemplo):
```json
{
  "jobName": "woocommerce-auth-prod",
  "jobGroup": "SYNC",
  "jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceAuthJob",
  "cronExpression": "0 0 */2 * * ?",
  "data": {
    "loginUrl": "https://api.ejemplo.com/auth/login",
    "syncLoginUrl": "https://api.ejemplo.com/sync/login",
    "skusUrl": "https://api.ejemplo.com/sync/get-skus",
    "username": "TU_USUARIO",
    "password": "TU_PASSWORD",
    "adminEmail": "admin@tuempresa.com",
    "tokenKey": "woocommerce-default",
    "maxErrors": "5"
  }
}
```

**2) `WooCommerceDataSyncJob`** (corre cada 15 min, **debe tener el mismo `tokenKey`** que el job anterior):
```json
{
  "jobName": "woocommerce-datasync-prod",
  "jobGroup": "SYNC",
  "jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceDataSyncJob",
  "cronExpression": "0 */15 * * * ?",
  "data": {
    "syncMongoUrl": "https://api.ejemplo.com/sync/syncMongo",
    "sendWebUrl": "https://api.ejemplo.com/sync/sendWeb",
    "sendWebExchangeRateUrl": "https://api.ejemplo.com/sync/sendWebExchangeRate",
    "sendCleanCacheUrl": "https://api.ejemplo.com/sync/sendCleanCache",
    "adminEmail": "admin@tuempresa.com",
    "tokenKey": "woocommerce-default",
    "maxErrors": "5"
  }
}
```

Si `WooCommerceDataSyncJob` no encuentra un token válido en caché (porque `WooCommerceAuthJob` nunca corrió con el mismo `tokenKey`, o el token venció), **falla de inmediato con un mensaje claro** — nunca intenta loguearse por su cuenta.

> ⚠️ **Nota de seguridad:** el `username`/`password` de `WooCommerceAuthJob` viajan en el body del `POST` y quedan guardados tal cual en la tabla `QRTZ_JOB_DETAILS` (JobDataMap serializado). No expongas `/api/jobs` sin autenticación en un entorno accesible desde fuera, y considera rotar estas credenciales si alguna vez se compartieron en texto plano (chat, ticket, repo, etc.).

---

## 5. La API REST completa

Todos los endpoints de jobs cuelgan de `/api/jobs`. Hay además una API interna de logs, ver sección 7.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/jobs` | Crea y programa un job nuevo (cron o intervalo) |
| `GET` | `/api/jobs` | Lista todos los jobs registrados con su estado |
| `GET` | `/api/jobs/{group}/{name}` | Detalle de un job concreto (404 si no existe) |
| `POST` | `/api/jobs/{group}/{name}/pause` | Pausa el job (no se borra) |
| `POST` | `/api/jobs/{group}/{name}/resume` | Reanuda un job pausado |
| `POST` | `/api/jobs/{group}/{name}/trigger` | Lo ejecuta ya mismo, sin esperar su horario |
| `DELETE` | `/api/jobs/{group}/{name}` | Lo borra definitivamente de Quartz |
| `GET` | `/internal/logs` | Consulta logs con filtros (traceId, jobKey, level, etc.) — requiere header `X-Internal-Token`, ver sección 7 |

Las respuestas de comandos (`schedule`, `pause`, `resume`, `trigger`, `delete`) usan el DTO `ApiResponse` (`status`, `message`, `job`, `error` — los campos que no aplican salen explícitos como `null`).

### Dos formas de programar un job (en el `POST /api/jobs`)

**A) Cron — horario fijo:**
```json
{ "jobName": "X", "jobGroup": "G", "jobClass": "...", "cronExpression": "0 0 8 * * ?" }
```

**B) Intervalo simple — cada N milisegundos:**
```json
{ "jobName": "X", "jobGroup": "G", "jobClass": "...", "intervalMs": 3600000 }
```

Si no envías ninguno de los dos, se usa un intervalo por defecto de 60 segundos.

### Formato de `cronExpression` (Quartz, no Unix cron)

⚠️ Ojo: el cron de Quartz **no** es el cron de Unix/Linux. Tiene un campo extra de **segundos** al inicio, y el campo de **año** es opcional al final:

```
segundo minuto hora día-del-mes mes día-de-la-semana [año]
   0       0     8       *        *         ?
```

| Campo | Valores permitidos | Notas |
|---|---|---|
| Segundo | `0-59` | |
| Minuto | `0-59` | |
| Hora | `0-23` | |
| Día del mes | `1-31` | usa `?` si vas a especificar día de la semana |
| Mes | `1-12` o `JAN-DEC` | |
| Día de la semana | `1-7` o `SUN-SAT` | usa `?` si vas a especificar día del mes |
| Año | `1970-2099` | opcional |

**Regla clave:** día-del-mes y día-de-la-semana **no pueden llevar `*` los dos a la vez** — exactamente uno de los dos debe ser `?`.

### Tabla de horarios de ejemplo

| Quiero que se ejecute... | `cronExpression` |
|---|---|
| Cada 15 segundos | `0/15 * * * * ?` |
| Cada minuto, en el segundo 0 | `0 * * * * ?` |
| Cada 5 minutos | `0 0/5 * * * ?` |
| Cada 15 minutos | `0 0/15 * * * ?` |
| Cada hora, en punto | `0 0 * * * ?` |
| Todos los días a las 8:00 AM | `0 0 8 * * ?` |
| Todos los días a las 8:30 PM | `0 30 20 * * ?` |
| Todos los días a medianoche | `0 0 0 * * ?` |
| De lunes a viernes a las 9:00 AM | `0 0 9 ? * MON-FRI` |
| Solo los sábados a las 10:00 AM | `0 0 10 ? * SAT` |
| El primer día de cada mes a las 6:00 AM | `0 0 6 1 * ?` |
| El último día de cada mes a las 11:00 PM | `0 0 23 L * ?` |
| Cada 1 de enero a medianoche (anual) | `0 0 0 1 1 ?` |
| Cada 30 minutos, solo en horario laboral (9am-6pm), de lunes a viernes | `0 0/30 9-18 ? * MON-FRI` |

> 💡 Tip: para probar rápido si una expresión cron es válida antes de mandarla, puedes usar cualquier validador de "Quartz cron expression" online, o simplemente mandar el `POST` contra tu `dev` local — si el cron está mal formado, `JobController` responde `400 Bad Request` con el `ApiResponse.error(...)` de Quartz.

---

## 6. Configuración: perfiles (`dev` / `pre` / `prod`) + `.env.*` por perfil

El proyecto tiene **tres perfiles de Spring** (`dev`, `pre`, `prod`), cada uno con su propio fichero de variables de entorno. Esto evita mezclar secretos con configuración de código y permite cambiar de base de datos, correo, etc. sin tocar Java.

```
.env.dev                → variables para el perfil dev
.env.pre                → variables para el perfil pre
.env.prod               → variables para el perfil prod
.env.example             → plantilla sin secretos; sí se versiona, sirve de referencia
application.yml         → config común: nombre app, Quartz, mail, actuator, logging, API interna de logs
application-dev.yml     → override para desarrollo: base de datos H2
application-pre.yml     → override para staging: base de datos MariaDB/MySQL (BD de pre)
application-prod.yml    → override para producción: base de datos MariaDB/MySQL (BD de prod)
```

### ¿Cómo se elige el perfil?

En `application.yml`:
```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

Esto significa: *"lee la variable de entorno `SPRING_PROFILES_ACTIVE`; si no existe, usa `dev` por defecto"*. Spring Boot entonces carga automáticamente `application-dev.yml`, `application-pre.yml` o `application-prod.yml` **encima** de `application.yml`, sobrescribiendo solo lo que cada perfil define (básicamente el `datasource`).

### Dos formas de correr el proyecto — y cómo elige el `.env.*` cada una

**A) Con Docker (recomendado para pre/prod, ver sección 8)** — no hay ninguna magia de "detectar" el `.env`: se lo pasas explícito con `--env-file` al comando:
```bash
docker compose --env-file .env.pre up -d
```

**B) Sin Docker, desde el IDE o `mvnw`** — usa `spring-dotenv`, que por defecto busca un archivo llamado `.env` a secas (no `.env.dev`). Para que lea `.env.pre`/`.env.prod` hay que decírselo explícito con la variable de entorno `SPRINGDOTENV_FILENAME`:

| Entorno | `SPRING_PROFILES_ACTIVE` | `SPRINGDOTENV_FILENAME` |
|---|---|---|
| dev  | `dev`  | `.env.dev` |
| pre  | `pre`  | `.env.pre` |
| prod | `prod` | `.env.prod` |

En IntelliJ esto se configura creando **una Run Configuration por perfil**, cada una con su propio `Active profiles` y su propia variable de entorno `SPRINGDOTENV_FILENAME` en *Modify options → Environment variables*. (Ver `ENV_configuracion_jetbrians.md` para el paso a paso completo con capturas del diálogo.)

**⚠️ Importante:** `spring.profiles.active` **no** elige el `.env` — son dos mecanismos independientes. Configurar solo uno de los dos es el error más común al levantar un perfil que no sea `dev`.

### ¿Por qué separar en `.env.*`?

Ningún dato sensible (contraseñas de BD, contraseña del correo) vive escrito dentro de los `.yml`. En su lugar, los `.yml` usan `${VARIABLE:valor_por_defecto}`, y el valor real se inyecta desde variables de entorno. Ningún `.env.*` real se sube al repositorio (están en `.gitignore`); solo `.env.example` se versiona, como plantilla sin secretos.

```bash
cp .env.example .env.dev
cp .env.example .env.pre
cp .env.example .env.prod
# edita cada uno con sus valores reales
```

### Tabla de variables de entorno (resumen)

| Variable | Dónde se usa | Ejemplo |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `application.yml` | `dev`, `pre` o `prod` |
| `SERVER_PORT` | `application.yml` (fuera de Docker) | `8080` |
| `HOST_PORT` | `docker-compose.yml` (solo con Docker) | `7080` en pre, `8080`/`8082` en dev/prod |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `application-pre.yml` / `application-prod.yml` | `localhost` (sin Docker) o `mariadb` (forzado dentro de Docker) |
| `DB_USERNAME`, `DB_PASSWORD` | `application-pre.yml` / `application-prod.yml` | tu usuario/clave de MariaDB/MySQL |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | `application.yml` | `smtp.gmail.com` / Mailtrap |
| `LOG_LEVEL` | `application.yml` | `INFO`, `DEBUG` |
| `QUARTZ_THREAD_COUNT` | `application.yml` | `5` |
| `QUARTZ_INIT_SCHEMA` | `application.yml` | `never` (normal) / `always` (solo bootstrap de BD nueva, ver sección 9) |
| `INTERNAL_API_TOKEN` | `application.yml` | token para `GET /internal/logs`, ver sección 7 |
| `IMAGE_TAG`, `REGISTRY`, `IMAGE_NAME` | `docker-compose.yml` (solo con Docker) | `dev`/`pre`/`prod` |

> 💡 Si usas Gmail para `MAIL_*`, genera una **contraseña de aplicación** dedicada (https://myaccount.google.com/apppasswords) en vez de tu contraseña normal de la cuenta.

### Dev vs Pre vs Prod, en una tabla

| | Dev (`application-dev.yml`) | Pre (`application-pre.yml`) | Prod (`application-prod.yml`) |
|---|---|---|---|
| Base de datos | H2 embebida en fichero (`./data/quartzjobs`) con `AUTO_SERVER=TRUE` | MariaDB/MySQL de staging | MariaDB/MySQL de producción |
| Consola H2 | Habilitada en `/h2-console` | Deshabilitada | Deshabilitada |
| `show-sql` | `true` (ves las queries en consola) | `false` | `false` |
| Log de `com.quartzjobs` | `DEBUG` | `INFO` | `INFO` |
| Formato de log (`logback-spring.xml`) | Consola legible con colores | JSON estructurado | JSON estructurado |

**`AUTO_SERVER=TRUE` en dev:** permite conectarte a la misma base H2 con DBeaver o la consola web **mientras la app sigue corriendo**, sin el clásico error `Database may be already in use`. Ver `H2_dbbeaver_conexion.md` para el paso a paso.

### `StartupConfigPrinter`: verifica qué configuración quedó realmente activa

Al arrancar, `JobsApplication` registra un listener (`StartupConfigPrinter`) que imprime en consola un resumen de la configuración efectiva: perfil activo, datasource, Quartz, mail, servidor, Swagger, Actuator y logging. Los valores sensibles se muestran parcialmente ocultos. Es la forma más rápida de confirmar que arrancaste con el `.env.*` correcto.

---

## 7. Logging estructurado, trazabilidad y consulta remota

Todo el logging técnico sale en **JSON estructurado** en `pre`/`prod` (vía Logback + `logstash-logback-encoder`), no texto plano — pensado para Grafana/Loki/ELK o consulta directa vía la API interna. Guía completa y detallada: **`readme_logging.md`** y **`readme_logging_query.md`**. Resumen:

### El núcleo: `LogContext` (100% portable, sin dependencias de framework)

Pone/quita valores del MDC de SLF4J. El resto del código (controllers, services, jobs) usa su `Logger` normal — el `traceId`/`jobKey` aparecen solos en el JSON, puestos por los "adapters":

| Adapter | Qué hace |
|---|---|
| `CorrelationIdFilter` | `traceId` para requests HTTP entrantes, traza también el response (status, duración) |
| `QuartzExecutionListener` + `QuartzLoggingConfig` | `jobKey`/`jobGroup`/`traceId` automático para **todos** los jobs de Quartz, sin tocar cada clase |
| `LoggingClientHttpRequestInterceptor` | Traza las llamadas HTTP **salientes** (`RestTemplate`, ej. hacia la API de WooCommerce), con el mismo `traceId` del job que las originó |

Con esto, buscando por un solo `traceId` ves de un tirón: el request HTTP → el job que disparó → cada llamada saliente que hizo — todo amarrado.

### `GET /internal/logs` — consultar logs sin acceso directo al servidor

Protegido por `InternalApiTokenFilter` (header `X-Internal-Token`, variable `INTERNAL_API_TOKEN`). Lee `logs/quartz-jobs.log` línea por línea (sin cargar el archivo entero en memoria), con límite duro de resultados.

```bash
curl -H "X-Internal-Token: TU_TOKEN" \
  "http://localhost:8080/internal/logs?jobKey=woocommerce.dataSyncJob&level=ERROR&limit=50"

# El caso más útil: todo lo de un flujo específico, con un solo traceId
curl -H "X-Internal-Token: TU_TOKEN" \
  "http://localhost:8080/internal/logs?traceId=a1b2c3d4-..."
```

Parámetros: `traceId`, `jobKey`, `jobGroup`, `level`, `source` (`http`/`job`), `message` (substring), `from`/`to` (rango ISO-8601), `limit` — todos combinables con coma (OR dentro del campo) y AND entre campos.

### El log de negocio a fichero (`writeLog(...)`) — con rotación

`AbstractWooCommerceJob.writeLog(...)` sigue existiendo como auditoría legible por humanos aparte del log técnico, pero ya no crece sin límite: usa `FileManagerUtil.appendLineWithRotation(...)`, que rota el fichero por tamaño automáticamente.

---

## 8. Docker

### `Dockerfile` — build multi-stage

Compila en una imagen pesada (`maven:3.9.9-eclipse-temurin-21`) y corre en una liviana (`eclipse-temurin:21-jre-alpine`), como usuario no-root. El puerto interno del contenedor **siempre es 8080, fijo**, en los 3 ambientes — el puerto "visible desde afuera" se resuelve en `docker-compose.yml`, no acá.

### `docker-compose.yml` — un solo fichero para los 3 ambientes

Levanta `quartz-jobs` + `mariadb` + `phpmyadmin`, en una red (`quartz-net`) y volumen (`mariadb-data`) con nombre explícito por ambiente (`jobs-dev-*`, `jobs-pre-*`, `jobs-prod-*` — nunca comparten datos entre sí). Ninguna variable queda quemada en el YAML: todo se resuelve con `${VAR}` desde el `.env.*` que le pases.

El servicio `quartz-jobs` tiene **`image:` y `build:` a la vez** — según el comando, se comporta distinto:

```bash
# Local: construye desde el Dockerfile
docker compose --env-file .env.dev build
docker compose --env-file .env.dev up -d

# pre/prod (VPS): descarga la imagen ya construida por CI, NUNCA compila ahí
docker compose --env-file .env.pre pull
docker compose --env-file .env.pre up -d
```

`IMAGE_TAG` (`dev`/`pre`/`prod`, en cada `.env.*`) decide qué tag de `ghcr.io/.../quartz-jobs` se usa.

### Bootstrap de una base de datos nueva

Un contenedor de MariaDB recién creado no tiene las tablas `QRTZ_*`. Corre **una sola vez** con el bootstrap activado, después vuelve a `never` (dejarlo en `always` de forma permanente **dropea y recrea** las tablas en cada reinicio, perdiendo todos los jobs):

```bash
QUARTZ_INIT_SCHEMA=always docker compose --env-file .env.pre up
```

Guía completa de comandos: **`tutorial_deploy.md`**.

---

## 9. CI/CD — GitHub Actions ("build once, promote everywhere")

Tres workflows en `.github/workflows/`:

| Workflow | Se dispara | Qué hace |
|---|---|---|
| `build.yml` | Push a `develop` (o manual) | Compila, testea, corre MegaLinter, construye **una sola** imagen Docker, la publica en GHCR con tags `dev-<sha>` (inmutable) y `dev`, la escanea con Trivy, y levanta la imagen real + MariaDB para un **integration test** contra un endpoint |
| `promote_pre.yml` | Manual (workflow_dispatch, con el SHA de `dev-<sha>`) | Re-etiqueta esa misma imagen (mismo digest, **sin recompilar**) como `pre` y `pre-<sha>` |
| `promote_prod.yml` | Manual (con el SHA ya validado en pre + una versión semántica) | Re-etiqueta como `prod` y `<version>` (ej. `v1.4.2`) |

La imagen que corre en producción es, **byte a byte**, la misma que se compiló una vez en `develop` — nunca se recompila entre `dev` → `pre` → `prod`, solo se re-etiqueta el mismo digest en el registro. Esto elimina el clásico "funcionaba en pre pero en prod se comporta distinto porque se compiló de nuevo".

---

## 10. Calidad de código — MegaLinter, Spotless, Checkstyle, PMD

Guía completa y detallada: **`MEGALINTER-GUIA.md`**. Resumen de comandos del día a día:

```bash
# Formatea Java + YAML + Markdown + JSON automáticamente (Spotless, con Prettier por dentro para JSON)
mvn spotless:apply

# Verifica sin tocar nada (esto corre solo en la fase "verify")
mvn spotless:check
mvn verify

# Correr MegaLinter localmente, igual que en CI (un solo .mega-linter.yml para ambos)
docker run --rm -v $(pwd):/tmp/lint:rw -e GIT_SAFE_DIRECTORY=/tmp/lint -e APPLY_FIXES=none ghcr.io/oxsecurity/megalinter-java:v9
```

`checkstyle.xml`/`pmd-ruleset.xml` (en `.github/linters/`) solo bloquean como error los hallazgos de **prioridad alta/real** (recursos sin cerrar, bugs, etc.) — el resto de reglas de estilo puro quedan como informativas, visibles en el reporte pero sin tumbar el build.

---

## 11. Extras que trae el `pom.xml`

- **Actuator** (`/actuator/health`, `/actuator/info`) → monitoreo básico de salud, usado por los health checks de `docker-compose.yml` y del pipeline de deploy.
- **Springdoc OpenAPI** → Swagger UI automático en `/swagger-ui.html`.
- **Validation** → disponible para `@NotBlank`/`@NotNull` en `ScheduleRequest`.
- **`RestTemplate`** (vía `RestTemplateConfig`) → timeouts preconfigurados + el interceptor de logging de llamadas salientes ya enganchado.
- **`spring-dotenv`** → carga variables desde el `.env.*` correspondiente (solo relevante corriendo sin Docker, ver sección 6).
- **`logstash-logback-encoder`** → serializa los logs a JSON estructurado en `pre`/`prod`.
- **`spotless-maven-plugin`** → formato de código automático (Java, YAML, Markdown, JSON), ver sección 10.

---

## 12. ¿Cómo añadir un Job nuevo? (guía rápida)

1. Crea una clase en `com.quartzjobs.jobs` que implemente `Job`:
   ```java
   @Component
   @DisallowConcurrentExecution
   public class MiNuevoJob implements Job {
       @Autowired
       private MiServicio miServicio;

       @Override
       public void execute(JobExecutionContext context) throws JobExecutionException {
           miServicio.hacerAlgo();
       }
   }
   ```
2. (Opcional) Crea el `Service` correspondiente en `com.quartzjobs.service` con la lógica real.
3. Prográmalo sin tocar ni una línea del controlador:
   ```json
   { "jobName": "Nuevo", "jobGroup": "MISC", "jobClass": "com.quartzjobs.jobs.MiNuevoJob", "intervalMs": 60000 }
   ```

**`@DisallowConcurrentExecution`**: ponla siempre salvo que necesites explícitamente que dos ejecuciones del mismo job puedan solaparse.

---

## 13. Arrancar el proyecto en local

### Sin Docker

```bash
cp .env.example .env.dev              # completa tus valores
./mvnw spring-boot:run                # perfil "dev" por defecto → H2, sin instalar nada más

curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"jobName":"Prueba","jobGroup":"TEST","jobClass":"com.quartzjobs.jobs.ReportJob","intervalMs":30000}'

curl http://localhost:8080/api/jobs        # ver todos los jobs
```

### Con Docker (recomendado para probar como en pre/prod)

```bash
cp .env.example .env.dev
docker compose --env-file .env.dev build
QUARTZ_INIT_SCHEMA=always docker compose --env-file .env.dev up   # solo la 1ra vez, BD nueva
```

Revisa la salida de `StartupConfigPrinter` al arrancar para confirmar que quedó todo bien apuntado.

---

## 14. Crear el usuario y la base de datos en MariaDB/MySQL (fuera de Docker)

Solo si corres MariaDB/MySQL directo, sin Docker (con Docker, `docker-compose.yml` ya crea usuario/BD automáticamente vía `MARIADB_USER`/`MARIADB_DATABASE`, ver sección 8):

```sql
CREATE USER 'quartzjobs_pre'@'%' IDENTIFIED WITH sha256_password BY 'TU_CONTRASEÑA_AQUI';
GRANT USAGE ON *.* TO 'quartzjobs_pre'@'%';
ALTER USER 'quartzjobs_pre'@'%' REQUIRE NONE WITH MAX_QUERIES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_USER_CONNECTIONS 0;
CREATE DATABASE IF NOT EXISTS `quartzjobs_pre`;
GRANT ALL PRIVILEGES ON `quartzjobs_pre`.* TO 'quartzjobs_pre'@'%';
FLUSH PRIVILEGES;
```
(repite con `_prod` para producción, y una contraseña **distinta** — nunca reutilices la misma entre ambientes)

---

## 15. Tutoriales relacionados en este repo

- **`ENV_configuracion_jetbrians.md`** → las 3 Run Configurations en IntelliJ (una por perfil).
- **`H2_dbbeaver_conexion.md`** → conectar DBeaver/consola web a la base de `dev` en caliente.
- **`readme_logging.md`** / **`readme_logging_query.md`** → sistema de logging estructurado y `/internal/logs`, en detalle.
- **`tutorial_deploy.md`** → todos los comandos de Docker Compose por ambiente.
- **`MEGALINTER-GUIA.md`** → configuración y comandos de MegaLinter/Spotless/Checkstyle/PMD.
- **`Instalacion_java_jdk_21.md`** → instalar JDK 21 + Maven en un Ubuntu Server nuevo.
