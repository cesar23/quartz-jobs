package com.quartzjobs.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilidad para leer, escribir, eliminar y "tailear" ficheros del sistema (equivalente a cat, echo,
 * rm y tail -n N de Linux).
 *
 * <p>El metodo tail(String, int) NO carga el fichero entero en memoria: lee por bloques desde el
 * final hacia atras con RandomAccessFile, igual que hace `tail -n N` a bajo nivel. Es seguro usarlo
 * sobre logs de varios GB como el rotado que genera logback-spring.xml en este proyecto.
 *
 * <p>Ver ejemplos de uso al final de este fichero.
 */
public final class FileManagerUtil {

    // ==================================================================
    // EJEMPLOS DE USO
    // ==================================================================
    //
    // Leer las ultimas 10 lineas de un log (equivalente a: tail -10 fichero):
    //
    //   List<String> ultimas10 = FileManagerUtil.tail("/var/log/factu-sync/app.log", 10);
    //   ultimas10.forEach(System.out::println);
    //
    // Leer las ultimas 10 lineas ya formateadas como JSON legible
    // (equivalente a: tail -n 10 app.log | jq):
    //
    //   List<String> ultimas10Json = FileManagerUtil.tailAsJson("/var/log/factu-sync/app.log", 10);
    //   ultimas10Json.forEach(System.out::println);
    //
    //   // o como un unico bloque de texto, listo para mostrar tal cual:
    //   String salida = FileManagerUtil.tailAsJsonText("/var/log/factu-sync/app.log", 10);
    //   System.out.println(salida);
    //
    // Leer el fichero completo como texto (equivalente a: cat fichero):
    //
    //   String contenido = FileManagerUtil.readFile("/var/log/factu-sync/app.log");
    //
    // Leer el fichero completo como lista de lineas:
    //
    //   List<String> lineas = FileManagerUtil.readAllLines("/var/log/factu-sync/app.log");
    //
    // Escribir sobreescribiendo el contenido (equivalente a: echo "..." > fichero):
    //
    //   FileManagerUtil.writeFile("/tmp/salida.txt", "hola mundo\n", false);
    //
    // Escribir añadiendo al final (equivalente a: echo "..." >> fichero):
    //
    //   FileManagerUtil.writeFile("/tmp/salida.txt", "otra linea\n", true);
    //
    // Escribir un log de negocio linea por linea, con ROTACION POR TAMAÑO
    // (evita que el fichero crezca sin limite en el servidor):
    //
    //   FileManagerUtil.appendLineWithRotation("logs/woocommerce-auth.txt",
    //       "INFO [job1] Token obtenido correctamente");
    //
    //   // con limites propios (2MB por fichero, hasta 3 copias rotadas):
    //   FileManagerUtil.appendLineWithRotation("logs/woocommerce-auth.txt",
    //       "INFO [job1] ...", 2 * 1024 * 1024, 3);
    //
    // Comprobar si existe y obtener su tamaño:
    //
    //   if (FileManagerUtil.exists("/tmp/salida.txt")) {
    //       long bytes = FileManagerUtil.size("/tmp/salida.txt");
    //       log.info("El fichero pesa {} bytes", bytes);
    //   }
    //
    // Eliminar un fichero (equivalente a: rm fichero):
    //
    //   boolean eliminado = FileManagerUtil.deleteFile("/tmp/salida.txt");
    //
    // Capturar errores (todas las operaciones lanzan una excepcion unchecked):
    //
    //   try {
    //       FileManagerUtil.tail("/ruta/que/no/existe.log", 10);
    //   } catch (FileManagerUtil.FileManagerUtilException e) {
    //       log.error("Error gestionando el fichero", e);
    //   }
    //
    // ==================================================================

    private static final Logger log = LoggerFactory.getLogger(FileManagerUtil.class);

    private static final int INITIAL_BUFFER_SIZE = 8 * 1024; // 8 KB
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024; // 64 MB tope de seguridad

    private static final long DEFAULT_MAX_LOG_SIZE_BYTES = 5L * 1024 * 1024; // 5MB por fichero
    private static final int DEFAULT_MAX_BACKUPS = 5; // .1 al .5 -> tope ~30MB por fichero
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Reutilizamos un unico ObjectMapper (es thread-safe para lectura/escritura)
    // en vez de crear uno nuevo en cada llamada.
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private FileManagerUtil() {}

    // ------------------------------------------------------------------
    // Consultas basicas
    // ------------------------------------------------------------------

    /** Equivalente a `test -f fichero`. */
    public static boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    /** Tamaño del fichero en bytes. Equivalente a `stat -c%s fichero`. */
    public static long size(String path) {
        try {
            return Files.size(Paths.get(path));
        } catch (IOException e) {
            log.error("No se pudo obtener el tamaño de {}", path, e);
            throw new FileManagerUtilException("No se pudo obtener el tamaño de " + path, e);
        }
    }

    // ------------------------------------------------------------------
    // Lectura
    // ------------------------------------------------------------------

    /** Lee el fichero completo como String (UTF-8). Equivalente a `cat fichero`. */
    public static String readFile(String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("No se pudo leer el fichero {}", path, e);
            throw new FileManagerUtilException("No se pudo leer el fichero " + path, e);
        }
    }

    /** Lee el fichero completo como lista de lineas. */
    public static List<String> readAllLines(String path) {
        try {
            return Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("No se pudo leer el fichero {}", path, e);
            throw new FileManagerUtilException("No se pudo leer el fichero " + path, e);
        }
    }

    // ------------------------------------------------------------------
    // Escritura
    // ------------------------------------------------------------------

    /**
     * Escribe contenido en el fichero.
     *
     * @param path ruta del fichero
     * @param content contenido a escribir
     * @param append true = añade al final (`echo "..." >> fichero`), false = sobreescribe (`echo
     *     "..." > fichero`)
     */
    public static void writeFile(String path, String content, boolean append) {
        try {
            Path p = Paths.get(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            StandardOpenOption modo =
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
            Files.writeString(p, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, modo);
        } catch (IOException e) {
            log.error("No se pudo escribir el fichero {}", path, e);
            throw new FileManagerUtilException("No se pudo escribir el fichero " + path, e);
        }
    }

    // ------------------------------------------------------------------
    // Escritura de logs con ROTACION POR TAMAÑO
    // ------------------------------------------------------------------
    //
    // A diferencia de writeFile(..., append=true), estos metodos estan
    // pensados para logs de negocio que se van escribiendo linea a linea
    // durante meses (ej: writeLog(...) de los Jobs de WooCommerce).
    // Antes de cada escritura, si el fichero ya supero el tamaño maximo,
    // lo rota: fichero.txt -> fichero.txt.1 -> fichero.txt.2 ... hasta
    // maxBackups, despues el mas viejo se borra. Asi el servidor nunca
    // acumula mas de (maxBackups + 1) * maxSizeBytes por fichero, sin
    // importar cuanto tiempo corra la aplicacion.

    /**
     * Igual que appendLineWithRotation(path, line, maxSizeBytes, maxBackups) pero con los limites
     * por defecto (5MB, 5 backups).
     */
    public static void appendLineWithRotation(String path, String line) {
        appendLineWithRotation(path, line, DEFAULT_MAX_LOG_SIZE_BYTES, DEFAULT_MAX_BACKUPS);
    }

    /**
     * Añade una linea (con timestamp) al fichero, rotandolo primero si ya supero maxSizeBytes.
     *
     * @param path ruta del fichero (ej. "logs/woocommerce-auth.txt")
     * @param line linea a escribir (se le antepone el timestamp)
     * @param maxSizeBytes tamaño maximo del fichero antes de rotar
     * @param maxBackups cuantas copias rotadas conservar como maximo
     */
    public static synchronized void appendLineWithRotation(
            String path, String line, long maxSizeBytes, int maxBackups) {
        try {
            Path p = Paths.get(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }

            if (Files.exists(p) && Files.size(p) >= maxSizeBytes) {
                rotate(p, maxBackups);
            }

            String timestamped =
                    LocalDateTime.now().format(TS_FORMAT) + " " + line + System.lineSeparator();
            Files.writeString(
                    p,
                    timestamped,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

        } catch (IOException e) {
            // Un log de negocio que falla NUNCA debe tumbar el job que lo llama.
            log.error("No se pudo escribir/rotar el fichero de log {}", path, e);
        }
    }

    private static void rotate(Path path, int maxBackups) throws IOException {
        // Borra el backup mas viejo si ya llegamos al tope.
        Path oldest = backupPath(path, maxBackups);
        Files.deleteIfExists(oldest);

        // Desplaza: fichero.N-1 -> fichero.N, ..., fichero.1 -> fichero.2
        for (int i = maxBackups - 1; i >= 1; i--) {
            Path src = backupPath(path, i);
            Path dst = backupPath(path, i + 1);
            if (Files.exists(src)) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // El fichero activo pasa a ser el backup .1
        Files.move(path, backupPath(path, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path backupPath(Path original, int index) {
        return Paths.get(original + "." + index);
    }

    // ------------------------------------------------------------------
    // Eliminacion
    // ------------------------------------------------------------------

    /** Elimina el fichero. Equivalente a `rm fichero`. Devuelve false si no existia. */
    public static boolean deleteFile(String path) {
        try {
            return Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.error("No se pudo eliminar el fichero {}", path, e);
            throw new FileManagerUtilException("No se pudo eliminar el fichero " + path, e);
        }
    }

    // ------------------------------------------------------------------
    // Tail (equivalente a `tail -n N fichero`)
    // ------------------------------------------------------------------

    /**
     * Devuelve las ultimas numLines lineas del fichero, equivalente a `tail -n numLines fichero`.
     *
     * <p>Lee el fichero por bloques desde el final hacia atras usando RandomAccessFile.seek(long),
     * sin cargarlo entero en memoria. Si el bloque inicial no contiene suficientes lineas, dobla el
     * tamaño del bloque y reintenta desde una posicion mas atras del fichero.
     *
     * @param path ruta del fichero (ej. "/var/log/factu-sync/app.log")
     * @param numLines numero de lineas finales a devolver (equivalente a -N)
     * @return lineas en orden natural (la mas antigua primero, la ultima al final)
     */
    public static List<String> tail(String path, int numLines) {
        if (numLines <= 0) {
            return new ArrayList<>();
        }

        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            throw new FileManagerUtilException("El fichero no existe: " + path);
        }

        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) {
                return new ArrayList<>();
            }

            int bufferSize = INITIAL_BUFFER_SIZE;

            while (true) {
                long chunkStart = Math.max(0, fileLength - bufferSize);
                int chunkLength = (int) (fileLength - chunkStart);

                byte[] chunk = new byte[chunkLength];
                raf.seek(chunkStart);
                raf.readFully(chunk);

                // Decodificamos el bloque completo como UTF-8 (evita cortar
                // caracteres multibyte a mitad, algo que si pasaria si
                // decodificaramos byte a byte durante la lectura).
                String texto = new String(chunk, StandardCharsets.UTF_8);
                String[] lineas = texto.split("\n", -1);

                // Si no empezamos desde el principio del fichero, la primera
                // "linea" del array puede estar incompleta (cortada a mitad),
                // asi que la descartamos salvo que ya estemos al inicio.
                int desde = (chunkStart > 0) ? 1 : 0;
                // Si el fichero termina en '\n', el split deja una ultima
                // cadena vacia que no cuenta como linea real.
                int hasta = lineas.length;
                if (hasta > desde && lineas[hasta - 1].isEmpty()) {
                    hasta--;
                }

                int lineasCompletas = hasta - desde;

                if (lineasCompletas >= numLines || chunkStart == 0) {
                    int start = Math.max(desde, hasta - numLines);
                    List<String> resultado = new ArrayList<>(hasta - start);
                    for (int i = start; i < hasta; i++) {
                        resultado.add(lineas[i]);
                    }
                    return resultado;
                }

                if (bufferSize >= MAX_BUFFER_SIZE) {
                    // Fichero con lineas extremadamente largas o numLines muy
                    // grande: devolvemos lo maximo que hemos podido leer.
                    log.warn(
                            "tail() alcanzo el buffer maximo ({} bytes) leyendo {}",
                            MAX_BUFFER_SIZE,
                            path);
                    int start = desde;
                    List<String> resultado = new ArrayList<>(hasta - start);
                    for (int i = start; i < hasta; i++) {
                        resultado.add(lineas[i]);
                    }
                    return resultado;
                }

                bufferSize *= 2;
            }
        } catch (IOException e) {
            log.error("No se pudo leer el final del fichero {}", path, e);
            throw new FileManagerUtilException("No se pudo leer el final del fichero " + path, e);
        }
    }

    // ------------------------------------------------------------------
    // Tail + JSON pretty-print (equivalente a `tail -n N fichero | jq`)
    // ------------------------------------------------------------------

    /**
     * Devuelve las ultimas numLines lineas del fichero, formateando cada linea que sea JSON valido
     * con indentacion legible (2 espacios), igual que hace jq por defecto.
     *
     * <p>Pensado para logs en formato JSON como los que genera este proyecto en los perfiles "pre"
     * y "prod" via LogstashEncoder (ver logback-spring.xml). Si una linea no es JSON valido (por
     * ejemplo un stacktrace multilinea en el perfil "dev"), se devuelve tal cual, sin fallar.
     *
     * @param path ruta del fichero (ej. "/var/log/factu-sync/app.log")
     * @param numLines numero de lineas finales a devolver
     * @return lista con cada linea formateada (una entrada de la lista puede contener varios saltos
     *     de linea si el JSON se expandio en varias lineas)
     */
    public static List<String> tailAsJson(String path, int numLines) {
        List<String> lineasCrudas = tail(path, numLines);
        List<String> resultado = new ArrayList<>(lineasCrudas.size());
        for (String linea : lineasCrudas) {
            resultado.add(prettyJson(linea));
        }
        return resultado;
    }

    /**
     * Igual que tailAsJson(String, int) pero devuelve el resultado completo ya unido en un unico
     * String, separado por saltos de linea, listo para imprimir o loguear tal cual como lo
     * mostraria: tail -n numLines fichero | jq
     *
     * @param path ruta del fichero
     * @param numLines numero de lineas finales a devolver
     * @return texto formateado, con las lineas JSON ya indentadas
     */
    public static String tailAsJsonText(String path, int numLines) {
        return String.join("\n", tailAsJson(path, numLines));
    }

    /**
     * Convierte una linea de texto en JSON indentado (2 espacios), al estilo de jq. Si la linea no
     * es JSON valido, se devuelve sin modificar en vez de lanzar una excepcion, para no romper el
     * tail completo por una sola linea "rara".
     */
    private static String prettyJson(String linea) {
        if (linea == null || linea.isBlank()) {
            return linea;
        }
        try {
            Object json = JSON_MAPPER.readValue(linea, Object.class);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            log.debug("La linea no es JSON valido, se devuelve tal cual: {}", linea);
            return linea;
        }
    }

    // ------------------------------------------------------------------
    // Excepcion interna
    // ------------------------------------------------------------------

    /**
     * Excepcion unchecked que envuelve cualquier IOException ocurrida al operar sobre un fichero.
     */
    public static class FileManagerUtilException extends RuntimeException {
        public FileManagerUtilException(String message) {
            super(message);
        }

        public FileManagerUtilException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
