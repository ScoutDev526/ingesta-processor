package com.example.ingesta.core.domain.model;

public record FileMetadata(
    String filePath,
    long fileSize,
    long records
) {}
