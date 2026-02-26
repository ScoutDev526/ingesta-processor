package com.example.ingesta.adapter.outbound.download;

import com.example.ingesta.core.domain.definition.FileSourceDefinition;
import com.example.ingesta.core.domain.definition.FileSourceType;
import com.example.ingesta.core.port.outbound.FileDownloaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;

@Component
public class LocalFileSystemDownloaderAdapter implements FileDownloaderPort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemDownloaderAdapter.class);

    @Value("${ingesta.working-directory:/tmp/ingesta}")
    private String workingDirectory;

    @Override
    public Path download(FileSourceDefinition source) {
        Path sourcePath = Path.of(source.location().path());
        log.info("Copying local file from: {}", sourcePath);

        if (!Files.exists(sourcePath)) {
            throw new RuntimeException("File not found: " + sourcePath);
        }

        try {
            Path workDir = Path.of(workingDirectory);
            Files.createDirectories(workDir);

            Path targetPath = workDir.resolve(sourcePath.getFileName());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("File copied to working directory: {}", targetPath);
            return targetPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file to working directory", e);
        }
    }

    @Override
    public boolean supports(FileSourceType type) {
        return type == FileSourceType.RESOURCES;
    }
}
