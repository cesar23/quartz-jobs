package com.quartzjobs.util;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * <b>FileDownloadUtil</b> es una utilidad profesional para descargar archivos desde una URL a una
 * ruta local usando la API moderna {@link HttpClient} (Java 11+). Permite configurar cabeceras como
 * User-Agent, timeout y maneja de forma robusta los errores más comunes (timeout, 404, 500, host no
 * encontrado, etc.).
 *
 * <p><b>Ejemplo de uso:</b>
 *
 * <pre>{@code
 * String url = "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png";
 * Path destino = Path.of("descargas/ejemplo.png");
 * FileDownloadUtil.downloadFile(url, destino);
 * }</pre>
 *
 * <p>El método imprime mensajes claros según el resultado de la descarga.
 */
public class FileDownloadUtil {

    /**
     * Descarga un archivo desde la URL especificada y lo guarda en la ruta destino.
     *
     * <p>El método imprime mensajes específicos según el tipo de error:
     *
     * <ul>
     *   <li>Timeout: "Error: Tiempo de espera agotado (timeout)..."
     *   <li>404: "Error: La URL no existe o el recurso no fue encontrado (404)..."
     *   <li>500: "Error del servidor (500)..."
     *   <li>Host no encontrado: "Error: No se pudo resolver el host..."
     *   <li>Otros errores HTTP: "Error HTTP ..."
     *   <li>Errores de E/S: "Error de entrada/salida..."
     * </ul>
     *
     * @param fileUrl URL del archivo a descargar (no nula). Ejemplo:
     *     "https://sercoplus.com/img/m/14-small_default.jpg"
     * @param targetPath Ruta local donde se guardará el archivo (no nula). Ejemplo:
     *     Path.of("descargas/ejemplo.png")
     * @return void. El archivo se guarda en la ruta indicada o se imprime un mensaje de error.
     * @throws NullPointerException si fileUrl o targetPath son nulos.
     * @throws IllegalArgumentException si la URL es inválida.
     * @example FileDownloadUtil.downloadFile("https://sercoplus.com/img/m/14-small_default.jpg",
     *     Path.of("descargas/ejemplo.png"));
     * @see HttpClient
     * @see HttpRequest
     * @see HttpResponse
     */
    public static void downloadFile(String fileUrl, Path targetPath) {
        Objects.requireNonNull(fileUrl, "La URL del archivo no puede ser nula");
        Objects.requireNonNull(targetPath, "La ruta de destino no puede ser nula");

        // Crear directorios si no existen
        Path parent = targetPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                System.err.println("No se pudo crear el directorio destino: " + e.getMessage());
                return;
            }
        }

        // Configuración del cliente HTTP con redirecciones y timeout
        HttpClient client =
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        // Construcción de la solicitud HTTP con User-Agent y timeout
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(fileUrl))
                        .timeout(Duration.ofSeconds(30)) // Timeout de 30 segundos
                        .header("User-Agent", "Mozilla/5.0 (compatible; FileDownloadUtil/1.0)")
                        .GET()
                        .build();

        try {
            // Envía la solicitud y guarda el archivo en targetPath
            HttpResponse<Path> response =
                    client.send(request, HttpResponse.BodyHandlers.ofFile(targetPath));
            int status = response.statusCode();
            if (status == 200) {
                System.out.println("Descarga completada: " + targetPath.toAbsolutePath());
            } else if (status == 404) {
                System.err.println(
                        "Error: La URL no existe o el recurso no fue encontrado (404): " + fileUrl);
            } else if (status == 500) {
                System.err.println("Error del servidor (500) al descargar: " + fileUrl);
            } else {
                System.err.println("Error HTTP " + status + " al descargar: " + fileUrl);
            }
        } catch (HttpTimeoutException e) {
            System.err.println(
                    "Error: Tiempo de espera agotado (timeout) al descargar: " + fileUrl);
        } catch (UnknownHostException e) {
            System.err.println("Error: No se pudo resolver el host de la URL: " + fileUrl);
        } catch (IOException e) {
            System.err.println(
                    "Error de entrada/salida al descargar: " + fileUrl + " - " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Descarga interrumpida para: " + fileUrl);
        } catch (IllegalArgumentException e) {
            System.err.println("URL inválida: " + fileUrl);
        }
    }

    /**
     * Ejemplo de uso de la utilidad para descargar un archivo de internet.
     *
     * <p>Descarga una imagen de Wikipedia y la guarda en la carpeta "descargas".
     *
     * @param args argumentos de línea de comandos (no usados)
     * @example java FileDownloadUtil → Descargará la imagen de ejemplo en la carpeta "descargas".
     */
    public static void main(String[] args) {
        // Ejemplo de uso profesional:
        String url = "https://sercoplus.com/img/m/14-small_default.jpg";
        Path destino = Path.of("descargas/ejemplo.png");
        FileDownloadUtil.downloadFile(url, destino);
    }
}
