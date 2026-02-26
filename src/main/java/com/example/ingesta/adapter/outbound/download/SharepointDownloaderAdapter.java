package com.example.ingesta.adapter.outbound.download;

import com.example.ingesta.core.domain.definition.FileSourceDefinition;
import com.example.ingesta.core.domain.definition.FileSourceType;
import com.example.ingesta.core.port.outbound.FileDownloaderPort;
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
