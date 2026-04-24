package es.ing.icenterprise.arthur.adapters.outbound.archive;

import es.ing.icenterprise.arthur.core.ports.outbound.ArchiveProcessedFilePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Moves a processed file into the configured cached directory, stripping the
 * {@code YYYY-MM-DD-} date prefix (and optional {@code "Full Dump "} marker)
 * from the filename so successive runs overwrite the previous snapshot instead
 * of piling up dated copies.
 */
@Component
public class ArchiveProcessedFileAdapter implements ArchiveProcessedFilePort {

    private static final Logger log = LoggerFactory.getLogger(ArchiveProcessedFileAdapter.class);

    // Matches optional "YYYY-MM-DD-" followed by optional "Full Dump " at the start of the filename.
    private static final Pattern DATE_AND_DUMP_PREFIX =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-(?:Full Dump\\s+)?");

    @Value("${ingesta.cached-directory:/tmp/ingesta/cached}")
    private String cachedDirectory;

    @Override
    public void archive(Path filePath) {
        try {
            if (!Files.exists(filePath)) {
                return;
            }

            Path cachedDir = Path.of(cachedDirectory);
            Files.createDirectories(cachedDir);

            String originalName = filePath.getFileName().toString();
            String cleanedName = DATE_AND_DUMP_PREFIX.matcher(originalName).replaceFirst("");
            Path targetPath = cachedDir.resolve(cleanedName);

            Files.move(filePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Archived processed file to: {}", targetPath);
        } catch (IOException e) {
            log.warn("Failed to archive file: {}. Error: {}", filePath, e.getMessage());
        }
    }
}
