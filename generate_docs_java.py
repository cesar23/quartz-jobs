import os
import re
import shutil
from pathlib import Path
from datetime import datetime


class SpringBootDocGenerator:
    def __init__(self, project_path, output_dir="docs_generated"):
        self.project_path = Path(project_path)
        self.src_path = self.project_path / "src"
        self.output_dir = Path(output_dir)

        self.clean_output_directory()
        self.output_dir.mkdir(exist_ok=True)

        self.extensions = ['.java', '.yml', '.yaml', '.properties', '.xml']

        # Archivos de configuración raíz del proyecto
        self.root_config_files = [
            'pom.xml',
            'build.gradle',
            'settings.gradle',
            'build.gradle.kts',
            'settings.gradle.kts',
            'docker-compose.yml',
            'docker-compose.yaml',
            'Dockerfile',
            '.env.dev',
            '.env.dev.example',
            '.env.dev.local',
            '.env.dev.development',
            '.env.dev.production',
        ]

        # Rutas comunes de application.properties/yml en Spring Boot
        self.spring_config_paths = [
            'src/main/resources/application.properties',
            'src/main/resources/application.yml',
            'src/main/resources/application.yaml',
            'src/main/resources/application-dev.yml',
            'src/main/resources/application-prod.yml',
            'src/main/resources/application-test.yml',
            'src/main/resources/application-dev.properties',
            'src/main/resources/application-prod.properties',
            'src/main/resources/application-test.properties',
            'src/main/resources/bootstrap.yml',
            'src/main/resources/bootstrap.properties',
        ]

    def clean_output_directory(self):
        """Limpia el directorio de salida antes de generar nuevos archivos"""
        if self.output_dir.exists():
            print(f"🧹 Limpiando directorio: {self.output_dir}")
            try:
                shutil.rmtree(self.output_dir)
                print(f"✅ Directorio limpiado exitosamente")
            except Exception as e:
                print(f"⚠️  Error al limpiar directorio: {e}")

    def should_include_file(self, file_path: Path) -> bool:
        """Determina si un archivo debe ser incluido"""
        exclude_dirs = {
            'target', 'build', 'node_modules', '.git', '.idea', 'out',
            'test', 'tests', 'it',  # FIX: excluir directorios de test
        }
        # FIX: excluir clases de test por convención Java
        exclude_patterns = [
            'Test.java', 'Tests.java', 'IT.java', 'Spec.java',
            'TestConfig.java', 'MockConfig.java',
        ]

        parts = file_path.parts
        if any(excl in parts for excl in exclude_dirs):
            return False

        # Excluir por patrón de nombre
        if any(file_path.name.endswith(pat) for pat in exclude_patterns):
            return False

        # Excluir carpetas de test por path completo (e.g. src/test/)
        path_str = str(file_path)
        if '/src/test/' in path_str or '\\src\\test\\' in path_str:
            return False

        return file_path.suffix in self.extensions

    def get_relative_path(self, file_path: Path) -> Path:
        """Obtiene la ruta relativa desde src/ o desde la raíz del proyecto"""
        try:
            return file_path.relative_to(self.src_path)
        except ValueError:
            try:
                return file_path.relative_to(self.project_path)
            except ValueError:
                return Path(file_path.name)

    def read_file_content(self, file_path: Path) -> str:
        """Lee el contenido de un archivo con manejo de errores"""
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                return f.read()
        except UnicodeDecodeError:
            try:
                with open(file_path, 'r', encoding='latin-1') as f:
                    return f.read()
            except Exception as e:
                return f"[Error al leer el archivo: {e}]"

    def extract_java_info(self, content: str, file_path: Path) -> dict:
        """Extrae información detallada de archivos Java"""
        info = {}

        if file_path.suffix != '.java':
            return info

        # Package
        package_match = re.search(r'package\s+([\w.]+);', content)
        if package_match:
            info['package'] = package_match.group(1)

        # Nombre de clase/interfaz/enum
        class_match = re.search(r'(?:public\s+)?(?:abstract\s+)?(?:class|interface|enum|record)\s+(\w+)', content)
        if class_match:
            info['class_name'] = class_match.group(1)

        # Herencia e interfaces implementadas
        extends_match = re.search(r'extends\s+([\w<>, ]+?)(?:\s+implements|\s*\{)', content)
        if extends_match:
            info['extends'] = extends_match.group(1).strip()

        implements_match = re.search(r'implements\s+([\w<>, ]+?)\s*\{', content)
        if implements_match:
            info['implements'] = implements_match.group(1).strip()

        # Anotaciones Spring (únicas, sin duplicados, límite 10)
        annotations = re.findall(r'@(\w+)', content)
        if annotations:
            info['annotations'] = list(dict.fromkeys(annotations))[:10]

        # Endpoints REST
        endpoints = re.findall(
            r'@(?:Get|Post|Put|Delete|Patch|Request)Mapping\s*\(\s*(?:value\s*=\s*)?["\']([^"\']+)["\']',
            content
        )
        if endpoints:
            info['endpoints'] = endpoints[:8]

        # Dependencias inyectadas (@Autowired o constructor)
        autowired = re.findall(r'@Autowired\s+(?:private\s+)?[\w<>]+\s+(\w+)', content)
        constructor_inject = re.findall(r'private\s+(?:final\s+)?[\w<>]+\s+(\w+);', content)
        deps = list(dict.fromkeys(autowired + constructor_inject))
        if deps:
            info['dependencies'] = deps[:8]

        return info

    def categorize_file(self, file_path: Path, content: str) -> str:
        """Categoriza el archivo según su tipo y ubicación"""
        path_str = str(file_path).lower()
        name = file_path.stem.lower()

        # Archivos de configuración raíz del proyecto
        if file_path.name in ['pom.xml', 'build.gradle', 'settings.gradle',
                               'build.gradle.kts', 'settings.gradle.kts']:
            return 'Project Configuration'

        # Por path/nombre en orden de especificidad
        if 'controller' in path_str or name.endswith('controller'):
            return 'Controllers'
        elif 'exceptionhandler' in path_str or 'controlleradvice' in path_str \
                or '@ControllerAdvice' in content or '@RestControllerAdvice' in content:
            return 'Exception Handlers'
        elif 'service' in path_str or name.endswith('service') or name.endswith('serviceimpl'):
            return 'Services'
        elif 'repository' in path_str or name.endswith('repository') or name.endswith('repo'):
            return 'Repositories'
        elif 'entity' in path_str or name.endswith('entity'):
            return 'Entities'
        elif 'model' in path_str or name.endswith('model'):
            return 'Models'
        elif 'dto' in path_str or name.endswith('dto') or name.endswith('request') or name.endswith('response'):
            return 'DTOs'
        elif 'mapper' in path_str or name.endswith('mapper'):
            return 'Mappers'
        elif 'security' in path_str or 'auth' in path_str:
            return 'Security & Auth'
        elif 'filter' in path_str or name.endswith('filter'):
            return 'Filters'
        elif 'aspect' in path_str or name.endswith('aspect'):
            return 'Aspects (AOP)'
        elif 'listener' in path_str or name.endswith('listener') \
                or 'event' in path_str or name.endswith('event'):
            return 'Events & Listeners'
        elif 'scheduler' in path_str or name.endswith('scheduler') or '@Scheduled' in content:
            return 'Schedulers'
        elif 'validator' in path_str or name.endswith('validator'):
            return 'Validators'
        elif 'util' in path_str or 'helper' in path_str \
                or name.endswith('util') or name.endswith('utils') or name.endswith('helper'):
            return 'Utils & Helpers'
        elif 'config' in path_str or name.endswith('config') or name.endswith('configuration'):
            return 'Configuration'
        elif file_path.suffix in ['.yml', '.yaml', '.properties']:
            return 'Configuration Files'
        elif file_path.suffix == '.xml' and file_path.name != 'pom.xml':
            return 'XML Resources'
        elif name in ('main', 'application', 'app', 'bootstrap', 'server'):
            return 'Entry Points'
        else:
            return 'Others'

    def generate_file_section(self, file_path: Path, content: str, category: str) -> str:
        """Genera una sección markdown para un archivo"""
        # FIX: usar solo ruta relativa, nunca absoluta
        relative_path = self.get_relative_path(file_path)
        java_info = self.extract_java_info(content, file_path)

        section = f"\n## 📄 {relative_path}\n\n"

        if java_info.get('package'):
            section += f"**Package:** `{java_info['package']}`\n\n"
        if java_info.get('class_name'):
            section += f"**Clase:** `{java_info['class_name']}`\n\n"
        if java_info.get('extends'):
            section += f"**Extends:** `{java_info['extends']}`\n\n"
        if java_info.get('implements'):
            section += f"**Implements:** `{java_info['implements']}`\n\n"
        if java_info.get('annotations'):
            section += f"**Anotaciones:** `{'`, `'.join('@' + a for a in java_info['annotations'])}`\n\n"
        if java_info.get('endpoints'):
            section += f"**Endpoints:** `{'`, `'.join(java_info['endpoints'])}`\n\n"
        if java_info.get('dependencies'):
            section += f"**Dependencias inyectadas:** `{'`, `'.join(java_info['dependencies'])}`\n\n"

        section += f"**Categoría:** {category}\n\n"
        section += f"**Ruta:** `{relative_path}`\n\n"  # FIX: solo ruta relativa

        lang_map = {
            '.java': 'java',
            '.yml': 'yaml',
            '.yaml': 'yaml',
            '.properties': 'properties',
            '.xml': 'xml',
        }
        lang = lang_map.get(file_path.suffix, 'text')

        section += f"```{lang}\n{content}\n```\n\n"
        section += "---\n"

        return section

    def collect_config_files(self) -> list[tuple[Path, str, str]]:
        """Recoge los archivos de configuración raíz y resources de Spring"""
        collected = []

        # Archivos raíz del proyecto
        for rel in self.root_config_files:
            fp = self.project_path / rel
            if fp.exists() and fp.is_file():
                content = self.read_file_content(fp)
                ext = fp.suffix.lstrip('.')
                lang_map = {
                    'xml': 'xml', 'gradle': 'groovy', 'kts': 'kotlin',
                    'yml': 'yaml', 'yaml': 'yaml', 'env': 'bash',
                    '': 'dockerfile',
                }
                lang = lang_map.get(ext, ext or 'text')
                collected.append((fp, content, lang))

        # FIX: archivos application.* en su ruta correcta dentro de src/main/resources/
        for rel in self.spring_config_paths:
            fp = self.project_path / rel
            if fp.exists() and fp.is_file():
                content = self.read_file_content(fp)
                ext = fp.suffix.lstrip('.')
                lang = 'yaml' if ext in ('yml', 'yaml') else 'properties'
                collected.append((fp, content, lang))

        return collected

    def generate_documentation(self):
        """Genera la documentación completa del proyecto"""
        if not self.src_path.exists():
            print(f"❌ No se encontró el directorio src en: {self.project_path}")
            return

        files_by_category: dict[str, list] = {}
        total_files = 0

        print("🔍 Escaneando archivos Java en src/main/...")

        for file_path in self.src_path.rglob('*'):
            if file_path.is_file() and self.should_include_file(file_path):
                content = self.read_file_content(file_path)
                category = self.categorize_file(file_path, content)
                files_by_category.setdefault(category, [])
                files_by_category[category].append((file_path, content))
                total_files += 1
                print(f"  ✔ {self.get_relative_path(file_path)}")

        if total_files == 0:
            print("❌ No se encontraron archivos en el directorio src/ especificado.")
            return

        print(f"\n📊 Encontrados {total_files} archivos en {len(files_by_category)} categorías")

        # Archivos de configuración
        config_files = self.collect_config_files()
        if config_files:
            print(f"⚙️  Archivos de configuración encontrados: {len(config_files)}")

        # Índice principal
        main_doc = self.generate_main_document(files_by_category, total_files, config_files)
        main_file = self.output_dir / "00_INDEX.md"
        with open(main_file, 'w', encoding='utf-8') as f:
            f.write(main_doc)
        print(f"✅ Generado: {main_file}")

        # Documento de configuración en posición fija (01)
        if config_files:
            self.generate_config_document(config_files)

        # Documentos por categoría a partir de (02)
        for idx, (category, files) in enumerate(sorted(files_by_category.items()), 2):
            self.generate_category_document(category, files, idx)

        # Documento consolidado
        self.generate_consolidated_document(files_by_category, config_files)

        print(f"\n✨ Documentación generada exitosamente en: {self.output_dir}")
        print(f"📁 Total de archivos Java procesados: {total_files}")

    def generate_main_document(self, files_by_category: dict, total_files: int, config_files: list) -> str:
        doc = f"""# 📚 Documentación del Proyecto Spring Boot

**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
**Proyecto:** {self.project_path.name}
**Total de archivos Java:** {total_files}

## 🗂️ Índice de Categorías

"""
        if config_files:
            doc += f"0. **Configuración** ({len(config_files)} archivos) - Ver `01_Configuration.md`\n"

        for idx, (category, files) in enumerate(sorted(files_by_category.items()), 2):
            # FIX: reemplazar tanto '/' como ' ' en el nombre del archivo
            safe_name = category.replace('/', '_').replace(' ', '_').replace('&', 'and')
            doc += f"{idx}. **{category}** ({len(files)} archivos) - Ver `{idx:02d}_{safe_name}.md`\n"

        doc += "\n## 📂 Estructura del Proyecto\n\n```\n"
        doc += self.generate_tree_structure()
        doc += "```\n\n"

        doc += """## 🚀 Archivos Generados

- `00_INDEX.md` - Este archivo (índice general)
- `01_Configuration.md` - Configuración del proyecto (pom.xml, application.yml, .env.dev, etc.)
- `02_XX.md` hasta `NN_XX.md` - Documentos por categoría
- `99_COMPLETE.md` - Documento consolidado con todo el código

## 💡 Uso recomendado

Sube estos archivos a un chat de IA (Claude, ChatGPT, etc.) para:
- Análisis de arquitectura y patrones de diseño
- Detección de vulnerabilidades y code smells
- Sugerencias de refactorización
- Migración a nuevas versiones de Spring Boot
- Generación de documentación adicional (OpenAPI, README, etc.)
- Revisión del modelo de seguridad y autenticación

"""
        return doc

    def generate_tree_structure(self) -> str:
        tree = []
        for file_path in sorted(self.src_path.rglob('*')):
            if file_path.is_file() and self.should_include_file(file_path):
                depth = len(file_path.relative_to(self.src_path).parts) - 1
                prefix = "  " * depth + "├── "
                tree.append(f"{prefix}{file_path.name}")
        return "\n".join(tree[:100])

    def generate_config_document(self, config_files: list):
        """FIX: documento de configuración siempre en posición fija 01"""
        filepath = self.output_dir / "01_Configuration.md"
        doc = f"""# ⚙️ Configuración del Proyecto

**Total de archivos:** {len(config_files)}
**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

---

"""
        for file_path, content, lang in config_files:
            relative_path = self.get_relative_path(file_path)
            doc += f"\n## 📄 {relative_path}\n\n"
            doc += f"**Ruta:** `{relative_path}`\n\n"
            doc += f"```{lang}\n{content}\n```\n\n"
            doc += "---\n"

        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(doc)
        print(f"✅ Generado: {filepath}")

    def generate_category_document(self, category: str, files: list, idx: int):
        # FIX: reemplazar '/', ' ' y '&' para evitar nombres de archivo inválidos
        safe_name = category.replace('/', '_').replace(' ', '_').replace('&', 'and')
        filename = f"{idx:02d}_{safe_name}.md"
        filepath = self.output_dir / filename

        doc = f"""# {category}

**Total de archivos:** {len(files)}
**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

---

"""
        for file_path, content in sorted(files):
            doc += self.generate_file_section(file_path, content, category)

        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(doc)
        print(f"✅ Generado: {filepath}")

    def generate_consolidated_document(self, files_by_category: dict, config_files: list):
        filepath = self.output_dir / "99_COMPLETE.md"

        doc = f"""# 📦 Proyecto Completo Spring Boot - {self.project_path.name}

**Generado:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

Este archivo contiene TODOS los archivos del proyecto organizados por categoría.

---

"""
        # Configuración primero
        if config_files:
            doc += "\n# ⚙️ Configuración\n\n"
            for file_path, content, lang in config_files:
                relative_path = self.get_relative_path(file_path)
                doc += f"\n## 📄 {relative_path}\n\n"
                doc += f"```{lang}\n{content}\n```\n\n---\n"

        # Luego código por categoría
        for category, files in sorted(files_by_category.items()):
            doc += f"\n# 📁 {category}\n\n"
            for file_path, content in sorted(files):
                doc += self.generate_file_section(file_path, content, category)

        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(doc)
        print(f"✅ Generado: {filepath}")


# 🎯 USO DEL SCRIPT
if __name__ == "__main__":
    # CONFIGURA AQUÍ LA RUTA DE TU PROYECTO SPRING BOOT
    project_path = "."  # Usar "." si ejecutas el script desde la raíz del proyecto
    # O especifica la ruta completa: project_path = "/home/user/mi-proyecto-spring"

    output_directory = "docs_md"  # Carpeta donde se guardarán los .md generados

    generator = SpringBootDocGenerator(project_path, output_directory)
    generator.generate_documentation()

    print("\n" + "=" * 60)
    print("🎉 ¡Proceso completado!")
    print(f"📁 Los archivos .md están en: {output_directory}/")
    print("=" * 60)
