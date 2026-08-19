"""
generate_docs_optimize_prompt_ia.py
====================================
Optimizador de archivos .md generados por la suite de generadores de documentación:
  - generate_docs_go.py
  - generate_docs_typescript.py
  - generate_docs_java.py
  - generate_docs_php.py  (Laravel / PHP)

Para consumo mínimo de tokens en LLMs (Claude, GPT-4, Gemini...).

Técnicas aplicadas:
  1. Eliminación de comentarios por lenguaje
       Go         → // línea y /* bloque */
       TS/JS      → // línea, /* bloque */, /** JSDoc */
       Java       → // línea, /* bloque */, /** Javadoc */
       Kotlin     → // línea, /* bloque */, /** KDoc */
       Groovy     → // línea, /* bloque */
       Vue        → <!-- --> (en <template>) y // dentro de <script>
       PHP        → // línea, # línea, /* bloque */, /** PHPDoc */
       Blade      → {{-- --}} y @php/@endphp internos
       YAML       → # comentarios (respetando strings con #)
       Properties → # y ! comentarios
  2. Normalización de espacios en blanco
       - Colapsar líneas en blanco múltiples a una sola
       - Quitar espacios/tabs al final de línea
  3. Eliminación de metadatos redundantes del markdown
       - "Ruta completa" / "Ruta" duplican info ya presente en el título
       - Emojis decorativos en encabezados (📄 📁 etc.) → texto plano
       - Timestamps de generación en cada sección → solo en índice
       - Separadores --- redundantes entre secciones
       - Campo "Categoría" (ya está en el título del documento)
  4. Compresión de bloques de código grandes
       - Archivos > MAX_CODE_CHARS: solo se conservan firmas de
         funciones/tipos (sin cuerpos) → resumen estructural
       - Soporta Go, TypeScript, Java, PHP
  5. Eliminación de secciones de bajo valor
       - Archivos de lock (go.sum, pnpm-lock) → solo primeras líneas
       - Archivos i18n JSON → solo claves de primer nivel
  6. Headers markdown compactados
       - ## 📄 `path/file.go` → ## path/file.go

Reducción esperada de tokens: 30–50% según tipo de archivo.
"""

import re
import json
import shutil
from pathlib import Path
from datetime import datetime


# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------
INPUT_DIR  = "docs_md"           # Carpeta generada por cualquier generador
OUTPUT_DIR = "docs_md_optimize"  # Carpeta de salida optimizada

# Umbral (chars) a partir del cual se aplica compresión de cuerpo de función
MAX_CODE_CHARS = 6_000

# Máximo de líneas que se conservan de archivos de lock/i18n
LOCK_PREVIEW_LINES = 8
I18N_PREVIEW_KEYS  = 12


# ---------------------------------------------------------------------------
# Utilidades: conteo aproximado de tokens (1 token ≈ 4 chars para cl100k)
# ---------------------------------------------------------------------------
def estimate_tokens(char_count: int) -> int:
    return max(1, char_count // 4)


# ---------------------------------------------------------------------------
# 1. Eliminación de comentarios por lenguaje
# ---------------------------------------------------------------------------

def remove_cstyle_block_comments(code: str) -> str:
    """Elimina /** */ y /* */ (compartido por Go, TS, Java, Kotlin, Groovy)."""
    code = re.sub(r'/\*\*.*?\*/', '', code, flags=re.DOTALL)
    code = re.sub(r'/\*.*?\*/', '',  code, flags=re.DOTALL)
    return code


def remove_cstyle_line_comments(code: str) -> str:
    """Elimina comentarios de línea // por línea (heurística: ignora URLs http://)."""
    lines = []
    for line in code.splitlines():
        stripped = re.sub(r'(?<!:)\s*//.*$', '', line)
        lines.append(stripped)
    return '\n'.join(lines)


def remove_go_comments(code: str) -> str:
    """Elimina comentarios Go (// y /* */)."""
    code = remove_cstyle_block_comments(code)
    return remove_cstyle_line_comments(code)


def remove_ts_comments(code: str) -> str:
    """Elimina comentarios TypeScript/JavaScript (// /** */ /* */)."""
    code = remove_cstyle_block_comments(code)
    return remove_cstyle_line_comments(code)


def remove_java_comments(code: str) -> str:
    """
    Elimina comentarios Java/Kotlin/Groovy (Javadoc /** */, bloques /* */, líneas //).
    Mismo mecanismo que TS — sintaxis idéntica.
    """
    code = remove_cstyle_block_comments(code)
    return remove_cstyle_line_comments(code)


def remove_vue_comments(code: str) -> str:
    """Elimina comentarios HTML (<!-- -->) y de script en Vue SFC."""
    code = re.sub(r'<!--.*?-->', '', code, flags=re.DOTALL)

    def strip_script_comments(m: re.Match) -> str:
        return remove_ts_comments(m.group(0))

    code = re.sub(
        r'<script[^>]*>.*?</script>',
        strip_script_comments,
        code,
        flags=re.DOTALL | re.IGNORECASE,
    )
    return code


def _strip_inline_hash_comment(line: str) -> str:
    """
    FIX: Elimina comentarios # inline respetando valores entre comillas.
    YAML y Properties comparten esta lógica.

    Ejemplos seguros:
      key: value  # comentario     → key: value
      url: "redis://host/0#db"     → url: "redis://host/0#db"  (no tocado)
      key: 'valor con # dentro'    → igual
    """
    in_single = False
    in_double = False
    for i, ch in enumerate(line):
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == '#' and not in_single and not in_double:
            # Solo es comentario si está al inicio o precedido por espacio
            if i == 0 or line[i - 1] in (' ', '\t'):
                return line[:i].rstrip()
            break
    return line


def remove_yaml_comments(code: str) -> str:
    """
    FIX: Elimina comentarios YAML (#) sin corromper strings que contienen #.
    Antes: re.sub(r'\\s*#.*$', '', line) — rompía valores como "redis://host/0#db".
    Ahora: parser carácter a carácter respetando comillas.
    """
    lines = []
    for line in code.splitlines():
        lines.append(_strip_inline_hash_comment(line))
    return '\n'.join(lines)


def remove_properties_comments(code: str) -> str:
    """
    Elimina comentarios de archivos .properties de Java/Spring.
    Soporta # y ! como marcadores de comentario.
    Solo se eliminan líneas de comentario puras (# o ! al inicio de la línea).
    Las propiedades con valores que contengan # o ! no se tocan.
    """
    lines = []
    for line in code.splitlines():
        stripped = line.lstrip()
        if stripped.startswith('#') or stripped.startswith('!'):
            lines.append('')
        else:
            lines.append(line)
    return '\n'.join(lines)


def remove_php_comments(code: str) -> str:
    """
    Elimina comentarios PHP:
      - PHPDoc / bloques  /** ... */ y /* ... */
      - Líneas            // ...
      - Hash              # ... (PHP también admite # como comentario de línea)
    No toca strings entre comillas simples o dobles con # o //.
    """
    # 1. Bloques /** */ y /* */
    code = remove_cstyle_block_comments(code)

    # 2. Comentarios de línea //  (heurística: respeta http://)
    code = remove_cstyle_line_comments(code)

    # 3. Comentarios de línea #  (fuera de strings)
    lines = []
    for line in code.splitlines():
        lines.append(_strip_inline_hash_comment(line))
    return '\n'.join(lines)


def remove_blade_comments(code: str) -> str:
    """
    Elimina comentarios de plantillas Blade de Laravel:
      - {{-- comentario --}}   → comentario nativo de Blade
      - <!-- comentario -->    → comentarios HTML que Blade también acepta
    Los bloques @php/@endphp se procesan como PHP puro para quitarles los //.
    """
    # Comentarios Blade nativos  {{-- ... --}}
    code = re.sub(r'\{\{--.*?--\}\}', '', code, flags=re.DOTALL)

    # Comentarios HTML <!-- ... -->
    code = re.sub(r'<!--.*?-->', '', code, flags=re.DOTALL)

    # Quitar comentarios // dentro de bloques @php ... @endphp
    def strip_php_block(m: re.Match) -> str:
        return remove_php_comments(m.group(0))

    code = re.sub(
        r'@php\b.*?@endphp',
        strip_php_block,
        code,
        flags=re.DOTALL | re.IGNORECASE,
    )
    return code


def remove_comments(code: str, lang: str) -> str:
    """Dispatcher principal: selecciona la función de eliminación según lenguaje."""
    dispatch = {
        'go':         remove_go_comments,
        'typescript': remove_ts_comments,
        'javascript': remove_ts_comments,
        'java':       remove_java_comments,
        'kotlin':     remove_java_comments,
        'groovy':     remove_java_comments,   # build.gradle
        'vue':        remove_vue_comments,
        'yaml':       remove_yaml_comments,
        'bash':       remove_yaml_comments,   # # es comentario en bash también
        'properties': remove_properties_comments,
        'php':        remove_php_comments,    # PHP / Laravel
        'blade':      remove_blade_comments,  # Blade templates
    }
    fn = dispatch.get(lang)
    return fn(code) if fn else code


# ---------------------------------------------------------------------------
# 2. Normalización de espacios en blanco
# ---------------------------------------------------------------------------

def normalize_whitespace(code: str) -> str:
    """
    - Elimina \\r (Windows line endings) antes de procesar
    - Elimina espacios/tabs al final de cada línea
    - Colapsa 2+ líneas en blanco consecutivas → 1 línea en blanco
    - No elimina indentación (rompe Python/YAML)
    """
    # FIX: normalizar \r\n → \n antes de splitlines para evitar líneas con \r residual
    code = code.replace('\r\n', '\n').replace('\r', '\n')

    lines = [l.rstrip() for l in code.splitlines()]
    result = []
    blank_count = 0
    for line in lines:
        if line == '':
            blank_count += 1
            if blank_count <= 1:
                result.append('')
        else:
            blank_count = 0
            result.append(line)
    return '\n'.join(result)


# ---------------------------------------------------------------------------
# 3. Compresión de código grande: extrae solo firmas
# ---------------------------------------------------------------------------

def compress_go_code(code: str) -> str:
    """
    Para archivos Go grandes: conserva imports y firmas de func/type/const/var.
    FIX: depth se actualiza también en líneas normales (no solo en skip_body),
    evitando desincronización por bloques multilínea fuera de funciones.
    """
    lines = code.splitlines()
    result = []
    depth = 0
    skip_body = False
    i = 0

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # Declaraciones de nivel superior: siempre incluir
        if re.match(r'^(package |import |type |const |var )', stripped):
            skip_body = False
            result.append(line)
            # Solo ajustar depth si hay llaves (type struct {, etc.)
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            i += 1
            continue

        # Firma de función
        if re.match(r'^func\s', stripped):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth > 0:
                skip_body = True
                result.append('  // ...cuerpo omitido...')
            i += 1
            continue

        if skip_body:
            depth += line.count('{') - line.count('}')
            if depth <= 0:
                depth = 0
                skip_body = False
                result.append(line)  # línea de cierre '}'
        else:
            # FIX: actualizar depth también en líneas normales fuera de skip_body
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            result.append(line)

        i += 1

    return '\n'.join(result)


def compress_ts_code(code: str) -> str:
    """
    Para archivos TS grandes: conserva imports, exports y firmas de función/clase/interface.
    FIX: depth se actualiza en TODAS las líneas (no solo en skip_body), evitando
    desincronización causada por imports multilínea como:
        import {
          ComponentA,
          ComponentB       ← estas líneas no actualizaban depth antes
        } from '@angular/core'
    """
    lines = code.splitlines()
    result = []
    depth = 0
    skip_body = False

    for line in lines:
        stripped = line.strip()

        # Imports y exports de tipo siempre incluir
        if re.match(r'^(import |export\s+(type\s+|interface\s+|enum\s+|class\s+|function\s+|const\s+|default\s+)?)', stripped):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            skip_body = False
            continue

        # Declaraciones de función/clase/interface
        if re.match(
            r'^(export\s+)?(async\s+)?function\s'
            r'|^(export\s+)?(abstract\s+)?class\s'
            r'|^(export\s+)?interface\s'
            r'|^(export\s+)?type\s+\w+\s*=',
            stripped,
        ):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth > 0:
                skip_body = True
                result.append('  // ...cuerpo omitido...')
            continue

        if skip_body:
            depth += line.count('{') - line.count('}')
            if depth <= 0:
                depth = 0
                skip_body = False
                result.append(line)  # línea de cierre '}'
        else:
            # FIX: actualizar depth en líneas normales para mantener sincronía
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            result.append(line)

    return '\n'.join(result)


def compress_java_code(code: str) -> str:
    """
    NEW: Para archivos Java grandes: conserva anotaciones, firmas de
    clase/método/campo, elimina cuerpos de métodos.

    Preserva:
      - package, import
      - Anotaciones (@Controller, @Override, etc.)
      - Firmas de clase/interface/enum/record
      - Firmas de métodos (incluyendo modificadores de acceso)
      - Declaraciones de campos
    """
    lines = code.splitlines()
    result = []
    depth = 0
    skip_body = False

    for line in lines:
        stripped = line.strip()

        # package e imports: siempre incluir
        if re.match(r'^(package\s|import\s)', stripped):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            skip_body = False
            continue

        # Anotaciones (@...) fuera de cuerpos: siempre incluir
        if stripped.startswith('@') and not skip_body:
            result.append(line)
            continue

        # Declaraciones de clase/interface/enum/record
        if re.match(
            r'^(public\s+|private\s+|protected\s+|abstract\s+|final\s+|static\s+)*'
            r'(class|interface|enum|record)\s+\w+',
            stripped,
        ):
            result.append(line)
            depth += line.count('{') - line.count('}')
            # No marcamos skip_body aquí: el cuerpo de la clase contiene métodos
            # que queremos seguir evaluando línea a línea
            skip_body = False
            continue

        # Firmas de método (public/private/protected ... nombreMetodo(...) {)
        method_match = re.match(
            r'^(?:(?:public|private|protected|static|final|abstract|synchronized|override)\s+)*'
            r'(?:[\w<>\[\],\s]+\s+)?(\w+)\s*\([^)]*\)\s*(?:throws\s+[\w,\s]+\s*)?(\{|;)?',
            stripped,
        )
        if method_match and not skip_body and stripped.endswith('{'):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth > 0:
                skip_body = True
                result.append('    // ...cuerpo omitido...')
            continue

        if skip_body:
            depth += line.count('{') - line.count('}')
            if depth <= 0:
                depth = 0
                skip_body = False
                result.append(line)  # línea de cierre '}'
        else:
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            result.append(line)

    return '\n'.join(result)


def compress_php_code(code: str) -> str:
    """
    Para archivos PHP/Laravel grandes: conserva namespace, use, anotaciones,
    firmas de clase/trait/interface/enum y firmas de métodos.
    Elimina cuerpos de métodos para reducir tokens.

    Preserva:
      - <?php / <?=
      - namespace, use
      - Atributos PHP8  #[Attribute(...)  ]
      - Anotaciones PHPDoc compactas (@param, @return, etc.) — ya eliminadas
        por remove_php_comments, pero si no se procesaron se conservan
      - Firmas de class / abstract class / final class / trait / interface / enum
      - Modificadores + firma de método (public/protected/private ... function nombre(...))
      - Declaraciones de propiedad ($campo con tipo)
      - Constantes de clase (const NAME = ...)
    """
    lines = code.splitlines()
    result = []
    depth = 0
    skip_body = False

    for line in lines:
        stripped = line.strip()

        # Etiquetas de apertura PHP
        if re.match(r'^<\?(?:php|=)?', stripped):
            result.append(line)
            continue

        # namespace y use
        if re.match(r'^(?:namespace|use)\s', stripped):
            result.append(line)
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            skip_body = False
            continue

        # Atributos PHP 8  #[...] (fuera de cuerpos)
        if stripped.startswith('#[') and not skip_body:
            result.append(line)
            continue

        # Declaraciones de clase / trait / interface / enum
        if re.match(
            r'^(?:(?:abstract|final|readonly)\s+)*(?:class|trait|interface|enum)\s+\w+',
            stripped,
        ):
            result.append(line)
            depth += line.count('{') - line.count('}')
            skip_body = False  # el cuerpo de la clase se evalúa línea a línea
            continue

        # Constantes de clase: const NAME = ...;
        if re.match(r'^(?:public\s+|protected\s+|private\s+)?const\s+', stripped) and not skip_body:
            result.append(line)
            continue

        # Propiedades tipadas: public/protected/private [static] [readonly] Type $prop
        if (
            re.match(
                r'^(?:public|protected|private)(?:\s+(?:static|readonly|static\s+readonly|readonly\s+static))?\s+'
                r'(?!\s*function\b)',
                stripped,
            )
            and not skip_body
            and not stripped.lstrip().startswith('function')
        ):
            # Solo si la línea no abre un método
            if not re.search(r'\bfunction\b', stripped):
                result.append(line)
                continue

        # Firmas de método: [modificadores] function nombre(...): tipo {
        method_match = re.match(
            r'^(?:(?:public|protected|private|static|abstract|final|override)\s+)*'
            r'function\s+\w+\s*\(',
            stripped,
        )
        if method_match and not skip_body:
            result.append(line)
            opens = line.count('{') - line.count('}')
            depth += opens
            # Estilo PSR-12 / PSR-2: '{' en misma línea → depth sube a ≥2
            if '{' in stripped and depth >= 2:
                skip_body = True
                result.append('    // ...cuerpo omitido...')
            else:
                # '{' vendrá en la próxima línea; marcamos que esperamos apertura
                # Se maneja en el bloque else abajo al detectar línea '{'
                pass
            continue

        # Apertura de cuerpo de método en línea aparte (estilo PSR-2: '{' sola)
        if stripped == '{' and not skip_body and depth >= 1:
            depth += 1
            if depth >= 2:
                result.append(line)
                skip_body = True
                result.append('    // ...cuerpo omitido...')
            else:
                result.append(line)
            continue

        if skip_body:
            depth += line.count('{') - line.count('}')
            # depth=1 → volvemos al nivel de la clase (cerramos el método)
            if depth <= 1:
                depth = max(depth, 0)
                skip_body = False
                result.append(line)  # línea de cierre '}'
        else:
            depth += line.count('{') - line.count('}')
            if depth < 0:
                depth = 0
            result.append(line)

    return '\n'.join(result)




def summarize_lock_file(code: str, filename: str) -> str:
    lines = code.splitlines()
    preview = lines[:LOCK_PREVIEW_LINES]
    total = len(lines)
    return (
        '\n'.join(preview)
        + f'\n\n# ... ({total} líneas totales — archivo de lock omitido para reducir tokens)'
    )


def _is_i18n_file(filename: str) -> bool:
    """
    FIX: Detección ampliada de archivos i18n JSON.
    Antes: solo detectaba códigos de 2 letras en el path (en, es, fr...).
    Ahora: también detecta patrones comunes como translations.json, messages.*.json, etc.
    """
    name_lower = filename.lower()

    # Patrón original: código de idioma ISO en el path
    lang_code_pattern = re.search(
        r'[/\\](ar|bg|ca|cs|da|de|el|en|es|et|fa|fi|fr|he|hr|hu|'
        r'id|is|it|ja|ko|lt|lv|ms|nl|no|pl|pt|ro|ru|sk|sl|sv|th|'
        r'tr|uk|vi|zh)[^/\\]*\.json',
        name_lower,
    )
    if lang_code_pattern:
        return True

    # Patrones adicionales: nombres comunes de archivos de traducción
    i18n_name_patterns = [
        r'translations?\.json$',
        r'messages?\.([\w-]+\.)?json$',
        r'strings?\.([\w-]+\.)?json$',
        r'locale[s]?\.([\w-]+\.)?json$',
        r'lang\.([\w-]+\.)?json$',
        r'i18n\.([\w-]+\.)?json$',
        r'\.(en|es|fr|de|it|pt|ru|ja|ko|zh)(-[\w]+)?\.json$',
        # Laravel: resources/lang/es/validation.php, auth.php, pagination.php, passwords.php
        r'[/\\]lang[/\\][\w-]+[/\\](?:validation|auth|pagination|passwords|messages?)\.(php|json)$',
    ]
    return any(re.search(p, name_lower) for p in i18n_name_patterns)


def summarize_i18n_json(code: str) -> str:
    """Muestra solo las claves de primer nivel del JSON de traducción."""
    try:
        data = json.loads(code)
        keys = list(data.keys())[:I18N_PREVIEW_KEYS]
        total = len(data)
        preview = {k: data[k] for k in keys}
        summary = json.dumps(preview, ensure_ascii=False, indent=2)
        return summary + f'\n\n// ... ({total} claves totales — i18n resumido para reducir tokens)'
    except Exception:
        lines = code.splitlines()
        return '\n'.join(lines[:LOCK_PREVIEW_LINES]) + '\n// ... (resumido)'


# ---------------------------------------------------------------------------
# 5. Limpieza de markdown: encabezados, metadatos, separadores
# ---------------------------------------------------------------------------

EMOJI_PATTERN = re.compile(
    '[\U0001F300-\U0001FFFF'
    '\U00002600-\U000027BF'
    '\U0001F900-\U0001F9FF'
    ']+',
    flags=re.UNICODE,
)


def clean_markdown_structure(md: str) -> str:
    """
    Limpia el markdown del archivo .md completo:
    - Quita emojis de encabezados
    - FIX: quita tanto "Ruta completa" como "Ruta" (formatos viejo y nuevo)
    - Quita timestamps de sección
    - Colapsa separadores --- múltiples
    - Quita línea "Categoría" (ya está en el título del doc)
    """
    lines = md.splitlines()
    result = []
    prev_was_sep = False

    for line in lines:
        # Quitar emojis de encabezados
        if line.startswith('#'):
            line = EMOJI_PATTERN.sub('', line).strip()
            line = re.sub(r'\s{2,}', ' ', line)

        # FIX: eliminar "Ruta completa" Y "Ruta" (ambos formatos de los generadores)
        if re.match(r'\*\*Ruta(?: completa)?:\*\*', line):
            continue

        # Eliminar campo Categoría (ya está en el título del documento)
        if re.match(r'\*\*Categoría:\*\*', line):
            continue

        # Eliminar campo Category (versión en inglés del Java generator original)
        if re.match(r'\*\*Category:\*\*', line):
            continue

        # Quitar timestamps de sección
        if re.match(r'\*\*Generado:\*\*\s+\d{4}-\d{2}-\d{2}', line):
            continue

        # Colapsar separadores --- consecutivos
        if line.strip() == '---':
            if prev_was_sep:
                continue
            prev_was_sep = True
        else:
            prev_was_sep = False

        result.append(line)

    return '\n'.join(result)


# ---------------------------------------------------------------------------
# 6. Optimizador de bloques de código dentro de un .md
# ---------------------------------------------------------------------------

CODE_BLOCK_RE = re.compile(
    r'```(?P<lang>\w*)\n(?P<code>.*?)```',
    flags=re.DOTALL,
)


def optimize_code_block(lang: str, code: str, filename: str) -> str:
    """
    Aplica optimizaciones al bloque de código según su lenguaje y tamaño.
    """
    name_lower = filename.lower()

    # Archivos de lock → resumen mínimo (agrega composer.lock de Laravel)
    if any(x in name_lower for x in ('go.sum', 'pnpm-lock', 'yarn.lock', 'package-lock', 'composer.lock')):
        return summarize_lock_file(code, filename)

    # FIX: i18n JSON con detección ampliada
    if lang == 'json' and _is_i18n_file(name_lower):
        return summarize_i18n_json(code)

    # Eliminar comentarios
    code = remove_comments(code, lang)

    # Normalizar espacios en blanco
    code = normalize_whitespace(code)

    # Comprimir cuerpos si el código es muy grande
    if len(code) > MAX_CODE_CHARS:
        if lang == 'go':
            code = compress_go_code(code)
        elif lang in ('typescript', 'javascript'):
            code = compress_ts_code(code)
        elif lang in ('java', 'kotlin'):
            code = compress_java_code(code)  # NEW
        elif lang == 'php':
            code = compress_php_code(code)   # PHP / Laravel
        code = normalize_whitespace(code)

    return code.strip()


# ---------------------------------------------------------------------------
# 7. Optimizador de contenido .md completo
# ---------------------------------------------------------------------------

def optimize_md_content(md_content: str, source_filename: str) -> tuple[str, dict]:
    """
    Procesa el contenido completo de un archivo .md.
    Retorna (contenido_optimizado, stats_dict).
    """
    original_len = len(md_content)

    # Limpiar estructura markdown
    md_content = clean_markdown_structure(md_content)

    # Optimizar bloques de código
    def replacer(m: re.Match) -> str:
        lang = m.group('lang').lower()
        code = m.group('code')
        optimized = optimize_code_block(lang, code, source_filename)
        return f'```{lang}\n{optimized}\n```'

    md_content = CODE_BLOCK_RE.sub(replacer, md_content)

    # Colapso final de líneas en blanco
    md_content = re.sub(r'\n{3,}', '\n\n', md_content)

    optimized_len = len(md_content)
    reduction_pct = (1 - optimized_len / original_len) * 100 if original_len > 0 else 0

    stats = {
        'original_chars':   original_len,
        'optimized_chars':  optimized_len,
        'reduction_pct':    round(reduction_pct, 1),
        'original_tokens':  estimate_tokens(original_len),
        'optimized_tokens': estimate_tokens(optimized_len),
    }
    return md_content, stats


# ---------------------------------------------------------------------------
# 8. Procesador principal
# ---------------------------------------------------------------------------

class MarkdownTokenOptimizer:
    def __init__(self, input_dir: str = INPUT_DIR, output_dir: str = OUTPUT_DIR):
        self.input_dir  = Path(input_dir)
        self.output_dir = Path(output_dir)

    def run(self):
        if not self.input_dir.exists():
            print(f"❌ No se encontró el directorio de entrada: {self.input_dir}")
            print(f"   Asegúrate de ejecutar primero el generador de documentación.")
            return

        # Limpiar y crear directorio de salida
        if self.output_dir.exists():
            print(f"🧹 Limpiando: {self.output_dir}")
            shutil.rmtree(self.output_dir)
        self.output_dir.mkdir(parents=True)

        md_files = sorted(self.input_dir.glob('*.md'))
        if not md_files:
            print(f"⚠️  No se encontraron archivos .md en {self.input_dir}")
            return

        print(f"🔍 Encontrados {len(md_files)} archivos .md en {self.input_dir}\n")
        print(f"{'Archivo':<35} {'Original':>10} {'Optimizado':>12} {'Reducción':>10} {'Tokens -':>10}")
        print('-' * 82)

        # FIX: naming consistente (antes INPUT_FOLDER/OUTPUT_FOLDER en __main__ no se usaban)
        global_stats = {
            'files': 0,
            'total_original_chars':   0,
            'total_optimized_chars':  0,
            'total_original_tokens':  0,
            'total_optimized_tokens': 0,
        }

        for md_file in md_files:
            content = md_file.read_text(encoding='utf-8')
            optimized, stats = optimize_md_content(content, md_file.name)

            out_path = self.output_dir / md_file.name
            out_path.write_text(optimized, encoding='utf-8')

            saved_tokens = stats['original_tokens'] - stats['optimized_tokens']
            print(
                f"{md_file.name:<35}"
                f"{stats['original_chars']:>10,}"
                f"{stats['optimized_chars']:>12,}"
                f"{stats['reduction_pct']:>9.1f}%"
                f"{saved_tokens:>+10,}"
            )

            global_stats['files'] += 1
            global_stats['total_original_chars']   += stats['original_chars']
            global_stats['total_optimized_chars']  += stats['optimized_chars']
            global_stats['total_original_tokens']  += stats['original_tokens']
            global_stats['total_optimized_tokens'] += stats['optimized_tokens']

        # Resumen global
        total_reduction = (
            1 - global_stats['total_optimized_chars'] / global_stats['total_original_chars']
        ) * 100 if global_stats['total_original_chars'] > 0 else 0
        total_saved_tokens = (
            global_stats['total_original_tokens'] - global_stats['total_optimized_tokens']
        )

        print('-' * 82)
        print(
            f"{'TOTAL (' + str(global_stats['files']) + ' archivos)':<35}"
            f"{global_stats['total_original_chars']:>10,}"
            f"{global_stats['total_optimized_chars']:>12,}"
            f"{total_reduction:>9.1f}%"
            f"{total_saved_tokens:>+10,}"
        )

        self._write_readme(global_stats, total_reduction, total_saved_tokens, md_files)

        print(f"\n✨ Archivos optimizados en: {self.output_dir}/")
        print(f"📉 Reducción total de tokens estimada: ~{total_saved_tokens:,} tokens ({total_reduction:.1f}%)")

    def _write_readme(self, stats: dict, reduction: float, saved_tokens: int, files: list):
        """Genera un README con el resumen del proceso de optimización."""
        readme = f"""# docs_md_optimize — Resumen de Optimización

**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
**Fuente:** `{self.input_dir}/`
**Destino:** `{self.output_dir}/`

## Resultados

| Métrica | Original | Optimizado | Diferencia |
|---|---|---|---|
| Caracteres | {stats['total_original_chars']:,} | {stats['total_optimized_chars']:,} | -{stats['total_original_chars'] - stats['total_optimized_chars']:,} |
| Tokens (aprox.) | {stats['total_original_tokens']:,} | {stats['total_optimized_tokens']:,} | -{saved_tokens:,} |
| Reducción | | | **{reduction:.1f}%** |

## Técnicas aplicadas

1. **Eliminación de comentarios** — Go (`//`, `/* */`), TypeScript/Java/Kotlin (`//`, `/** */`),
   PHP (`//`, `#`, `/** */`), Blade (`{{-- --}}`), Vue (`<!-- -->`), YAML/Properties (`#`).
   Los comentarios no aportan semántica al LLM pero consumen tokens.
2. **Normalización de espacios** — Líneas finales, líneas en blanco múltiples, `\\r\\n` Windows.
3. **Limpieza de metadatos markdown redundantes** — Emojis decorativos, timestamps por sección,
   campo "Ruta" / "Ruta completa" (duplicado del título), campo "Categoría".
4. **Compresión de código grande** (>{MAX_CODE_CHARS} chars) — Se conservan firmas de funciones
   y tipos, eliminando cuerpos. Soporta Go, TypeScript, Java y PHP (Laravel).
5. **Resumen de archivos especiales**:
   - `go.sum`, `pnpm-lock`, `yarn.lock`, `composer.lock` → solo primeras {LOCK_PREVIEW_LINES} líneas
   - Archivos i18n JSON/PHP → solo las primeras {I18N_PREVIEW_KEYS} claves de primer nivel
6. **Deduplicación de separadores** `---` consecutivos en markdown.

## Archivos procesados

{chr(10).join(f'- `{f.name}`' for f in files)}

## Uso recomendado

Sube los archivos de `{self.output_dir}/` a tu sesión de IA en lugar de los de
`{self.input_dir}/`. Obtendrás el mismo contexto arquitectural con ~{reduction:.0f}% menos tokens.

Para análisis completo de implementación (lógica interna), usa los archivos
originales de `{self.input_dir}/`.
"""
        (self.output_dir / "00_OPTIMIZE_README.md").write_text(readme, encoding='utf-8')
        print(f"\n📋 Resumen guardado en: {self.output_dir}/00_OPTIMIZE_README.md")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    # FIX: naming consistente con las constantes del módulo (antes INPUT_FOLDER/OUTPUT_FOLDER
    # eran variables locales que nunca afectaban al constructor de la clase)
    INPUT_DIR  = "docs_md"           # Generado por cualquier generador de la suite
    OUTPUT_DIR = "docs_md_optimize"  # Carpeta de salida optimizada

    print('=' * 82)
    print('🚀 Optimizador de documentación para consumo mínimo de tokens en LLMs')
    print('=' * 82 + '\n')

    optimizer = MarkdownTokenOptimizer(INPUT_DIR, OUTPUT_DIR)
    optimizer.run()

    print('\n' + '=' * 82)
    print('🎉 ¡Proceso completado!')
    print('=' * 82)