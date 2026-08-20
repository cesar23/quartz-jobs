package com.quartzjobs.util;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilidad para operaciones comunes con cadenas de texto.
 *
 * <p>Ejemplo de uso:
 *
 * <pre>
 *     String slug = StringUtil.stringSlugify("¡Hola mundo! Prueba de Slugify.", "title");
 *     // Resultado: "Hola-Mundo-Prueba-De-Slugify"
 * </pre>
 */
public class StringUtil {

    private static final Logger log = LoggerFactory.getLogger(StringUtil.class);

    private static final Pattern NON_DESIRED_CHARS = Pattern.compile("[^\\w\\s\\-]");
    private static final Pattern SPACES_AND_UNDERSCORES =
            Pattern.compile("[\\s_\\-]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern LEADING_TRAILING_DASHES = Pattern.compile("^-+|-+$");

    /**
     * Convierte una cadena en un slug, permitiendo controlar el formato de mayúsculas/minúsculas.
     * Elimina caracteres no deseados y reemplaza espacios/guiones bajos por guiones.
     *
     * @param s Cadena original (no nula) que se desea transformar en slug.
     * @param caseFormat Formato de capitalización: "lower" (solo minúsculas), "upper" (solo
     *     mayúsculas), "capitalize" (primera letra en mayúscula), "title" (primera letra de cada
     *     palabra en mayúscula), "none" (conserva el formato original).
     * @return Cadena en formato slug, lista para URLs o identificadores.
     * @throws IllegalArgumentException si caseFormat es inválido o la cadena es null.
     * @example StringUtil.stringSlugify("¡Hola mundo! Prueba de Slugify.", "title") →
     *     "Hola-Mundo-Prueba-De-Slugify"
     * @example StringUtil.stringSlugify("¡Hola mundo! Prueba de Slugify.", "lower") →
     *     "hola-mundo-prueba-de-slugify"
     * @example StringUtil.stringSlugify("¡Hola mundo! Prueba de Slugify.", "none") →
     *     "Hola-mundo-Prueba-de-Slugify"
     */
    public static String stringSlugify(String s, String caseFormat) {
        if (s == null) throw new IllegalArgumentException("La cadena no puede ser nula");
        String effectiveCaseFormat = (caseFormat == null) ? "none" : caseFormat;
        String result = s.trim();

        // Selecciona el formato de capitalización
        switch (effectiveCaseFormat) {
            case "lower":
                result = result.toLowerCase(Locale.ROOT);
                break;
            case "upper":
                result = result.toUpperCase(Locale.ROOT);
                break;
            case "capitalize":
                result = capitalizeFirst(result);
                break;
            case "title":
                result = toTitleCase(result);
                break;
            case "none":
                // No cambiar
                break;
            default:
                throw new IllegalArgumentException(
                        "Parámetro 'caseFormat' inválido. Usa: 'lower', 'upper', 'capitalize', 'title' o 'none'.");
        }

        // Eliminar caracteres no deseados, excepto letras/números/guiones/espacios
        result = NON_DESIRED_CHARS.matcher(result).replaceAll("");
        // Reemplazar espacios y guiones bajos por guiones
        result = SPACES_AND_UNDERSCORES.matcher(result).replaceAll("-");
        // Eliminar guiones al inicio o fin
        result = LEADING_TRAILING_DASHES.matcher(result).replaceAll("");

        return result;
    }

    /**
     * Capitaliza solo la primera letra de la cadena.
     *
     * @param s Cadena de entrada.
     * @return Cadena con la primera letra en mayúscula y el resto en minúscula.
     * @example StringUtil.capitalizeFirst("hOLA MUNDO") → "Hola mundo"
     */
    public static String capitalizeFirst(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Capitaliza la primera letra de cada palabra.
     *
     * @param s Cadena de entrada.
     * @return Cadena con la primera letra de cada palabra en mayúscula.
     * @example StringUtil.toTitleCase("hola mundo prueba") → "Hola Mundo Prueba"
     */
    public static String toTitleCase(String s) {
        if (s.isEmpty()) return s;
        StringBuilder result = new StringBuilder(s.length());
        BreakIterator wordIterator = BreakIterator.getWordInstance(Locale.ROOT);
        wordIterator.setText(s);
        int start = wordIterator.first();
        for (int end = wordIterator.next();
                end != BreakIterator.DONE;
                start = end, end = wordIterator.next()) {
            String word = s.substring(start, end);
            if (Character.isLetterOrDigit(word.codePointAt(0))) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            } else {
                result.append(word);
            }
        }
        return result.toString();
    }

    /**
     * Invierte la cadena.
     *
     * @param s Cadena a invertir. Si es null, retorna null.
     * @return Cadena invertida, o null si la entrada es null.
     * @example StringUtil.reverse("Hola") → "aloH"
     */
    public static String reverse(String s) {
        if (s == null) return null;
        return new StringBuilder(s).reverse().toString();
    }

    /**
     * Elimina tildes y acentos de la cadena.
     *
     * @param s Cadena de entrada. Si es null, retorna null.
     * @return Cadena sin acentos ni tildes.
     * @example StringUtil.removeAccents("canción") → "cancion"
     */
    public static String removeAccents(String s) {
        if (s == null) return null;
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /**
     * Devuelve solo los dígitos de la cadena.
     *
     * @param s Cadena de entrada. Si es null, retorna null.
     * @return Cadena solo con los dígitos encontrados.
     * @example StringUtil.onlyDigits("Tel: +51 999-888-777") → "51999888777"
     */
    public static String onlyDigits(String s) {
        if (s == null) return null;
        return s.replaceAll("\\D", "");
    }

    /**
     * Recorta la cadena a un máximo de caracteres, agregando "..." si es necesario.
     *
     * @param s Cadena de entrada. Si es null, retorna null.
     * @param maxLength Longitud máxima permitida (>=0).
     * @return Cadena recortada, con "..." si fue necesario.
     * @throws IllegalArgumentException si maxLength es negativo.
     * @example StringUtil.truncate("Hola mundo", 8) → "Hola ..."
     */
    public static String truncate(String s, int maxLength) {
        if (s == null) return null;
        if (maxLength < 0) throw new IllegalArgumentException("maxLength debe ser >= 0");
        if (s.length() <= maxLength) return s;
        if (maxLength <= 3) return s.substring(0, maxLength);
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Cuenta cuántas veces aparece un substring en la cadena.
     *
     * @param s Cadena principal. Si es null, retorna 0.
     * @param sub Subcadena a buscar. Si es null o vacía, retorna 0.
     * @return Número de ocurrencias de sub en s.
     * @example StringUtil.countOccurrences("banana", "an") → 2
     */
    public static int countOccurrences(String s, String sub) {
        if (s == null || sub == null || sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * Rellena la cadena a la izquierda hasta el largo deseado con el caracter dado.
     *
     * @param s Cadena de entrada. Si es null, se considera "".
     * @param length Longitud total deseada.
     * @param padChar Caracter de relleno.
     * @return Cadena rellenada a la izquierda.
     * @example StringUtil.padLeft("42", 5, '0') → "00042"
     */
    public static String padLeft(String s, int length, char padChar) {
        String value = (s == null) ? "" : s;
        if (value.length() >= length) return value;
        StringBuilder sb = new StringBuilder(length);
        for (int i = value.length(); i < length; i++) sb.append(padChar);
        sb.append(value);
        return sb.toString();
    }

    /**
     * Rellena la cadena a la derecha hasta el largo deseado con el caracter dado.
     *
     * @param s Cadena de entrada. Si es null, se considera "".
     * @param length Longitud total deseada.
     * @param padChar Caracter de relleno.
     * @return Cadena rellenada a la derecha.
     * @example StringUtil.padRight("42", 5, '0') → "42000"
     */
    public static String padRight(String s, int length, char padChar) {
        String value = (s == null) ? "" : s;
        if (value.length() >= length) return value;
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = value.length(); i < length; i++) sb.append(padChar);
        return sb.toString();
    }

    /**
     * Elimina caracteres que no sean ASCII.
     *
     * @param s Cadena de entrada. Si es null, retorna null.
     * @return Cadena solo con caracteres ASCII.
     * @example StringUtil.removeNonAscii("Español: año 2024 ✓") → "Espanol: ano 2024 "
     */
    public static String removeNonAscii(String s) {
        if (s == null) return null;
        return s.replaceAll("[^\\x00-\\x7F]", "");
    }

    /**
     * Obtiene la extensión del archivo a partir de una URL o ruta.
     *
     * <p>Esta función extrae la extensión (sin el punto) del nombre de archivo presente en una URL
     * o ruta local. Si la URL contiene parámetros de consulta, estos son ignorados. Si no hay
     * extensión, retorna una cadena vacía.
     *
     * @param url URL o ruta del archivo. Puede ser una URL completa (http, https) o una ruta local.
     *     Si es null, retorna "".
     * @return La extensión del archivo (sin el punto), o cadena vacía si no hay extensión.
     * @example
     *     StringUtil.getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default.jpg") →
     *     "jpg"
     * @example
     *     StringUtil.getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default.yaml") →
     *     "yaml"
     * @example StringUtil.getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default") →
     *     ""
     * @example StringUtil.getFileExtensionFromUrl(null) → ""
     */
    public static String getFileExtensionFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "";

        // Elimina parámetros como ?version=123 o #ancla
        String limpia = url.split("\\?")[0].split("#")[0];
        // Extrae lo que está después del último punto
        int ultimaPunto = limpia.lastIndexOf('.');

        if (ultimaPunto != -1 && ultimaPunto < limpia.length() - 1) {
            return limpia.substring(ultimaPunto + 1).toLowerCase(); // retorna en minúscula
        }

        return "";
    }

    // Ejemplo de uso desde main
    public static void main(String[] args) {
        String texto = "¡Hola mundo! Prueba de Slugify.";
        String slugNone = stringSlugify(texto, "none");
        String slugLower = stringSlugify(texto, "lower");
        String slugUpper = stringSlugify(texto, "upper");
        String slugCapitalize = stringSlugify(texto, "capitalize");
        String slugTitle = stringSlugify(texto, "title");
        String extJpg =
                getFileExtensionFromUrl("https://sercoplus.com/img/m/771-small_default.jpg");
        String extJs = getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default.js");
        String extYaml =
                getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default.yaml");
        String extNone = getFileExtensionFromUrl("https://sercoplus.com/img/m/14-small_default");

        log.info("{}", slugNone); // Hola-mundo-Prueba-de-Slugify
        log.info("{}", slugLower); // hola-mundo-prueba-de-slugify
        log.info("{}", slugUpper); // HOLA-MUNDO-PRUEBA-DE-SLUGIFY
        log.info("{}", slugCapitalize); // Hola-mundo-prueba-de-slugify
        log.info("{}", slugTitle); // Hola-Mundo-Prueba-De-Slugify
        log.info("{}", extJpg); // jpg
        log.info("{}", extJs); // js
        log.info("{}", extYaml); // yaml
        log.info("{}", extNone); // ""
    }
}
