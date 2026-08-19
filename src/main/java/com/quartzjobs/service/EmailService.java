package com.quartzjobs.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * ╔══════════════════════════════════════════════════════════════╗ EmailService — Servicio de envío
 * de correos electrónicos ╚══════════════════════════════════════════════════════════════╝
 *
 * <p>Métodos disponibles: --------------------- send(to, subject, body) → texto plano sendHtml(to,
 * subject, htmlBody) → HTML sendHtmlWithAttachment(to, subject, html, ...) → HTML + adjunto
 *
 * <p>Configuración SMTP requerida (ver application.yml / .env.dev):
 * --------------------------------------------------------- MAIL_HOST=smtp.gmail.com MAIL_PORT=587
 * MAIL_USERNAME=tu_correo@gmail.com MAIL_PASSWORD=tu_app_password
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ══════════════════════════════════════════════════════════════
    //  Método 1: Email de texto plano
    // ══════════════════════════════════════════════════════════════

    /**
     * Envía un correo electrónico de texto plano (sin formato).
     *
     * <p>Úsalo cuando: el mensaje es simple y no necesita estilos.
     *
     * @param to Destinatario. Ej: "usuario@empresa.com"
     * @param subject Asunto. Ej: "Notificación del sistema"
     * @param body Cuerpo en texto plano.
     */
    public void send(String to, String subject, String body) {
        log.info("Enviando email (texto) a: {}", to);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);
        log.info("✔ Email (texto) enviado a: {}", to);
    }

    // ══════════════════════════════════════════════════════════════
    //  Método 2: Email con contenido HTML
    // ══════════════════════════════════════════════════════════════

    /**
     * Envía un correo electrónico con contenido HTML.
     *
     * <p>Úsalo cuando: quieres colores, tablas, botones o cualquier formato visual en el email.
     *
     * <p>Ejemplo de uso desde un Job: ───────────────────────────── String html = """
     *
     * <h2 style="color: #2e86c1;">Reporte Diario</h2>
     *
     * <p>Hola, aquí está el resumen de hoy:
     *
     * <table border="1" cellpadding="8">
     *         <tr><th>Métrica</th><th>Valor</th></tr>
     *         <tr><td>Ventas</td><td>150</td></tr>
     *         <tr><td>Usuarios</td><td>320</td></tr>
     *       </table>
     *
     * <br>
     * <a href="https://mi-sistema.com/reportes" style="background:#2e86c1; color:white;
     * padding:10px 20px; text-decoration:none; border-radius:5px;"> Ver reporte completo </a> """;
     *
     * <p>emailService.sendHtml("jefe@empresa.com", "Reporte del día", html);
     *
     * @param to Destinatario.
     * @param subject Asunto.
     * @param htmlBody Cuerpo en formato HTML.
     * @throws MessagingException Si hay error al construir el mensaje. En el Job captura con: catch
     *     (Exception e) { ... }
     */
    public void sendHtml(String to, String subject, String htmlBody) throws MessagingException {

        log.info("Enviando email (HTML) a: {}", to);

        /*
         * MimeMessage: tipo de mensaje que soporta HTML, adjuntos e imágenes.
         * Es más completo que SimpleMailMessage pero requiere MimeMessageHelper.
         */
        MimeMessage mimeMessage = mailSender.createMimeMessage();

        /*
         * MimeMessageHelper: asistente de Spring que simplifica
         * la configuración del MimeMessage.
         *
         * Parámetros del constructor:
         *   mimeMessage  → el mensaje a configurar
         *   true         → multipart = true (necesario para HTML + adjuntos)
         *   "UTF-8"      → codificación que soporta tildes, ñ, emojis, etc.
         */
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(subject);

        /*
         * setText(contenido, esHtml):
         *   true  → interpreta el contenido como HTML (renderiza estilos)
         *   false → muestra el texto tal cual (el HTML aparece crudo)
         */
        helper.setText(htmlBody, true);

        mailSender.send(mimeMessage);
        log.info("✔ Email (HTML) enviado a: {}", to);
    }

    // ══════════════════════════════════════════════════════════════
    //  Método 3: Email HTML con archivo adjunto
    // ══════════════════════════════════════════════════════════════

    /**
     * Envía un correo HTML con un archivo adjunto.
     *
     * <p>Úsalo cuando: necesitas adjuntar un PDF, Excel u otro archivo generado por tu sistema
     * junto al contenido HTML.
     *
     * <p>Ejemplos de uso: ─────────────────
     *
     * <p>// Adjuntar un archivo desde disco: FileSystemResource archivo = new
     * FileSystemResource(new File("/tmp/reporte.pdf")); emailService.sendHtmlWithAttachment(
     * "gerente@empresa.com", "Reporte de enero", "
     *
     * <h1>Adjunto el reporte mensual.</h1>
     *
     * ", "reporte-enero.pdf", archivo );
     *
     * <p>// Adjuntar bytes generados en memoria (ej: reporte generado con iText/JasperReports):
     * byte[] pdfBytes = reporteService.generarPdf(); ByteArrayResource recurso = new
     * ByteArrayResource(pdfBytes); emailService.sendHtmlWithAttachment( "gerente@empresa.com",
     * "Reporte generado", "
     *
     * <h1>Reporte generado automáticamente.</h1>
     *
     * ", "reporte.pdf", recurso );
     *
     * @param to Destinatario.
     * @param subject Asunto.
     * @param htmlBody Cuerpo en HTML.
     * @param attachmentName Nombre del archivo como aparece en el email. Ej: "reporte-enero.pdf"
     * @param attachment El archivo a adjuntar (InputStreamSource). Tipos compatibles: new
     *     FileSystemResource(file) → desde disco new ByteArrayResource(bytes) → desde memoria new
     *     ClassPathResource("x.pdf") → desde resources/
     * @throws MessagingException Si hay error al construir el mensaje.
     */
    public void sendHtmlWithAttachment(
            String to,
            String subject,
            String htmlBody,
            String attachmentName,
            InputStreamSource attachment)
            throws MessagingException {

        log.info("Enviando email (HTML + adjunto: '{}') a: {}", attachmentName, to);

        MimeMessage mimeMessage = mailSender.createMimeMessage();

        // true = multipart, obligatorio cuando el email tiene adjuntos
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true); // true = es HTML

        /*
         * addAttachment(): añade el archivo al email.
         *
         * Primer parámetro  → nombre que verá el destinatario en su bandeja
         * Segundo parámetro → el contenido del archivo (InputStreamSource)
         *
         * Puedes llamar a addAttachment() varias veces para adjuntar
         * múltiples archivos en el mismo email.
         */
        helper.addAttachment(attachmentName, attachment);

        mailSender.send(mimeMessage);
        log.info("✔ Email (HTML + adjunto) enviado a: {}", to);
    }
}
