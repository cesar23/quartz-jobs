# Sistema de logging — guía completa y actualizada

Todo lo que se armó para trazabilidad estructurada, consulta remota y logs
de negocio con rotación. Reemplaza cualquier README anterior sobre este
tema — este es el que refleja el estado actual del código.

## 1. Mapa de archivos

```
util/log/core/
└── LogContext.java                    portable a CUALQUIER proyecto Java (solo slf4j-api)

util/log/web/
├── CorrelationIdFilter.java           traceId para requests HTTP entrantes (+ log de response)
└── InternalApiTokenFilter.java        protege /internal/** con header X-Internal-Token

util/log/quartz/
├── QuartzExecutionListener.java       jobKey/traceId automático para TODOS los jobs de Quartz
└── QuartzLoggingConfig.java           registra el listener anterior en el Scheduler

util/log/httpclient/
└── LoggingClientHttpRequestInterceptor.java   traza llamadas HTTP SALIENTES (RestTemplate)

util/log/query/
├── LogEntry.java                      whitelist de campos que se exponen por la API de consulta
├── LogFilter.java                     agrupa los criterios de filtrado (multi-valor + rango de fechas)
├── LogQueryService.java               lee/filtra el archivo de log, con límites duros
└── LogQueryController.java            GET /internal/logs

config/
└── RouteListPrinter.java              lista de rutas al arrancar: tabla ANSI en dev, JSON estructurado en pre/prod

util/CurlLogger.java                   genera el cURL equivalente de un request (debug)
util/FileManagerUtil.java              cat/tail/rm + escritura de logs de negocio CON ROTACIÓN
```

**Ya NO existen / hay que borrar si siguen en el proyecto:** `ActuatorTokenFilter.java`,
`RotatingFileWriter.java`, `FileWriterService`, `GeneralLogHelper.java`,
`DbPathUtil.java` — detalle de cada uno en la sección 9.

---

## 2. Los dos sistemas de logging que conviven en el proyecto (y por qué)

| | **Log técnico estructurado** | **Log de negocio por fichero** |
|---|---|---|
| Qué es | `log.info(...)` normal → JSON vía `logback-spring.xml` | `writeLog(logFilePath, "...")` → `.txt` plano por job |
| Dónde queda | `logs/quartz-jobs.log` (rotado por Logback) | `logs/woocommerce-auth.txt`, etc. (rotado por `FileManagerUtil`) |
| Para qué sirve | Debugging técnico, Grafana/Loki, `/internal/logs` | Auditoría legible por humanos de un job específico |
| Trae `traceId`/`jobKey` | Sí, automático (adapters) | No — es texto libre que arma cada job |

Ambos rotan (ninguno crece sin límite), pero por mecanismos distintos:
Logback (`SizeAndTimeBasedRollingPolicy`) para el primero, `FileManagerUtil`
para el segundo.

---

## 3. `core` — `LogContext`

Único archivo 100% portable. Pon/quita valores del MDC de SLF4J:

```java
LogContext.put(LogContext.TRACE_ID, LogContext.newTraceId());
  try {
  log.info("Procesando");
} finally {
  LogContext.clear();
}
```

Para agregar un campo temporal SIN perder el contexto del job/request en
curso (ej. dentro de un loop), usa `scope(...)` en vez de `put()`+`clear()`:

```java
try (AutoCloseable ignored = LogContext.scope("sku", sku.getCodigo())) {
  log.info("Procesando SKU");
}
```

## 4. `web` — requests HTTP entrantes

`CorrelationIdFilter` traza request Y response (status, duración,
content-type) con el mismo `traceId`. No lo llamas manualmente, Spring lo
detecta como `@Component`.

## 5. `quartz` — ejecuciones de Job

`QuartzExecutionListener` pone `jobKey`/`jobGroup`/`traceId` (=
`fireInstanceId`) en el MDC antes de cada ejecución, para TODOS los jobs,
sin tocar cada clase de Job. Se registra vía `QuartzLoggingConfig`.

## 6. `httpclient` — llamadas HTTP salientes

`LoggingClientHttpRequestInterceptor` traza cada llamada que hace
`RestTemplate` (login, sync, etc.) con el mismo `traceId` del job que la
originó. Se registra agregando `.additionalInterceptors(...)` al `@Bean
RestTemplate`. No loguea el body (evita filtrar credenciales).

---

## 7. `query` — API de consulta remota (`GET /internal/logs`)

### Configuración (`application.yml`)

```yaml
app:
  internal-api:
    token: ${INTERNAL_API_TOKEN:}   # el ":" al final evita que el arranque falle si falta la env var
    log-file: logs/quartz-jobs.log
```

Y en tu `.env` / entorno del servidor:
```
INTERNAL_API_TOKEN=un-valor-random-real   # genera uno con: openssl rand -hex 32
```
Sin esta variable seteada, el endpoint responde `503` en vez de tumbar la
app entera — pero no funciona hasta que la configures.

### Uso — **método GET**, no POST

```bash
curl -H "X-Internal-Token: ..." \
  "http://localhost:8080/internal/logs?jobKey=woocommerce.dataSyncJob&level=ERROR&limit=50"
```

El caso más útil — todo lo que pasó en un flujo específico (request →
job → cada llamada saliente que hizo), todo bajo el mismo `traceId`:
```bash
curl -H "X-Internal-Token: ..." \
  "http://localhost:8080/internal/logs?traceId=a1b2c3d4-e5f6-..."
```

### Parámetros soportados

Todos opcionales. **Dentro de un mismo parámetro, varios valores separados
por coma se combinan con OR.** Entre parámetros distintos se combina con
AND.

| Parámetro | Ejemplo | Descripción |
|---|---|---|
| `traceId` | `a1b2c3d4-...` | Amarra request/job/llamadas salientes de un mismo flujo |
| `jobKey` | `woocommerce.dataSyncJob` | Filtra por job específico (acepta varios separados por coma) |
| `jobGroup` | `SYNC` | Filtra por grupo de jobs |
| `level` | `ERROR,WARN` | `INFO` / `WARN` / `ERROR`, uno o varios |
| `source` | `job` | `http` (request entrante) o `job` (ejecución de Quartz) |
| `message` | `timeout` | Busca substring (case-insensitive) dentro del mensaje |
| `from` / `to` | `2026-08-10T16:00:00-05:00` | Rango de fechas (ISO-8601 con offset) sobre el campo `timestamp` |
| `limit` | `50` | Cantidad máxima a devolver (default 100, tope duro 500) |

Ejemplo combinando varios:
```bash
# jobGroup=SYNC Y (level=ERROR O level=WARN)
curl -H "X-Internal-Token: ..." \
  "http://localhost:8080/internal/logs?jobGroup=SYNC&level=ERROR,WARN&limit=20"

# rango de fechas
curl -H "X-Internal-Token: ..." \
  "http://localhost:8080/internal/logs?from=2026-08-10T16:00:00-05:00&to=2026-08-10T16:15:00-05:00"
```

`from`/`to` tienen que venir en el mismo formato ISO-8601 con offset que
ya trae el campo `timestamp` de la respuesta. Si el valor no es
parseable, ese filtro puntual se ignora (queda un `WARN` en el log, la
consulta no se rompe). Con un rango activo, las líneas sin `timestamp`
válido quedan excluidas (no se puede evaluar si están dentro del rango).

### Decisiones de seguridad

1. La ruta del archivo nunca viene del request (fija en `application.yml`) → sin path traversal.
2. `limit` tiene tope duro de 500 (`HARD_MAX_LIMIT` en `LogQueryService`) → sin DoS por memoria.
3. Lectura línea por línea, nunca se carga el archivo completo en memoria.
4. Parseo con Jackson, no regex → sin ReDoS.
5. `LogEntry` es una whitelist explícita de campos.
6. Protegido por `InternalApiTokenFilter` (header `X-Internal-Token`) en cualquier ruta bajo `/internal/`.
7. Es un secreto compartido simple — suficiente para "otra app interna mía" por red privada/VPN. Si se expone a internet público, migra a `spring-boot-starter-security`.

### Bug corregido: `timestamp` venía siempre `null`

`LogstashEncoder` escribe el timestamp como `"@timestamp"` (con arroba),
pero `LogEntry` lo mapeaba como `timestamp` — Jackson nunca los matcheaba
al deserializar, así que el campo quedaba `null` en TODAS las respuestas
de `/internal/logs` (aunque en el archivo real sí estaba). Se arregló
anotando el **setter** con `@JsonProperty("@timestamp")` — solo la
lectura del archivo entiende el `@`; la API sigue devolviendo `timestamp`
(sin arroba) para no romper a quien ya la consume.

### Limitación conocida

Solo lee el archivo activo (`quartz-jobs.log`), no los ya rotados
(`quartz-jobs-2026-08-08.1.log`). Avísame si necesitas que la búsqueda
también recorra rotados.

---

## 8. `FileManagerUtil` — rotación de los logs de negocio (`writeLog`)

`AbstractWooCommerceJob.writeLog(logFilePath, mensaje)` llama a:

```java
FileManagerUtil.appendLineWithRotation(logFilePath, mensaje);
```

Rota automáticamente por tamaño: al llegar a 5MB, `archivo.txt` pasa a
`archivo.txt.1`, y así hasta 5 copias (`.1` a `.5`) — tope real ~30MB por
job, nunca crece sin límite. Límites propios si hace falta:

```java
FileManagerUtil.appendLineWithRotation(path, mensaje, 2 * 1024 * 1024, 3); // 2MB, 3 backups
```

`FileManagerUtil` también trae `tail(path, n)` (lee las últimas N líneas
sin cargar el archivo entero, útil para logs de varios GB) y
`tailAsJsonText(path, n)` (igual pero con pretty-print, al estilo `| jq`).

---

## 9. `RouteListPrinter` — lista de rutas al arrancar

Imprime todas las rutas registradas en el startup (`CommandLineRunner`),
al estilo `php artisan route:list`. Se comporta distinto según el perfil
activo (detecta vía `Environment`):

- **`dev`** (o sin perfil activo): tabla ASCII con colores ANSI, como
  siempre. Perfecta para leer en una terminal.
- **`pre`/`prod`** (salida JSON): una tabla con `\r\n` y códigos ANSI
  escapados dentro de un campo `"message"` se ve horrible en JSON y no se
  puede filtrar. En su lugar, **cada ruta genera su propio log
  estructurado** con `httpMethod`, `httpPath`, `controller`, `action`
  como campos reales (via `StructuredArguments` de
  `logstash-logback-encoder`) — consultable desde `/internal/logs`, ej:
  `?message=api/jobs`.

---

## 10. `logback-spring.xml` — dos fixes acumulados

1. **Rotación por tamaño**: los perfiles `pre`/`prod` usan
   `SizeAndTimeBasedRollingPolicy` (no `TimeBasedRollingPolicy`, que no
   soporta `<maxFileSize>` ni el token `%i` — causaba un crash al
   arrancar: *"contains an integer token converter, i.e. %i,
   INCOMPATIBLE with this configuration"*).
2. **`pre` y `prod` unificados** en un solo `<springProfile name="pre,prod">`
   (antes estaban duplicados byte a byte).
3. **Fallback opcional**: un `<root>` + appender simple fuera de los
   `<springProfile>`, que evita el WARN cosmético *"No appenders present
   in context..."* que sale una sola vez al arrancar (antes de que Spring
   resuelva el perfil activo). No es obligatorio, es solo estético.

---

## 11. Qué borrar del proyecto (código muerto de iteraciones anteriores)

- `ActuatorTokenFilter.java` — reemplazado por `InternalApiTokenFilter.java`.
- `RotatingFileWriter.java` (si existe suelto) — su lógica ya vive en `FileManagerUtil.appendLineWithRotation(...)`.
- `FileWriterService` — ya no lo usa nadie (`writeLog` llama a `FileManagerUtil` directo). Confirma que ningún otro fichero del proyecto lo use antes de borrarlo.
- `GeneralLogHelper.java` — nunca compiló (dependía de Lombok, que no está en el `pom.xml`, y de una clase de otro proyecto).
- **`DbPathUtil.java`** — leftover de una migración SQLite → H2/MySQL. El proyecto ya no usa SQLite en ningún perfil, así que su único método (`getAbsoluteDbPath()`) siempre lanzaría `IllegalStateException` si se llamara. Además, al ser `@Component` con un `@Value("${spring.datasource.url}")` sin default, puede tumbar el arranque completo si esa property no resuelve por cualquier motivo (pasó con el perfil `pre`). Confirma con "Find Usages" que nada la referencia y bórrala.
- Config de Actuator en `application.yml` (`management.actuator.access-token`, `management.endpoints.logfile.*`) — ya no se usa, reemplazada por `app.internal-api.*`.

## 12. Seguridad — recordatorio pendiente

En `WooCommerceAuthJob.login(...)` sigue estando `log.info("token:",token);`
sin placeholder `{}` — hoy no imprime el token por el bug (se ve
`"message": "token:"` en los logs), pero si alguien lo "arregla" sin
pensarlo, filtraría el `access_token` completo. Bórrala o cámbiala por
algo enmascarado (`token.substring(0,8) + "..."`).
