package es.ing.icenterprise.arthur.core.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ing.icenterprise.arthur.adapters.outbound.persistence.ImportDataHrDeleteMapper;
import es.ing.icenterprise.arthur.adapters.outbound.persistence.ImportDataHrMapper;
import es.ing.icenterprise.arthur.core.domain.model.ImportDataHrPerson;
import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportDataHrServiceTest {

    @Mock private ImportDataHrDeleteMapper deleteMapper;
    @Mock private ImportDataHrMapper insertMapper;

    @InjectMocks private ImportDataHrService service;

    @Test
    @DisplayName("importDataHr deletes the current day and inserts one row per LDAP person")
    void importDataHrDeletesAndInserts() {
        PersonLdap p = new PersonLdap();
        p.setSamAccountName("XT5ORI");
        p.setGivenName("givenName: Maria");
        p.setLastName("sn: Fuentes Artola");
        p.setMail("mail: Maria.Fuentes.Artola@ing.com");
        p.setDepartment("department: HR Expatriados Retail");
        p.setTitle("title: CFO VIII");
        p.setManager("CN=NT25YI,OU=E3,OU=Production,OU=Users,OU=INGUsers,DC=ad,DC=ing,DC=net");

        when(deleteMapper.deleteByTimestamp(any(LocalDateTime.class))).thenReturn(5);

        service.importDataHr(List.of(p));

        ArgumentCaptor<ImportDataHrPerson> captor = ArgumentCaptor.forClass(ImportDataHrPerson.class);
        verify(insertMapper, times(1)).insertOne(captor.capture());
        ImportDataHrPerson row = captor.getValue();

        assertThat(row.getSamAccountName()).isEqualTo("XT5ORI");
        assertThat(row.getFullName()).isEqualTo("Maria Fuentes Artola");
        assertThat(row.getMail()).isEqualTo("Maria.Fuentes.Artola@ing.com");
        assertThat(row.getDepartment()).isEqualTo("HR Expatriados Retail");
        assertThat(row.getManager()).isEqualTo("NT25YI");
        assertThat(row.getTitle()).isEqualTo("CFO VIII");
        assertThat(row.getTimestamp()).isNotNull();

        verify(deleteMapper, times(1)).deleteByTimestamp(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("importDataHr with empty list only calls delete, no inserts")
    void importDataHrEmptyList() {
        service.importDataHr(List.of());

        verify(deleteMapper, times(1)).deleteByTimestamp(any(LocalDateTime.class));
        verify(insertMapper, times(0)).insertOne(any());
    }
}
