package com.quartzjobs.util.log.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reemplazo directo del patrón "FileWriter + append sin límite" usado en {@code writeLog(...)}.
 * Antes de cada escritura, si el archivo ya superó el tamaño máximo, lo rota (igual que hace
 * Logback con SizeAndTimeBasedRollingPolicy, pero acá manual y simple).
 *
 * <p>Rotación: {@code archivo.log} -> {@code archivo.log.1} -> {@code archivo.log.2} ... hasta
 * {@code maxBackups}, después el más viejo se borra. Así el servidor nunca acumula más de {@code
 * (maxBackups + 1) * maxSizeBytes} por archivo, sin importar cuánto corra la aplicación.
 *
 * <p>Uso (reemplaza el cuerpo actual de tu método {@code writeLog}):
 *
 * <pre>
 * private void writeLog(String logFilePath, String message) {
 *     RotatingFileWriter.append(logFilePath, message);
 * }
 * </pre>
 */
public final class RotatingFileWriter {

    private static final Logger log = LoggerFactory.getLogger(RotatingFileWriter.class);
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024 * 1024; // 5MB por archivo
    private static final int DEFAULT_MAX_BACKUPS = 5; // .1 al .5 -> tope ~30MB por job

    private RotatingFileWriter() {}

    /** Escribe una línea con timestamp, rotando con los límites por defecto (5MB x 5 backups). */
    public static void append(String filePath, String message) {
        append(filePath, message, DEFAULT_MAX_SIZE_BYTES, DEFAULT_MAX_BACKUPS);
    }

    /**
     * @param filePath ruta del archivo (ej: "logs/woocommerce-datasync.txt")
     * @param message línea a escribir (se le antepone el timestamp)
     * @param maxSizeBytes tamaño máximo antes de rotar
     * @param maxBackups cuántas copias rotadas conservar como máximo
     */
    public static synchronized void append(
            String filePath, String message, long maxSizeBytes, int maxBackups) {
        try {
            Path path = Path.of(filePath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (Files.exists(path) && Files.size(path) >= maxSizeBytes) {
                rotate(path, maxBackups);
            }

            String line =
                    LocalDateTime.now().format(TS_FORMAT) + " " + message + System.lineSeparator();
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (IOException e) {
            // Nunca debe tumbar el job por un problema de logging a archivo.
            log.error("No se pudo escribir/rotar el archivo de log '{}'", filePath, e);
        }
    }

    private static void rotate(Path path, int maxBackups) throws IOException {
        // Borra el backup más viejo si ya llegamos al tope.
        Path oldest = backupPath(path, maxBackups);
        Files.deleteIfExists(oldest);

        // Desplaza: archivo.N-1 -> archivo.N, ..., archivo.1 -> archivo.2
        for (int i = maxBackups - 1; i >= 1; i--) {
            Path src = backupPath(path, i);
            Path dst = backupPath(path, i + 1);
            if (Files.exists(src)) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // El archivo activo pasa a ser el backup .1
        Files.move(path, backupPath(path, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path backupPath(Path original, int index) {
        return Path.of(original + "." + index);
    }
}
