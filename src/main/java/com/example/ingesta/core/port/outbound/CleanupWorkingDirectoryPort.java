package com.example.ingesta.core.port.outbound;

import java.nio.file.Path;

/**
 * Cleans up the temporary directory where files are processed for the next ingestion.
 */
public interface CleanupWorkingDirectoryPort {

    void delete(Path filePath);
}
