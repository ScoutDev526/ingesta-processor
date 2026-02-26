package es.ing.icenterprise.arthur.core.domain.model;

import java.util.List;

/**
 * Holds the result of reading an entire data file:
 * the headers and the list of Actions (rows).
 */
public record FullDumpResult(
    List<String> headers,
    List<Action> data
) {}
