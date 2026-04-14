package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.core.ports.outbound.RoleQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleOwnershipServiceTest {

    @Mock private RoleQueryPort roleQueryPort;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);

    @Test
    @DisplayName("Known roles get batchInsertOwnerRoles called with their owners")
    void processesKnownRolesAndCallsBatchInsert() {
        // Only map "CEO" so all other ROLE_QUERIES entries are skipped
        when(roleQueryPort.loadRoleNameToIdMapping())
                .thenReturn(Map.of("CEO", 1));
        when(roleQueryPort.executeRoleQuery(anyString(), eq(DATE), anyInt()))
                .thenReturn(Set.of("ck-ceo-1", "ck-ceo-2"));

        RoleOwnershipService service = new RoleOwnershipService(roleQueryPort);
        RoleOwnershipService.RoleOwnershipResult result = service.execute(DATE);

        verify(roleQueryPort).truncateOwnerRoles(DATE);
        verify(roleQueryPort).batchInsertOwnerRoles(DATE, 1, Set.of("ck-ceo-1", "ck-ceo-2"));
        assertThat(result.rolesProcessed()).isEqualTo(1);
        assertThat(result.totalRecords()).isEqualTo(2);
    }

    @Test
    @DisplayName("Roles present in ROLE_QUERIES but absent from the ROLES table are skipped")
    void skipsRolesNotInRolesTable() {
        // Return empty map → no role has a DB ID
        when(roleQueryPort.loadRoleNameToIdMapping()).thenReturn(Map.of());

        new RoleOwnershipService(roleQueryPort).execute(DATE);

        // No queries should be executed for any role
        verify(roleQueryPort, never()).executeRoleQuery(anyString(), any(), anyInt());
        // Truncate is still called (step 3 always runs)
        verify(roleQueryPort).truncateOwnerRoles(DATE);
    }

    @Test
    @DisplayName("Exception in a role query is swallowed and processing continues for other roles")
    void exceptionInQueryContinuesProcessingOtherRoles() {
        when(roleQueryPort.loadRoleNameToIdMapping())
                .thenReturn(Map.of("CEO", 1, "TribeLead", 2));
        when(roleQueryPort.executeRoleQuery(anyString(), eq(DATE), anyInt()))
                .thenThrow(new RuntimeException("DB error"))  // first call throws
                .thenReturn(Set.of("ck-tribe-1"));            // second call succeeds

        RoleOwnershipService.RoleOwnershipResult result =
                new RoleOwnershipService(roleQueryPort).execute(DATE);

        // truncate always happens
        verify(roleQueryPort).truncateOwnerRoles(DATE);
        // batchInsert called at least once for the successful role
        verify(roleQueryPort, atLeastOnce()).batchInsertOwnerRoles(eq(DATE), anyInt(), anySet());
        // At least one role was processed despite the error
        assertThat(result.rolesProcessed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("truncateOwnerRoles is always called exactly once regardless of query errors")
    void truncateOwnerRolesAlwaysCalledOnce() {
        when(roleQueryPort.loadRoleNameToIdMapping())
                .thenReturn(Map.of("CEO", 1));
        when(roleQueryPort.executeRoleQuery(anyString(), eq(DATE), anyInt()))
                .thenThrow(new RuntimeException("fail"));

        new RoleOwnershipService(roleQueryPort).execute(DATE);

        verify(roleQueryPort, times(1)).truncateOwnerRoles(DATE);
    }
}
