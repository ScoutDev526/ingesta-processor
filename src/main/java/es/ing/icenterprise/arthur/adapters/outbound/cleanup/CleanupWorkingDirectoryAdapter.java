package es.ing.icenterprise.arthur.adapters.outbound.cleanup;

import es.ing.icenterprise.arthur.core.ports.outbound.CleanupWorkingDirectoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CleanupWorkingDirectoryAdapter implements CleanupWorkingDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(CleanupWorkingDirectoryAdapter.class);

    @Override
    public void delete(Path filePath) {
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Cleaned up file: {}", filePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete file: {}. Error: {}", filePath, e.getMessage());
        }
    }
}
