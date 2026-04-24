package es.ing.icenterprise.arthur.adapters.outbound.download;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;
import es.ing.icenterprise.arthur.core.ports.outbound.FileDownloaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class LocalFileSystemDownloaderAdapter implements FileDownloaderPort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemDownloaderAdapter.class);

    @Value("${ingesta.working-directory:/tmp/ingesta}")
    private String workingDirectory;

    @Value("${ingesta.data-directory:}")
    private String dataDirectory;

    @Override
    public Path download(FileSourceDefinition source) {
        String resourcePath = source.location().path();
        log.info("Resolving file from: {}", resourcePath);

        try {
            Path workDir = Path.of(workingDirectory);
            Files.createDirectories(workDir);

            Path sourcePath;
            if (!dataDirectory.isBlank()) {
                sourcePath = resolveFromDataDirectory(resourcePath);
            } else {
                sourcePath = resolveSource(resourcePath);
            }

            Path targetPath = workDir.resolve(sourcePath.getFileName());
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("File copied to working directory: {}", targetPath);
            return targetPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file to working directory", e);
        }
    }

    /**
     * Resolves a file from the configured data-directory by matching the suffix.
     * Given suffix "Actions-ES", finds files like "2026-03-06-Full Dump Actions-ES.xlsx".
     * If multiple files match, picks the one with the most recent filename (lexicographic).
     */
    private Path resolveFromDataDirectory(String suffix) {
        Path dataDir = Path.of(dataDirectory);
        if (!Files.isDirectory(dataDir)) {
            throw new RuntimeException("Data directory does not exist: " + dataDirectory);
        }

        String suffixLower = suffix.toLowerCase();

        Comparator<Path> byDate = Comparator.comparing(
                LocalFileSystemDownloaderAdapter::extractPrefixDate,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());

        try (Stream<Path> files = Files.list(dataDir)) {
            Path match = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        int dotIndex = name.lastIndexOf('.');
                        String nameWithoutExt = dotIndex > 0 ? name.substring(0, dotIndex) : name;
                        return endsWithAtWordBoundary(nameWithoutExt, suffixLower);
                    })
                    .max(byDate.thenComparing(byName))
                    .orElse(null);

            if (match != null) {
                log.info("Resolved '{}' from data directory as: {}", suffix, match);
                return match;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan data directory: " + dataDirectory, e);
        }

        throw new RuntimeException("No file matching suffix '" + suffix + "' found in data directory: " + dataDirectory);
    }

    /**
     * True when {@code name} ends with {@code suffix} at a real boundary.
     *
     * <p>A boundary is real when any of the following holds:
     *
     * <ul>
     *   <li>{@code suffix} is the whole name.
     *   <li>The character immediately before the suffix is any non-alphanumeric separator OTHER
     *       than a plain {@code '-'} — typical cases: whitespace, {@code '_'} (e.g.
     *       {@code "Table_CMDB.xlsx"}).
     *   <li>The character immediately before the suffix is a {@code '-'} that closes a
     *       {@code YYYY-MM-DD-} date prefix (e.g. {@code "2025-01-01-Report-ES.xlsx"}).
     * </ul>
     *
     * <p>A plain {@code '-'} is NOT treated as a boundary by itself, because dataset names may
     * contain internal hyphens (e.g. {@code "Sub-Processes-ES"}). Without that exception, suffix
     * {@code "Processes-ES"} would incorrectly match {@code "...Full Dump Sub-Processes-ES.xlsx"}.
     */
    private static boolean endsWithAtWordBoundary(String name, String suffix) {
        if (!name.endsWith(suffix)) {
            return false;
        }
        int boundary = name.length() - suffix.length();
        if (boundary == 0) {
            return true;
        }
        char before = name.charAt(boundary - 1);
        if (Character.isLetterOrDigit(before)) {
            return false;
        }
        if (before == '-') {
            if (boundary < DATE_PREFIX_LENGTH) {
                return false;
            }
            String datePrefix = name.substring(boundary - DATE_PREFIX_LENGTH, boundary);
            return DATE_PREFIX_PATTERN.matcher(datePrefix).matches();
        }
        return true;
    }

    private static final int DATE_PREFIX_LENGTH = 11; // "YYYY-MM-DD-"
    private static final int DATE_LENGTH = 10; // "YYYY-MM-DD"
    private static final Pattern DATE_PREFIX_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-");

    private static LocalDate extractPrefixDate(Path p) {
        String name = p.getFileName().toString();
        if (name.length() < DATE_PREFIX_LENGTH) {
            return null;
        }
        if (!DATE_PREFIX_PATTERN.matcher(name.substring(0, DATE_PREFIX_LENGTH)).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(name.substring(0, DATE_LENGTH));
        } catch (DateTimeParseException e) {
            return null;
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
