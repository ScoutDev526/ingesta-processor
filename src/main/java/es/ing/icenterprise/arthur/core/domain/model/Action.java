package es.ing.icenterprise.arthur.core.domain.model;

import java.util.Map;

/**
 * Represents a single row of data read from an Excel/XML file,
 * ready to be persisted.
 */
public record Action(
    Map<String, Object> data
) {
    public Object get(String key) {
        return data.get(key);
    }

    public boolean hasKey(String key) {
        return data.containsKey(key);
    }
}
