package com.quartzjobs.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * 📅 Utilidades para manejo de fechas, horas y timestamps en Java usando la API moderna
 * (java.time).
 */
public class DateUtils {

    /**
     * Obtiene la fecha y hora actual del sistema.
     *
     * @return LocalDateTime actual
     * @example DateUtils.now() → 2025-05-04T22:00:00
     */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    /**
     * Obtiene la fecha y hora actual en formato UTC.
     *
     * @return LocalDateTime en zona UTC
     * @example DateUtils.nowUtc() → 2025-05-05T03:00:00
     */
    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Obtiene el timestamp actual (en milisegundos desde Epoch).
     *
     * @return long con el timestamp
     * @example DateUtils.timestampNow() → 1746400800000L
     */
    public static long timestampNow() {
        return Instant.now().toEpochMilli();
    }

    /**
     * Convierte un timestamp en milisegundos a LocalDateTime.
     *
     * @param millis timestamp en milisegundos
     * @return LocalDateTime correspondiente
     * @example DateUtils.fromTimestamp(1746400800000L) → 2025-05-04T22:00:00
     */
    public static LocalDateTime fromTimestamp(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * Formatea un LocalDateTime a string con un patrón específico.
     *
     * @param dt LocalDateTime a formatear
     * @param pattern patrón (ej: "yyyy-MM-dd HH:mm:ss")
     * @return String formateado
     * @example DateUtils.format(DateUtils.now(), "yyyy-MM-dd") → "2025-05-04"
     */
    public static String format(LocalDateTime dt, String pattern) {
        return dt.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Convierte un String de fecha a LocalDateTime según un patrón.
     *
     * @param str fecha como String
     * @param pattern patrón (ej: "yyyy-MM-dd HH:mm:ss")
     * @return LocalDateTime parseado
     * @example DateUtils.parse("2025-05-04 22:00:00", "yyyy-MM-dd HH:mm:ss")
     */
    public static LocalDateTime parse(String str, String pattern) {
        return LocalDateTime.parse(str, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Convierte un String de fecha a LocalDate según un patrón.
     *
     * @param str fecha como String
     * @param pattern patrón (ej: "yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss")
     * @return LocalDate parseado
     * @example DateUtils.parseToLocalDate("2025-05-04", "yyyy-MM-dd")
     * @example DateUtils.parseToLocalDate("2025-05-04", "yyyy-MM-dd HH:mm:ss")
     */
    public static LocalDate parseToLocalDate(String str, String pattern) {
        return LocalDate.parse(str, DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * Agrega días a una fecha dada.
     *
     * @param dt fecha base
     * @param days cantidad de días a agregar
     * @return nueva fecha
     * @example DateUtils.addDays(DateUtils.now(), 5)
     */
    public static LocalDateTime addDays(LocalDateTime dt, int days) {
        return dt.plusDays(days);
    }

    /**
     * Resta días a una fecha dada.
     *
     * @param dt fecha base
     * @param days cantidad de días a restar
     * @return nueva fecha
     * @example DateUtils.subtractDays(DateUtils.now(), 5)
     */
    public static LocalDateTime subtractDays(LocalDateTime dt, int days) {
        return dt.minusDays(days);
    }

    /**
     * Devuelve el inicio del día (00:00:00.000) de una fecha.
     *
     * @param dt fecha base
     * @return inicio del día
     * @example DateUtils.startOfDay(LocalDateTime.now())
     */
    public static LocalDateTime startOfDay(LocalDateTime dt) {
        return dt.toLocalDate().atStartOfDay();
    }

    /**
     * Devuelve el fin del día (23:59:59.999999999) de una fecha.
     *
     * @param dt fecha base
     * @return fin del día
     * @example DateUtils.endOfDay(LocalDateTime.now())
     */
    public static LocalDateTime endOfDay(LocalDateTime dt) {
        return dt.withHour(23).withMinute(59).withSecond(59).withNano(999_999_999);
    }

    /**
     * Devuelve el inicio del mes de una fecha.
     *
     * @param dt fecha base
     * @return primer día del mes
     * @example DateUtils.startOfMonth(LocalDateTime.now())
     */
    public static LocalDateTime startOfMonth(LocalDateTime dt) {
        return dt.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * Devuelve el último momento del mes (último día a las 23:59:59.999).
     *
     * @param dt fecha base
     * @return fin del mes
     * @example DateUtils.endOfMonth(LocalDateTime.now())
     */
    public static LocalDateTime endOfMonth(LocalDateTime dt) {
        return dt.withDayOfMonth(dt.toLocalDate().lengthOfMonth())
                .withHour(23)
                .withMinute(59)
                .withSecond(59)
                .withNano(999_999_999);
    }

    /**
     * Calcula la diferencia de días entre dos fechas.
     *
     * @param start fecha inicial
     * @param end fecha final
     * @return número de días entre ambas
     * @example DateUtils.daysBetween(now.minusDays(5), now) → 5
     */
    public static long daysBetween(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toDays();
    }

    /**
     * Convierte una fecha a formato "viernes 5 de enero del 2025".
     *
     * @param date Fecha a convertir
     * @return String en formato largo en español
     * @example DateUtils.toLongSpanishDate(LocalDate.of(2025, 1, 5)) → "domingo 5 de enero del
     *     2025"
     */
    public static String toLongSpanishDate(LocalDate date) {
        Locale locale = new Locale("es", "ES");

        String diaSemana = date.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
        int dia = date.getDayOfMonth();
        String mes = date.getMonth().getDisplayName(TextStyle.FULL, locale);
        int anio = date.getYear();

        return String.format("%s %d de %s del %d", diaSemana, dia, mes, anio);
    }

    /** Función main para probar todas las funciones de DateUtils. */
    public static void main(String[] args) {
        System.out.println("🧪 PRUEBAS DE DateUtils");
        System.out.println("========================\n");

        // 1. Fecha y hora actual
        System.out.println("1️⃣  now() - Fecha y hora actual del sistema:");
        LocalDateTime ahora = now();
        System.out.println("   Resultado: " + ahora);
        System.out.println();

        // 2. Fecha y hora actual en UTC
        System.out.println("2️⃣  nowUtc() - Fecha y hora actual en UTC:");
        LocalDateTime ahoraUtc = nowUtc();
        System.out.println("   Resultado: " + ahoraUtc);
        System.out.println();

        // 3. Timestamp actual
        System.out.println("3️⃣  timestampNow() - Timestamp actual en milisegundos:");
        long timestamp = timestampNow();
        System.out.println("   Resultado: " + timestamp);
        System.out.println();

        // 4. Convertir timestamp a LocalDateTime
        System.out.println("4️⃣  fromTimestamp(long) - Convertir timestamp a LocalDateTime:");
        LocalDateTime desdeTimestamp = fromTimestamp(timestamp);
        System.out.println("   Resultado: " + desdeTimestamp);
        System.out.println();

        // 5. Formatear fecha
        System.out.println("5️⃣  format(LocalDateTime, String) - Formatear fecha:");
        String fechaFormato1 = format(ahora, "yyyy-MM-dd");
        String fechaFormato2 = format(ahora, "dd/MM/yyyy HH:mm:ss");
        System.out.println("   Formato 'yyyy-MM-dd': " + fechaFormato1);
        System.out.println("   Formato 'dd/MM/yyyy HH:mm:ss': " + fechaFormato2);
        System.out.println();

        // 6. Parsear fecha desde String
        System.out.println("6️⃣  parse(String, String) - Parsear fecha desde String:");
        LocalDateTime fechaParsed = parse("2025-06-15 14:30:00", "yyyy-MM-dd HH:mm:ss");
        System.out.println("   Input: '2025-06-15 14:30:00'");
        System.out.println("   Resultado: " + fechaParsed);
        System.out.println();

        // 7. Agregar días
        System.out.println("7️⃣  addDays(LocalDateTime, int) - Agregar 10 días:");
        LocalDateTime masDias = addDays(ahora, 10);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   + 10 días: " + masDias);
        System.out.println();

        // 8. Restar días
        System.out.println("8️⃣  subtractDays(LocalDateTime, int) - Restar 7 días:");
        LocalDateTime menosDias = subtractDays(ahora, 7);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   - 7 días: " + menosDias);
        System.out.println();

        // 9. Inicio del día
        System.out.println("9️⃣  startOfDay(LocalDateTime) - Inicio del día:");
        LocalDateTime inicioDia = startOfDay(ahora);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   Inicio del día: " + inicioDia);
        System.out.println();

        // 10. Fin del día
        System.out.println("🔟 endOfDay(LocalDateTime) - Fin del día:");
        LocalDateTime finDia = endOfDay(ahora);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   Fin del día: " + finDia);
        System.out.println();

        // 11. Inicio del mes
        System.out.println("1️⃣1️⃣  startOfMonth(LocalDateTime) - Inicio del mes:");
        LocalDateTime inicioMes = startOfMonth(ahora);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   Inicio del mes: " + inicioMes);
        System.out.println();

        // 12. Fin del mes
        System.out.println("1️⃣2️⃣  endOfMonth(LocalDateTime) - Fin del mes:");
        LocalDateTime finMes = endOfMonth(ahora);
        System.out.println("   Fecha actual: " + ahora);
        System.out.println("   Fin del mes: " + finMes);
        System.out.println();

        // 13. Diferencia en días
        System.out.println(
                "1️⃣3️⃣  daysBetween(LocalDateTime, LocalDateTime) - Días entre fechas:");
        long diasEntre = daysBetween(menosDias, masDias);
        System.out.println("   Fecha 1: " + menosDias);
        System.out.println("   Fecha 2: " + masDias);
        System.out.println("   Diferencia: " + diasEntre + " días");
        System.out.println();

        // 14. Fecha larga en español
        System.out.println("1️⃣4️⃣  toLongSpanishDate(LocalDate) - Fecha en formato español:");
        LocalDate hoy = LocalDate.now();
        String fechaEspanol = toLongSpanishDate(hoy);
        System.out.println("   Fecha: " + hoy);
        System.out.println("   Resultado: " + fechaEspanol);
        System.out.println();

        // 15. Parsear fecha a LocalDate desde String
        System.out.println(
                "1️⃣5️⃣  parseToLocalDate(String, String) - Parsear fecha a LocalDate desde String:");
        LocalDate fechaParsedLocalDate = parseToLocalDate("2025-06-15", "yyyy-MM-dd");
        System.out.println("   Input: '2025-06-15'");
        System.out.println("   Resultado: " + fechaParsedLocalDate);
        System.out.println();

        System.out.println("========================");
        System.out.println("✅ Todas las pruebas completadas exitosamente!");
    }
}
