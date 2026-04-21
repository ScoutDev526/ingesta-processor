package es.ing.icenterprise.arthur.adapters.outbound.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersonLdapAttributesMapperTest {

    @Test
    @DisplayName("mapFromAttributes populates every field from LDAP attributes")
    void mapsAllFields() throws NamingException {
        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("department", "HR Expatriados"));
        attrs.put(new BasicAttribute("mail", "maria@ing.com"));
        attrs.put(new BasicAttribute("manager", "CN=NT25YI,OU=E3"));
        attrs.put(new BasicAttribute("title", "CFO VIII"));
        attrs.put(new BasicAttribute("givenName", "Maria"));
        attrs.put(new BasicAttribute("sn", "Fuentes"));
        attrs.put(new BasicAttribute("sAMAccountName", "XT5ORI"));

        PersonLdap p = new PersonLdapAttributesMapper().mapFromAttributes(attrs);

        assertThat(p.getDepartment()).isEqualTo("HR Expatriados");
        assertThat(p.getMail()).isEqualTo("maria@ing.com");
        assertThat(p.getManager()).isEqualTo("CN=NT25YI,OU=E3");
        assertThat(p.getTitle()).isEqualTo("CFO VIII");
        assertThat(p.getGivenName()).isEqualTo("Maria");
        assertThat(p.getLastName()).isEqualTo("Fuentes");
        assertThat(p.getSamAccountName()).isEqualTo("XT5ORI");
    }

    @Test
    @DisplayName("mapFromAttributes tolerates missing attributes")
    void missingAttributesProduceNulls() throws NamingException {
        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put(new BasicAttribute("sAMAccountName", "XX"));

        PersonLdap p = new PersonLdapAttributesMapper().mapFromAttributes(attrs);

        assertThat(p.getSamAccountName()).isEqualTo("XX");
        assertThat(p.getDepartment()).isNull();
        assertThat(p.getMail()).isNull();
        assertThat(p.getManager()).isNull();
    }
}
