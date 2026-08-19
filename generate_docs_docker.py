import re
import argparse
from pathlib import Path
from datetime import datetime

try:
    import yaml
    YAML_AVAILABLE = True
except ImportError:
    YAML_AVAILABLE = False
    print("⚠️  PyYAML no instalado. Análisis de docker-compose será limitado.")
    print("   Instala con: pip install PyYAML")


# ================================================================
#  CONFIGURACIÓN
# ================================================================

PROJECT_PATH = "."
OUTPUT_FILE  = "./docs_docker/docs_docker.md"

# Lista cerrada de archivos a buscar (rutas relativas al proyecto).
# Solo se reporta lo que exista; el resto se ignora en silencio.
TARGET_FILES = [
    "docker-compose.yml",
    "docker-compose.dev.yml",
    "docker-compose.pre.yml",
    "docker-compose.prod.yml",
    "Dockerfile",
    "Dockerfile.dev",
    "Dockerfile.pre",
    "Dockerfile.prod",
    ".env.dev",
    ".env.dev.dev",
    ".env.dev.pre",
    ".env.dev.prod",
    ".docker/phpmyadmin/dev/config.user.inc.php",
    ".docker/phpmyadmin/pre/config.user.inc.php",
    ".docker/phpmyadmin/prod/config.user.inc.php",
    ".docker/mariadb/dev/my.cnf",
    ".docker/mariadb/pre/my.cnf",
    ".docker/mariadb/prod/my.cnf",
]

MAX_FILE_SIZE_KB = 512
MAX_FILE_LINES   = 1600

_EXPORT_RE = re.compile(r'^export\s+')


# ================================================================
#  GENERADOR
# ================================================================

class DockerDocGenerator:

    def __init__(self, project_path: str | None = None, output_file: str | None = None):
        self.project_path = Path(project_path or PROJECT_PATH).resolve()
        self.output_file  = Path(output_file or OUTPUT_FILE)

    # ─── UTILIDADES ──────────────────────────────────────────────

    def _read_file(self, file_path: Path, size_kb: float) -> str:
        if size_kb > MAX_FILE_SIZE_KB:
            return f"[Archivo omitido: {size_kb:.0f} KB supera el límite de {MAX_FILE_SIZE_KB} KB]"

        try:
            raw = file_path.read_bytes()
        except OSError:
            return "[Error: no se pudo acceder al archivo]"

        # utf-8 cubre casi todos los casos; solo se reintenta si falla.
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            try:
                text = raw.decode("latin-1")
            except UnicodeDecodeError:
                return "[Error: no se pudo decodificar el archivo con ningún encoding conocido]"

        return self._truncate(text)

    def _truncate(self, text: str) -> str:
        if not MAX_FILE_LINES:
            return text
        lines = text.splitlines()
        if len(lines) <= MAX_FILE_LINES:
            return text
        omitted = len(lines) - MAX_FILE_LINES
        kept = lines[:MAX_FILE_LINES]
        kept.append(f"\n... [{omitted} líneas omitidas — límite MAX_FILE_LINES={MAX_FILE_LINES}]")
        return "\n".join(kept)

    def _lang_for(self, file_path: Path) -> str:
        name = file_path.name
        ext  = file_path.suffix.lower()
        if name.startswith("Dockerfile"):
            return "dockerfile"
        if name.startswith(".env.dev"):
            return "dotenv"
        return {
            ".yml": "yaml", ".yaml": "yaml", ".conf": "nginx",
            ".ini": "ini", ".cnf": "ini", ".php": "php",
        }.get(ext, "text")

    def _categorize(self, file_path: Path) -> str:
        name = file_path.name
        if name.startswith('.env.dev'):
            return 'Environment Variables'
        if 'docker-compose' in name:
            return 'Docker Compose'
        if name.startswith('Dockerfile'):
            return 'Dockerfiles'
        if name.endswith(('.conf', '.cnf')):
            return 'Configuration Files'
        if name.endswith('.php'):
            return 'phpMyAdmin Config'
        return 'Other'

    # ─── PARSERS ─────────────────────────────────────────────────

    def _parse_env(self, content: str) -> dict:
        variables = {}
        for raw_line in content.splitlines():
            line = raw_line.strip()
            if not line or line.startswith('#'):
                continue
            line = _EXPORT_RE.sub('', line)
            if '=' in line:
                key, _, value = line.partition('=')
                key = key.strip()
                if key:
                    variables[key] = value.strip().strip('"').strip("'")
        return variables

    def _parse_compose(self, content: str) -> dict:
        if not YAML_AVAILABLE:
            return {'error': 'PyYAML no disponible'}
        try:
            data = yaml.safe_load(content) or {}
        except Exception as e:
            return {'error': f'Error al parsear YAML: {e}'}

        services_raw = data.get('services') or {}
        services_detail = {}

        for svc_name, svc_cfg in services_raw.items():
            svc_cfg = svc_cfg or {}
            raw_depends = svc_cfg.get('depends_on', [])
            if isinstance(raw_depends, dict):
                depends = list(raw_depends.keys())
            elif isinstance(raw_depends, list):
                depends = raw_depends
            else:
                depends = []

            build = svc_cfg.get('build')
            build_str = build.get('context', str(build)) if isinstance(build, dict) else (str(build) if build else None)

            env = svc_cfg.get('environment', [])
            env_count = len(env) if isinstance(env, (list, dict)) else 0

            services_detail[svc_name] = {
                'image':      svc_cfg.get('image') or '_(build local)_',
                'build':      build_str,
                'ports':      [str(p) for p in (svc_cfg.get('ports') or [])],
                'env_count':  env_count,
                'vol_count':  len(svc_cfg.get('volumes') or []),
                'depends_on': depends,
                'restart':    svc_cfg.get('restart'),
                'networks':   list((svc_cfg.get('networks') or {}).keys())
                if isinstance(svc_cfg.get('networks'), dict)
                else (svc_cfg.get('networks') or []),
            }

        return {
            'version':         data.get('version', '_no especificada_'),
            'services':        list(services_raw.keys()),
            'networks':        list((data.get('networks') or {}).keys()),
            'volumes':         list((data.get('volumes') or {}).keys()),
            'services_detail': services_detail,
        }

    # ─── ESCANEO (lista cerrada) ─────────────────────────────────

    def scan_files(self) -> list[Path]:
        """Comprueba la lista cerrada TARGET_FILES y devuelve solo los existentes."""
        found = []
        print("🔍 Verificando lista de archivos objetivo...")
        for rel in TARGET_FILES:
            fp = self.project_path / rel
            if fp.is_file():
                found.append(fp)
                print(f"  ✓ {rel}")
            else:
                print(f"  ✗ {rel} (no existe, omitido)")
        return found

    # ─── SECCIÓN MARKDOWN POR ARCHIVO ────────────────────────────

    def _file_section(self, file_path: Path, content: str, category: str, size_kb: float) -> str:
        rel = file_path.relative_to(self.project_path)

        lines = [
            f"\n## 📄 `{rel}`\n",
            f"**Categoría:** {category} | **Tamaño:** {size_kb:.1f} KB\n",
        ]

        name = file_path.name

        if name.startswith('.env.dev'):
            variables = self._parse_env(content)
            lines.append(f"**Variables definidas:** {len(variables)}\n")
            if variables:
                lines.append("| Variable | Valor |")
                lines.append("|----------|-------|")
                for key, val in variables.items():
                    lines.append(f"| `{key}` | `{val}` |")
                lines.append("")

        elif 'docker-compose' in name:
            info = self._parse_compose(content)
            if 'error' not in info:
                lines.append(f"**Servicios:** {len(info['services'])}")
                if info['networks']:
                    lines.append(f" | **Redes:** {', '.join(f'`{n}`' for n in info['networks'])}")
                if info['volumes']:
                    lines.append(f" | **Volúmenes:** {', '.join(f'`{v}`' for v in info['volumes'])}")
                lines.append("\n")
                lines.append("| Servicio | Imagen | Puertos | Env vars | Volúmenes | Depende de |")
                lines.append("|---------|--------|---------|:--------:|:---------:|------------|")
                for svc, d in info['services_detail'].items():
                    ports   = '<br>'.join(d['ports']) if d['ports'] else '—'
                    depends = ', '.join(f'`{x}`' for x in d['depends_on']) if d['depends_on'] else '—'
                    lines.append(
                        f"| **{svc}** | `{d['image']}` | {ports} "
                        f"| {d['env_count']} | {d['vol_count']} | {depends} |"
                    )
                lines.append("")
            else:
                lines.append(f"> ⚠️ {info['error']}\n")

        lang = self._lang_for(file_path)
        lines.append("### 📋 Contenido\n")
        lines.append(f"```{lang}")
        lines.append(content)
        lines.append("```\n---")

        return "\n".join(lines) + "\n"

    # ─── PUNTO DE ENTRADA ────────────────────────────────────────

    def generate(self):
        if not self.project_path.exists():
            print(f"\n❌ ERROR: Ruta no encontrada → {self.project_path}")
            return

        print(f"\n{'='*60}")
        print("🐳 GENERADOR DE DOCUMENTACIÓN DOCKER (compacto)")
        print(f"{'='*60}")
        print(f"Proyecto : {self.project_path}")
        print(f"Salida   : {self.output_file}\n")

        files_found = self.scan_files()

        if not files_found:
            print("\n❌ Ninguno de los archivos de la lista objetivo existe en el proyecto.")
            return

        files_by_cat: dict[str, list] = {}
        for fp in files_found:
            size_kb  = fp.stat().st_size / 1024
            content  = self._read_file(fp, size_kb)
            category = self._categorize(fp)
            files_by_cat.setdefault(category, []).append((fp, content, size_kb))

        total = len(files_found)
        print(f"\n📊 Archivos encontrados: {total}/{len(TARGET_FILES)}")
        print("📝 Generando documento único...")

        parts = [
            f"# 🐳 Documentación Docker — {self.project_path.name}\n\n"
            f"**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  \n"
            f"**Archivos encontrados:** {total}/{len(TARGET_FILES)}\n\n---\n"
        ]
        for cat, files in sorted(files_by_cat.items()):
            parts.append(f"\n# 📁 {cat}\n")
            for fp, content, size_kb in sorted(files, key=lambda x: str(x[0])):
                parts.append(self._file_section(fp, content, cat, size_kb))

        doc = "".join(parts)
        self.output_file.parent.mkdir(parents=True, exist_ok=True)
        self.output_file.write_text(doc, encoding="utf-8")
        print(f"  ✅ {self.output_file}")
        print(f"\n✨ Documentación generada: {self.output_file}")
        print(f"{'='*60}")


# ================================================================
def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Genera un único Markdown compacto con una lista fija de archivos Docker.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Ejemplos:
  python generate_docs_docker.py
  python generate_docs_docker.py /home/cesar/factu_sync
  python generate_docs_docker.py ./mi-proyecto -o docs_docker.md
        """,
    )
    parser.add_argument("project_path", nargs="?", default=None,
                        help=f"Ruta al proyecto (default: '{PROJECT_PATH}')")
    parser.add_argument("-o", "--output", default=None, metavar="FILE",
                        help=f"Archivo .md de salida (default: '{OUTPUT_FILE}')")
    return parser.parse_args()


if __name__ == "__main__":
    args = _parse_args()
    DockerDocGenerator(
        project_path=args.project_path,
        output_file=args.output,
    ).generate()
