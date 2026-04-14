package es.ing.icenterprise.arthur.adapters.outbound.cleanup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class CleanupWorkingDirectoryAdapterTest {

    private final CleanupWorkingDirectoryAdapter adapter = new CleanupWorkingDirectoryAdapter();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("delete removes an existing file")
    void deleteRemovesExistingFile() throws IOException {
        Path file = tempDir.resolve("temp.xlsx");
        Files.writeString(file, "content");

        adapter.delete(file);

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("delete does not throw when file does not exist")
    void deleteDoesNothingForMissingFile() {
        Path missing = tempDir.resolve("ghost.xlsx");
        assertThatNoException().isThrownBy(() -> adapter.delete(missing));
    }

    @Test
    @DisplayName("delete does not throw for a non-deletable path (IOException swallowed)")
    void deleteSwallowsIoException() throws IOException {
        // Delete a directory that has content — Files.delete would throw
        Path dir = tempDir.resolve("subdir");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("child.txt"), "x"); // dir is non-empty

        // adapter.delete swallows IOException, so no exception should propagate
        assertThatNoException().isThrownBy(() -> adapter.delete(dir));
    }
}
