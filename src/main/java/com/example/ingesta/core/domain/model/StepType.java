package com.example.ingesta.core.domain.model;

public enum StepType {
    // Transformation types
    TRIM,
    UPPERCASE,
    CONCATENATE,
    // Persistence types
    SELECT,
    INSERT,
    TRUNCATE
}
