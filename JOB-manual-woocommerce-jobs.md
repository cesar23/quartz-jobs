# Tutorial: paquete `jobs/woocommerce` — sincronización con WooCommerce

Este tutorial explica **qué hace cada archivo** del paquete
`com.quartzjobs.jobs.woocommerce`, **cómo viaja la ejecución** entre ellos,
**cómo programar/probar/disparar manualmente** los 2 jobs reales que
sincronizan datos con WooCommerce, y por qué la base de datos "se resetea"
al reiniciar la app.

```
jobs/
└── woocommerce/
    ├── AbstractWooCommerceJob.java   ← helpers compartidos (no es un Job)
    ├── SyncErrorTracker.java         ← contador de errores consecutivos
    ├── WooCommerceTokenCache.java    ← cache en memoria del access_token
    ├── WooCommerceAuthJob.java       ← Job 1: login (cada 2h)
    └── WooCommerceDataSyncJob.java   ← Job 2: sincroniza datos (cada 15 min)
```

---

## Índice

1. [¿Por qué 2 jobs en vez de 1?](#1-por-qué-2-jobs-en-vez-de-1)
2. [`AbstractWooCommerceJob.java` — la base común](#2-abstractwoocommercejobjava--la-base-común)
3. [`SyncErrorTracker.java` — contador de errores consecutivos](#3-syncerrortrackerjava--contador-de-errores-consecutivos)
4. [Qué pasa cuando algo falla — `handleFailure(...)`](#4-qué-pasa-cuando-algo-falla--handlefailure)
5. [`WooCommerceTokenCache.java` — el "buzón" del token](#5-woocommercetokencachejava--el-buzón-del-token)
6. [`WooCommerceAuthJob.java` — Job 1 (cada 2h)](#6-woocommerceauthjobjava--job-1-cada-2h)
7. [`WooCommerceDataSyncJob.java` — Job 2 (cada 15 min)](#7-woocommercedatasyncjobjava--job-2-cada-15-min)
8. [Programar los 2 jobs (curl)](#8-programar-los-2-jobs-curl)
9. [Ejecutar un job manualmente, sin esperar el cron](#9-ejecutar-un-job-manualmente-sin-esperar-el-cron)
10. [Ejecución secuencial (nota para quien viene de Node/JS)](#10-ejecución-secuencial-nota-para-quien-viene-de-nodejs)
11. [Troubleshooting](#11-troubleshooting)
12. [¿Por qué se resetea la base de datos al reiniciar?](#12-por-qué-se-resetea-la-base-de-datos-al-reiniciar)
13. [¿Cómo agregar un tercer job siguiendo el mismo patrón?](#13-cómo-agregar-un-tercer-job-siguiendo-el-mismo-patrón)

---

## 1. ¿Por qué 2 jobs en vez de 1?

Originalmente había un solo job (`WooCommerceSyncJob`, ya eliminado) que hacía
**7 pasos secuenciales** en cada ejecución: login → validar token → traer SKUs
→ sync Mongo → enviar a la web → tipo de cambio → limpiar caché. El problema:
todo corría con la **misma frecuencia**, aunque no todos los pasos necesitan
la misma urgencia.

Se dividió en 2 jobs con horarios distintos:

| Job | Frecuencia típica | Qué hace |
|---|---|---|
| `WooCommerceAuthJob` | Cada 2 horas | Login + validación + verificación de SKUs. Deja el token listo para el otro job |
| `WooCommerceDataSyncJob` | Cada 15 minutos | Sincroniza productos, envía pendientes, actualiza tipo de cambio, limpia caché. **Reutiliza** el token, no vuelve a loguearse |

El **puente** entre ambos es `WooCommerceTokenCache`: un componente en memoria
donde el primero "deja" el token y el segundo lo "recoge".

```
WooCommerceAuthJob (cada 2h)
        │
        │ login → token
        ▼
WooCommerceTokenCache  ← memoria compartida (vive mientras la app esté arriba)
        ▲
        │ lee token
        │
WooCommerceDataSyncJob (cada 15 min)
```

---

## 2. `AbstractWooCommerceJob.java` — la base común

No es un `Job` que se programe directamente (no lleva `@Component`) —
es una clase abstracta de la que **heredan** `WooCommerceAuthJob` y
`WooCommerceDataSyncJob`, para no repetir el mismo código dos veces.

Qué provee a sus hijos:

| Método | Para qué sirve |
|---|---|
| `bearerHeaders(token)` | Arma el header `Authorization: Bearer <token>` para las llamadas HTTP |
| `parseJson(body)` | Convierte el `String` de respuesta HTTP en un `JsonNode` navegable, o lanza excepción si no es JSON válido |
| `extractErrorDetail(e)` | Si el error viene de una llamada HTTP fallida, extrae el código de estado + el cuerpo de la respuesta; si no, el mensaje de la excepción |
| `writeLog(path, línea)` | Escribe una línea en el archivo de log del job (vía `FileWriterService`), sin tumbar el job si falla la escritura |
| `handleFailure(...)` | El manejador central de errores — ver sección 4 |
| `isBlank`, `orDefault`, `parseIntOrDefault`, `parseLongOrDefault`, `escapeJson` | Utilidades pequeñas para leer parámetros del `JobDataMap` de forma segura |

También declara 3 dependencias `@Autowired` que ambos jobs necesitan:
`FileWriterService` (logging a disco), `EmailService` (alertas) y
`SyncErrorTracker` (contador de errores).

El método `log()` es abstracto — cada subclase lo implementa devolviendo
**su propio** logger (`LoggerFactory.getLogger(WooCommerceAuthJob.class)`,
por ejemplo), para que los mensajes en consola digan de qué clase vienen
exactamente.

---

## 3. `SyncErrorTracker.java` — contador de errores consecutivos

Un `@Component` simple: un `Map<jobKey, contador>` en memoria.

```java
incrementAndGet(jobKey)   // suma 1 y devuelve el total
reset(jobKey)             // borra el contador (tras éxito o tras alertar)
currentCount(jobKey)      // consulta sin modificar
```

`jobKey` es el identificador único de Quartz (`grupo.nombre`, ej.
`SYNC.woocommerce-datasync-prod`), así que cada job programado lleva su
**propio** contador — un fallo en `AuthJob` no afecta el contador de
`DataSyncJob`.

> ⚠️ Vive en memoria: si reinicias la app, todos los contadores vuelven a 0.
> Si algún día necesitas que sobreviva a reinicios, habría que persistirlo en
> una tabla de la base de datos en vez de un `ConcurrentHashMap`.

---

## 4. Qué pasa cuando algo falla — `handleFailure(...)`

Este método (en `AbstractWooCommerceJob`) es el que **ambos** jobs llaman
dentro de su `catch`. Hace 3 cosas, en orden:

1. **Loguea el error** — en consola (`log().error(...)`) y en el archivo de
   log del job (`writeLog(...)`), con el detalle HTTP si vino de una llamada
   REST fallida.
2. **Incrementa el contador** de `SyncErrorTracker` para ese `jobKey`.
3. **Si el contador llega a `maxErrors`**, manda un email de alerta a
   `adminEmail` con el job, cuántas veces falló seguido y el último error —
   y **resetea el contador a 0** (para no seguir mandando un email en cada
   ejecución fallida siguiente; hay que esperar otras `maxErrors` fallas
   consecutivas para que vuelva a alertar).

```
1ra falla → contador=1 → (no alerta, 1 < 5)
2da falla → contador=2 → (no alerta)
...
5ta falla → contador=5 → 🔔 email a adminEmail, contador vuelve a 0
6ta falla → contador=1 → (no alerta de nuevo, arrancó de cero)
```

Si la ejecución es exitosa en cualquier punto, el job llama
`errorTracker.reset(jobKey)` — el contador se limpia y el ciclo de alertas
vuelve a empezar desde cero.

---

## 5. `WooCommerceTokenCache.java` — el "buzón" del token

Otro `@Component`, con dos operaciones:

```java
put(tokenKey, token)                  // el AuthJob deja el token aquí
get(tokenKey, maxAge) → Optional<String>  // el DataSyncJob lo recoge
```

Cada entrada guarda el token **junto con la hora exacta** en que se obtuvo
(`Instant.now()`). Cuando `DataSyncJob` pide el token, pasa un `maxAge`
(ej. 1380 minutos) — si el token cacheado es más viejo que eso, `get(...)`
devuelve `Optional.empty()` aunque el token técnicamente siga ahí, como
mecanismo de seguridad para no usar un token demasiado viejo si el
`AuthJob` dejó de correr por algún motivo (error, deploy detenido, etc).

`tokenKey` es una clave de texto libre que **tú defines** en el JSON de
ambos jobs — debe ser **idéntica** en `WooCommerceAuthJob` y
`WooCommerceDataSyncJob` para que compartan el mismo token. Esto permite,
si algún día tienes 2 tiendas o 2 ambientes, correr 2 pares
auth/datasync en paralelo sin que se pisen (usando `tokenKey` distintos,
ej. `"tienda-a"` / `"tienda-b"`).

---

## 6. `WooCommerceAuthJob.java` — Job 1 (cada 2h)

### Qué hace, paso a paso

```
1) POST {loginUrl}       → { email, password }  →  obtiene access_token
2) POST {syncLoginUrl}   → Bearer token          →  valida el token (isValid)
3) GET  {skusUrl}        → Bearer token          →  cuenta los SKUs (verificación)
4) tokenCache.put(tokenKey, token)   ← deja el token listo para el otro job
```

Cada paso se ejecuta uno después del otro (Java bloquea en cada llamada
HTTP hasta que responde — no hace falta ningún `await`, ver sección 10).
Si cualquier paso lanza una excepción, el `catch` llama a `handleFailure(...)`
y el job termina en error — **no** llega a guardar el token si algo falló
antes.

### Parámetros del `JobDataMap`

| Parámetro | Obligatorio | Default | Descripción |
|---|---|---|---|
| `loginUrl` | Sí | — | Endpoint de login |
| `syncLoginUrl` | Sí | — | Endpoint que valida el token |
| `skusUrl` | Sí | — | Endpoint que devuelve los SKUs |
| `username` | Sí | — | Usuario/email para el login |
| `password` | Sí | — | Contraseña |
| `adminEmail` | Sí | — | A quién avisar si falla `maxErrors` veces seguidas |
| `tokenKey` | No | `"woocommerce-default"` | Debe coincidir con el del `DataSyncJob` |
| `maxErrors` | No | `"5"` | Umbral de fallas consecutivas antes de alertar |
| `logFilePath` | No | `"logs/woocommerce-auth.txt"` | Dónde escribe su bitácora |

---

## 7. `WooCommerceDataSyncJob.java` — Job 2 (cada 15 min)

### Qué hace, paso a paso

```
0) tokenCache.get(tokenKey, tokenTtlMinutes) → si no hay token válido, FALLA
   inmediatamente (no intenta loguearse por su cuenta)

1) GET {syncMongoUrl}            → sincroniza productos (Mongo)
2) GET {sendWebUrl}               → envía pendientes a la web
3) GET {sendWebExchangeRateUrl}  → actualiza tipo de cambio
4) GET {sendCleanCacheUrl}       → limpia caché de la web
```

Igual que el `AuthJob`: cada paso espera al anterior, y cualquier falla
corta la cadena y dispara `handleFailure(...)`.

### Parámetros del `JobDataMap`

| Parámetro | Obligatorio | Default | Descripción |
|---|---|---|---|
| `syncMongoUrl` | Sí | — | Sincroniza productos en Mongo |
| `sendWebUrl` | Sí | — | Envía pendientes a la web |
| `sendWebExchangeRateUrl` | Sí | — | Actualiza tipo de cambio |
| `sendCleanCacheUrl` | Sí | — | Limpia caché de la web |
| `adminEmail` | Sí | — | A quién avisar si falla `maxErrors` veces seguidas |
| `tokenKey` | No | `"woocommerce-default"` | Debe coincidir con el del `AuthJob` |
| `tokenTtlMinutes` | No | `"1380"` (23h) | Cuánto se acepta como "vigente" el token cacheado |
| `maxErrors` | No | `"5"` | Umbral de fallas consecutivas antes de alertar |
| `logFilePath` | No | `"logs/woocommerce-datasync.txt"` | Dónde escribe su bitácora |

> 💡 El `tokenTtlMinutes` (1380 min = 23h) asume que el `access_token` real
> dura 24h — deja 1h de margen de seguridad. Si tu API cambia la duración
> del token, ajusta este valor (o el default en el código) para que siga
> teniendo sentido.

---

## 8. Programar los 2 jobs (curl)

**1) Auth — cada 2 horas:**

```json
curl --location 'http://localhost:8080/api/jobs' \
--header 'Content-Type: application/json' \
--data-raw '{
"jobName": "woocommerce-auth-prod",
"jobGroup": "SYNC",
"jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceAuthJob",
"cronExpression": "0 0 0/2 * * ?",
"data": {
"loginUrl": "http://100.83.15.65:18080/api/v1/auth/login",
"syncLoginUrl": "http://100.83.15.65:18080/api/v1/sync/login",
"skusUrl": "http://100.83.15.65:18080/api/v1/sync/get-skus",
"username": "perucaos@gmail.com",
"password": "cesar203",
"adminEmail": "admin@tuempresa.com",
"tokenKey": "woocommerce-prod",
"maxErrors": "5",
"logFilePath": "logs/woocommerce-auth-prod.txt"
}
}'
```

**2) DataSync — cada 15 minutos:**

```json
curl --location 'http://localhost:8080/api/jobs' \
--header 'Content-Type: application/json' \
--data-raw '{
"jobName": "woocommerce-datasync-prod",
"jobGroup": "SYNC",
"jobClass": "com.quartzjobs.jobs.woocommerce.WooCommerceDataSyncJob",
"cronExpression": "0 */15 * * * ?",
"data": {
"syncMongoUrl": "http://100.83.15.65:18080/api/v1/sync/syncMongo",
"sendWebUrl": "http://100.83.15.65:18080/api/v1/sync/sendWeb",
"sendWebExchangeRateUrl": "http://100.83.15.65:18080/api/v1/sync/sendWebExchangeRate",
"sendCleanCacheUrl": "http://100.83.15.65:18080/api/v1/sync/sendCleanCache",
"adminEmail": "admin@tuempresa.com",
"tokenKey": "woocommerce-prod",
"tokenTtlMinutes": "1380",
"maxErrors": "5",
"logFilePath": "logs/woocommerce-datasync-prod.txt"
}
}'
```

**⚠️ Orden importante:** manda primero el request 1 (Auth) y espera a que
corra al menos una vez — o dispáralo manualmente (ver sección 9 de abajo).
Si mandas el request 2 antes de que exista un token cacheado, va a fallar
con "No hay token válido en caché" — es el comportamiento esperado, no un
bug.

---

## 9. Ejecutar un job manualmente, sin esperar el cron

`JobController` ya expone un endpoint para disparar cualquier job **ya
mismo**, sin importar su `cronExpression`, usando el mismo `JobDataMap` con
el que se programó originalmente:

```
POST /api/jobs/{group}/{name}/trigger
```

### Para tus 2 jobs:

```bash
# Disparar el auth job
curl -X POST http://localhost:8080/api/jobs/SYNC/woocommerce-auth-prod/trigger

# Disparar el data-sync job
curl -X POST http://localhost:8080/api/jobs/SYNC/woocommerce-datasync-prod/trigger
```

`SYNC` es el `jobGroup` con el que los creaste — ajústalo si usaste otro.

### Flujo típico para probar de punta a punta sin esperar

```bash
# 1) Dispara el auth job manualmente
curl -X POST http://localhost:8080/api/jobs/SYNC/woocommerce-auth-prod/trigger

# 2) Revisa el log para confirmar que sí guardó el token
tail -f logs/woocommerce-auth-prod.txt

# 3) Ya con el token cacheado, dispara el data-sync job manualmente
curl -X POST http://localhost:8080/api/jobs/SYNC/woocommerce-datasync-prod/trigger

# 4) Revisa su log
tail -f logs/woocommerce-datasync-prod.txt
```

### Otros endpoints útiles mientras pruebas

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/api/jobs/SYNC/woocommerce-auth-prod` | Ver detalle/estado del job (sin ejecutarlo) |
| `GET` | `/api/jobs` | Listar todos los jobs y su estado |
| `POST` | `/api/jobs/SYNC/woocommerce-auth-prod/pause` | Pausarlo (sigue programado, pero no corre) |
| `POST` | `/api/jobs/SYNC/woocommerce-auth-prod/resume` | Reanudarlo |

Con `/trigger` no necesitas pausar ni tocar el cron — el job programado
sigue corriendo en su horario normal, esto solo agrega una ejecución extra
inmediata.

---

## 10. Ejecución secuencial (nota para quien viene de Node/JS)

Ni `AuthJob` ni `DataSyncJob` usan hilos, `@Async` ni `CompletableFuture` —
cada llamada (`restTemplate.postForEntity(...)`, `restTemplate.exchange(...)`)
es **bloqueante por defecto** en Java. Eso significa que el código ya corre
paso a paso, exactamente como si cada línea llevara un `await` en Node:

```java
String token = login(...);        // ⏸ espera aquí
boolean ok   = syncLogin(...);    // no corre hasta que login() terminó
int skus     = getSkus(...);      // no corre hasta que syncLogin() terminó
```

No hace falta ninguna palabra clave especial — es el comportamiento por
defecto de cualquier llamada a método en Java. Solo si quisieras que 2
pasos corrieran **en paralelo** (por ejemplo, si `sendWeb` y
`sendWebExchangeRate` no dependieran uno del otro) necesitarías
`CompletableFuture.supplyAsync(...)` explícitamente.

---

## 11. Troubleshooting

| Síntoma en el log | Causa probable | Solución |
|---|---|---|
| `No hay token válido en caché para tokenKey='...'` | El `AuthJob` nunca corrió con ese `tokenKey`, o pasaron más de `tokenTtlMinutes` desde la última vez | Dispara el `AuthJob` manualmente (sección 9) y confirma que use el mismo `tokenKey` |
| `Respuesta no es JSON válido: ...` | El endpoint devolvió HTML (ej. error 500 con página de error) en vez de JSON | Revisa el log completo — `extractErrorDetail` debería mostrar el HTTP status y el cuerpo |
| `La respuesta de login no contiene data.access_token` | Cambió el formato de respuesta de `loginUrl`, o las credenciales son incorrectas | Prueba el `loginUrl` directo con curl/Postman y compara la estructura del JSON |
| `ClassNotFoundException` al arrancar Quartz | El `jobClass` guardado en la BD no coincide con el paquete actual de la clase | Borra el job (`DELETE /api/jobs/{group}/{name}`) y créalo de nuevo con el FQCN correcto |
| El job falla siempre pero nunca llega el email de alerta | `maxErrors` es muy alto, o el job tuvo éxito al menos una vez entre fallas (resetea el contador) | Revisa `logs/woocommerce-*.txt` para ver el conteo real de fallas consecutivas |
| Al reiniciar la app, los jobs programados desaparecen | `spring.quartz.jdbc.initialize-schema: always` está recreando las tablas `QRTZ_*` en cada arranque | Ver sección 12 |

---

## 12. ¿Por qué se resetea la base de datos al reiniciar?

La causa está en `application.yml` (común a los 3 perfiles):

```yaml
spring:
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: always   # 👈 aquí está el problema
```

### Qué hace `initialize-schema: always`

En **cada arranque**, Spring Boot ejecuta el script de esquema de Quartz
(`org/quartz/impl/jdbcjobstore/tables_*.sql`), que empieza con sentencias
tipo:

```sql
DROP TABLE IF EXISTS QRTZ_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_JOB_DETAILS;
-- ... etc
CREATE TABLE QRTZ_JOB_DETAILS (...);
CREATE TABLE QRTZ_TRIGGERS (...);
```

Es decir: **borra y vuelve a crear las tablas `QRTZ_*` cada vez que
reinicias**. Todos los jobs programados (`woocommerce-auth-prod`,
`woocommerce-datasync-prod`, etc.), sus triggers y el historial de
ejecuciones desaparecen.

> ℹ️ Esto **no** borra las entidades JPA normales (esas las maneja
> `ddl-auto: update`, que solo agrega/actualiza columnas, nunca hace
> `DROP`). El "reseteo" es específicamente **Quartz perdiendo los jobs
> programados**, no la base de datos completa.

### La solución: separar el valor por perfil

| Perfil | Valor recomendado | Por qué |
|---|---|---|
| `dev` | `always` | En desarrollo no importa perder jobs de prueba al reiniciar; ahorra correr el schema a mano |
| `pre` / `prod` | `never` | Ahí sí interesa que los jobs programados **sobrevivan** a un reinicio/deploy |

**`application.yml`** (quita la línea `initialize-schema` de aquí):
```yaml
spring:
  quartz:
    job-store-type: jdbc
    properties:
      org:
        quartz:
          scheduler:
            instanceName: QuartzJobsScheduler
            instanceId: AUTO
          threadPool:
            threadCount: ${QUARTZ_THREAD_COUNT:5}
```

**`application-dev.yml`** (agrega):
```yaml
spring:
  quartz:
    jdbc:
      initialize-schema: always
```

**`application-pre.yml` y `application-prod.yml`** (agrega):
```yaml
spring:
  quartz:
    jdbc:
      initialize-schema: never
```

⚠️ **Ojo con el primer despliegue en pre/prod con `never`:** las tablas
`QRTZ_*` no van a existir todavía en una base MySQL nueva. Dos opciones:

1. Despliega **una vez** con `initialize-schema: always`, confirma que
   arrancó bien creando las tablas, y **luego** cámbialo a `never` para los
   despliegues siguientes.
2. O corre tú mismo el script SQL de Quartz una vez contra esa base MySQL
   (está en el JAR de `quartz-jobs`, bajo
   `org/quartz/impl/jdbcjobstore/tables_mysql_innodb.sql`) y dejas `never`
   desde el inicio.

---

## 13. ¿Cómo agregar un tercer job siguiendo el mismo patrón?

Si más adelante quieres separar, por ejemplo, `sendWebExchangeRate` +
`sendCleanCache` en su propio horario (distinto de `syncMongo` + `sendWeb`):

1. Crea `WooCommerceExchangeRateJob.java` en el mismo paquete, extendiendo
   `AbstractWooCommerceJob` (igual que `WooCommerceDataSyncJob`).
2. Inyecta `WooCommerceTokenCache` y léelo con `tokenCache.get(tokenKey, ...)`
   — **no** hagas login por tu cuenta, reutiliza el mismo `tokenKey` que ya
   usan `AuthJob` y `DataSyncJob`.
3. Prográmalo con su propio `cronExpression` y su propio `logFilePath`.

No hace falta tocar `AbstractWooCommerceJob`, `SyncErrorTracker` ni
`WooCommerceTokenCache` — están diseñados justamente para que cualquier
job nuevo de WooCommerce los reutilice sin cambios.
