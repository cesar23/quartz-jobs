# Guía completa: Calidad de código en un proyecto Spring Boot + Java 21

Esta guía documenta la configuración completa de calidad de código del proyecto:
**MegaLinter** (Checkstyle, PMD, yamllint, markdownlint, hadolint, prettier) +
**Spotless** (formateo automático tipo Prettier para Java/YAML/Markdown) +
**EditorConfig** (estilo consistente entre editores).

Incluye no solo el "cómo", sino **por qué** cada pieza está configurada así — la mayoría
de estas decisiones vinieron de errores reales que fuimos encontrando y corrigiendo.

---

## 1. Visión general

| Herramienta | Qué hace | Cuándo corre |
| --- | --- | --- |
| **EditorConfig** | Indentación, charset, salto de línea final — mientras escribes | En tu editor, en tiempo real |
| **Spotless** (Maven) | Autoformatea Java/YAML/Markdown, falla el build si algo no está formateado | `mvn verify` (o manual) |
| **MegaLinter** (Docker) | Analiza calidad de código real: Checkstyle, PMD, yamllint, markdownlint, hadolint | Manual / CI |

La idea: **EditorConfig** evita que se cuelen problemas al escribir; **Spotless** los
arregla automáticamente si se cuelan igual; **MegaLinter** es la última red de
seguridad que analiza calidad más allá del simple formato (imports sin usar,
código muerto, complejidad, buenas prácticas de Spring, etc.).

---

## 2. Estructura de archivos

```
proyecto-spring-boot/
├── .editorconfig
├── .yamllint.yml
├── .mega-linter.yml
├── pom.xml                      # con plugin Spotless configurado
├── .github/linters/
│   ├── checkstyle.xml
│   ├── pmd-ruleset.xml
│   └── suppressions.xml
└── src/
```

---

## 3. EditorConfig

Archivo `.editorconfig` en la raíz. No requiere instalar nada: IntelliJ IDEA y
Eclipse lo soportan de forma nativa; VS Code necesita la extensión "EditorConfig
for VS Code".

```ini
# EditorConfig ayuda a mantener un estilo consistente entre editores/IDEs
# https://editorconfig.org — soportado de forma nativa por IntelliJ IDEA,
# VS Code (con extensión), Eclipse (con plugin), etc.
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.java]
indent_size = 4

[*.{yml,yaml,json}]
indent_size = 2

[*.md]
indent_size = 2
# En Markdown, dos espacios al final de línea = salto de línea intencional
trim_trailing_whitespace = false

[*.xml]
indent_size = 4

[Makefile]
indent_style = tab
```

> **Importante:** guarda este archivo con codificación **LF** (no CRLF). Si lo
> editaste en Windows y tiene `\r\n`, Spotless/yamllint lo van a marcar luego.

---

## 4. Spotless (el "Prettier" de Java) — en `pom.xml`

Spotless es un plugin de Maven que formatea código automáticamente y puede
verificar el formato en el build, exactamente como Prettier hace en un
proyecto Node — de hecho, para JSON **llama a Prettier por dentro** (ver
más abajo), así que terminas usando el motor de Prettier sin tener que
invocarlo aparte con `npx`.

- `mvn spotless:apply` → reformatea todo: Java, YAML, Markdown **y JSON**
  (como `prettier` en modo escritura)
- `mvn spotless:check` → solo verifica, sin tocar nada; falla si algo no está
  formateado (como `prettier` en modo comprobación)
- Atado a la fase `verify`: cada `mvn verify` / `mvn install` lo ejecuta solo

Fragmento a añadir dentro de `<build><plugins>` en tu `pom.xml`:

```xml
<plugin>
    <!-- Equivalente a Prettier para Java: formatea el código y puede
         verificar el formato en CI. Usa "mvn spotless:apply" para
         autoformatear (como prettier en modo escritura) y
         "mvn spotless:check" para solo verificar (como prettier en modo check). -->
    <groupId>com.diffplug.spotless</groupId>
    <artifactId>spotless-maven-plugin</artifactId>
    <version>2.44.3</version>
    <configuration>
        <!-- Fuerza LF en todos los formatos gestionados por Spotless,
             evita el aviso "wrong new line character" de yamllint -->
        <lineEndings>UNIX</lineEndings>
        <java>
            <googleJavaFormat>
                <version>1.25.2</version>
                <style>AOSP</style>
            </googleJavaFormat>
            <removeUnusedImports/>
            <trimTrailingWhitespace/>
            <endWithNewline/>
        </java>
        <formats>
            <format>
                <includes>
                    <include>*.md</include>
                    <include>.gitignore</include>
                </includes>
                <trimTrailingWhitespace/>
                <endWithNewline/>
            </format>
            <format>
                <!-- docker-compose*.yml, application*.yml, .mega-linter.yml, etc. -->
                <includes>
                    <include>**/*.yml</include>
                    <include>**/*.yaml</include>
                </includes>
                <excludes>
                    <exclude>target/**</exclude>
                    <exclude>build/**</exclude>
                </excludes>
                <trimTrailingWhitespace/>
                <endWithNewline/>
            </format>
        </formats>
        <json>
            <!-- JSON real (no .json.example, esos son plantillas de config,
                 no datos -- ver excludes). Usa Prettier POR DENTRO (Spotless
                 lo invoca solo), asi mvn spotless:apply/check cubre tambien
                 JSON sin necesitar "npx prettier" aparte. -->
            <includes>
                <include>**/*.json</include>
            </includes>
            <excludes>
                <exclude>target/**</exclude>
                <exclude>my_resources/**</exclude>
            </excludes>
            <prettier/>
        </json>
    </configuration>
    <executions>
        <execution>
            <!-- Verifica el formato automáticamente en cada "mvn verify" (p.ej. en CI) -->
            <id>spotless-check</id>
            <phase>verify</phase>
            <goals>
                <goal>check</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Primer uso

La primera vez que actives Spotless en un proyecto con historial, casi seguro
`mvn spotless:check` falla, porque el código/docs existentes no estaban
formateados según estas reglas. Es normal — corrige así:

```bash
mvn spotless:apply     # reformatea todo lo que haga falta
git diff                # revisa que el cambio sea solo whitespace/formato
git add -A
git commit -m "chore: aplica formato Spotless"
```

A partir de ahí, `mvn spotless:check` (y por tanto `mvn verify`) debería pasar
en verde salvo que alguien introduzca formato inconsistente de nuevo.

### JSON — Spotless llamando a Prettier por dentro

El bloque `<json><prettier/></json>` no es un formateador propio de
Spotless: es Spotless invocando el **motor real de Prettier** (el mismo
que usarías con `npx prettier`), pero orquestado desde Maven, con el
mismo comando `spotless:apply`/`spotless:check` que ya usas para todo lo
demás — en vez de tener un comando distinto para cada tipo de archivo.

Requiere **Node.js/npm** instalados en la máquina donde corras Maven —
Spotless no lo trae embebido, solo lo invoca. En `ubuntu-latest` (el
runner por defecto de GitHub Actions) Node ya viene preinstalado, así
que en CI no hace falta ningún paso extra; en local, si no tienes
Node/npm:

```bash
sudo apt install -y nodejs npm
```

La primera vez que corra este step va a descargar Prettier vía npm
(tarda unos segundos más); las siguientes veces usa caché.

### Comandos de formateo — resumen

```bash
# Formatear TODO (Java + YAML + Markdown + JSON) de una vez
mvn spotless:apply

# Solo verificar, sin tocar nada (lo que corre "mvn verify" automáticamente)
mvn spotless:check

# Si en vez de Spotless prefieres correr Prettier suelto para JSON
# (por ejemplo, para no depender del wrapper de Maven en ese momento):
npx prettier --write "**/*.json"
npx prettier --check "**/*.json"
```

### Lo que Spotless **no** arregla

Spotless solo toca espacios en blanco y saltos de línea — no reescribe
contenido. Cosas como `document-start` (falta el `---` inicial) o
`comments-indentation` en YAML son cambios de **contenido/estilo**, no de
espacios en blanco, así que para esas se ajustó directamente la configuración
de yamllint (sección 6) en vez de intentar que Spotless las "arregle".

---

## 5. MegaLinter — `.mega-linter.yml`

```yaml
---
# .mega-linter.yml
# Configuración optimizada para proyecto Spring Boot (Java 21)

SHOW_ELAPSED_TIME: true
IGNORE_GITIGNORED_FILES: true
PRINT_ALPACA: false

# Solo autoarreglar formatos triviales y seguros (JSON).
# Checkstyle/PMD no tienen modo "fix" real, así que no tiene sentido
# incluirlos aquí, y dejar Java fuera evita sorpresas de reescritura en CI.
APPLY_FIXES:
  - JSON_PRETTIER

# Linters principales para Java + Spring Boot
ENABLE_LINTERS:
  - JAVA_CHECKSTYLE
  - JAVA_PMD
  - MARKDOWN_MARKDOWNLINT
  - YAML_YAMLLINT
  - JSON_PRETTIER
  - DOCKERFILE_HADOLINT

# Carpetas a excluir del análisis (build, tooling, editor, reportes propios de MegaLinter)
EXCLUDED_DIRECTORIES:
  - target
  - build
  - node_modules
  - .github
  - .mvn
  - .gradle
  - .git
  - .vscode
  - .idea
  - megalinter-reports

# NOTA: ya NO se excluye globalmente .md/.yml/.yaml aquí, porque eso
# desactivaba por completo a MARKDOWN_MARKDOWNLINT y YAML_YAMLLINT
# (estaban activados en ENABLE_LINTERS pero nunca encontraban archivos).
# Si quieres seguir ignorando algún markdown puntual, hazlo con
# MARKDOWN_MARKDOWNLINT_FILTER_REGEX_EXCLUDE en vez de la regla global.

# Configuración específica por linter
JAVA_CHECKSTYLE_CONFIG_FILE: .github/linters/checkstyle.xml
JAVA_PMD_CONFIG_FILE: .github/linters/pmd-ruleset.xml
YAML_YAMLLINT_CONFIG_FILE: .yamllint.yml

# Los hallazgos de prioridad 5 (warnings de estilo, ver pmd-ruleset.xml)
# no deben tumbar el linter: solo prioridad 1-4 cuenta como error.
JAVA_PMD_ARGUMENTS:
  - "--minimum-priority"
  - "4"

#DISABLE_LINTERS:
#  - JAVA_CHECKSTYLE   # Desactiva temporalmente para debug
```

### ¿Por qué está así y no de otra forma?

- **`APPLY_FIXES` solo trae `JSON_PRETTIER`**, no `all`. `all` en CI reescribe
  archivos en cada corrida sin control — arriesgado si corres MegaLinter en un
  pipeline. Prettier para JSON es seguro de autoarreglar; Checkstyle/PMD ni
  siquiera tienen modo "fix" real.
- **No hay `FILTER_REGEX_EXCLUDE: \.(md|yml|yaml)$` global.** La versión
  original tenía esa regla Y `MARKDOWN_MARKDOWNLINT` / `YAML_YAMLLINT`
  activados al mismo tiempo — se anulaban entre sí: los linters estaban
  "encendidos" pero el filtro global les impedía ver cualquier archivo.
- **`EXCLUDED_DIRECTORIES` incluye `.git`, `.vscode`, `.idea`,
  `megalinter-reports`.** Sin esto, `JSON_PRETTIER` terminaba analizando
  archivos irrelevantes como `.vscode/settings.json` o metadata interna de
  Git, ensuciando el reporte.

---

## 6. yamllint — `.yamllint.yml`

```yaml
---
# .yamllint.yml
# Basado en el perfil "default" de yamllint, relajando solo reglas
# puramente estilísticas que no afectan la validez ni la legibilidad
# real del YAML (docker-compose, application.yml, etc.)
extends: default

rules:
  # No exigir "---" al inicio de cada archivo YAML: es opcional y
  # muchos archivos de Spring Boot / docker-compose no lo usan.
  document-start: disable

  # Los comentarios alineados "a ojo" en docker-compose/application.yml
  # no siempre calzan con la indentación estricta que exige esta regla.
  comments-indentation: disable

  # Solo exige al menos 1 espacio después de "#", sin ser tan estricto
  # como el default (que pide 2 espacios antes del comentario en línea).
  comments:
    min-spaces-from-content: 1

  # Spring Boot suele tener líneas largas en application.yml
  # (URLs de datasource, mensajes, etc.)
  line-length:
    max: 160
    level: warning
```

Todo lo demás del perfil `default` de yamllint queda activo: indentación
consistente, duplicados de claves, `truthy` (evita `yes`/`no` ambiguos),
espacios en corchetes, etc. Solo se relajaron las 3-4 reglas que generaban
ruido real en `docker-compose*.yml` y `application*.yml` sin aportar valor.

---

## 7. Checkstyle — `.github/linters/checkstyle.xml`

```xml
<?xml version="1.0"?>
<!--.github/linters/checkstyle.xml-->
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">

<module name="Checker">
    <property name="charset" value="UTF-8"/>

    <module name="SuppressionFilter">
        <property name="file"
                  value=".github/linters/suppressions.xml"/>
    </module>

    <module name="LineLength">
        <property name="max" value="120"/>
        <property name="severity" value="warning"/>
    </module>

    <module name="TreeWalker">
        <module name="UnusedImports"/>
        <module name="AvoidStarImport"/>
    </module>
</module>
```

### Errores que corregimos aquí (y por qué importan si tocas este archivo)

1. **`LineLength` estaba anidado dentro de `TreeWalker`.** Checkstyle exige
   que `LineLength` sea hijo directo de `Checker` (no de `TreeWalker`), porque
   es una regla "de archivo crudo", no del árbol sintáctico. El error exacto
   era: `TreeWalker is not allowed as a parent of LineLength`.
2. **La declaración `<?xml version="1.0"?>` tenía un comentario antes.**
   El parser XML estricto que usa Checkstyle (Xerces) exige que `<?xml?>`
   sea *literalmente* la primera línea del archivo — ni un comentario puede
   ir antes. El error era: `The processing instruction target matching
   "[xX][mM][lL]" is not allowed`.
3. **`LineLength` se rebajó a `severity="warning"`.** Con esto, sigue
   apareciendo en el reporte pero no rompe el build — Checkstyle solo falla
   el exit code por violaciones de severidad `error` (el default).

---

## 8. PMD — `.github/linters/pmd-ruleset.xml`

```xml
<?xml version="1.0"?>
<!--.github/linters/pmd-ruleset.xml-->
<ruleset name="Custom PMD Rules for Spring Boot"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0 http://pmd.sourceforge.net/ruleset_2_0_0.xsd">

    <description>Reglas PMD para proyecto Spring Boot Java 21</description>

    <!-- Reglas recomendadas -->
    <rule ref="category/java/bestpractices.xml">
        <!-- Demasiado ruidosas como error para tests unitarios con estilo BDD; se degradan a warning más abajo -->
        <exclude name="UnitTestShouldIncludeAssert"/>
        <exclude name="UnitTestContainsTooManyAsserts"/>
    </rule>

    <rule ref="category/java/codestyle.xml">
        <!-- Pedantes / cuestión de estilo; se degradan a warning más abajo en vez de eliminarse -->
        <exclude name="MethodArgumentCouldBeFinal"/>
        <exclude name="LocalVariableCouldBeFinal"/>
        <exclude name="AtLeastOneConstructor"/>
        <exclude name="ShortVariable"/>
        <exclude name="LongVariable"/>
        <exclude name="ShortClassName"/>
        <exclude name="OnlyOneReturn"/>
        <exclude name="TooManyStaticImports"/>
        <!-- Los tests usan nombres en español tipo snake/camel mixto (ej. listar_devuelveLaVista...) -->
        <exclude name="MethodNamingConventions"/>
    </rule>

    <rule ref="category/java/design.xml">
        <!-- Se auto-desactivaba con warning por falta de configuración; la excluimos explícitamente -->
        <exclude name="LoosePackageCoupling"/>
    </rule>

    <rule ref="category/java/errorprone.xml"/>
    <rule ref="category/java/performance.xml"/>

    <!-- ==================== REGLAS DEGRADADAS A WARNING ====================
         Prioridad 5 = Info/Warning en PMD (1=más grave, 5=menos grave).
         Se siguen mostrando en el reporte, junto al archivo y línea, pero no
         se tratan como errores bloqueantes. -->
    <rule ref="category/java/codestyle.xml/MethodArgumentCouldBeFinal"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/LocalVariableCouldBeFinal"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/AtLeastOneConstructor"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/ShortVariable"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/LongVariable"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/ShortClassName"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/OnlyOneReturn"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/TooManyStaticImports"><priority>5</priority></rule>
    <rule ref="category/java/codestyle.xml/MethodNamingConventions"><priority>5</priority></rule>
    <rule ref="category/java/bestpractices.xml/UnitTestShouldIncludeAssert"><priority>5</priority></rule>
    <rule ref="category/java/bestpractices.xml/UnitTestContainsTooManyAsserts"><priority>5</priority></rule>

    <!-- Desactivar reglas muy estrictas si quieres -->
    <!-- <rule ref="category/java/design.xml/TooManyMethods" level="warning"/> -->

</ruleset>
```

### Por qué el patrón "excluir + re-incluir con prioridad 5"

PMD no distingue "error" vs "warning" como concepto — solo tiene
**prioridades 1 (más grave) a 5 (menos grave)**. Por defecto, MegaLinter
marca el linter en rojo (❌) ante **cualquier** violación, sin importar su
prioridad.

El patrón que usamos:

1. Se **excluyen** las reglas pedantes del bloque de categoría (`bestpractices.xml`,
   `codestyle.xml`) para que no entren con su prioridad original (alta).
2. Se **vuelven a incluir individualmente**, cada una con `<priority>5</priority>`.

Así siguen apareciendo en el reporte (visibles, junto al archivo y línea),
pero como prioridad 5. Para que PMD deje de fallar el build por ellas,
hace falta además el flag `--minimum-priority 4` que ya está en
`JAVA_PMD_ARGUMENTS` dentro de `.mega-linter.yml` (sección 5) — eso le dice
a PMD que solo cuente como error las prioridades 1-4.

### Reglas que quedaron activas como error real (no tocadas)

Todo `errorprone.xml` y `performance.xml` completos, más las reglas de
`bestpractices.xml`, `codestyle.xml` y `design.xml` que no aparecen en la
lista de exclusión/degradación — esas sí deben tratarse como errores
bloqueantes porque suelen señalar bugs reales, no solo preferencias de
estilo.

### El bug de XML que también tuvo este archivo

Igual que `checkstyle.xml`, tenía un comentario antes de `<?xml version="1.0"?>`,
lo cual rompía el parser XML de PMD con el mismo error de
"processing instruction". Se corrigió moviendo el comentario después de la
declaración XML.

---

## 9. Supresiones de Checkstyle — `.github/linters/suppressions.xml`

```xml
<?xml version="1.0"?>
<!--.github/linters/suppressions.xml-->
<!DOCTYPE suppressions PUBLIC
        "-//Checkstyle//DTD SuppressionFilter Configuration 1.2//EN"
        "https://checkstyle.org/dtds/suppressions_1_2.dtd">

<suppressions>
    <!-- ==================== CHECKSTYLE SUPRESIONES ==================== -->

    <!-- Ignorar archivos generados o de tests -->
    <suppress files="[\\/]target[\\/]" checks=".+"/>
    <suppress files="[\\/]build[\\/]" checks=".+"/>
    <suppress files="Test\.java" checks=".+"/>
    <suppress files="IT\.java" checks=".+"/>

    <!-- Reglas comunes que suelen molestar en Spring Boot -->
    <suppress files="\.java$" checks="MissingJavadocMethod" message="Missing a Javadoc comment"/>
    <suppress files="\.java$" checks="MissingJavadocType"/>
    <suppress files="\.java$" checks="MissingJavadocPackage"/>

    <!-- Ignorar en controladores y DTOs (muy comunes en Spring) -->
    <suppress files="Controller\.java|Dto\.java|Request\.java|Response\.java" checks="DataClass"/>
    <suppress files="Controller\.java" checks="CyclomaticComplexity"/>
    <suppress files="Controller\.java" checks="GodClass"/>

    <!-- Ignorar serialVersionUID en clases Serializable -->
    <suppress files="\.java$" checks="MagicNumber" message=".*serialVersionUID.*"/>

    <!-- ==================== PMD SUPRESIONES (si usas PMD) ==================== -->
    <!-- PMD usa el mismo archivo suppressions.xml si lo configuras correctamente -->

</suppressions>
```

> **Nota importante:** este `suppressions.xml` solo lo lee **Checkstyle** (vía
> `SuppressionFilter`). PMD **no** entiende este formato ni este archivo — el
> comentario final es aspiracional, no funcional. Las supresiones de PMD se
> manejan directamente en `pmd-ruleset.xml` con `<exclude>` (sección 8).

Ya no incluye la supresión de `LineLength`, porque ahora es `severity="warning"`
en `checkstyle.xml` (sección 7) — al no fallar el build, tiene sentido dejarla
visible en vez de ocultarla del todo.

---

## 10. Flujo de trabajo día a día

```bash
# 1. Mientras escribes: EditorConfig actúa solo en tu editor, sin comandos.

# 2. Antes de hacer commit / al preparar un PR:
mvn spotless:apply      # autoformatea Java + YAML + Markdown + JSON
mvn verify               # corre spotless:check + tests + build

# 3. Antes de un merge importante, o periódicamente:
docker run --rm \
  -v $(pwd):/tmp/lint:rw \
  -e GIT_SAFE_DIRECTORY=/tmp/lint \
  -e APPLY_FIXES=none \
  ghcr.io/oxsecurity/megalinter-java:v9

### Con correcciones automáticas:
docker run --rm \
  -v $(pwd):/tmp/lint:rw \
  -e GIT_SAFE_DIRECTORY=/tmp/lint \
  -e APPLY_FIXES=all \
  ghcr.io/oxsecurity/megalinter-java:v9

```

### Alias útiles (agrega a `~/.bashrc` o `~/.zshrc`)

```bash
alias megalint="docker run --rm -v \$(pwd):/tmp/lint:rw -e GIT_SAFE_DIRECTORY=/tmp/lint -e APPLY_FIXES=none ghcr.io/oxsecurity/megalinter-java:v9"
alias spotless-fix="mvn spotless:apply"
```

---

## 11. Checklist de diagnóstico (por si algo vuelve a fallar)

| Síntoma | Causa típica | Dónde mirar |
| --- | --- | --- |
| `Checkstyle... cannot initialize module TreeWalker` | Un módulo de "archivo crudo" (`LineLength`, `FileLength`, etc.) está mal ubicado dentro de `TreeWalker` | `checkstyle.xml` — debe ir directo bajo `Checker` |
| `unable to parse configuration... processing instruction` | Algo (comentario, BOM) antes de `<?xml version="1.0"?>` | Cualquier XML del proyecto — el `<?xml?>` debe ser la primera línea, sin excepción |
| PMD marca ❌ aunque la regla tenga prioridad 5 | Falta `--minimum-priority` en `JAVA_PMD_ARGUMENTS` | `.mega-linter.yml` |
| Un linter activado en `ENABLE_LINTERS` nunca encuentra archivos | `FILTER_REGEX_EXCLUDE` global bloqueando esa extensión | `.mega-linter.yml` |
| `JSON_PRETTIER` lintando archivos raros (`.vscode`, `.git`) | Falta esa carpeta en `EXCLUDED_DIRECTORIES` | `.mega-linter.yml` |
| yamllint: `wrong new line character` / `no new line at EOF` | Archivo con `\r\n` o sin salto final, y Spotless no lo cubre | Añade la extensión al bloque `formats` de Spotless en `pom.xml`, luego `mvn spotless:apply` |
| yamllint: `missing document start "---"` / `comments-indentation` | Reglas de contenido/estilo que Spotless no puede autoarreglar | Ajusta `.yamllint.yml` (relajar la regla) o corrige el archivo a mano |
| `mvn spotless:check` falla en un archivo que "no toqué" | Es la primera vez que corre sobre ese archivo/historial | `mvn spotless:apply` una vez, commitear, y listo |
| `spotless:apply`/`check` falla en JSON con error de Node/npm | El step `<json><prettier/></json>` necesita Node.js/npm instalado en esa máquina | `sudo apt install -y nodejs npm` (en CI con `ubuntu-latest` no hace falta, ya lo trae) |

---

## 12. Próximos pasos sugeridos

1. Corre `mvn spotless:apply` y `megalint` una vez para dejar el repo limpio
   con esta configuración.
2. Haz commit de los 6 archivos de configuración (`.editorconfig`,
   `.yamllint.yml`, `.mega-linter.yml`, `pom.xml`, `checkstyle.xml`,
   `pmd-ruleset.xml`, `suppressions.xml`).
3. Integra `mvn verify` (con Spotless) y el `docker run megalinter` como
   pasos de tu pipeline de CI/CD (GitHub Actions), para que cada PR se
   valide automáticamente sin depender de que alguien lo corra a mano.
