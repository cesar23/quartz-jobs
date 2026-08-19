package com.quartzjobs.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ╔══════════════════════════════════════════════════════════════╗ FileWriterService — Escribe
 * líneas con fecha/hora en un fichero
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>Responsabilidad: ----------------- Este servicio tiene UNA sola responsabilidad: abrir un
 * fichero y añadir una línea con la fecha y hora actual.
 *
 * <p>El fichero se crea automáticamente si no existe. Si ya existe, las líneas se van AÑADIENDO al
 * final (append).
 *
 * <p>Ruta del fichero por defecto: logs/registro.txt (relativa al directorio de trabajo del
 * proceso)
 *
 * <p>Puedes cambiarla pasando el parámetro "filePath" desde el Job.
 */
@Service
public class FileWriterService {

    private static final Logger log = LoggerFactory.getLogger(FileWriterService.class);

    // Formato de fecha/hora → "2026-03-10 19:30:00"
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Ruta por defecto si el Job no especifica ninguna
    private static final String DEFAULT_PATH = "logs/registro.txt";

    /**
     * Añade una línea con la fecha y hora actual al fichero.
     *
     * @param filePath Ruta del fichero donde escribir. Si es null o vacío, usa DEFAULT_PATH.
     * @param extra Texto adicional opcional que se añade junto a la fecha. Puede ser null.
     */
    public void appendLine(String filePath, String extra) throws IOException {

        // Si no llega ruta, usamos la por defecto
        String resolvedPath = (filePath != null && !filePath.isBlank()) ? filePath : DEFAULT_PATH;

        Path path = Paths.get(resolvedPath);

        // Crea el directorio padre si no existe
        // Ejemplo: si la ruta es "logs/registro.txt" → crea la carpeta "logs/"
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        // Construye la línea a escribir
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String line =
                (extra != null && !extra.isBlank())
                        ? "[" + timestamp + "] " + extra
                        : "[" + timestamp + "] Job ejecutado";

        // Escribe la línea en el fichero
        // StandardOpenOption.CREATE      → crea el fichero si no existe
        // StandardOpenOption.APPEND      → añade al final, nunca sobreescribe
        Files.writeString(
                path,
                line + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        log.info("Línea escrita en [{}]: {}", resolvedPath, line);
    }
}
