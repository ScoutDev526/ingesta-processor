package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.model.FileMetadata;
import com.example.ingesta.core.domain.model.FileType;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Opens a local file and streams its data.
 */
public interface FileReaderPort {

    Stream<Map<String, Object>> read(Path filePath);

    FileMetadata readFileMetadata(Path filePath);

    boolean supports(FileType type);
}
