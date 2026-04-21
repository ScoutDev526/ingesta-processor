package es.ing.icenterprise.arthur.adapters.inbound.rest;

import static org.assertj.core.api.Assertions.assertThat;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersonLdapMapperTest {

    @Test
    @DisplayName("toResponse maps all 7 fields")
    void toResponseMapsAllFields() {
        PersonLdap p = new PersonLdap();
        p.setSamAccountName("XT5ORI");
        p.setManager("CN=NT25YI");
        p.setDepartment("HR");
        p.setMail("m@i.com");
        p.setGivenName("Maria");
        p.setLastName("Fuentes");
        p.setTitle("CFO");

        PersonLdapResponse r = new PersonLdapMapper().toResponse(p);

        assertThat(r.samAccountName()).isEqualTo("XT5ORI");
        assertThat(r.manager()).isEqualTo("CN=NT25YI");
        assertThat(r.department()).isEqualTo("HR");
        assertThat(r.mail()).isEqualTo("m@i.com");
        assertThat(r.givenName()).isEqualTo("Maria");
        assertThat(r.lastName()).isEqualTo("Fuentes");
        assertThat(r.title()).isEqualTo("CFO");
    }

    @Test
    @DisplayName("toResponseList transforms each element")
    void toResponseListMapsEach() {
        PersonLdap p1 = new PersonLdap();
        p1.setSamAccountName("A");
        PersonLdap p2 = new PersonLdap();
        p2.setSamAccountName("B");

        List<PersonLdapResponse> r = new PersonLdapMapper().toResponseList(List.of(p1, p2));

        assertThat(r).extracting(PersonLdapResponse::samAccountName).containsExactly("A", "B");
    }
}
