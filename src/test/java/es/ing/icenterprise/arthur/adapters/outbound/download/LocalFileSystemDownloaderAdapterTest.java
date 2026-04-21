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
    @DisplayName("download with dataDirectory 'Process-ES' does NOT pick a 'SubProcess-ES' file")
    void downloadWithDataDirectoryRespectsWordBoundary() throws IOException {
        Path processFile = sourceDir.resolve("2026-04-21-Full Dump Process-ES.xlsx");
        Path subprocessFile = sourceDir.resolve("2026-04-21-Full Dump SubProcess-ES.xlsx");
        Files.writeString(processFile, "process");
        Files.writeString(subprocessFile, "subprocess");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition processSource = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Process-ES"),
                null);

        Path processResult = adapter.download(processSource);
        assertThat(processResult.getFileName().toString())
                .isEqualTo("2026-04-21-Full Dump Process-ES.xlsx");

        FileSourceDefinition subprocessSource = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("SubProcess-ES"),
                null);

        Path subprocessResult = adapter.download(subprocessSource);
        assertThat(subprocessResult.getFileName().toString())
                .isEqualTo("2026-04-21-Full Dump SubProcess-ES.xlsx");
    }

    @Test
    @DisplayName("download with dataDirectory 'Process-ES' fails if only a 'SubProcess-ES' file exists")
    void downloadWithDataDirectoryDoesNotMatchSubprocessWhenSearchingProcess() throws IOException {
        Path subprocessFile = sourceDir.resolve("2026-04-21-Full Dump SubProcess-ES.xlsx");
        Files.writeString(subprocessFile, "subprocess");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Process-ES"),
                null);

        assertThatThrownBy(() -> adapter.download(source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No file matching suffix");
    }

    @Test
    @DisplayName("download with dataDirectory 'Processes-ES' does NOT pick a 'Sub-Processes-ES' file")
    void downloadWithDataDirectoryRespectsHyphenatedDatasetNames() throws IOException {
        Path subProcessFile = sourceDir.resolve("2026-04-21-Full Dump Sub-Processes-ES.xlsx");
        Path processFile = sourceDir.resolve("2026-04-21-Full Dump Processes-ES.xlsx");
        Files.writeString(subProcessFile, "sub");
        Files.writeString(processFile, "main");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition processesSource = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Processes-ES"),
                null);

        Path resolvedProcesses = adapter.download(processesSource);
        assertThat(resolvedProcesses.getFileName().toString())
                .isEqualTo("2026-04-21-Full Dump Processes-ES.xlsx");

        FileSourceDefinition subProcessesSource = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Sub-Processes-ES"),
                null);

        Path resolvedSubProcesses = adapter.download(subProcessesSource);
        assertThat(resolvedSubProcesses.getFileName().toString())
                .isEqualTo("2026-04-21-Full Dump Sub-Processes-ES.xlsx");
    }

    @Test
    @DisplayName("download with dataDirectory 'Processes-ES' fails when only 'Sub-Processes-ES' exists")
    void downloadWithDataDirectoryFailsWhenOnlySubProcessesExists() throws IOException {
        Files.writeString(
                sourceDir.resolve("2026-04-21-Full Dump Sub-Processes-ES.xlsx"), "sub");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        FileSourceDefinition source = new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("Processes-ES"),
                null);

        assertThatThrownBy(() -> adapter.download(source))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No file matching suffix");
    }

    @Test
    @DisplayName("download with dataDirectory resolves Table_CMDB.xlsx and Table_ITRMP.xlsx (underscore separator)")
    void downloadWithDataDirectoryAcceptsUnderscoreSeparator() throws IOException {
        Path cmdb = sourceDir.resolve("Table_CMDB.xlsx");
        Path itrmp = sourceDir.resolve("Table_ITRMP.xlsx");
        Files.writeString(cmdb, "cmdb");
        Files.writeString(itrmp, "itrmp");

        ReflectionTestUtils.setField(adapter, "dataDirectory", sourceDir.toString());

        Path resolvedCmdb = adapter.download(new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("CMDB"),
                null));
        assertThat(resolvedCmdb.getFileName().toString()).isEqualTo("Table_CMDB.xlsx");

        Path resolvedItrmp = adapter.download(new FileSourceDefinition(
                FileSourceType.RESOURCES,
                new FileSourceLocationDefinition("ITRMP"),
                null));
        assertThat(resolvedItrmp.getFileName().toString()).isEqualTo("Table_ITRMP.xlsx");
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
