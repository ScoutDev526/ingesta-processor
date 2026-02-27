package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import es.ing.icenterprise.arthur.core.domain.enums.FileType;

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
