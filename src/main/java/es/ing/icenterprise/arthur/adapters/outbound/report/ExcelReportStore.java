package es.ing.icenterprise.arthur.adapters.outbound.report;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for generated Excel report bytes, keyed by ProcessReport ID.
 * Allows the REST endpoint to serve the Excel without re-generating it.
 */
@Component
public class ExcelReportStore {

    private final Map<UUID, byte[]> store = new ConcurrentHashMap<>();

    public void save(UUID reportId, byte[] excelBytes) {
        store.put(reportId, excelBytes);
    }

    public Optional<byte[]> find(UUID reportId) {
        return Optional.ofNullable(store.get(reportId));
    }
}
