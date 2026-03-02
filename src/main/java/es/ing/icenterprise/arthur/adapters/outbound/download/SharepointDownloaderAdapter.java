package es.ing.icenterprise.arthur.adapters.outbound.download;

import es.ing.icenterprise.arthur.core.config.SharepointProperties;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;
import es.ing.icenterprise.arthur.core.ports.outbound.FileDownloaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class SharepointDownloaderAdapter implements FileDownloaderPort {

    private static final Logger log = LoggerFactory.getLogger(SharepointDownloaderAdapter.class);

    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token";
    private static final String GRAPH_ITEM_URL =
            "https://graph.microsoft.com/v1.0/drives/{driveId}/items/{itemId}/content";
    private static final String GRAPH_PATH_URL =
            "https://graph.microsoft.com/v1.0/drives/{driveId}/root:/{filePath}:/content";

    private final SharepointProperties config;
    private final RestClient restClient;

    @Value("${ingesta.working-directory:/tmp/ingesta}")
    private String workingDirectory;

    // Token cache (volatile sufficient for single-threaded scheduled execution)
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SharepointDownloaderAdapter(SharepointProperties config) {
        this.config = config;
        this.restClient = RestClient.create();
    }

    @Override
    public boolean supports(FileSourceType type) {
        return type == FileSourceType.SHAREPOINT;
    }

    @Override
    public Path download(FileSourceDefinition source) {
        Map<String, String> props = source.location().properties();
        String driveId       = requireProp(props, "driveId");
        String fileWithinZip = requireProp(props, "fileWithinZip");

        String accessToken = getAccessToken();

        Path workDir = Path.of(workingDirectory);
        Path zipPath = workDir.resolve("sharepoint-download.zip");
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create working directory: " + workDir, e);
        }

        downloadZip(accessToken, driveId, props, zipPath);

        Path extractDir = workDir.resolve("zip-extract");
        try {
            Files.createDirectories(extractDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create extract directory: " + extractDir, e);
        }

        return extractFileFromZip(zipPath, extractDir, fileWithinZip);
    }

    private void downloadZip(String accessToken, String driveId,
                             Map<String, String> props, Path zipPath) {
        String itemId   = props.get("itemId");
        String filePath = props.get("filePath");

        byte[] zipBytes;
        if (itemId != null && !itemId.isBlank()) {
            log.info("Downloading ZIP from SharePoint via itemId: {}", itemId);
            zipBytes = restClient.get()
                    .uri(GRAPH_ITEM_URL, driveId, itemId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(byte[].class);
        } else if (filePath != null && !filePath.isBlank()) {
            // Strip leading slash if present — Graph API path format requires no leading slash
            String normalizedPath = filePath.startsWith("/") ? filePath.substring(1) : filePath;
            log.info("Downloading ZIP from SharePoint via filePath: {}", normalizedPath);
            zipBytes = restClient.get()
                    .uri(GRAPH_PATH_URL, driveId, normalizedPath)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(byte[].class);
        } else {
            throw new IllegalArgumentException(
                    "SharePoint source requires 'itemId' or 'filePath' in source.location.properties");
        }

        try {
            Files.write(zipPath, zipBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("ZIP saved to: {}", zipPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ZIP to disk", e);
        }
    }

    private String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            log.debug("Using cached SharePoint access token");
            return cachedToken;
        }

        log.info("Acquiring SharePoint access token for tenant: {}", config.tenantId());
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "client_credentials");
        body.add("client_id",     config.clientId());
        body.add("client_secret", config.clientSecret());
        body.add("scope",         "https://graph.microsoft.com/.default");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(TOKEN_URL, config.tenantId())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);

        String token     = (String) response.get("access_token");
        Number expiresIn = (Number) response.get("expires_in"); // seconds
        // Keep 60s buffer before actual expiry
        this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn.longValue() - 60);
        this.cachedToken    = token;

        log.info("Token acquired, valid for ~{}s", expiresIn);
        return token;
    }

    private Path extractFileFromZip(Path zipPath, Path extractDir, String targetFileName) {
        log.info("Extracting '{}' from: {}", targetFileName, zipPath);
        try (InputStream fis = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Use only the filename part to ignore directory prefixes inside the ZIP
                String entryName = Path.of(entry.getName()).getFileName().toString();
                if (entryName.equals(targetFileName)) {
                    Path target = extractDir.resolve(targetFileName);
                    // ZipSlip protection
                    if (!target.normalize().startsWith(extractDir.normalize())) {
                        throw new RuntimeException(
                                "ZIP entry path traversal detected: " + entry.getName());
                    }
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Extracted to: {}", target);
                    zis.closeEntry();
                    return target;
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract file from ZIP", e);
        }

        throw new RuntimeException(
                "File '" + targetFileName + "' not found in ZIP: " + zipPath);
    }

    private static String requireProp(Map<String, String> props, String key) {
        String value = props != null ? props.get(key) : null;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "SharePoint source requires 'source.location.properties." + key + "'");
        }
        return value;
    }
}
