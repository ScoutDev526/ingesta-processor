package es.ing.icenterprise.arthur.core.ports.outbound;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Port for department update database operations.
 * Used by DepartmentUpdateService to discover new departments and populate DEPARTMENT2.
 */
public interface DepartmentQueryPort {

    /**
     * Returns a map of CK → domain lead role name for all domain lead roles at the given timestamp.
     * Combines the HR timestamp filter with the ROLES_ASSIGNATION lookup.
     */
    Map<String, String> getDomainLeadCkToRoleMap(LocalDate timestamp);

    /**
     * Returns (ck, departmentId) pairs for departments present in HR but not yet in DEPARTMENT2.
     */
    List<String[]> getNewDepartments(LocalDate timestamp);

    /**
     * Walks up the manager hierarchy from the given CK and returns all ancestor CKs (inclusive).
     * Stops at the root (null ckmanager) or when a cycle is detected.
     */
    List<String> getManagerTreeUp(String ck, LocalDate timestamp);

    /**
     * Inserts a new row into DEPARTMENT2.
     */
    void insertDepartment(String departmentId, String departmentType, String domain);
}
