package es.ing.icenterprise.arthur.core.domain.model;

public record FileMetadata(
    String filePath,
    long fileSize,
    long records
) {}
