package com.quartzjobs.util;

import java.util.Random;
import java.util.UUID;

public class RandomUtils {
    private static final Random RANDOM = new Random(System.currentTimeMillis());

    /**
     * Genera un UUID aleatorio como String, recortado o extendido a la longitud especificada.
     *
     * @param length: Longitud deseada de la cadena UUID (debe ser mayor o igual a 0).
     * @return: UUID aleatorio como String de la longitud indicada.
     * @example: RandomUtils.randomUUIDString(12) → "a1b2c3d4e5f6"
     * @example: RandomUtils.randomUUIDString(40) → "a1b2c3d4e5f6..." (40 caracteres)
     */
    public static String randomUUIDString(int length) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        if (length <= 0) return "";
        if (length <= uuid.length()) {
            return uuid.substring(0, length);
        } else {
            StringBuilder sb = new StringBuilder(uuid);
            while (sb.length() < length) {
                sb.append(UUID.randomUUID().toString().replace("-", ""));
            }
            return sb.substring(0, length);
        }
    }

    /**
     * Genera un número entero aleatorio entre 0 (inclusive) y max (exclusivo).
     *
     * @param max: Límite superior (exclusivo), debe ser mayor que 0.
     * @return: Número entero aleatorio en el rango [0, max).
     * @throws: IllegalArgumentException si max <= 0.
     * @example: RandomUtils.randomInt(100) → 0..99
     */
    public static int randomInt(int max) {
        if (max <= 0) throw new IllegalArgumentException("max debe ser mayor que 0");
        return RANDOM.nextInt(max);
    }

    /**
     * Genera un número entero aleatorio entre min (inclusive) y max (exclusivo).
     *
     * @param min: Límite inferior (inclusive).
     * @param max: Límite superior (exclusivo), debe ser mayor que min.
     * @return: Número entero aleatorio en el rango [min, max).
     * @throws: IllegalArgumentException si max <= min.
     * @example: RandomUtils.randomInt(10, 50) → 10..49
     */
    public static int randomInt(int min, int max) {
        if (max <= min) throw new IllegalArgumentException("max debe ser mayor que min");
        return RANDOM.nextInt(max - min) + min;
    }

    /**
     * Genera un número double aleatorio entre 0.0 (inclusive) y 1.0 (exclusivo).
     *
     * @return: Número double aleatorio en el rango [0.0, 1.0).
     * @example: RandomUtils.randomDouble() → 0.123456789
     */
    public static double randomDouble() {
        return RANDOM.nextDouble();
    }

    /**
     * Genera un booleano aleatorio.
     *
     * @return: Booleano aleatorio (true o false).
     * @example: RandomUtils.randomBoolean() → true
     * @example: RandomUtils.randomBoolean() → false
     */
    public static boolean randomBoolean() {
        return RANDOM.nextBoolean();
    }

    /**
     * Genera una cadena alfanumérica aleatoria de longitud dada.
     *
     * @param length: Longitud de la cadena (debe ser mayor o igual a 0).
     * @return: Cadena alfanumérica aleatoria de la longitud indicada.
     * @throws: IllegalArgumentException si length < 0.
     * @example: RandomUtils.randomAlphaNumeric(8) → "aB3dE9fG"
     */
    public static String randomAlphaNumeric(int length) {
        if (length < 0) throw new IllegalArgumentException("length debe ser mayor o igual a 0");
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
