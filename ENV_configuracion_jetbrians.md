# Tutorial: Variables de entorno por perfil en IntelliJ IDEA (quartz-jobs)

Este tutorial explica cómo configurar correctamente las variables de entorno
para cada perfil (`dev`, `pre`, `prod`) del proyecto **quartz-jobs**, usando
`spring-dotenv` + Run Configurations de IntelliJ IDEA.

---

## 0. El problema que resuelve este tutorial

`spring.profiles.active=pre` **no hace que `spring-dotenv` cargue
automáticamente un archivo `.env.pre`**. La librería solo busca un archivo
con nombre fijo (por defecto `.env`), sin importar qué perfil de Spring esté
activo. Ambos mecanismos son independientes:

- `spring.profiles.active` → decide qué `application-{perfil}.yml` carga Spring.
- `SPRINGDOTENV_FILENAME` → decide qué archivo `.env*` carga spring-dotenv.

Hay que configurar los dos, a mano, para cada entorno.

---

## 1. Estructura de archivos en la raíz del proyecto

```
quartz-jobs/
├── .env          # variables para perfil dev (nombre por defecto de spring-dotenv)
├── .env.pre      # variables para perfil pre
├── .env.prod     # variables para perfil prod
├── .env.example  # plantilla sin secretos, sí se versiona
├── .gitignore
├── pom.xml
└── src/
    └── main/resources/
        ├── application.yml
        ├── application-dev.yml
        ├── application-pre.yml
        └── application-prod.yml
```

### `.gitignore`

Asegúrate de que **ningún** `.env*` real llegue al repositorio:

```gitignore
.env.dev
.env.pre
.env.prod
```

(`.env.example` sí se versiona, como plantilla de referencia sin credenciales
reales.)

---

## 2. Dependencia necesaria (ya instalada)

```xml
<dependency>
    <groupId>me.paulschwarz</groupId>
    <artifactId>springboot3-dotenv</artifactId>
    <version>5.0.1</version>
    <optional>true</optional>
</dependency>
```

Por defecto esta librería busca un archivo llamado `.env`. Para decirle que
use otro nombre, se configura mediante la variable de entorno
`SPRINGDOTENV_FILENAME` (ver tabla de opciones más abajo).

| Propiedad | Variable de entorno | Default |
|---|---|---|
| `springdotenv.filename` | `SPRINGDOTENV_FILENAME` | `.env` |
| `springdotenv.enabled` | `SPRINGDOTENV_ENABLED` | `true` |
| `springdotenv.ignoreIfMissing` | `SPRINGDOTENV_IGNORE_IF_MISSING` | `true` |

---

## 3. Crear una Run Configuration por perfil en IntelliJ

La idea es tener **una configuración de ejecución independiente por
entorno**, cada una con su propio perfil activo y su propio archivo `.env`.

### 3.1 Abrir el editor de configuraciones

Menú superior → selector de configuración (donde dice `JobsApplication`) →
**Edit Configurations...**

### 3.2 Configurar el perfil activo

En el campo **Active profiles**, escribe el perfil correspondiente:
```
dev
```
```
pre
```
```
prod
```
(solo uno por configuración)

### 3.3 Agregar la variable `SPRINGDOTENV_FILENAME`

1. Click en **Modify options** (o `Alt+M`)
2. En la sección **Operating System**, selecciona **Environment variables**
   (`Alt+E`)
3. Se agrega el campo **Environment variables** al formulario. Escribe:

   ```
   SPRINGDOTENV_FILENAME=.env.pre
   ```

   (o `.env.prod` según corresponda; para `dev` no hace falta agregarlo,
   porque `.env` ya es el nombre por defecto)

4. **OK** para guardar.

### 3.4 Resultado: tabla resumen

Crea 3 configuraciones (usa el ícono de "duplicar" en la barra izquierda del
diálogo para no repetir todo desde cero):

| Run Configuration | Active profiles | Environment variables |
|---|---|---|
| `JobsApplication (dev)`  | `dev`  | *(vacío — usa `.env` por defecto)* |
| `JobsApplication (pre)`  | `pre`  | `SPRINGDOTENV_FILENAME=.env.pre` |
| `JobsApplication (prod)` | `prod` | `SPRINGDOTENV_FILENAME=.env.prod` |

---

## 4. Verificación

1. Selecciona la configuración deseada en el dropdown superior de IntelliJ
   (ej. `JobsApplication (pre)`).
2. Ejecuta (▶️ Run).
3. Revisa la salida de `StartupConfigPrinter` al arrancar: los valores de
   `spring.datasource.*`, `spring.mail.*`, etc. deben corresponder a los
   definidos en el archivo `.env.pre` correcto, no a los de `dev`.

Ejemplo de salida esperada con perfil `pre`:
```
📌 PERFIL ACTIVO
spring.profiles.active = pre

🗄️ DATASOURCE
spring.datasource.url = jdbc:mysql://localhost:3306/quartzjobs_prod?...
spring.datasource.username = quartzjobs_prod
```

Si en cambio ves los valores de `dev` (H2, usuario `sa`, etc.) estando en
perfil `pre`, revisa que:
- El campo **Environment variables** tenga exactamente
  `SPRINGDOTENV_FILENAME=.env.pre` (sin espacios extra, sin comillas).
- El archivo `.env.pre` exista en la raíz del proyecto (mismo nivel que
  `pom.xml`).
- Estés ejecutando la Run Configuration correcta (no la de `dev` por error).

---

## 5. Notas y errores relacionados ya resueltos en este proyecto

- **`.env` no cargaba nada al inicio** → causa: Spring Boot no lee `.env`
  de forma nativa; se resolvió agregando `springboot3-dotenv` como
  dependencia.
- **`534-5.7.9 Application-specific password required`** (Gmail) → no es un
  problema de `.env`, sino que Gmail exige un **App Password** de 16
  caracteres en vez de la contraseña normal de la cuenta
  (ver https://myaccount.google.com/apppasswords).
- **`SPRINGDOTENV_FILENAME` no es automático por perfil** → hay que
  declararlo explícitamente por Run Configuration, tal como se explica en
  este tutorial.
