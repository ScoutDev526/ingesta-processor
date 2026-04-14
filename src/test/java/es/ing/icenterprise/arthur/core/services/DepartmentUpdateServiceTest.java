package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.ports.outbound.DepartmentQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepartmentUpdateServiceTest {

    @Mock private DepartmentQueryPort departmentQueryPort;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);

    @Test
    @DisplayName("CIO domain lead → department inserted as IT type")
    void insertsCioDepartmentAsItType() {
        when(departmentQueryPort.getDomainLeadCkToRoleMap(DATE))
                .thenReturn(Map.of("ck-cio", "CIO_DomainLead"));
        when(departmentQueryPort.getNewDepartments(DATE))
                .thenReturn(Collections.singletonList(new String[]{"ck-employee", "dept-IT-001"}));
        when(departmentQueryPort.getManagerTreeUp("ck-employee", DATE))
                .thenReturn(List.of("ck-employee", "ck-manager", "ck-cio"));

        DepartmentUpdateService service = new DepartmentUpdateService(departmentQueryPort);
        DepartmentUpdateService.DepartmentUpdateResult result = service.execute(DATE);

        verify(departmentQueryPort).insertDepartment("dept-IT-001", "IT", "CIO");
        assertThat(result.departmentsProcessed()).isEqualTo(1);
        assertThat(result.departmentsInserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("Non-CIO domain lead → department inserted as Business type")
    void insertsNonCioDepartmentAsBusinessType() {
        when(departmentQueryPort.getDomainLeadCkToRoleMap(DATE))
                .thenReturn(Map.of("ck-coo", "COO_DomainLead"));
        when(departmentQueryPort.getNewDepartments(DATE))
                .thenReturn(Collections.singletonList(new String[]{"ck-emp", "dept-BUS-001"}));
        when(departmentQueryPort.getManagerTreeUp("ck-emp", DATE))
                .thenReturn(List.of("ck-emp", "ck-coo"));

        new DepartmentUpdateService(departmentQueryPort).execute(DATE);

        verify(departmentQueryPort).insertDepartment("dept-BUS-001", "Business", "COO");
    }

    @Test
    @DisplayName("Same department appearing for two employees is inserted only once")
    void deduplicatesDepartmentAcrossMultipleEmployees() {
        when(departmentQueryPort.getDomainLeadCkToRoleMap(DATE))
                .thenReturn(Map.of("ck-lead", "HR_DomainLead"));
        when(departmentQueryPort.getNewDepartments(DATE))
                .thenReturn(List.of(
                        new String[]{"ck-emp1", "dept-SHARED"},
                        new String[]{"ck-emp2", "dept-SHARED"}
                ));
        when(departmentQueryPort.getManagerTreeUp("ck-emp1", DATE))
                .thenReturn(List.of("ck-emp1", "ck-lead"));

        DepartmentUpdateService.DepartmentUpdateResult result =
                new DepartmentUpdateService(departmentQueryPort).execute(DATE);

        verify(departmentQueryPort, times(1)).insertDepartment(eq("dept-SHARED"), anyString(), anyString());
        // getManagerTreeUp is only called for the first employee; second is deduplicated
        verify(departmentQueryPort, times(1)).getManagerTreeUp(any(), eq(DATE));
        assertThat(result.departmentsProcessed()).isEqualTo(1);
        assertThat(result.departmentsInserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("No domain lead in manager tree → department not inserted")
    void doesNotInsertWhenNoDomainLeadInTree() {
        when(departmentQueryPort.getDomainLeadCkToRoleMap(DATE))
                .thenReturn(Map.of("ck-lead", "CIO_DomainLead"));
        when(departmentQueryPort.getNewDepartments(DATE))
                .thenReturn(Collections.singletonList(new String[]{"ck-emp", "dept-ORPHAN"}));
        when(departmentQueryPort.getManagerTreeUp("ck-emp", DATE))
                .thenReturn(List.of("ck-emp", "ck-mid-manager")); // lead not in tree

        DepartmentUpdateService.DepartmentUpdateResult result =
                new DepartmentUpdateService(departmentQueryPort).execute(DATE);

        verify(departmentQueryPort, never()).insertDepartment(anyString(), anyString(), anyString());
        assertThat(result.departmentsProcessed()).isEqualTo(1);
        assertThat(result.departmentsInserted()).isEqualTo(0);
    }

    @Test
    @DisplayName("Empty new departments list returns (0, 0)")
    void emptyNewDepartmentsReturnsZeroCounts() {
        when(departmentQueryPort.getDomainLeadCkToRoleMap(DATE)).thenReturn(Map.of());
        when(departmentQueryPort.getNewDepartments(DATE)).thenReturn(List.of());

        DepartmentUpdateService.DepartmentUpdateResult result =
                new DepartmentUpdateService(departmentQueryPort).execute(DATE);

        verify(departmentQueryPort, never()).insertDepartment(anyString(), anyString(), anyString());
        assertThat(result.departmentsProcessed()).isZero();
        assertThat(result.departmentsInserted()).isZero();
    }
}
