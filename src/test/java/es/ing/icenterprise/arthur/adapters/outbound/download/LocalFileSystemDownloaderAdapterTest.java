package es.ing.icenterprise.arthur.adapters.outbound.download;

import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceLocationDefinition;
import es.ing.icenterprise.arthur.core.domain.definition.ingest.FileSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemDownloaderAdapterTest {

    @TempDir
    Path sourceDir;

    @TempDir
    Path workDir;

    private LocalFileSystemDownloaderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LocalFileSystemDownloaderAdapter();
        ReflectionTestUtils.setField(adapter, "workingDirectory", workDir.toString());
        ReflectionTestUtils.setField(adapter, "dataDirectory", "");
    }

    // ── supports ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("supports returns true only for RESOURCES type")
    void supportsResourcesType() {
        assertThat(adapter.supports(FileSourceType.RESOURCES)).isTrue();
    }

    @Test
    @DisplayName("supports returns false for SHAREPOINT type")
    void supportsReturnsFalseForSharepoint() {
        assertThat(adapter.supports(FileSourceType.SHAREPOINT)).isFalse();
    }

    // ── download via filesystem path ──────────────────────────────────────────

    @Test
    @DisplayName("download copies an existing file to the working directory")
    void downloadCopiesFileToWorkDir() throws IOException {
        Path srcFile = sourceDir.resolve("data.xlsx");
        Files.writeString(srcFile, "content");

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition(srcFile.toString()),
                null);

        Path result = adapter.download(source);

        assertThat(result.getParent()).isEqualTo(workDir);
        assertThat(result.getFileName().toString()).isEqualTo("data.xlsx");
        assertThat(Files.exists(result)).isTrue();
        assertThat(Files.readString(result)).isEqualTo("content");
    }

    @Test
    @DisplayName("download throws RuntimeException when file not found anywhere")
    void downloadThrowsWhenFileNotFound() {
        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("/nonexistent/path/ghost.xlsx"),
                null);

        assertThatThrownBy(() -> adapter.download(source))
                .isInstanceOf(RuntimeException.class);
    }

    // ── download via data-directory ───────────────────────────────────────────

    @Test
    @DisplayName("download with dataDirectory resolves file by suffix and copies it")
    void downloadWithDataDirectoryResolvesBySuffix() throws IOException {
        // File name ends with "Actions-ES" before extension
        Path matchFile = sourceDir.resolve("2026-03-06-Full Dump Actions-ES.xlsx");
        Files.writeString(matchFile, "dump content");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Actions-ES"),
                null);

        Path result = adapter.download(source);

        assertThat(result.getFileName().toString()).isEqualTo("2026-03-06-Full Dump Actions-ES.xlsx");
        assertThat(Files.exists(result)).isTrue();
    }

    @Test
    @DisplayName("download with dataDirectory picks lexicographically latest when multiple files match")
    void downloadWithDataDirectoryPicksLatestFile() throws IOException {
        Path older = sourceDir.resolve("2025-01-01-Report-ES.xlsx");
        Path newer = sourceDir.resolve("2026-03-01-Report-ES.xlsx");
        Files.writeString(older, "old");
        Files.writeString(newer, "new");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Report-ES"),
                null);

        Path result = adapter.download(source);

        assertThat(result.getFileName().toString()).isEqualTo("2026-03-01-Report-ES.xlsx");
    }

    @Test
    @DisplayName("download with dataDirectory throws when no matching file found")
    void downloadWithDataDirectoryThrowsWhenNoMatch() throws IOException {
        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("NonExistentSuffix"),
                null);

        assertThatThrownBy(() -> adapter.download(source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No file matching suffix");
    }

    @Test
    @DisplayName("download with dataDirectory throws when dataDirectory does not exist")
    void downloadWithDataDirectoryThrowsWhenDirNotFound() {
        ReflectionTestUtils.setField(adapter, "dataDirectory", "/nonexistent/data/dir");

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("anything"),
                null);

        assertThatThrownBy(() -> adapter.download(source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Data directory does not exist");
    }
}
