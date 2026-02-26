package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;
import es.ing.icenterprise.arthur.core.ports.ProcessReportRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryProcessReportRepository implements ProcessReportRepository {

    private final Map<UUID, ProcessReport> store = new ConcurrentHashMap<>();

    @Override
    public ProcessReport save(ProcessReport report) {
        store.put(report.getId(), report);
        return report;
    }

    @Override
    public Optional<ProcessReport> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }
}
