# Logging estructurado — núcleo portable + adapters

## Estructura (así se ve en tu IDE)

```
util/log/
├── core/
│   └── LogContext.java                        ← portable a CUALQUIER proyecto Java
├── web/
│   └── CorrelationIdFilter.java                ← solo si hay Spring MVC / controllers
├── quartz/
│   ├── QuartzExecutionListener.java            ← solo si hay Quartz
│   └── QuartzLoggingConfig.java
└── httpclient/
    └── LoggingClientHttpRequestInterceptor.java ← solo si hay llamadas salientes con RestTemplate
```

## Por qué está dividido así

`LogContext` es la ÚNICA pieza que de verdad necesitas para que esto sea
"reutilizable en cualquier proyecto". Depende solo de `slf4j-api`
(prácticamente todo proyecto Java con logging ya lo tiene). No sabe qué es
un `HttpServletRequest`, un `JobExecutionContext` ni un `RestTemplate` —
solo pone y quita valores del MDC.

Los otros archivos son **adapters**: piezas delgadas que conectan
`LogContext` con un framework específico. Si mañana usas esto en otro
proyecto, copias solo las carpetas que apliquen:

| Proyecto nuevo tiene...              | Copia también...       |
|----------------------------------------|--------------------------|
| Nada más que `core/`                   | (siempre)                |
| Controllers REST (Spring MVC)          | `web/`                    |
| Jobs con Quartz                        | `quartz/`                |
| Llamadas salientes con RestTemplate    | `httpclient/`             |
| Otra cosa (Kafka, CLI, batch, WebClient) | Nada — escribe tu propio adapter de ~30 líneas usando `LogContext` (ver patrón al final) |

---

## 1. `core` — `LogContext`

### Uso básico (dentro de un adapter, punto de entrada de un flujo)

```java
LogContext.put(LogContext.TRACE_ID, LogContext.newTraceId());
LogContext.put(LogContext.SOURCE, "batch");
try {
    log.info("Iniciando proceso");
    // ...
} finally {
    LogContext.clear(); // SIEMPRE en finally, y SIEMPRE al final del flujo completo
}
```

### Uso anidado — agregar un campo temporal SIN perder el contexto del padre

Este es el caso típico dentro de un **use case o service** que ya corre
dentro de un job o un request (el `traceId`/`jobKey` ya están puestos por
el adapter correspondiente). Usa `scope(...)` en vez de `put(...)` +
`clear()`, porque `scope` restaura el valor anterior al cerrar en vez de
borrar todo el contexto:

```java
public class SincronizarSkusUseCase {

    private static final Logger log = LoggerFactory.getLogger(SincronizarSkusUseCase.class);

    public void ejecutar(List<Sku> skus) {
        for (Sku sku : skus) {
            try (AutoCloseable ignored = LogContext.scope("sku", sku.getCodigo())) {
                log.info("Procesando SKU"); // log sale con traceId + jobKey + sku
                procesar(sku);
            } catch (Exception e) {
                log.error("Error procesando SKU", e);
            }
            // acá "sku" ya se quitó, pero traceId/jobKey del job SIGUEN intactos
        }
    }
}
```

### Uso directo en un Controller o Service (lo normal, el 95% de los casos)

No necesitas llamar a `LogContext` para nada — ya está puesto por el
adapter (`CorrelationIdFilter` o `QuartzExecutionListener`). Solo usas tu
`Logger` de siempre:

```java
private static final Logger log = LoggerFactory.getLogger(ProductoService.class);

public Producto buscar(Long id) {
    log.info("Buscando producto id={}", id);
    return repo.findById(id).orElseThrow();
}
```

---

## 2. `web` — `CorrelationIdFilter` (requests HTTP entrantes)

No necesitas llamarlo desde tu código — es un `@Component` + `Filter`,
Spring lo detecta y ejecuta solo en cada request. Traza tanto el
**request** (método, path, query string) como el **response** (status,
duración, content-type), con el mismo `traceId` en ambos logs:

```json
{"message":"→ Request iniciado query=page=2","traceId":"a1b2...","source":"http","httpMethod":"GET","httpPath":"/productos"}
{"message":"← Request finalizado status=200 durationMs=42 contentType=application/json","traceId":"a1b2...","httpMethod":"GET","httpPath":"/productos"}
```

Si el response es 4xx/5xx, el segundo log sale como `WARN`/`ERROR`
automáticamente — así puedes armar una alerta en Grafana solo con
`level="ERROR"` sin tener que adivinar en qué controller se dio el error.

Si necesitas leer el `traceId` actual desde un controller (por ejemplo
para devolverlo en el body de un error personalizado):

```java
String traceId = LogContext.get(LogContext.TRACE_ID);
```

---

## 3. `quartz` — `QuartzExecutionListener` + `QuartzLoggingConfig`

Igual que el anterior: no lo llamas manualmente, se registra una vez
(`QuartzLoggingConfig`) y aplica a TODOS los jobs. Antes tenías que
construir el prefijo a mano:

```java
// Antes:
log.info("✔ [{}] WooCommerceDataSyncJob completado, pendientes={}", jobKey, total);

// Ahora (jobKey ya viene en el MDC):
log.info("✔ WooCommerceDataSyncJob completado, pendientes={}", total);
```

```json
{"message":"▶ Job disparado","traceId":"<fireInstanceId>","source":"job","jobKey":"woocommerce.dataSyncJob","jobGroup":"woocommerce","triggerKey":"..."}
{"message":"✔ WooCommerceDataSyncJob completado, pendientes=3","jobKey":"woocommerce.dataSyncJob", ...}
{"message":"✔ Job finalizado OK durationMs=1832","jobKey":"woocommerce.dataSyncJob", ...}
```

---

## 4. `httpclient` — `LoggingClientHttpRequestInterceptor` (requests HTTP **salientes**) — NUEVO

Esto es lo que te faltaba: tus jobs de WooCommerce (`login`, `syncLogin`,
`getSkus`, `syncMongo`, `sendWeb`, etc.) hacen llamadas SALIENTES con
`RestTemplate` hacia una API externa. Este interceptor las traza igual que
el filtro traza las entrantes, **con el mismo `traceId` del job que las
originó** — así en Grafana ves en una sola búsqueda todo el árbol: request
entrante → job → cada llamada saliente que hizo ese job.

### Cómo registrarlo (1 línea en tu config existente)

```java
// RestTemplateConfig.java
@Configuration
public class RestTemplateConfig {

    private final LoggingClientHttpRequestInterceptor loggingInterceptor;

    public RestTemplateConfig(LoggingClientHttpRequestInterceptor loggingInterceptor) {
        this.loggingInterceptor = loggingInterceptor;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(60))
            .additionalInterceptors(loggingInterceptor)   // ← esta línea
            .build();
    }
}
```

Salida:
```json
{"message":"→ HTTP saliente GET https://api.externa.com/sync/get-skus","traceId":"<fireInstanceId>","jobKey":"woocommerce.dataSyncJob"}
{"message":"← HTTP saliente GET https://api.externa.com/sync/get-skus status=200 durationMs=310","traceId":"<fireInstanceId>","jobKey":"woocommerce.dataSyncJob"}
```

Si la API externa está lenta o cae, ves EXACTAMENTE qué llamada saliente
fue (URL + duración + status) sin tener que instrumentar cada método de
`AbstractWooCommerceJob` a mano.

### ⚠️ Importante — no loguea el body a propósito

El interceptor deliberadamente NO loguea el `body` del request ni del
response. En tu caso, `login()` envía `{"email":..., "password":...}` en
el body — loguear eso filtraría credenciales en texto plano a tus logs
(y de ahí a Grafana/Loki, donde cualquiera con acceso al dashboard las
vería). Si en algún endpoint puntual necesitas loguear el body para
debug, hazlo de forma explícita y con máscara, nunca por defecto en un
interceptor global:

```java
log.debug("Body enviado (enmascarado): {}", body.replaceAll("\"password\":\"[^\"]*\"", "\"password\":\"***\""));
```

---

## El patrón para un adapter nuevo (Kafka, WebClient, CLI, etc.)

Todos los adapters siguen la misma receta de 3 pasos:

```java
try {
    LogContext.put(LogContext.TRACE_ID, /* id del mensaje/evento */);
    LogContext.put(LogContext.SOURCE, "kafka");
    LogContext.put("topic", record.topic());

    log.info("→ Mensaje recibido");
    // ...procesar...
    log.info("← Mensaje procesado OK");

} catch (Exception e) {
    log.error("← Mensaje falló", e);
    throw e;
} finally {
    LogContext.clear(); // solo en el punto de entrada del flujo completo
}
```

---

## Configuración de Logback (sin cambios)

`logback-spring.xml`: perfil `dev` = consola legible, perfil `prod` = JSON
vía `logstash-logback-encoder` (ya está en tu `pom.xml`). No depende de
cuántos adapters uses — solo lee lo que haya en el MDC en cada momento.

## Qué borrar

- `GeneralLogHelper.java` — no compila (Lombok no está en el `pom.xml`, y
  depende de una clase de otro proyecto que no existe aquí).
- El `writeLog(...)` a archivo de texto plano en tus jobs de WooCommerce —
  con `jobKey` en cada log (adapter `quartz`) y ahora también cada llamada
  saliente trazada (adapter `httpclient`), ya no necesitas ese `.txt` como
  fuente de trazabilidad. Si quieres conservar un log de auditoría de
  negocio legible por humanos, está bien mantenerlo, pero trátalo como
  algo aparte del log técnico.
