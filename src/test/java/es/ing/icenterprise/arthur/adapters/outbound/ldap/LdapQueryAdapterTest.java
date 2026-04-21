package es.ing.icenterprise.arthur.adapters.outbound.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.outbound.SearchCriteria;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;

@ExtendWith(MockitoExtension.class)
class LdapQueryAdapterTest {

    @Mock private LdapTemplate ldapTemplate;
    @InjectMocks private LdapQueryAdapter adapter;

    private static final SearchCriteria CRITERIA =
            new SearchCriteria("OU=E3,DC=ad", "(objectCategory=person)");

    @Test
    @DisplayName("search delegates to LdapTemplate and returns the mapped list")
    void searchDelegates() {
        PersonLdap p = new PersonLdap();
        p.setSamAccountName("XT5ORI");
        when(ldapTemplate.search(any(LdapQuery.class), any(AttributesMapper.class)))
                .thenReturn(List.of(p));

        List<PersonLdap> result = adapter.search(CRITERIA, new PersonLdapAttributesMapper());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSamAccountName()).isEqualTo("XT5ORI");
    }

    @Test
    @DisplayName("searchOne returns the unique result")
    void searchOneReturnsUnique() {
        PersonLdap p = new PersonLdap();
        p.setSamAccountName("X1");
        when(ldapTemplate.search(any(LdapQuery.class), any(AttributesMapper.class)))
                .thenReturn(List.of(p));

        PersonLdap result = adapter.searchOne(CRITERIA, new PersonLdapAttributesMapper());

        assertThat(result.getSamAccountName()).isEqualTo("X1");
    }

    @Test
    @DisplayName("searchOne throws when no entry is found")
    void searchOneThrowsOnEmpty() {
        when(ldapTemplate.search(any(LdapQuery.class), any(AttributesMapper.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> adapter.searchOne(CRITERIA, new PersonLdapAttributesMapper()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No entry found");
    }

    @Test
    @DisplayName("searchOne throws when multiple entries are found")
    void searchOneThrowsOnMultiple() {
        PersonLdap p1 = new PersonLdap();
        PersonLdap p2 = new PersonLdap();
        when(ldapTemplate.search(any(LdapQuery.class), any(AttributesMapper.class)))
                .thenReturn(List.of(p1, p2));

        assertThatThrownBy(() -> adapter.searchOne(CRITERIA, new PersonLdapAttributesMapper()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Multiple entries");
    }
}
