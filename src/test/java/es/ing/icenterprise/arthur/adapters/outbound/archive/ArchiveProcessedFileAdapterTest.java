package es.ing.icenterprise.arthur.adapters.outbound.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ArchiveProcessedFileAdapterTest {

    @TempDir
    Path workDir;

    @TempDir
    Path cachedDir;

    private ArchiveProcessedFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ArchiveProcessedFileAdapter();
        ReflectionTestUtils.setField(adapter, "cachedDirectory", cachedDir.toString());
    }

    @Test
    @DisplayName("archive moves file to cached dir stripping YYYY-MM-DD-Full Dump prefix")
    void archiveStripsDateAndFullDumpPrefix() throws IOException {
        Path source = workDir.resolve("2026-03-06-Full Dump Actions-ES.xlsx");
        Files.writeString(source, "payload");

        adapter.archive(source);

        assertThat(Files.exists(source)).isFalse();
        Path archived = cachedDir.resolve("Actions-ES.xlsx");
        assertThat(Files.exists(archived)).isTrue();
        assertThat(Files.readString(archived)).isEqualTo("payload");
    }

    @Test
    @DisplayName("archive strips YYYY-MM-DD prefix when 'Full Dump' is absent")
    void archiveStripsBareDatePrefix() throws IOException {
        Path source = workDir.resolve("2026-03-01-Report-ES.xlsx");
        Files.writeString(source, "payload");

        adapter.archive(source);

        assertThat(Files.exists(cachedDir.resolve("Report-ES.xlsx"))).isTrue();
    }

    @Test
    @DisplayName("archive keeps the original name when no date prefix is present")
    void archiveKeepsNameWhenNoDatePrefix() throws IOException {
        Path source = workDir.resolve("Table_CMDB.xlsx");
        Files.writeString(source, "payload");

        adapter.archive(source);

        assertThat(Files.exists(cachedDir.resolve("Table_CMDB.xlsx"))).isTrue();
    }

    @Test
    @DisplayName("archive overwrites a previously archived file with the same name")
    void archiveOverwritesExistingCachedCopy() throws IOException {
        Path older = cachedDir.resolve("Actions-ES.xlsx");
        Files.writeString(older, "old");

        Path source = workDir.resolve("2026-04-24-Full Dump Actions-ES.xlsx");
        Files.writeString(source, "new");

        adapter.archive(source);

        assertThat(Files.readString(cachedDir.resolve("Actions-ES.xlsx"))).isEqualTo("new");
        assertThat(Files.exists(source)).isFalse();
    }

    @Test
    @DisplayName("archive creates the cached directory if it does not exist")
    void archiveCreatesCachedDirIfMissing() throws IOException {
        Path deepDir = cachedDir.resolve("nested").resolve("cached");
        ReflectionTestUtils.setField(adapter, "cachedDirectory", deepDir.toString());

        Path source = workDir.resolve("2026-03-06-Full Dump Actions-ES.xlsx");
        Files.writeString(source, "payload");

        adapter.archive(source);

        assertThat(Files.isDirectory(deepDir)).isTrue();
        assertThat(Files.exists(deepDir.resolve("Actions-ES.xlsx"))).isTrue();
    }

    @Test
    @DisplayName("archive is a no-op when the source file does not exist")
    void archiveNoOpForMissingFile() {
        Path missing = workDir.resolve("ghost.xlsx");
        assertThatNoException().isThrownBy(() -> adapter.archive(missing));
    }
}
