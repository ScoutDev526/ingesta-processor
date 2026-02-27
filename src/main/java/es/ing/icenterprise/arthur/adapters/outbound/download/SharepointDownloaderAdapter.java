package es.ing.icenterprise.arthur.adapters.outbound.download;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;
import es.ing.icenterprise.arthur.core.ports.outbound.FileDownloaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class SharepointDownloaderAdapter implements FileDownloaderPort {

    private static final Logger log = LoggerFactory.getLogger(SharepointDownloaderAdapter.class);

    @Override
    public Path download(FileSourceDefinition source) {
        log.info("Downloading from SharePoint: {}", source.location().path());
        // TODO: Implement SharePoint Graph API integration
        throw new UnsupportedOperationException("SharePoint download not yet implemented");
    }

    @Override
    public boolean supports(FileSourceType type) {
        return type == FileSourceType.SHAREPOINT;
    }
}
