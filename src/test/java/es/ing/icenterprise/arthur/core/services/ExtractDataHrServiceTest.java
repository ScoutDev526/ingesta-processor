package es.ing.icenterprise.arthur.core.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ing.icenterprise.arthur.core.domain.exception.LdapSearchException;
import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.inbound.ImportDataHrUseCase;
import es.ing.icenterprise.arthur.core.ports.outbound.LdapRepository;
import es.ing.icenterprise.arthur.core.ports.outbound.SearchCriteria;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.AttributesMapper;

@ExtendWith(MockitoExtension.class)
class ExtractDataHrServiceTest {

    @Mock private LdapRepository ldapRepository;
    @Mock private ImportDataHrUseCase importDataHrService;

    @InjectMocks private ExtractDataHrService service;

    @Test
    @DisplayName("extractDataHr runs 4 searches and aggregates results into combined list")
    void extractDataHrAggregatesResults() {
        PersonLdap p1 = new PersonLdap();
        p1.setSamAccountName("XT5ORI");
        PersonLdap p2 = new PersonLdap();
        p2.setSamAccountName("AB12CD");
        PersonLdap p3 = new PersonLdap();
        p3.setSamAccountName("YY99ZZ");

        when(ldapRepository.search(any(SearchCriteria.class), any(AttributesMapper.class)))
                .thenReturn(List.of(p1))
                .thenReturn(List.of(p2))
                .thenReturn(List.of(p3))
                .thenReturn(List.of());

        List<PersonLdap> result = service.extractDataHr();

        assertThat(result).hasSize(3);
        assertThat(result).extracting(PersonLdap::getSamAccountName)
                .containsExactly("XT5ORI", "AB12CD", "YY99ZZ");
        verify(ldapRepository, times(4)).search(any(SearchCriteria.class), any(AttributesMapper.class));
        verify(importDataHrService, times(1)).importDataHr(result);
    }

    @Test
    @DisplayName("safeSearch returns empty list when LdapSearchException is thrown")
    void safeSearchSwallowsLdapExceptions() {
        when(ldapRepository.search(any(SearchCriteria.class), any(AttributesMapper.class)))
                .thenThrow(new LdapSearchException("boom"))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of());

        List<PersonLdap> result = service.extractDataHr();

        assertThat(result).isEmpty();
        verify(importDataHrService).importDataHr(List.of());
    }

    @Test
    @DisplayName("safeSearch swallows any generic exception")
    void safeSearchSwallowsGenericExceptions() {
        when(ldapRepository.search(any(SearchCriteria.class), any(AttributesMapper.class)))
                .thenThrow(new RuntimeException("unexpected"))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of());

        List<PersonLdap> result = service.extractDataHr();

        assertThat(result).isEmpty();
        verify(importDataHrService, times(1)).importDataHr(any());
        verify(importDataHrService, never()).importDataHr(null);
    }
}
