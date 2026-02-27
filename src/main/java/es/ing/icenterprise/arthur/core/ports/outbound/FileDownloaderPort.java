package es.ing.icenterprise.arthur.core.ports.outbound;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;

import java.nio.file.Path;

/**
 * Downloads the file to a local tmp working path and returns that path.
 */
public interface FileDownloaderPort {

    Path download(FileSourceDefinition source);

    boolean supports(FileSourceType type);
}
