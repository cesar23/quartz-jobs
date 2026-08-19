# 📚 Tutorial completo — Quartz Jobs

> Guía de arquitectura y flujo de código para cualquier programador que se una al proyecto.
> Explica **qué hace la aplicación**, **cómo están organizados los ficheros** y **cómo viaja una petición** desde que entra por HTTP hasta que Quartz ejecuta un job real.

---

## 1. ¿Qué es esta aplicación?

**Quartz Jobs** es un servicio Spring Boot que expone una **API REST para crear, pausar, reanudar, disparar y eliminar tareas programadas (jobs)**, usando [Quartz Scheduler](https://www.quartz-scheduler.org/) como motor de programación.

En lugar de tener jobs "fijos" definidos en código (como haría `@Scheduled`), esta aplicación permite **crear jobs dinámicamente en tiempo de ejecución**, vía HTTP:

- Puedes decir "ejecuta la clase `EmailJob` todos los días a las 8 AM" sin recompilar nada.
- Los jobs y sus horarios se guardan en una base de datos (tablas `QRTZ_*`), así que **sobreviven a un reinicio del servidor**.
- Puedes pausar, reanudar, disparar manualmente o borrar cualquier job en caliente.

### Jobs que ya trae el proyecto

| Job | Qué hace | Servicio(s) que usa |
|---|---|---|
| `EmailJob` | Envía un correo (asunto + cuerpo) | `EmailService` |
| `FileWriterJob` | Escribe una línea con fecha/hora en un fichero | `FileWriterService` |
| `ReportJob` | Plantilla vacía de ejemplo (espera 500ms y loguea) | — |
| `WooCommerceSyncJob` | Job real de producción: hace login contra una API externa y sincroniza SKUs / stock / precios / tipo de cambio con WooCommerce, limpiando caché al final. Loguea a fichero, cuenta errores y avisa por email si se supera el máximo de errores permitido | `RestTemplate`, `EmailService`, `FileWriterService`, `SyncErrorTracker` |

`SyncErrorTracker` no es un job — es un componente auxiliar (contador en memoria, thread-safe) que usan los jobs de sincronización para saber cuántos errores consecutivos llevan y decidir cuándo avisar por correo.

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

Como el `JobStore` es JDBC, **todos los jobs y triggers se guardan en la base de datos** (H2 en `dev`, MySQL en `pre`/`prod`), no solo en memoria. Por eso, si reinicias la app, los jobs programados siguen ahí.

---

## 3. Mapa de ficheros del proyecto

```
quartz-jobs/
├── pom.xml                          # Dependencias Maven
├── .env                             # Variables para perfil dev (NUNCA se sube al repo)
├── .env.pre                         # Variables para perfil pre (NUNCA se sube al repo)
├── .env.prod                        # Variables para perfil prod (NUNCA se sube al repo)
├── .env.example                     # Plantilla sin secretos, sí se versiona
├── .gitignore
└── src/main/
    ├── resources/
    │   ├── application.yml          # Config común a todos los perfiles
    │   ├── application-dev.yml      # Perfil dev  → H2 (AUTO_SERVER=TRUE)
    │   ├── application-pre.yml      # Perfil pre  → MySQL (staging)
    │   └── application-prod.yml     # Perfil prod → MySQL (producción)
    └── java/com/quartzjobs/
        ├── JobsApplication.java          # Punto de entrada (main)
        │
        ├── config/
        │   ├── QuartzConfig.java         # Conecta Quartz con el contenedor de Spring
        │   ├── RestTemplateConfig.java    # RestTemplate con timeouts (usado por jobs que llaman APIs externas)
        │   └── StartupConfigPrinter.java  # Imprime en consola, al arrancar, qué config/perfil/variables quedaron activas (oculta secretos)
        │
        ├── controller/
        │   └── JobController.java   # API REST: /api/jobs/**
        │
        ├── dto/
        │   └── ScheduleRequest.java # "Formulario" que llega en el body del POST
        │
        ├── jobs/
        │   ├── EmailJob.java             # Tarea: enviar email
        │   ├── FileWriterJob.java        # Tarea: escribir en fichero
        │   ├── ReportJob.java            # Tarea: plantilla/ejemplo
        │   ├── WooCommerceSyncJob.java   # Tarea real: sincronización con WooCommerce
        │   └── SyncErrorTracker.java     # Contador de errores en memoria (no es un Job)
        │
        └── service/
            ├── SchedulerService.java    # Habla directamente con el Scheduler de Quartz
            ├── EmailService.java        # Envío de correo: texto plano, HTML y HTML con adjuntos
            └── FileWriterService.java   # Lógica de escritura en disco
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
    "jobName": "woocommerce-sync-prod",
    "jobGroup": "SYNC",
    "jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceSyncJob",
    "cronExpression": "0 */15 * * * ?",
    "data": {
        "loginUrl": "http://100.83.15.65:18080/api/v1/auth/login",
        "syncLoginUrl": "http://100.83.15.65:18080/api/v1/sync/login",
        "skusUrl": "http://100.83.15.65:18080/api/v1/sync/get-skus",
        "syncMongoUrl": "http://100.83.15.65:18080/api/v1/sync/syncMongo",
        "sendWebUrl": "http://100.83.15.65:18080/api/v1/sync/sendWeb",
        "sendWebExchangeRateUrl": "http://100.83.15.65:18080/api/v1/sync/sendWebExchangeRate",
        "sendCleanCacheUrl": "http://100.83.15.65:18080/api/v1/sync/sendCleanCache",
        "username": "perucaos@gmail.com",
        "password": "cesar203",
        "adminEmail": "admin@tuempresa.com",
        "maxErrors": "5",
        "logFilePath": "logs/woocommerce-sync-prod.txt"
    }
}
```

### Paso 2 — `JobController.schedule(...)`

- Deserializa el JSON en un `ScheduleRequest` (el DTO).
- Carga la clase del job dinámicamente: `Class.forName("com.quartzjobs.jobs.EmailJob")`.
  Esto es lo que permite añadir jobs nuevos **sin tocar el controlador**: solo creas la clase y la referencias por nombre completo en el JSON.
- Si viene `cronExpression`, llama a `schedulerService.scheduleCronJob(...)`; si no, usa `scheduleSimpleJob(...)` con el `intervalMs` (o 60s por defecto).
- Si algo falla (clase no existe, cron mal formado, etc.) devuelve un `400` o `500` con el mensaje de error — nunca deja que la excepción rompa la respuesta.

### Paso 3 — `SchedulerService.scheduleCronJob(...)`

- Construye un `JobDetail`: qué clase ejecutar (`EmailJob`), con qué nombre/grupo (`EmailDiario`/`EMAIL`) y qué datos lleva (`to`, `subject`, `body` empaquetados en un `JobDataMap`).
- Construye un `Trigger` tipo `CronTrigger` con la expresión `"0 0 8 * * ?"`.
- Llama a `scheduler().scheduleJob(jobDetail, trigger)` → esto es lo que **realmente registra el job en Quartz**, que a su vez lo persiste en las tablas `QRTZ_JOB_DETAILS` y `QRTZ_TRIGGERS` de la base de datos.

### Paso 4 — Espera... y llega la hora programada

Quartz, por su cuenta (sin que tu código haga nada), detecta que son las 8:00 AM y:

1. Instancia `EmailJob` — pero no con `new EmailJob()`, sino a través de `SpringBeanJobFactory` (definida en `QuartzConfig`), que le permite a Spring inyectar los `@Autowired` dentro del job.
2. Llama a `emailJob.execute(context)`.

### Paso 5 — `EmailJob.execute(...)`

- Extrae `to`, `subject`, `body` del `JobDataMap` que viajó desde el `SchedulerService`.
- Llama a `emailService.send(to, subject, body)`.
- Si algo falla, lanza `JobExecutionException` para que Quartz registre el fallo (y decida si reintenta, según cómo la lances).

### Paso 6 — `EmailService.send(...)`

- Usa el `JavaMailSender` (autoconfigurado por Spring Boot con los datos de `application.yml` / `.env`) para mandar el correo real por SMTP. `EmailService` también expone `sendHtml(...)` y `sendHtmlWithAttachment(...)` para correos en HTML y con adjuntos.

**Resumen visual del recorrido:**

```
HTTP POST /api/jobs
   → JobController.schedule()
      → SchedulerService.scheduleCronJob()
         → Quartz guarda JobDetail + Trigger en la BD (QRTZ_*)
            → [... llega la hora programada ...]
               → Quartz instancia EmailJob (vía SpringBeanJobFactory)
                  → EmailJob.execute()
                     → EmailService.send()
                        → Email enviado por SMTP ✅
```

### Caso real: `WooCommerceSyncJob`

El mismo flujo aplica al job de sincronización, solo que en vez de `to`/`subject`/`body`, el `data` del `POST /api/jobs` trae las URLs de la API externa (login, sync, SKUs, etc.), credenciales y configuración de reintentos/logging:

```json
{
  "jobName": "woocommerce-sync-prod",
  "jobGroup": "SYNC",
  "jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceSyncJob",
  "cronExpression": "0 */15 * * * ?",
  "data": {
    "loginUrl": "...",
    "syncLoginUrl": "...",
    "skusUrl": "...",
    "syncMongoUrl": "...",
    "sendWebUrl": "...",
    "sendWebExchangeRateUrl": "...",
    "sendCleanCacheUrl": "...",
    "username": "...",
    "password": "...",
    "adminEmail": "admin@tuempresa.com",
    "maxErrors": "5",
    "logFilePath": "logs/woocommerce-sync-prod.txt"
  }
}
```

Internamente usa `RestTemplate` (configurado con timeouts en `RestTemplateConfig`) para hablar con la API externa, `FileWriterService` para dejar rastro en `logFilePath`, `SyncErrorTracker` para contar fallos consecutivos y `EmailService` para avisar a `adminEmail` si se supera `maxErrors`.

> ⚠️ **Nota de seguridad:** el `username`/`password` de este job viajan en el body del `POST` y quedan guardados tal cual en la tabla `QRTZ_JOB_DETAILS` (JobDataMap serializado). No expongas `/api/jobs` sin autenticación en un entorno accesible desde fuera, y considera rotar estas credenciales si alguna vez se compartieron en texto plano (chat, ticket, repo, etc.).

---

## 5. La API REST completa

Todos los endpoints cuelgan de `/api/jobs`.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/api/jobs` | Crea y programa un job nuevo (cron o intervalo) |
| `GET` | `/api/jobs` | Lista todos los jobs registrados con su estado |
| `GET` | `/api/jobs/{group}/{name}` | Detalle de un job concreto (404 si no existe) |
| `POST` | `/api/jobs/{group}/{name}/pause` | Pausa el job (no se borra) |
| `POST` | `/api/jobs/{group}/{name}/resume` | Reanuda un job pausado |
| `POST` | `/api/jobs/{group}/{name}/trigger` | Lo ejecuta ya mismo, sin esperar su horario |
| `DELETE` | `/api/jobs/{group}/{name}` | Lo borra definitivamente de Quartz |

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

### Ejemplos completos de `POST /api/jobs` (formato Postman)

**A) Cada 15 minutos — sincronización recurrente:**
```postman_json
{
  "jobName": "woocommerce-sync-prod",
  "jobGroup": "SYNC",
  "jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceSyncJob",
  "cronExpression": "0 */15 * * * ?",
  "data": {
    "adminEmail": "admin@tuempresa.com",
    "maxErrors": "5",
    "logFilePath": "logs/woocommerce-sync-prod.txt"
  }
}
```

**B) Todos los días a las 8:00 AM — reporte diario por email:**
```postman_json
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

**C) De lunes a viernes a las 9:00 AM — solo días hábiles:**
```postman_json
{
  "jobName": "RecordatorioLaboral",
  "jobGroup": "EMAIL",
  "jobClass": "com.quartzjobs.jobs.EmailJob",
  "cronExpression": "0 0 9 ? * MON-FRI",
  "data": {
    "to": "equipo@empresa.com",
    "subject": "Buenos días",
    "body": "Recordatorio del día laboral"
  }
}
```

**D) El primer día de cada mes a las 6:00 AM — job mensual:**
```postman_json
{
  "jobName": "ReporteMensual",
  "jobGroup": "REPORTS",
  "jobClass": "com.quartzjobs.jobs.ReportJob",
  "cronExpression": "0 0 6 1 * ?"
}
```

**E) Cada 5 minutos, escribiendo en fichero:**
```postman_json
{
  "jobName": "HeartbeatLog",
  "jobGroup": "MISC",
  "jobClass": "com.quartzjobs.jobs.FileWriterJob",
  "cronExpression": "0 0/5 * * * ?",
  "data": {
    "filePath": "logs/heartbeat.txt",
    "extra": "heartbeat ok"
  }
}
```

> 💡 Tip: para probar rápido si una expresión cron es válida antes de mandarla, puedes usar cualquier validador de "Quartz cron expression" online, o simplemente mandar el `POST` contra tu `dev` local — si el cron está mal formado, `JobController` responde `400 Bad Request` con el mensaje de error de Quartz.

---

## 6. Configuración: perfiles (`dev` / `pre` / `prod`) + `.env` por perfil

El proyecto ya no tiene dos entornos, sino **tres perfiles de Spring** (`dev`, `pre`, `prod`), cada uno con su propio fichero de variables de entorno. Esto evita mezclar secretos con configuración de código y permite cambiar de base de datos, correo, etc. sin tocar Java.

```
.env                    → variables para el perfil dev (nombre por defecto que espera spring-dotenv)
.env.pre                → variables para el perfil pre
.env.prod               → variables para el perfil prod
.env.example            → plantilla sin secretos; sí se versiona, sirve de referencia
application.yml         → config común: nombre app, Quartz, mail, actuator, logging
application-dev.yml     → override para desarrollo: base de datos H2
application-pre.yml     → override para staging: base de datos MySQL (BD de pre)
application-prod.yml    → override para producción: base de datos MySQL (BD de prod)
```

### ¿Cómo se elige el perfil?

En `application.yml`:
```yaml
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
```

Esto significa: *"lee la variable de entorno `SPRING_PROFILES_ACTIVE`; si no existe, usa `dev` por defecto"*. Spring Boot entonces carga automáticamente `application-dev.yml`, `application-pre.yml` o `application-prod.yml` **encima** de `application.yml`, sobrescribiendo solo lo que cada perfil define (básicamente el `datasource`).

### ⚠️ Importante: `spring.profiles.active` NO elige el `.env`

Esto sorprende a quien es nuevo en el proyecto: **`spring-dotenv` no sabe qué perfil de Spring está activo**. Solo busca un archivo con nombre fijo (`.env` por defecto). Son dos mecanismos totalmente independientes:

| Mecanismo | Decide | Variable de control |
|---|---|---|
| Spring Profiles | Qué `application-{perfil}.yml` carga Spring | `SPRING_PROFILES_ACTIVE` |
| spring-dotenv | Qué archivo `.env*` se lee | `SPRINGDOTENV_FILENAME` |

Por eso hay que configurar **ambas** variables, a mano, para cada entorno que no sea `dev`:

| Entorno | `spring.profiles.active` | `SPRINGDOTENV_FILENAME` |
|---|---|---|
| dev  | `dev`  | *(no hace falta, `.env` es el default)* |
| pre  | `pre`  | `.env.pre` |
| prod | `prod` | `.env.prod` |

En IntelliJ esto se configura creando **una Run Configuration por perfil**, cada una con su propio `Active profiles` y su propia variable de entorno `SPRINGDOTENV_FILENAME` en *Modify options → Environment variables*. (Ver el tutorial `ENV_configuracion_jetbrians.md` para el paso a paso completo con capturas del diálogo.)

### ¿Por qué separar en `.env*`?

Ningún dato sensible (contraseñas de BD, contraseña del correo) vive escrito dentro de los `.yml`. En su lugar, los `.yml` usan `${VARIABLE:valor_por_defecto}`, y el valor real se inyecta desde variables de entorno cargadas por `spring-dotenv` desde el `.env*` correspondiente al perfil. Ningún `.env*` real se sube al repositorio (están en `.gitignore`); solo `.env.example` se versiona, como plantilla sin secretos.

```bash
cp .env.dev.example .env.dev         # perfil dev
cp .env.dev.example .env.dev.pre     # perfil pre
cp .env.dev.example .env.dev.prod    # perfil prod
# edita cada uno con sus valores reales
```

### Tabla de variables de entorno

| Variable | Dónde se usa | Ejemplo |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `application.yml` | `dev`, `pre` o `prod` |
| `SPRINGDOTENV_FILENAME` | Run Configuration / entorno del proceso | `.env.pre`, `.env.prod` |
| `SERVER_PORT` | `application.yml` | `8080` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `application-pre.yml` / `application-prod.yml` | `localhost`, `3306`, `quartzjobs_prod` |
| `DB_USERNAME`, `DB_PASSWORD` | `application-pre.yml` / `application-prod.yml` | tu usuario/clave de MySQL |
| `MAIL_HOST`, `MAIL_PORT` | `application.yml` | `smtp.gmail.com` / `sandbox.smtp.mailtrap.io` |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | `application.yml` | tu correo o usuario de Mailtrap + contraseña de aplicación |
| `LOG_LEVEL` | `application.yml` | `INFO`, `DEBUG` |
| `QUARTZ_THREAD_COUNT` | `application.yml` | `5` |

> 💡 Si usas Gmail para `MAIL_*`, genera una **contraseña de aplicación** dedicada (https://myaccount.google.com/apppasswords) en vez de tu contraseña normal de la cuenta — Gmail la exige y rechaza la contraseña normal con el error `534-5.7.9 Application-specific password required`.

### Dev vs Pre vs Prod, en una tabla

| | Dev (`application-dev.yml`) | Pre (`application-pre.yml`) | Prod (`application-prod.yml`) |
|---|---|---|---|
| Base de datos | H2 embebida en fichero (`./data/quartzjobs`) con `AUTO_SERVER=TRUE` | MySQL de staging | MySQL de producción |
| Consola H2 | Habilitada en `/h2-console` | Deshabilitada | Deshabilitada |
| `show-sql` | `true` (ves las queries en consola) | `false` | `false` |
| Log de `com.quartzjobs` | `DEBUG` | `INFO` | `INFO` |

**`AUTO_SERVER=TRUE` en dev:** permite conectarte a la misma base H2 con DBeaver o la consola web **mientras la app sigue corriendo**, sin el clásico error `Database may be already in use` / `The file is locked`. El primer proceso en abrir el archivo `.mv.db` (normalmente la app) levanta un mini-servidor TCP interno; los siguientes clientes hablan con ese servidor en vez de pelear por el archivo. No combines esto con `DB_CLOSE_ON_EXIT=FALSE`: H2 no soporta ambos flags juntos. Ver el tutorial `H2_dbbeaver_conexion.md` para el paso a paso de conexión con la consola web y con DBeaver.

### `StartupConfigPrinter`: verifica qué configuración quedó realmente activa

Al arrancar, `JobsApplication` registra un listener (`StartupConfigPrinter`) que imprime en consola un resumen de la configuración efectiva: perfil activo, datasource, Quartz, mail, servidor, Swagger, Actuator y logging. Los valores sensibles (`spring.datasource.password`, `spring.mail.password`) se muestran parcialmente ocultos (solo los primeros 4 caracteres + `****`). Es la forma más rápida de confirmar, por ejemplo, que arrancaste con el `.env.pre` correcto y no terminaste apuntando por error a la base de `dev`.

---

## 7. Extras que trae el `pom.xml`

- **Actuator** (`/actuator/health`, `/actuator/info`) → para monitoreo básico de salud del servicio.
- **Springdoc OpenAPI** → genera Swagger UI automáticamente en `/swagger-ui.html`, documentando los endpoints REST sin esfuerzo extra.
- **Validation** → disponible para añadir anotaciones `@NotBlank`, `@NotNull`, etc. en `ScheduleRequest` si en el futuro quieres validar la entrada antes de llegar al controlador.
- **`RestTemplate` (vía `RestTemplateConfig`)** → bean con timeouts de conexión (5s) y lectura (10s) preconfigurados, usado por jobs que llaman APIs externas (como `WooCommerceSyncJob`).
- **`springboot3-dotenv`** → carga variables desde `.env*` automáticamente al arrancar; ver sección 6 para cómo elegir el fichero correcto por perfil.

---

## 8. ¿Cómo añadir un Job nuevo? (guía rápida)

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

**`@DisallowConcurrentExecution`**: ponla siempre salvo que necesites explícitamente que dos ejecuciones del mismo job puedan solaparse. Evita que una segunda ejecución arranque mientras la anterior sigue corriendo.

---

## 9. Arrancar el proyecto en local

```bash
cp .env.dev.example .env.dev          # completa tus valores
mvn spring-boot:run           # perfil "dev" por defecto → H2, sin instalar nada más

# Probar:
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"jobName":"Prueba","jobGroup":"TEST","jobClass":"com.quartzjobs.jobs.ReportJob","intervalMs":30000}'

curl http://localhost:8080/api/jobs        # ver todos los jobs
```

Para `pre` o `prod`, define `SPRING_PROFILES_ACTIVE` (`pre` o `prod`) junto con `SPRINGDOTENV_FILENAME` (`.env.pre` o `.env.prod`) y las variables `DB_HOST`, `DB_USERNAME`, `DB_PASSWORD` de MySQL. El mismo `mvn spring-boot:run` (o el `.jar` empaquetado) usará MySQL automáticamente. Revisa la salida de `StartupConfigPrinter` al arrancar para confirmar que quedó todo bien apuntado.

---

## 10. Crear el usuario y la base de datos en MySQL

Este comando crea el usuario, la base de datos y la contraseña para `pre` o `prod` (reemplaza `TU_CONTRASEÑA_AQUI` por una contraseña real y guárdala solo en el `.env*` correspondiente, nunca en el repo):

```sql
-- ========================= Para PRE
CREATE USER 'quartzjobs_pre'@'%' IDENTIFIED WITH sha256_password BY 'TU_CONTRASEÑA_AQUI';
GRANT USAGE ON *.* TO 'quartzjobs_pre'@'%';
ALTER USER 'quartzjobs_pre'@'%' REQUIRE NONE WITH MAX_QUERIES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_USER_CONNECTIONS 0;
CREATE DATABASE IF NOT EXISTS `quartzjobs_pre`;
GRANT ALL PRIVILEGES ON `quartzjobs_pre`.* TO 'quartzjobs_pre'@'%';
FLUSH PRIVILEGES;

-- ========================= Para PROD
CREATE USER 'quartzjobs_prod'@'%' IDENTIFIED WITH sha256_password BY 'TU_CONTRASEÑA_AQUI';
GRANT USAGE ON *.* TO 'quartzjobs_prod'@'%';
ALTER USER 'quartzjobs_prod'@'%' REQUIRE NONE WITH MAX_QUERIES_PER_HOUR 0 MAX_CONNECTIONS_PER_HOUR 0 MAX_UPDATES_PER_HOUR 0 MAX_USER_CONNECTIONS 0;
CREATE DATABASE IF NOT EXISTS `quartzjobs_prod`;
GRANT ALL PRIVILEGES ON `quartzjobs_prod`.* TO 'quartzjobs_prod'@'%';
FLUSH PRIVILEGES;
```

---

## 11. Tutoriales relacionados en este repo

- **`ENV_configuracion_jetbrians.md`** → cómo crear las 3 Run Configurations en IntelliJ (una por perfil) con su `SPRINGDOTENV_FILENAME` correspondiente.
- **`H2_dbbeaver_conexion.md`** → cómo conectar la consola web de H2 y DBeaver a la base de `dev` mientras la app sigue corriendo, usando `AUTO_SERVER=TRUE`.
