package es.ing.icenterprise.arthur.core.domain.enums;

public enum StepType {
    // Transformation types
    TRIM,
    UPPERCASE,
    CONCATENATE,
    DEDUPLICATE,
    // Relationship types
    LINK_PARENT,
    // Persistence types
    SELECT,
    INSERT,
    TRUNCATE,
    VALIDATE_REFERENCE
}
