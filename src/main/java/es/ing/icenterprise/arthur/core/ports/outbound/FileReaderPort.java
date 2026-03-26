package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import es.ing.icenterprise.arthur.core.domain.enums.FileType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Opens a local file and streams its data.
 */
public interface FileReaderPort {

    Stream<Map<String, Object>> read(Path filePath);

    /** Read with extra reader params (e.g. {@code sheetIndex} for Excel). */
    default Stream<Map<String, Object>> read(Path filePath, Map<String, Object> params) {
        return read(filePath);
    }

    /** Returns the names of all sheets in the file (Excel-specific; returns single-element list for other formats). */
    default List<String> getSheetNames(Path filePath) {
        return List.of("Sheet1");
    }

    FileMetadata readFileMetadata(Path filePath);

    boolean supports(FileType type);
}
