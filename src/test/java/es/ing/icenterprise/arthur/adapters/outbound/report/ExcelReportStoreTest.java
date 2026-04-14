package es.ing.icenterprise.arthur.adapters.outbound.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelReportStoreTest {

    private final ExcelReportStore store = new ExcelReportStore();

    @Test
    @DisplayName("save() then find() by same UUID returns the stored bytes")
    void saveAndFindByUuidReturnsBytes() {
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[]{1, 2, 3, 4};

        store.save(id, bytes);

        Optional<byte[]> result = store.find(id);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("find() for unknown UUID returns empty Optional")
    void findUnknownUuidReturnsEmpty() {
        Optional<byte[]> result = store.find(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Multiple reports are stored and retrieved independently")
    void multipleReportsStoredIndependently() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        byte[] bytes1 = new byte[]{10, 20};
        byte[] bytes2 = new byte[]{30, 40, 50};

        store.save(id1, bytes1);
        store.save(id2, bytes2);

        assertThat(store.find(id1)).isPresent().get().isEqualTo(bytes1);
        assertThat(store.find(id2)).isPresent().get().isEqualTo(bytes2);
    }
}
