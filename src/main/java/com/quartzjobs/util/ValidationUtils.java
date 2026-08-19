package com.quartzjobs.util;

import java.util.regex.Pattern;

public final class ValidationUtils {

    private ValidationUtils() {}

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{9,15}$");

    /** Valida formato de email */
    public static boolean isValidEmail(String email) {
        if (email == null) return false;
        return EMAIL_PATTERN.matcher(email).matches();
    }

    /** Valida formato de teléfono */
    public static boolean isValidPhone(String phone) {
        if (phone == null) return false;
        return PHONE_PATTERN.matcher(phone).matches();
    }

    /** Valida que un string solo contenga números */
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        return str.matches("\\d+");
    }

    /** Valida DNI peruano (8 dígitos) */
    public static boolean isValidDNI(String dni) {
        return dni != null && dni.matches("\\d{8}");
    }

    /** Valida RUC peruano (11 dígitos) */
    public static boolean isValidRUC(String ruc) {
        return ruc != null && ruc.matches("\\d{11}");
    }
}
