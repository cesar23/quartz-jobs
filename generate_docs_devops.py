#!/usr/bin/env python3
"""
generate_docs_devops.py
========================
Genera documentación Markdown a partir de todos los ficheros relacionados
con DevOps / CI-CD / IaC de un proyecto: Docker, docker-compose, variables
de entorno, pipelines (GitHub Actions, GitLab CI, Jenkins, Azure DevOps,
CircleCI, Drone, Buildkite, AppVeyor, Semaphore, TeamCity, AWS CodeBuild/
CodePipeline, Google Cloud Build...), IaC (Terraform, Pulumi, CloudFormation
/ SAM, Ansible, Kubernetes/Helm), GitOps (ArgoCD/Flux), Serverless Framework,
PaaS (Vercel, Netlify, Render, Fly.io, Heroku), Vagrant y scripts de despliegue.

Novedades respecto a la versión anterior:
  - Escaneo RECURSIVO real (no solo carpetas en la raíz): detecta CI/CD
    y despliegues en monorepos, con servicios anidados en varias carpetas.
  - Muchas más plataformas de CI/CD y PaaS reconocidas y categorizadas.
  - Enmascarado de secretos ampliado: no solo en `.env`, también detecta
    claves tipo PASSWORD/TOKEN/SECRET/API_KEY/PRIVATE_KEY en cualquier
    fichero (yaml, tfvars, properties, etc.).
  - Excluye ficheros de estado de Terraform (`.tfstate*`), que pueden
    contener secretos en texto plano.
  - Protección ante binarios "colados" y ficheros enormes (se truncan
    con aviso en vez de volcar megabytes al Markdown).
Uso:
    1. Edita las variables de configuración al inicio del fichero
       (PROJECT_PATH, OUTPUT_DIR, MASK_ENV_VALUES, MASK_SECRETS_EVERYWHERE,
       MAX_FILE_SIZE_KB) o directamente en el bloque `if __name__ == "__main__"`.
    2. Ejecuta:  python generate_docs_devops.py
"""

import os
import re
import shutil
from pathlib import Path
from datetime import datetime


# ================================================================
#  CONFIGURACIÓN POR DEFECTO — puedes editar esto o usar --path/--out
# ================================================================

PROJECT_PATH = "."
OUTPUT_DIR = "docs_md_devops"

# Enmascarar valores en ficheros .env (todas las líneas clave=valor)
MASK_ENV_VALUES = True

# Enmascarar valores "sensibles" en CUALQUIER fichero (password, token,
# secret, api key, private key...), aunque no sea un .env
MASK_SECRETS_EVERYWHERE = True

# Ficheros más grandes que esto se truncan en el .md (con aviso), para no
# generar documentos gigantes por un lockfile o dump grande que se coló.
MAX_FILE_SIZE_KB = 400

# ── Carpetas "marcadoras" de DevOps ─────────────────────────────
# Si el nombre de CUALQUIER carpeta en la ruta (a cualquier profundidad)
# coincide con esta lista, los ficheros de configuración dentro de ella
# se documentan (siempre que su extensión esté en INCLUDE_EXTENSIONS).
# Esto es lo que permite detectar CI/CD en monorepos, no solo en la raíz.
INCLUDE_DIR_MARKERS = {
    # Docker
    '.docker', '.devcontainer',
    # Git / plataformas CI/CD por carpeta
    '.github', '.gitlab', '.husky',
    '.circleci', '.azure-pipelines', '.teamcity', '.jenkins',
    '.buildkite', '.semaphore', '.drone', 'buildkite', 'semaphore',
    # GitOps
    'argocd', 'argo-cd', 'flux', 'gitops',
    # IaC
    'terraform', 'pulumi', 'ansible',
    'k8s', 'kubernetes', 'helm', 'charts', 'kustomize',
    'cloudformation', 'sam',
    # Serverless / PaaS
    'serverless', '.serverless',
    # Despliegue / Ops genérico
    'infra', 'infrastructure', 'deploy', 'deployment', 'deployments',
    'ops', 'scripts', 'envs', 'environments', 'env',
    'pipeline', 'pipelines', 'ci', 'cd', 'cicd', '.ci',
}

# ── Nombres de fichero SIEMPRE incluidos (en cualquier carpeta) ─
# name -> categoría. Coincidencia EXACTA de nombre.
ALWAYS_INCLUDE_EXACT = {
    'Jenkinsfile':               'Jenkins',
    'Makefile':                  'Scripts',
    'Vagrantfile':                'Vagrant',
    'Procfile':                  'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'app.yaml':                  'PaaS (Vercel/Netlify/Render/Fly/Heroku)',   # Google App Engine
    'app.json':                  'PaaS (Vercel/Netlify/Render/Fly/Heroku)',  # Heroku
    '.gitlab-ci.yml':            'GitLab CI/CD',
    '.gitlab-ci.yaml':           'GitLab CI/CD',
    'azure-pipelines.yml':       'Azure DevOps',
    'azure-pipelines.yaml':      'Azure DevOps',
    'bitbucket-pipelines.yml':   'Otros CI/CD',
    '.travis.yml':               'Otros CI/CD',
    '.drone.yml':                'Drone CI',
    '.drone.yaml':               'Drone CI',
    'appveyor.yml':              'AppVeyor',
    '.appveyor.yml':             'AppVeyor',
    '.semaphore.yml':            'Semaphore CI',
    'buildspec.yml':             'AWS CodeBuild / CodePipeline',
    'buildspec.yaml':            'AWS CodeBuild / CodePipeline',
    'buildkite.yml':             'Buildkite',
    'cloudbuild.yaml':           'Google Cloud Build',
    'cloudbuild.yml':            'Google Cloud Build',
    'serverless.yml':            'Serverless Framework',
    'serverless.yaml':           'Serverless Framework',
    'netlify.toml':              'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'vercel.json':               'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'render.yaml':               'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'fly.toml':                  'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'ansible.cfg':                'Ansible',
    'playbook.yml':              'Ansible',
    'playbook.yaml':             'Ansible',
    'requirements.yml':          'Ansible',
    'inventory':                 'Ansible',
    'hosts':                     'Ansible',
    'skaffold.yaml':             'Kubernetes / Helm',
    'skaffold.yml':              'Kubernetes / Helm',
    'kustomization.yaml':        'Kubernetes / Helm',
    'kustomization.yml':         'Kubernetes / Helm',
    'Pulumi.yaml':               'Pulumi',
    'Pulumi.yml':                'Pulumi',
    'samconfig.toml':            'CloudFormation / SAM',
    'template.yaml':             'CloudFormation / SAM',
    'template.yml':              'CloudFormation / SAM',
    'docker-swarm.yml':          'Docker Compose',
    '.dockerignore':             'Docker',

    # AJUSTE (quartz-jobs): ficheros de calidad/lint que tienes en la raíz
    # del proyecto y que antes se perdían silenciosamente.
    '.mega-linter.yml':          'Calidad de Código / Linters',
    '.yamllint.yml':             'Calidad de Código / Linters',
    '.markdownlint':             'Calidad de Código / Linters',
    '.editorconfig':             'Calidad de Código / Linters',
}

# ── Prefijos de fichero SIEMPRE incluidos (en cualquier carpeta) ─
ALWAYS_INCLUDE_PREFIX = [
    ('Dockerfile',      'Docker'),
    ('docker-compose',  'Docker Compose'),
    ('compose.',        'Docker Compose'),   # nueva convención "compose.yaml"
    ('.env',            'Variables de Entorno'),
    ('.env.dev',         'Variables de Entorno'),
    ('.env.pre',         'Variables de Entorno'),
    ('.env.prod',        'Variables de Entorno'),
]

# ── Palabras clave de carpeta -> categoría (cuando el fichero se ─
#    incluye por estar dentro de un INCLUDE_DIR_MARKER) ──────────
# Se evalúa en orden; la primera que coincide gana.
PATH_KEYWORD_CATEGORY = [
    ('.github',        'GitHub Actions'),
    ('.gitlab',         'GitLab CI/CD'),
    ('jenkins',         'Jenkins'),
    ('circleci',        'CircleCI'),
    ('azure',           'Azure DevOps'),
    ('buildkite',       'Buildkite'),
    ('semaphore',       'Semaphore CI'),
    ('teamcity',        'TeamCity'),
    ('.drone',          'Drone CI'),
    ('argocd',          'GitOps (ArgoCD/Flux)'),
    ('argo-cd',         'GitOps (ArgoCD/Flux)'),
    ('flux',            'GitOps (ArgoCD/Flux)'),
    ('gitops',          'GitOps (ArgoCD/Flux)'),
    ('terraform',       'Terraform'),
    ('pulumi',          'Pulumi'),
    ('cloudformation',  'CloudFormation / SAM'),
    ('/sam/',           'CloudFormation / SAM'),
    ('ansible',         'Ansible'),
    ('kustomize',       'Kubernetes / Helm'),
    ('kubernetes',      'Kubernetes / Helm'),
    ('helm',            'Kubernetes / Helm'),
    ('charts',          'Kubernetes / Helm'),
    ('k8s',             'Kubernetes / Helm'),
    ('serverless',      'Serverless Framework'),
    ('.devcontainer',   'Docker'),
    ('.docker',         'Docker'),
    ('nginx',           'Nginx / Proxy'),
    ('scripts',         'Scripts'),
]

# ── Extensiones permitidas dentro de un INCLUDE_DIR_MARKER ──────
INCLUDE_EXTENSIONS = {
    '.yml', '.yaml', '.json', '.toml', '.ini', '.cfg', '.conf', '.config',
    '.tf', '.tfvars', '.hcl',
    '.sh', '.bash', '.zsh', '.ps1', '.bat', '.cmd', '.py', '.rb', '.pl',
    '.dockerfile',
    '.nginx', '.htaccess',
    '.env', '.properties', '.xml',
}

# Extensiones sin condición de carpeta: SIEMPRE se incluyen si aparecen
# en cualquier parte del proyecto (son inequívocamente IaC).
ALWAYS_INCLUDE_EXTENSIONS = {'.tf', '.tfvars', '.hcl'}

INCLUDE_NO_EXT_NAMES = {
    'Dockerfile', 'Makefile', 'Jenkinsfile', 'Vagrantfile', 'Procfile',
    'inventory', 'hosts',
}

# ── Extensiones EXCLUIDAS (binarios, media, estado sensible, etc.) ──
EXCLUDE_EXTENSIONS = {
    '.png', '.jpg', '.jpeg', '.gif', '.svg', '.ico', '.webp',
    '.bmp', '.tiff', '.psd', '.ai', '.eps',
    '.woff', '.woff2', '.ttf', '.eot', '.otf',
    '.mp4', '.mp3', '.wav', '.avi', '.mov', '.ogg', '.flac', '.webm',
    '.zip', '.rar', '.iso', '.tar', '.gz', '.7z', '.bz2', '.tgz',
    '.exe', '.dll', '.so', '.bin', '.dat', '.class', '.jar',
    '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
    '.log', '.csv',
    '.md', '.rst',
    '.lock',
    # Estado de Terraform: puede contener secretos en texto plano
    '.tfstate', '.tfstate.backup',
}

# ── Directorios a ignorar completamente ─────────────────────────
EXCLUDE_DIRS = {
    'node_modules', 'vendor', '.git', '.idea', '.vscode',
    'dist', 'build', '__pycache__', '.cache', 'coverage',
    '.next', '.nuxt', 'out', '.turbo', 'tmp', 'temp',
    'target', 'logs', 'project_structure_docs', 'Resources', 'www',
    '.terraform',   # caché de providers de Terraform (pesado, no útil)

    # AJUSTE (quartz-jobs): carpetas de SALIDA de tus propios generadores
    # de documentación y de reportes de herramientas. Sin esto, el
    # escaneo recursivo se documenta a sí mismo (ruido y duplicados).
    'docs_docker', 'docs_md', 'docs_md_optimize', 'megalinter-reports',
    'my_resources', 'data',
}

# ── Ficheros a ignorar por nombre ────────────────────────────────
EXCLUDE_FILES = {
    '.DS_Store', 'Thumbs.db', 'desktop.ini',
    'package-lock.json', 'yarn.lock', 'composer.lock', 'pnpm-lock.yaml',
    '.gitkeep', '.gitattributes',

    # AJUSTE (quartz-jobs): fichero de volcado accidental de Git Bash,
    # no aporta nada a la documentación.
    'bash.exe.stackdump',
}

# ================================================================
#  GENERADOR — no es necesario editar más abajo
# ================================================================

CATEGORY_ORDER = [
    'Docker',
    'Docker Compose',
    'Variables de Entorno',
    'GitHub Actions',
    'GitLab CI/CD',
    'Jenkins',
    'Azure DevOps',
    'CircleCI',
    'Drone CI',
    'Buildkite',
    'AppVeyor',
    'Semaphore CI',
    'TeamCity',
    'AWS CodeBuild / CodePipeline',
    'Google Cloud Build',
    'Otros CI/CD',
    'GitOps (ArgoCD/Flux)',
    'Terraform',
    'Pulumi',
    'CloudFormation / SAM',
    'Ansible',
    'Kubernetes / Helm',
    'Serverless Framework',
    'PaaS (Vercel/Netlify/Render/Fly/Heroku)',
    'Vagrant',
    'Scripts',
    'Nginx / Proxy',
    'Calidad de Código / Linters',  # AJUSTE (quartz-jobs): nueva categoría
    'Otros',
]

LANG_MAP = {
    '.yml': 'yaml', '.yaml': 'yaml', '.json': 'json', '.toml': 'toml',
    '.tf': 'hcl', '.tfvars': 'hcl', '.hcl': 'hcl',
    '.sh': 'bash', '.bash': 'bash', '.zsh': 'bash',
    '.ps1': 'powershell', '.bat': 'bat', '.cmd': 'bat',
    '.py': 'python', '.rb': 'ruby', '.xml': 'xml',
    '.properties': 'properties', '.ini': 'ini', '.cfg': 'ini',
    '.conf': 'nginx', '.nginx': 'nginx',
}

# Regex para enmascarar valores sensibles fuera de ficheros .env
SENSITIVE_KEY_RE = re.compile(
    r'^(?P<indent>\s*)(?P<key>[\w\.\-]*?(?:SECRET|PASSWORD|PASSWD|PWD|TOKEN|'
    r'API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|CREDENTIALS?|CLIENT[_-]?SECRET|'
    r'AUTH[_-]?KEY)[\w\.\-]*?)(?P<sep>\s*[:=]\s*)(?P<value>.+)$',
    re.IGNORECASE,
)


class DevOpsDocGenerator:

    def __init__(self, project_path=PROJECT_PATH, output_dir=OUTPUT_DIR,
                 mask_env=MASK_ENV_VALUES, mask_secrets=MASK_SECRETS_EVERYWHERE,
                 max_file_kb=MAX_FILE_SIZE_KB):
        self.project_path = Path(project_path).resolve()
        self.output_dir = Path(output_dir)
        self.mask_env = mask_env
        self.mask_secrets = mask_secrets
        self.max_file_bytes = max_file_kb * 1024
        self.stats = {'skipped_binary': 0, 'skipped_huge': 0, 'truncated': 0}
        self._clean_output()
        self.output_dir.mkdir(exist_ok=True, parents=True)

    # ─── LIMPIEZA ────────────────────────────────────────────────

    def _clean_output(self):
        if self.output_dir.exists():
            print(f"🧹 Limpiando directorio anterior: {self.output_dir}")
            try:
                shutil.rmtree(self.output_dir)
                print("✅ Directorio limpiado")
            except Exception as e:
                print(f"⚠️  No se pudo limpiar: {e}")

    # ─── CLASIFICACIÓN (¿se incluye? ¿en qué categoría?) ─────────

    def _matching_dir_marker(self, rel_parts) -> bool:
        """¿Alguna carpeta de la ruta relativa coincide con un marcador DevOps?"""
        lowered = {p.lower() for p in rel_parts}
        return any(marker.strip('.').lower() in lowered or marker.lower() in lowered
                   for marker in INCLUDE_DIR_MARKERS)

    def _category_from_path(self, path_str_lower: str) -> str:
        for keyword, category in PATH_KEYWORD_CATEGORY:
            if keyword in path_str_lower:
                return category
        return 'Otros'

    def _classify(self, file_path: Path):
        """
        Devuelve la categoría (str) si el fichero debe documentarse,
        o None si debe ignorarse.
        """
        name = file_path.name
        ext = file_path.suffix.lower()

        if name in EXCLUDE_FILES:
            return None
        if ext in EXCLUDE_EXTENSIONS:
            return None

        # 1) Coincidencia exacta de nombre (máxima prioridad, a cualquier profundidad)
        if name in ALWAYS_INCLUDE_EXACT:
            return ALWAYS_INCLUDE_EXACT[name]

        # 2) Coincidencia por prefijo (Dockerfile*, docker-compose*, .env*, compose.*)
        for prefix, category in ALWAYS_INCLUDE_PREFIX:
            if name.startswith(prefix):
                return category

        # 3) Extensiones inequívocamente IaC (Terraform), en cualquier carpeta
        if ext in ALWAYS_INCLUDE_EXTENSIONS:
            return 'Terraform'

        # 4) Nombres sin extensión de la lista blanca
        if ext == '' and name in INCLUDE_NO_EXT_NAMES:
            path_str = str(file_path).lower()
            return self._category_from_path(path_str)

        # 5) Dentro de una carpeta "marcadora" DevOps + extensión permitida
        try:
            rel_parts = file_path.relative_to(self.project_path).parts[:-1]
        except ValueError:
            rel_parts = file_path.parts[:-1]

        if ext in INCLUDE_EXTENSIONS and self._matching_dir_marker(rel_parts):
            path_str = str(file_path).lower().replace('\\', '/')
            return self._category_from_path(path_str)

        return None

    # ─── LECTURA / SANEADO ────────────────────────────────────────

    def _is_probably_binary(self, sample: bytes) -> bool:
        if b'\x00' in sample:
            return True
        # Heurística simple: demasiados bytes no imprimibles
        text_chars = bytearray({7, 8, 9, 10, 12, 13, 27} | set(range(0x20, 0x100)) - {0x7f})
        nontext = sample.translate(None, bytes(text_chars))
        return len(sample) > 0 and len(nontext) / len(sample) > 0.30

    def _read_file(self, file_path: Path):
        """Devuelve (content:str|None, notice:str|None)."""
        try:
            size = file_path.stat().st_size
        except OSError:
            return None, "[Error: no se pudo acceder al fichero]"

        try:
            with open(file_path, 'rb') as fh:
                sample = fh.read(4096)
        except (PermissionError, OSError):
            return None, "[Error: sin permisos de lectura]"

        if self._is_probably_binary(sample):
            self.stats['skipped_binary'] += 1
            return None, "[Fichero omitido: detectado como binario]"

        for enc in ('utf-8', 'latin-1'):
            try:
                content = file_path.read_text(encoding=enc)
                break
            except (UnicodeDecodeError, PermissionError):
                content = None
        if content is None:
            return None, "[Error: no se pudo decodificar el fichero]"

        if size > self.max_file_bytes:
            self.stats['truncated'] += 1
            limit_chars = self.max_file_bytes
            content = (content[:limit_chars] +
                       f"\n\n[... TRUNCADO: fichero de {size/1024:.1f} KB, "
                       f"se muestran los primeros {self.max_file_bytes/1024:.0f} KB ...]")
        return content, None

    def _mask_env(self, raw: str) -> str:
        """Oculta TODOS los valores clave=valor en ficheros .env."""
        lines = []
        for line in raw.splitlines():
            stripped = line.strip()
            if '=' in stripped and not stripped.startswith('#') and not stripped.startswith('//'):
                key = stripped.split('=', 1)[0]
                lines.append(f"{key}=***")
            else:
                lines.append(line)
        return '\n'.join(lines)

    def _mask_secrets(self, raw: str) -> str:
        """Oculta valores de claves con pinta de secreto en cualquier fichero."""
        out = []
        for line in raw.splitlines():
            stripped = line.strip()
            if stripped.startswith('#') or stripped.startswith('//'):
                out.append(line)
                continue
            m = SENSITIVE_KEY_RE.match(line)
            if m:
                out.append(f"{m.group('indent')}{m.group('key')}{m.group('sep')}***")
            else:
                out.append(line)
        return '\n'.join(out)

    # ─── RECOPILACIÓN DE FICHEROS (ESCANEO RECURSIVO REAL) ───────

    def _collect_files(self) -> dict:
        """Devuelve {categoria: [(path, content), ...]} recorriendo TODO el
        árbol del proyecto (no solo la raíz ni carpetas de primer nivel),
        lo que permite detectar CI/CD en monorepos con servicios anidados."""
        by_cat: dict = {}
        seen = 0

        for root, dirs, files in os.walk(self.project_path):
            root_path = Path(root)
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS and d != '.git']

            for fname in sorted(files):
                fp = root_path / fname
                category = self._classify(fp)
                if category is None:
                    continue

                content, notice = self._read_file(fp)
                if content is None:
                    # Se documenta la referencia con el motivo, sin contenido
                    content = notice or "[Fichero no legible]"
                else:
                    if self.mask_env and fname.startswith('.env'):
                        content = self._mask_env(content)
                    elif self.mask_secrets:
                        content = self._mask_secrets(content)

                by_cat.setdefault(category, []).append((fp, content))
                seen += 1
                rel = fp.relative_to(self.project_path)
                print(f"     ✓ [{category}] {rel}")

        return by_cat

    # ─── HELPERS DE FORMATO ──────────────────────────────────────

    def _lang(self, file_path: Path) -> str:
        name = file_path.name
        if name.startswith('Dockerfile'):
            return 'dockerfile'
        if name.startswith('.env') or name in {'inventory', 'hosts'}:
            return 'dotenv'
        if name == 'Jenkinsfile':
            return 'groovy'
        if name == 'Makefile' or name == 'Vagrantfile':
            return 'ruby' if name == 'Vagrantfile' else 'makefile'
        return LANG_MAP.get(file_path.suffix.lower(), '')

    def _rel(self, file_path: Path) -> str:
        try:
            return str(file_path.relative_to(self.project_path))
        except ValueError:
            return file_path.name

    # ─── SECCIÓN POR FICHERO ─────────────────────────────────────

    def _file_section(self, file_path: Path, content: str) -> str:
        rel = self._rel(file_path)
        lang = self._lang(file_path)
        lines = content.count('\n') + 1
        size = file_path.stat().st_size if file_path.exists() else 0
        size_h = f"{size:,} bytes" if size < 1024 else f"{size/1024:.1f} KB"

        section = f"\n## 📄 `{rel}`\n\n"
        section += "| Campo | Valor |\n"
        section += "|---|---|\n"
        section += f"| **Ruta completa** | `{file_path}` |\n"
        section += f"| **Nombre** | `{file_path.name}` |\n"
        section += f"| **Extensión** | `{file_path.suffix or '(sin extensión)'}` |\n"
        section += f"| **Tamaño** | {size_h} |\n"
        section += f"| **Líneas** | {lines} |\n\n"
        section += f"```{lang}\n{content}\n```\n\n"
        section += "---\n"
        return section

    # ─── ÁRBOL DE FICHEROS DOCUMENTADOS ──────────────────────────

    def _build_tree_dict(self, rel_paths):
        tree_dict: dict = {}
        for rp in rel_paths:
            parts = Path(rp).parts
            node = tree_dict
            for part in parts[:-1]:
                node = node.setdefault(part, {})
            node[parts[-1]] = None
        return tree_dict

    def _render_tree(self, node: dict, prefix: str = '') -> list:
        lines = []
        entries = sorted(node.keys(), key=lambda k: (node[k] is None, k.lower()))
        for i, name in enumerate(entries):
            is_last = (i == len(entries) - 1)
            connector = '└── ' if is_last else '├── '
            child = node[name]
            if child is None:
                lines.append(f"{prefix}{connector}{name}")
            else:
                lines.append(f"{prefix}{connector}{name}/")
                extension = '    ' if is_last else '│   '
                lines.extend(self._render_tree(child, prefix + extension))
        return lines

    def _tree(self, by_cat: dict) -> str:
        rel_paths = sorted(self._rel(fp) for files in by_cat.values() for fp, _ in files)
        tree_dict = self._build_tree_dict(rel_paths)
        result = [f"{self.project_path.name}/"]
        result.extend(self._render_tree(tree_dict))
        return '\n'.join(result)

    # ─── DOCUMENTOS ──────────────────────────────────────────────

    def _write(self, filename: str, content: str):
        path = self.output_dir / filename
        path.write_text(content, encoding='utf-8')
        print(f"   ✅ {path}")

    def _gen_index(self, by_cat: dict, total: int):
        total_cats = len(by_cat)
        cat_lines = ''
        idx = 1
        ordered_cats = [c for c in CATEGORY_ORDER if c in by_cat] + \
                       [c for c in by_cat if c not in CATEGORY_ORDER]
        for cat in ordered_cats:
            n = len(by_cat[cat])
            slug = cat.replace('/', '_').replace(' ', '_').replace('(', '').replace(')', '')
            cat_lines += f"{idx}. **{cat}** — {n} fichero(s) → `{idx:02d}_{slug}.md`\n"
            idx += 1

        notices = ''
        if any(self.stats.values()):
            notices = (
                f"\n## ⚠️ Avisos del escaneo\n\n"
                f"- Ficheros binarios omitidos: {self.stats['skipped_binary']}\n"
                f"- Ficheros truncados por tamaño (> {MAX_FILE_SIZE_KB} KB): {self.stats['truncated']}\n"
            )

        doc = f"""# 🚀 Documentación DevOps / CI·CD — {self.project_path.name}

**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
**Proyecto:** `{self.project_path}`
**Ficheros documentados:** {total}
**Categorías detectadas:** {total_cats}

---

## 📑 Índice de categorías

{cat_lines}
- **Documento consolidado** → `99_COMPLETO.md`
{notices}
---

## 🗂️ Árbol de ficheros documentados

```
{self._tree(by_cat)}
```

---

## 💡 Uso sugerido

Sube los `.md` generados a tu IA favorita para:
- Revisar y validar tus pipelines y configuraciones en cada ambiente
- Detectar problemas de seguridad en `.env`, IAM o secretos embebidos
- Documentar la arquitectura de despliegue end-to-end
- Optimizar Dockerfiles, compose files y manifiestos de Kubernetes
- Comparar configuración entre dev / staging / producción
- Generar un README de infraestructura

---
_Generado con `generate_docs_devops.py` — escaneo recursivo de todo el proyecto._
"""
        self._write("00_INDEX.md", doc)

    def _gen_category(self, category: str, files: list, idx: int):
        slug = category.replace('/', '_').replace(' ', '_').replace('(', '').replace(')', '')
        filename = f"{idx:02d}_{slug}.md"

        groups: dict = {}
        for fp, ct in sorted(files, key=lambda x: self._rel(x[0])):
            rel = self._rel(fp)
            parts = Path(rel).parts
            folder = str(Path(*parts[:-1])) if len(parts) > 1 else '(raíz del proyecto)'
            groups.setdefault(folder, []).append((fp, ct))

        cat_paths = sorted(self._rel(fp) for fp, _ in files)
        mini_tree = '\n'.join(self._render_tree(self._build_tree_dict(cat_paths)))

        doc = f"""# {category}

**Total de ficheros:** {len(files)}
**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

---

## 🗂️ Estructura de esta categoría

```
{mini_tree}
```

---

"""
        for folder in sorted(groups.keys()):
            folder_files = groups[folder]
            doc += f"## 📁 `{folder}`\n\n"
            doc += f"_{len(folder_files)} fichero(s) en esta carpeta_\n\n"
            doc += '---\n'
            for fp, ct in folder_files:
                doc += self._file_section(fp, ct)

        self._write(filename, doc)

    def _gen_complete(self, by_cat: dict):
        parts = [f"""# 📦 Proyecto Completo DevOps — {self.project_path.name}

**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

Este fichero contiene TODOS los ficheros DevOps del proyecto organizados por categoría.

---
"""]
        ordered_cats = [c for c in CATEGORY_ORDER if c in by_cat] + \
                       [c for c in by_cat if c not in CATEGORY_ORDER]
        for cat in ordered_cats:
            parts.append(f"\n# 📁 {cat}\n")
            for fp, content in sorted(by_cat[cat], key=lambda x: self._rel(x[0])):
                parts.append(self._file_section(fp, content))

        self._write("99_COMPLETO.md", ''.join(parts))

    # ─── PUNTO DE ENTRADA ────────────────────────────────────────

    def generate(self):
        if not self.project_path.exists():
            print(f"\n❌ Ruta no encontrada: {self.project_path}")
            return

        print(f"\n{'='*60}")
        print(f"  Proyecto : {self.project_path.name}")
        print(f"  Salida   : {self.output_dir}/")
        print(f"{'='*60}\n")

        print("🔍 Recopilando ficheros DevOps (escaneo recursivo completo)...\n")
        by_cat = self._collect_files()
        total = sum(len(v) for v in by_cat.values())

        print(f"\n📊 {total} ficheros en {len(by_cat)} categorías\n")
        print("📝 Generando documentos...\n")

        self._gen_index(by_cat, total)

        cat_idx = 1
        ordered_cats = [c for c in CATEGORY_ORDER if c in by_cat] + \
                       [c for c in by_cat if c not in CATEGORY_ORDER]
        for cat in ordered_cats:
            self._gen_category(cat, by_cat[cat], cat_idx)
            cat_idx += 1

        self._gen_complete(by_cat)

        print(f"\n{'='*60}")
        print(f"🎉 ¡Documentación generada en: {self.output_dir}/")
        print(f"   Ficheros procesados : {total}")
        print(f"   Documentos .md      : {cat_idx + 1}")
        if any(self.stats.values()):
            print(f"   Binarios omitidos   : {self.stats['skipped_binary']}")
            print(f"   Truncados por tamaño: {self.stats['truncated']}")
        print(f"{'='*60}\n")


if __name__ == "__main__":
    # ── Variables en crudo: edita aquí directamente y ejecuta el script ──
    # (usan por defecto los valores de configuración definidos al inicio
    #  del fichero: PROJECT_PATH, OUTPUT_DIR, MASK_ENV_VALUES, etc.)
    project_path = PROJECT_PATH          # ej: "." o "/home/user/mi-proyecto"
    output_dir = OUTPUT_DIR              # ej: "docs_devops"
    mask_env = MASK_ENV_VALUES           # True para ocultar valores de .env
    mask_secrets = MASK_SECRETS_EVERYWHERE  # True para ocultar secretos en cualquier fichero
    max_file_kb = MAX_FILE_SIZE_KB       # tamaño máx. por fichero antes de truncar (KB)

    DevOpsDocGenerator(
        project_path=project_path,
        output_dir=output_dir,
        mask_env=mask_env,
        mask_secrets=mask_secrets,
        max_file_kb=max_file_kb,
    ).generate()
