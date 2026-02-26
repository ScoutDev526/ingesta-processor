package es.ing.icenterprise.arthur.core.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Normalizes column names to UPPER_SNAKE_CASE for matching
 * between Excel headers and database column names.
 *
 * Examples:
 *   "Nombre Producto"    → "NOMBRE_PRODUCTO"
 *   "código región"      → "CODIGO_REGION"
 *   "First Name"         → "FIRST_NAME"
 *   "camelCaseField"     → "CAMEL_CASE_FIELD"
 *   "  spaces  around  " → "SPACES_AROUND"
 *   "ALREADY_UPPER"      → "ALREADY_UPPER"
 *   "with-dashes"        → "WITH_DASHES"
 *   "with.dots"          → "WITH_DOTS"
 */
public final class ColumnNormalizer {

    private static final Pattern ACCENT_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<=[a-z])(?=[A-Z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private ColumnNormalizer() {
    }

    /**
     * Normalizes a column name to UPPER_SNAKE_CASE.
     *
     * @param name raw column name (from Excel header or any source)
     * @return normalized name, or null if input is null/blank
     */
    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String result = name.strip();

        // 1. Remove accents: á → a, ñ → n, ü → u
        result = Normalizer.normalize(result, Normalizer.Form.NFD);
        result = ACCENT_PATTERN.matcher(result).replaceAll("");

        // 2. Split camelCase: "camelCase" → "camel Case"
        result = CAMEL_SPLIT.matcher(result).replaceAll(" ");

        // 3. Replace non-alphanumeric chars with underscore
        result = NON_ALNUM.matcher(result).replaceAll("_");

        // 4. Collapse multiple underscores and trim
        result = MULTI_UNDERSCORE.matcher(result).replaceAll("_");
        result = result.replaceAll("^_|_$", "");

        // 5. Uppercase
        return result.toUpperCase();
    }
}
