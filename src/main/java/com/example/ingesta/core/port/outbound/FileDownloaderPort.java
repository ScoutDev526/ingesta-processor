package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.definition.FileSourceDefinition;
import com.example.ingesta.core.domain.definition.FileSourceType;

import java.nio.file.Path;

/**
 * Downloads the file to a local tmp working path and returns that path.
 */
public interface FileDownloaderPort {

    Path download(FileSourceDefinition source);

    boolean supports(FileSourceType type);
}
