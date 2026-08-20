package com.quartzjobs.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🛠️ Utilidad para convertir objetos Java ↔ JSON
 *
 * <p>Facilita la serialización y deserialización de objetos JSON usando Jackson. Incluye métodos
 * para convertir entre String, Map, List y objetos personalizados.
 *
 * <h3>Ejemplos de uso:</h3>
 *
 * <pre>
 * // 1️⃣ Objeto → JSON String
 * User user = new User("Juan", "juan@example.com");
 * String json = JsonUtil.toJson(user);
 * // {"name":"Juan","email":"juan@example.com"}
 *
 * // 2️⃣ JSON String → Objeto
 * User user = JsonUtil.fromJson(json, User.class);
 *
 * // 3️⃣ Map → JSON String
 * Map<String, Object> map = new HashMap<>();
 * map.put("name", "Juan");
 * map.put("age", 30);
 * String json = JsonUtil.mapToJson(map);
 *
 * // 4️⃣ JSON String → Map
 * Map<String, Object> map = JsonUtil.jsonToMap(json);
 *
 * // 5️⃣ JSON String → List
 * List<User> users = JsonUtil.jsonToList(json, User.class);
 *
 * // 6️⃣ Pretty Print
 * String prettyJson = JsonUtil.toPrettyJson(user);
 * </pre>
 *
 * @author Sistema Común
 * @version 1.0
 */
public final class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);

    private static final ObjectMapper MAPPER = createObjectMapper();

    private JsonUtil() {}

    /** Configura el ObjectMapper con las opciones necesarias */
    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // ✅ Soporte para Java 8+ Date/Time API (LocalDateTime, LocalDate, etc.)
        mapper.registerModule(new JavaTimeModule());

        // ✅ No fallar si hay propiedades desconocidas al deserializar
        mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);

        // ✅ Formatear fechas como ISO-8601 en lugar de timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }

    // ================================================================
    // 📤 SERIALIZACIÓN: Objeto Java → JSON String
    // ================================================================

    /**
     * Convierte un objeto Java a JSON String.
     *
     * @param object Objeto a convertir (puede ser POJO, Map, List, etc.)
     * @return JSON String o null si hay error
     * @example User user = new User("Juan", "juan@example.com"); String json =
     *     JsonUtil.toJson(user); // {"name":"Juan","email":"juan@example.com"}
     */
    public static String toJson(Object object) {
        if (object == null) {
            log.error("⚠️ Intento de convertir objeto null a JSON");
            return null;
        }

        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("❌ Error al convertir objeto a JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convierte un objeto Java a JSON String con formato legible (pretty print).
     *
     * @param object Objeto a convertir
     * @return JSON String formateado o null si hay error
     * @example String prettyJson = JsonUtil.toPrettyJson(user); // { // "name" : "Juan", // "email"
     *     : "juan@example.com" // }
     */
    public static String toPrettyJson(Object object) {
        if (object == null) {
            log.warn("⚠️ Intento de convertir objeto null a JSON");
            return null;
        }

        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("❌ Error al convertir objeto a JSON pretty: {}", e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 📥 DESERIALIZACIÓN: JSON String → Objeto Java
    // ================================================================

    /**
     * Convierte un JSON String a un objeto Java.
     *
     * @param json JSON String
     * @param clazz Clase del objeto destino
     * @param <T> Tipo del objeto
     * @return Objeto deserializado o null si hay error
     * @example String json = "{\"name\":\"Juan\",\"email\":\"juan@example.com\"}"; User user =
     *     JsonUtil.fromJson(json, User.class);
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("⚠️ JSON vacío o null");
            return null;
        }

        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error(
                    "❌ Error al convertir JSON a objeto {}: {}",
                    clazz.getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Convierte un JSON String a un objeto usando TypeReference (para tipos genéricos).
     *
     * @param json JSON String
     * @param typeReference TypeReference del tipo destino
     * @param <T> Tipo del objeto
     * @return Objeto deserializado o null si hay error
     * @example String json = "[{\"name\":\"Juan\"},{\"name\":\"María\"}]"; List<User> users =
     *     JsonUtil.fromJson(json, new TypeReference<List<User>>() {});
     */
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("⚠️ JSON vacío o null");
            return null;
        }

        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("❌ Error al convertir JSON a TypeReference: {}", e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 🗂️ CONVERSIONES MAP ↔ JSON
    // ================================================================

    /**
     * Convierte un Map a JSON String.
     *
     * @param map Map a convertir
     * @return JSON String o null si hay error
     * @example Map<String, Object> data = new HashMap<>(); data.put("name", "Juan");
     *     data.put("age", 30); String json = JsonUtil.mapToJson(data); // {"name":"Juan","age":30}
     */
    public static String mapToJson(Map<String, Object> map) {
        return toJson(map);
    }

    /**
     * Convierte un JSON String a Map<String, Object>.
     *
     * @param json JSON String
     * @return Map con los datos o null si hay error
     * @example String json = "{\"name\":\"Juan\",\"age\":30}"; Map<String, Object> map =
     *     JsonUtil.jsonToMap(json); // {name=Juan, age=30}
     */
    public static Map<String, Object> jsonToMap(String json) {
        return fromJson(json, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * Convierte un JSON String a Map<String, String>.
     *
     * @param json JSON String
     * @return Map con los datos como String o null si hay error
     */
    public static Map<String, String> jsonToMapString(String json) {
        return fromJson(json, new TypeReference<Map<String, String>>() {});
    }

    // ================================================================
    // 📋 CONVERSIONES LIST ↔ JSON
    // ================================================================

    /**
     * Convierte una List a JSON String.
     *
     * @param list Lista a convertir
     * @return JSON String o null si hay error
     * @example List<String> nombres = Arrays.asList("Juan", "María", "Pedro"); String json =
     *     JsonUtil.listToJson(nombres); // ["Juan","María","Pedro"]
     */
    public static String listToJson(List<?> list) {
        return toJson(list);
    }

    /**
     * Convierte un JSON String a List de objetos.
     *
     * @param json JSON String (debe ser un array JSON)
     * @param clazz Clase de los elementos de la lista
     * @param <T> Tipo de los elementos
     * @return Lista de objetos o lista vacía si hay error
     * @example String json = "[{\"name\":\"Juan\"},{\"name\":\"María\"}]"; List<User> users =
     *     JsonUtil.jsonToList(json, User.class);
     */
    public static <T> List<T> jsonToList(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty()) {
            log.warn("⚠️ JSON vacío o null");
            return List.of();
        }

        try {
            return MAPPER.readValue(
                    json, MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException e) {
            log.error(
                    "❌ Error al convertir JSON a List<{}>: {}",
                    clazz.getSimpleName(),
                    e.getMessage());
            return List.of();
        }
    }

    // ================================================================
    // 🔄 CONVERSIONES OBJETO ↔ MAP
    // ================================================================

    /**
     * Convierte un objeto Java a Map<String, Object>.
     *
     * @param object Objeto a convertir
     * @return Map con los datos del objeto o mapa vacío si hay error
     * @example User user = new User("Juan", "juan@example.com"); Map<String, Object> map =
     *     JsonUtil.objectToMap(user); // {name=Juan, email=juan@example.com}
     */
    public static Map<String, Object> objectToMap(Object object) {
        if (object == null) {
            log.warn("⚠️ Intento de convertir objeto null a Map");
            return Map.of();
        }

        try {
            return MAPPER.convertValue(object, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException e) {
            log.error("❌ Error al convertir objeto a Map: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Convierte un Map a un objeto Java.
     *
     * @param map Map con los datos
     * @param clazz Clase del objeto destino
     * @param <T> Tipo del objeto
     * @return Objeto creado desde el Map o null si hay error
     * @example Map<String, Object> map = new HashMap<>(); map.put("name", "Juan"); map.put("email",
     *     "juan@example.com"); User user = JsonUtil.mapToObject(map, User.class);
     */
    public static <T> T mapToObject(Map<String, Object> map, Class<T> clazz) {
        if (map == null) {
            log.warn("⚠️ Map vacío o null");
            return null;
        }

        try {
            return MAPPER.convertValue(map, clazz);
        } catch (IllegalArgumentException e) {
            log.error("❌ Error al convertir Map a {}: {}", clazz.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 📄 LECTURA/ESCRITURA DE ARCHIVOS JSON
    // ================================================================

    /**
     * Lee un archivo JSON y lo convierte a objeto.
     *
     * @param file Archivo JSON
     * @param clazz Clase del objeto destino
     * @param <T> Tipo del objeto
     * @return Objeto leído desde el archivo o null si hay error
     * @example File configFile = new File("config.json"); Config config =
     *     JsonUtil.readFromFile(configFile, Config.class);
     */
    public static <T> T readFromFile(File file, Class<T> clazz) {
        if (file == null || !file.exists()) {
            log.error("❌ Archivo no existe: {}", file);
            return null;
        }

        try {
            return MAPPER.readValue(file, clazz);
        } catch (IOException e) {
            log.error("❌ Error al leer archivo JSON {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Escribe un objeto a un archivo JSON.
     *
     * @param object Objeto a escribir
     * @param file Archivo destino
     * @return true si se escribió correctamente, false si hubo error
     * @example User user = new User("Juan", "juan@example.com"); File outputFile = new
     *     File("user.json"); boolean success = JsonUtil.writeToFile(user, outputFile);
     */
    public static boolean writeToFile(Object object, File file) {
        if (object == null) {
            log.warn("⚠️ Intento de escribir objeto null a archivo");
            return false;
        }

        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, object);
            log.info("✅ Archivo JSON creado: {}", file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("❌ Error al escribir archivo JSON {}: {}", file.getName(), e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 🔍 VALIDACIÓN Y UTILIDADES
    // ================================================================

    /**
     * Verifica si una cadena es un JSON válido.
     *
     * @param json Cadena a validar
     * @return true si es JSON válido, false en caso contrario
     * @example boolean isValid = JsonUtil.isValidJson("{\"name\":\"Juan\"}"); // true boolean
     *     isValid = JsonUtil.isValidJson("texto plano"); // false
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return false;
        }

        try {
            MAPPER.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * Obtiene un JsonNode desde un JSON String. Útil para navegar por un JSON sin deserializarlo
     * completamente.
     *
     * @param json JSON String
     * @return JsonNode o null si hay error
     * @example String json = "{\"user\":{\"name\":\"Juan\"}}"; JsonNode node =
     *     JsonUtil.parseTree(json); String name = node.get("user").get("name").asText(); // "Juan"
     */
    public static JsonNode parseTree(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            log.error("❌ Error al parsear JSON tree: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Combina/fusiona dos Maps en uno solo.
     *
     * @param map1 Primer Map
     * @param map2 Segundo Map (sus valores sobrescriben los de map1)
     * @return Map resultante de la fusión
     * @example Map<String, Object> base = Map.of("name", "Juan", "age", 30); Map<String, Object>
     *     update = Map.of("age", 31, "city", "Lima"); Map<String, Object> merged =
     *     JsonUtil.mergeMaps(base, update); // {name=Juan, age=31, city=Lima}
     */
    public static Map<String, Object> mergeMaps(
            Map<String, Object> map1, Map<String, Object> map2) {
        Map<String, Object> result = new HashMap<>(map1);
        result.putAll(map2);
        return result;
    }

    /**
     * Obtiene un ObjectMapper personalizado si necesitas configuraciones especiales.
     *
     * @return ObjectMapper configurado
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    // ================================================================
    // 🧪 BUILDER HELPER: Crear Maps fácilmente
    // ================================================================

    /**
     * Crea un Map<String, Object> de forma fluida.
     *
     * @return MapBuilder para construir el Map
     * @example Map<String, Object> data = JsonUtil.createMap() .put("name", "Juan") .put("age", 30)
     *     .put("active", true) .build();
     */
    public static MapBuilder createMap() {
        return new MapBuilder();
    }

    /** Builder para crear Maps de forma fluida. */
    public static class MapBuilder {
        private final Map<String, Object> map = new HashMap<>();

        public MapBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }

        public String toJson() {
            return JsonUtil.toJson(map);
        }

        public String toPrettyJson() {
            return JsonUtil.toPrettyJson(map);
        }
    }

    // ================================================================
    // SHORTCUTS COMUNES
    // ================================================================

    /**
     * Crea un Map simple de clave-valor.
     *
     * @param key Clave
     * @param value Valor
     * @return Map con un solo par clave-valor
     * @example Map<String, Object> result = JsonUtil.of("status", "ok"); String json =
     *     JsonUtil.toJson(result); // {"status":"ok"}
     */
    public static Map<String, Object> of(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    /** Crea un Map con dos pares clave-valor. */
    public static Map<String, Object> of(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    /** Crea un Map con tres pares clave-valor. */
    public static Map<String, Object> of(
            String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = new HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }
}
