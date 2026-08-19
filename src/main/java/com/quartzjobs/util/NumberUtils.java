package com.quartzjobs.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public final class NumberUtils {

    private NumberUtils() {}

    /** Formatea un número con 2 decimales */
    public static String formatDecimal(double value) {
        DecimalFormat df = new DecimalFormat("#.##");
        return df.format(value);
    }

    /** Redondea un BigDecimal a 2 decimales */
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Verifica si un número está entre un rango */
    public static boolean isBetween(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /** Convierte bytes a formato legible (KB, MB, GB) */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        return String.format("%.2f %s", bytes / Math.pow(1024, exp), units[exp]);
    }

    /**
     * Formatea un número con un número específico de decimales
     *
     * @param value El número a formatear
     * @param decimals El número de decimales deseados
     * @return El número formateado como String
     */
    public static String formatNumber(double value, int decimals) {
        if (decimals < 0) {
            throw new IllegalArgumentException("El número de decimales no puede ser negativo");
        }
        String pattern = "#." + "0".repeat(decimals);
        DecimalFormat df = new DecimalFormat(pattern);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(value);
    }

    /**
     * Formatea un número (pasado como String) con un número específico de decimales
     *
     * @param value El número como String a formatear
     * @param decimals El número de decimales deseados
     * @return El número formateado como String
     */
    public static String formatNumber(String value, int decimals) {
        try {
            double num = Double.parseDouble(value);
            return formatNumber(num, decimals);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El string no es un número válido: " + value, e);
        }
    }

    /** Método main para probar todos los métodos de la clase */
    public static void main(String[] args) {
        System.out.println("=== Pruebas de NumberUtils ===");

        // Prueba de formatDecimal
        System.out.println("\n1. Prueba de formatDecimal:");
        double value1 = 123.4;
        String formatted = formatDecimal(value1);
        System.out.println("formatDecimal(" + value1 + ") = " + formatted);

        double value2 = 0.0;
        formatted = formatDecimal(value2);
        System.out.println("formatDecimal(" + value2 + ") = " + formatted);

        // Prueba de round
        System.out.println("\n2. Prueba de round:");
        BigDecimal bd1 = BigDecimal.valueOf(123.456789);
        BigDecimal rounded1 = round(bd1);
        System.out.println("round(" + bd1 + ") = " + rounded1);

        BigDecimal bd2 = BigDecimal.valueOf(123.444);
        BigDecimal rounded2 = round(bd2);
        System.out.println("round(" + bd2 + ") = " + rounded2);

        // Prueba de isBetween
        System.out.println("\n3. Prueba de isBetween:");
        boolean result1 = isBetween(5, 1, 10);
        System.out.println("isBetween(5, 1, 10) = " + result1);

        boolean result2 = isBetween(15, 1, 10);
        System.out.println("isBetween(15, 1, 10) = " + result2);

        boolean result3 = isBetween(1, 1, 10);
        System.out.println("isBetween(1, 1, 10) = " + result3);

        // Prueba de formatBytes
        System.out.println("\n4. Prueba de formatBytes:");
        long bytes1 = 512;
        String formattedBytes1 = formatBytes(bytes1);
        System.out.println("formatBytes(" + bytes1 + ") = " + formattedBytes1);

        long bytes2 = 1024;
        String formattedBytes2 = formatBytes(bytes2);
        System.out.println("formatBytes(" + bytes2 + ") = " + formattedBytes2);

        long bytes3 = 1048576; // 1 MB
        String formattedBytes3 = formatBytes(bytes3);
        System.out.println("formatBytes(" + bytes3 + ") = " + formattedBytes3);

        long bytes4 = 1073741824L; // 1 GB
        String formattedBytes4 = formatBytes(bytes4);
        System.out.println("formatBytes(" + bytes4 + ") = " + formattedBytes4);

        // Prueba de formatNumber
        System.out.println("\n5. Prueba de formatNumber:");
        System.out.println("Ejemplo esperado: formatNumber(12.0, 2) -> '12.00'");
        String formattedNum1 = formatNumber(12.0, 2);
        System.out.println("Resultado: formatNumber(12.0, 2) = '" + formattedNum1 + "'");

        System.out.println("Ejemplo esperado: formatNumber(9.0, 2) -> '9.00'");
        String formattedNum2 = formatNumber(9.0, 2);
        System.out.println("Resultado: formatNumber(9.0, 2) = '" + formattedNum2 + "'");

        String formattedNum3 = formatNumber(9.123, 2);
        System.out.println("formatNumber(9.123, 2) = " + formattedNum3);

        String formattedNum4 = formatNumber(123.456789, 3);
        System.out.println("formatNumber(123.456789, 3) = " + formattedNum4);

        String formattedNum5 = formatNumber(5, 0);
        System.out.println("formatNumber(5, 0) = " + formattedNum5);

        // Pruebas adicionales para formatNumber con String
        System.out.println("\nPruebas de formatNumber con String:");
        System.out.println("Ejemplo esperado: formatNumber(\"12.0\", 2) -> '12.00'");
        String formattedStr1 = formatNumber("12.0", 2);
        System.out.println("Resultado: formatNumber(\"12.0\", 2) = '" + formattedStr1 + "'");

        System.out.println("Ejemplo esperado: formatNumber(\"9.0\", 2) -> '9.00'");
        String formattedStr2 = formatNumber("9.0", 2);
        System.out.println("Resultado: formatNumber(\"9.0\", 2) = '" + formattedStr2 + "'");

        String formattedStr3 = formatNumber("9.123", 2);
        System.out.println("formatNumber(\"9.123\", 2) = " + formattedStr3);

        String formattedStr4 = formatNumber("123.456789", 3);
        System.out.println("formatNumber(\"123.456789\", 3) = " + formattedStr4);

        System.out.println("\n=== Fin de pruebas ===");
    }
}
