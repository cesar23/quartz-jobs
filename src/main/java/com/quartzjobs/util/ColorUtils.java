package com.quartzjobs.util;

/** 🎨 Utilidades para manejo de colores en la consola usando códigos ANSI. */
public class ColorUtils {

    // Colores Regulares
    public static final String COLOR_OFF = "\033[0m"; // Reset de color.
    public static final String BLACK = "\033[0;30m"; // Negro.
    public static final String RED = "\033[0;31m"; // Rojo.
    public static final String GREEN = "\033[0;32m"; // Verde.
    public static final String YELLOW = "\033[0;33m"; // Amarillo.
    public static final String BLUE = "\033[0;34m"; // Azul.
    public static final String PURPLE = "\033[0;35m"; // Púrpura.
    public static final String CYAN = "\033[0;36m"; // Cian.
    public static final String WHITE = "\033[0;37m"; // Blanco.
    public static final String GRAY = "\033[0;90m"; // Gris.
    public static final String ORANGE = "\033[0;91m"; // Naranja.
    public static final String PINK = "\033[0;95m"; // Rosa.

    // Colores en Negrita
    public static final String B_BLACK = "\033[1;30m"; // Negro (negrita).
    public static final String B_RED = "\033[1;31m"; // Rojo (negrita).
    public static final String B_GREEN = "\033[1;32m"; // Verde (negrita).
    public static final String B_YELLOW = "\033[1;33m"; // Amarillo (negrita).
    public static final String B_BLUE = "\033[1;34m"; // Azul (negrita).
    public static final String B_PURPLE = "\033[1;35m"; // Púrpura (negrita).
    public static final String B_CYAN = "\033[1;36m"; // Cian (negrita).
    public static final String B_WHITE = "\033[1;37m"; // Blanco (negrita).
    public static final String B_GRAY = "\033[1;90m"; // Gris (negrita).
    public static final String B_ORANGE = "\033[1;91m"; // Naranja (negrita).
    public static final String B_PINK = "\033[1;95m"; // Rosa (negrita).

    /**
     * Ejemplo de uso: imprime un texto con un color específico.
     *
     * @param color Código de color ANSI.
     * @param text Texto a imprimir.
     */
    public static void printWithColor(String color, String text) {
        System.out.println(color + text + COLOR_OFF);
    }
}
