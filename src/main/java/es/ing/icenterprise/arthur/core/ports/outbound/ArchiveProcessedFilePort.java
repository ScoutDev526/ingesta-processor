package es.ing.icenterprise.arthur.core.ports.outbound;

import java.nio.file.Path;

/**
 * Archives a processed data file (e.g. moves the XLSX to a history folder with
 * a stable, date-agnostic name). Called after processing completes, as an
 * alternative to {@link CleanupWorkingDirectoryPort#delete(Path)} when the file
 * should be kept instead of deleted.
 */
public interface ArchiveProcessedFilePort {

    void archive(Path filePath);
}
