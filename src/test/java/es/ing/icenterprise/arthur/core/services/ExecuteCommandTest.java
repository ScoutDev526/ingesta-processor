package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.usecases.ExecuteCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteCommandTest {

    @Test
    @DisplayName("fromScheduler creates non-manual command with empty filter")
    void fromScheduler() {
        ExecuteCommand cmd = ExecuteCommand.fromScheduler();
        assertThat(cmd.manuallyTriggered()).isFalse();
        assertThat(cmd.jobFilter()).isEmpty();
        assertThat(cmd.shouldRunAll()).isTrue();
    }

    @Test
    @DisplayName("fromManual creates manual command with empty filter")
    void fromManual() {
        ExecuteCommand cmd = ExecuteCommand.fromManual();
        assertThat(cmd.manuallyTriggered()).isTrue();
        assertThat(cmd.shouldRunAll()).isTrue();
    }

    @Test
    @DisplayName("fromManual with filter creates command with specific jobs")
    void fromManualWithFilter() {
        ExecuteCommand cmd = ExecuteCommand.fromManual(List.of("job-a", "job-b"));
        assertThat(cmd.manuallyTriggered()).isTrue();
        assertThat(cmd.shouldRunAll()).isFalse();
        assertThat(cmd.jobFilter()).containsExactly("job-a", "job-b");
    }
}
