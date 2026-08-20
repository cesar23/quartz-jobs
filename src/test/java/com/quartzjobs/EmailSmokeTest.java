package com.quartzjobs;

// EmailSmokeTest.java

//
// Test STANDALONE (no necesita Spring ni levantar toda la app) para validar
// que MAIL_USERNAME / MAIL_PASSWORD del .env.dev realmente autentican con Gmail
// y logran enviar un correo.
//
// CÓMO EJECUTARLO (usa las mismas libs jakarta.mail que ya trae tu proyecto):
//
//  Opción A (recomendada, desde IntelliJ/Eclipse):
//    1. Copia este archivo a: src/test/java/com/quartzjobs/EmailSmokeTest.java
//    2. Click derecho -> Run 'EmailSmokeTest.main()'
//
//  Opción B (línea de comandos, sin tocar el proyecto):
//    javac -cp "target/classes;$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
// EmailSmokeTest.java
//    java  -cp ".;target/classes;..." EmailSmokeTest
//
// El script lee el archivo .env.dev de la raíz del proyecto (mismo formato que usas
// con spring-dotenv), así no tienes que exportar nada manualmente.

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EmailSmokeTest {

    @Test
    void maskReturnsPlaceholderForNullOrBlank() {
        assertEquals("⚠️ NO DEFINIDA", mask(null));
        assertEquals("⚠️ NO DEFINIDA", mask("   "));
    }

    @Test
    void maskObfuscatesShortAndLongValues() {
        assertEquals("****", mask("abc"));
        assertEquals("ab****", mask("abcdef"));
    }

    @Test
    void loadDotEnvParsesKeyValuePairs(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env.test");
        Files.writeString(
                envFile,
                """
            # comment
            MAIL_HOST=smtp.gmail.com
            MAIL_PORT=587
            QUOTED="hello"
            SINGLE='world'
            """);

        Map<String, String> env = loadDotEnv(envFile.toString());

        assertEquals("smtp.gmail.com", env.get("MAIL_HOST"));
        assertEquals("587", env.get("MAIL_PORT"));
        assertEquals("hello", env.get("QUOTED"));
        assertEquals("world", env.get("SINGLE"));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = loadDotEnv(".env.dev");

        String host = env.getOrDefault("MAIL_HOST", "smtp.gmail.com");
        int port = Integer.parseInt(env.getOrDefault("MAIL_PORT", "587"));
        String username = env.get("MAIL_USERNAME");
        String password = env.get("MAIL_PASSWORD");

        System.out.println("═".repeat(60));
        System.out.println("  📧 TEST SMTP GMAIL");
        System.out.println("═".repeat(60));
        System.out.println("  host     = " + host);
        System.out.println("  port     = " + port);
        System.out.println("  username = " + (username == null ? "⚠️ NO DEFINIDA" : username));
        System.out.println("  password = " + mask(password));
        System.out.println();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            System.out.println(
                    "❌ Faltan MAIL_USERNAME o MAIL_PASSWORD en el .env.dev (raíz del proyecto).");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        // timeouts cortos para que el test falle rápido, no se cuelgue
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session =
                Session.getInstance(
                        props,
                        new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(username, password);
                            }
                        });

        try {
            System.out.println("→ Conectando y autenticando...");

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(username));
            message.setSubject("[quartz-jobs] Test SMTP OK");
            message.setText(
                    "Este correo confirma que MAIL_USERNAME y MAIL_PASSWORD "
                            + "del .env.dev autentican correctamente contra "
                            + host
                            + ":"
                            + port
                            + ".");

            Transport.send(message);

            System.out.println("✅ ÉXITO: autenticación correcta y correo enviado a " + username);
            System.out.println(
                    "   Revisa esa bandeja de entrada (o Spam) para confirmar la llegada.");

        } catch (AuthenticationFailedException e) {
            System.out.println("❌ FALLÓ LA AUTENTICACIÓN: " + e.getMessage());
            System.out.println();
            if (e.getMessage() != null && e.getMessage().contains("5.7.9")) {
                System.out.println("   → Google exige un 'App Password', no tu contraseña normal.");
                System.out.println("   → Genera una en: https://myaccount.google.com/apppasswords");
            } else if (e.getMessage() != null && e.getMessage().contains("5.7.8")) {
                System.out.println("   → Usuario o contraseña incorrectos (BadCredentials).");
                System.out.println("   → Verifica que copiaste el App Password sin espacios.");
            } else {
                System.out.println(
                        "   → Revisa usuario/contraseña y que la verificación en 2 pasos esté activa.");
            }
        } catch (MessagingException e) {
            System.out.println("❌ ERROR DE CONEXIÓN/ENVÍO: " + e.getMessage());
            System.out.println("   → Verifica host/puerto, firewall o conexión a internet.");
        }

        System.out.println("═".repeat(60));
    }

    private static Map<String, String> loadDotEnv(String path) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx < 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                // quita comillas si las tiene
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        } catch (Exception e) {
            System.out.println(
                    "⚠️  No se pudo leer .env.dev ("
                            + e.getMessage()
                            + "). "
                            + "Asegúrate de ejecutar este test desde la raíz del proyecto.");
        }
        return map;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) return "⚠️ NO DEFINIDA";
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "*".repeat(value.length() - 2);
    }
}
