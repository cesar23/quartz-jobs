# Tutorial: Conectar la base de datos H2 (quartz-jobs) con la consola web y con DBeaver

Este tutorial cubre dos formas de explorar la base de datos H2 usada en el
perfil `dev` del proyecto **quartz-jobs**: la consola web integrada de H2
(equivalente ligero a phpMyAdmin) y DBeaver (cliente de escritorio, más
potente para trabajo diario).

---

## 0. Requisito previo: habilitar acceso concurrente (`AUTO_SERVER`)

Por defecto, H2 en modo `file` solo permite **una conexión a la vez** al
archivo `.mv.db`. Si tu aplicación Spring Boot está corriendo, cualquier otro
cliente (consola web, DBeaver) que intente abrir el mismo archivo fallará con:

```
Database may be already in use: null
The file is locked: .../data/quartzjobs.mv.db
```

Para permitir que la app y un cliente externo se conecten **al mismo tiempo**,
agrega `AUTO_SERVER=TRUE` a la URL en `application-dev.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/quartzjobs;AUTO_SERVER=TRUE
```

> ⚠️ **No combines esto con `DB_CLOSE_ON_EXIT=FALSE`.** H2 no soporta ambos
> flags juntos y falla al arrancar con:
> `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"`

Reinicia la aplicación después de este cambio para que tome efecto.

---

## 1. Consola web de H2 (rápida, sin instalar nada)

### 1.1 Arranca la app

Debes correr la app en perfil `dev` (el que trae la consola habilitada vía
`spring.h2.console.enabled: true`).

### 1.2 Abre la consola en el navegador

```
http://localhost:8080/h2-console
```

### 1.3 Completa el formulario de login

| Campo | Valor |
|---|---|
| **Driver Class** | `org.h2.Driver` |
| **JDBC URL** | `jdbc:h2:file:./data/quartzjobs;AUTO_SERVER=TRUE` |
| **User Name** | `sa` |
| **Password** | *(vacío)* |

> ⚠️ El JDBC URL debe coincidir **exactamente** con el de tu
> `application-dev.yml` — no el valor por defecto (`jdbc:h2:~/test`) que trae
> el formulario. Si no coincide, te conectas a una base H2 distinta (vacía).

### 1.4 Connect

Verás el árbol de tablas a la izquierda (tus entidades JPA + tablas de
Quartz: `QRTZ_JOB_DETAILS`, `QRTZ_TRIGGERS`, etc.) y un editor SQL arriba
para correr `SELECT`, `INSERT`, etc.

---

## 2. DBeaver (cliente de escritorio recomendado)

### 2.1 Nueva conexión

**Database → New Database Connection**, selecciona **H2** (embebido, no
"H2 Server (Remote)") → **Next**.

### 2.2 Configura la conexión

| Campo | Valor |
|---|---|
| **Connect by** | URL |
| **JDBC URL** | `jdbc:h2:file:D:/repos/quartz-jobs/data/quartzjobs;AUTO_SERVER=TRUE` |
| **Username** | `sa` |
| **Password** | *(vacío)* |

> ⚠️ **Usa ruta absoluta**, no relativa. A diferencia de IntelliJ, DBeaver no
> arranca desde el working directory de tu proyecto, así que
> `./data/quartzjobs` no resuelve. Ajusta la ruta a donde esté tu repo real.

### 2.3 Descarga el driver (primera vez)

Si es la primera conexión H2 que creas en DBeaver, te pedirá descargar el
driver JDBC. Click **Download** (requiere internet, se descarga desde Maven
Central).

### 2.4 Probar conexión → Aceptar

Si sale ✅, dale **Aceptar**. Ya puedes navegar tablas, ejecutar SQL, ver
diagramas ER y exportar datos como en cualquier cliente de BD.

---

## 3. Errores comunes y solución rápida

| Error | Causa | Solución |
|---|---|---|
| `Database "..." not found` | El nombre de archivo en la URL no coincide con el real (ej. `mibase` vs `quartzjobs`) | Verifica el nombre exacto del `.mv.db` en `data/` |
| `Database may be already in use` / `The file is locked` | Otro proceso (la app) tiene el archivo abierto sin `AUTO_SERVER` | Agrega `AUTO_SERVER=TRUE` en la URL de **todos** los clientes (app, consola, DBeaver) |
| `Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"` | Ambos flags combinados | Quita `DB_CLOSE_ON_EXIT=FALSE`, deja solo `AUTO_SERVER=TRUE` |

---

## 4. Cómo funciona `AUTO_SERVER` (por si tienes curiosidad)

El primer proceso que abre el archivo (normalmente tu app Spring Boot al
arrancar) levanta automáticamente un mini-servidor TCP interno de H2. Los
procesos que se conectan después (DBeaver, la consola web) hablan con ese
servidor en vez de intentar abrir el archivo directamente — por eso deja de
haber conflicto de bloqueo. Si cierras la app y conectas primero con
DBeaver, funciona igual: el primero en conectar actúa como servidor.
