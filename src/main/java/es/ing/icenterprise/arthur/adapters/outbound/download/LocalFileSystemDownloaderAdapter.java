package es.ing.icenterprise.arthur.adapters.outbound.download;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;
import es.ing.icenterprise.arthur.core.ports.outbound.FileDownloaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;

@Component
public class LocalFileSystemDownloaderAdapter implements FileDownloaderPort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemDownloaderAdapter.class);

    @Value("${ingesta.working-directory:/tmp/ingesta}")
    private String workingDirectory;

    @Override
    public Path download(FileSourceDefinition source) {
        String resourcePath = source.location().path();
        log.info("Resolving file from: {}", resourcePath);

        try {
            Path workDir = Path.of(workingDirectory);
            Files.createDirectories(workDir);

            Path sourcePath = resolveSource(resourcePath);
            Path targetPath = workDir.resolve(sourcePath.getFileName());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("File copied to working directory: {}", targetPath);
            return targetPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file to working directory", e);
        }
    }

    private Path resolveSource(String resourcePath) {
        // 1. Try as absolute/relative filesystem path
        Path fsPath = Path.of(resourcePath);
        if (Files.exists(fsPath)) {
            log.info("Resolved as filesystem path: {}", fsPath);
            return fsPath;
        }

        // 2. Try from classpath (resources folder, useful in debug/IDE)
        URL classpathUrl = getClass().getClassLoader().getResource(resourcePath);
        if (classpathUrl != null) {
            try {
                Path classpathPath = Path.of(classpathUrl.toURI());
                if (Files.exists(classpathPath)) {
                    log.info("Resolved from classpath: {}", classpathPath);
                    return classpathPath;
                }
            } catch (URISyntaxException e) {
                log.warn("Failed to convert classpath URL to path: {}", classpathUrl, e);
            }
        }

        throw new RuntimeException("File not found in filesystem nor classpath: " + resourcePath);
    }

    @Override
    public boolean supports(FileSourceType type) {
        return type == FileSourceType.RESOURCES;
    }
}
