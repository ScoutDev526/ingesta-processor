package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.ports.outbound.DepartmentQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Detects new departments (present in HR but absent from DEPARTMENT2) and classifies
 * them by walking the manager hierarchy until a domain lead is found.
 * <p>
 * Migrated from UpdateDepartments.py
 */
@Service
public class DepartmentUpdateService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentUpdateService.class);

    private final DepartmentQueryPort departmentQueryPort;

    public DepartmentUpdateService(DepartmentQueryPort departmentQueryPort) {
        this.departmentQueryPort = departmentQueryPort;
    }

    /**
     * Detects and inserts new departments for the given HR snapshot timestamp.
     *
     * @param timestamp the ingest date to query HR against
     * @return summary of the operation
     */
    public DepartmentUpdateResult execute(LocalDate timestamp) {
        log.info("Starting department update for timestamp: {}", timestamp);

        // 1. Build CK → roleName map for all domain leads
        Map<String, String> domainLeadMap = departmentQueryPort.getDomainLeadCkToRoleMap(timestamp);

        // 2. Iterate over employees whose department is not yet in DEPARTMENT2
        List<String[]> newDepts = departmentQueryPort.getNewDepartments(timestamp);
        Set<String> processedDepts = new LinkedHashSet<>();
        int insertCount = 0;

        for (String[] row : newDepts) {
            String ck = row[0];
            String deptId = row[1];

            if (processedDepts.contains(deptId)) continue;
            processedDepts.add(deptId);

            // Walk up the manager tree until we find a domain lead
            for (String managerCk : departmentQueryPort.getManagerTreeUp(ck, timestamp)) {
                if (!domainLeadMap.containsKey(managerCk)) continue;

                String roleName = domainLeadMap.get(managerCk);
                String domain = roleName.split("_")[0]; // e.g. "CIO" from "CIO_DomainLead"
                String deptType = "CIO".equals(domain) ? "IT" : "Business";

                departmentQueryPort.insertDepartment(deptId, deptType, domain);
                log.info("insert: {} {} {}", deptId, deptType, domain);
                insertCount++;
                break;
            }
        }

        log.info("Department update finished: {} unique departments processed, {} inserted",
                processedDepts.size(), insertCount);
        return new DepartmentUpdateResult(processedDepts.size(), insertCount);
    }

    public record DepartmentUpdateResult(int departmentsProcessed, int departmentsInserted) {}
}
