package es.ing.icenterprise.arthur.adapters.outbound.ldap;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import org.springframework.ldap.core.AttributesMapper;

/**
 * Maps LDAP {@link Attributes} to a {@link PersonLdap} domain object.
 *
 * <p>This mapper is used by Spring LDAP to convert raw LDAP attributes into a structured {@link
 * PersonLdap} instance. It extracts fields such as department, email, manager, and personal details
 * from the LDAP entry.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>
 * ldapRepository.search(criteria, new PersonLdapAttributesMapper());
 * </pre>
 */
public class PersonLdapAttributesMapper implements AttributesMapper<PersonLdap> {

    /**
     * Converts LDAP attributes into a {@link PersonLdap} object.
     *
     * @param attributes the LDAP attributes to map
     * @return a {@link PersonLdap} instance populated with values from the LDAP entry
     */
    @Override
    public PersonLdap mapFromAttributes(Attributes attributes) throws NamingException {
        PersonLdap personLdap = new PersonLdap();
        personLdap.setDepartment(safeString(attributes, "department"));
        personLdap.setMail(safeString(attributes, "mail"));
        personLdap.setManager(safeString(attributes, "manager"));
        personLdap.setTitle(safeString(attributes, "title"));
        personLdap.setGivenName(safeString(attributes, "givenName"));
        personLdap.setLastName(safeString(attributes, "sn"));
        personLdap.setSamAccountName(safeString(attributes, "sAMAccountName"));
        return personLdap;
    }

    private static String safeString(Attributes attributes, String name) throws NamingException {
        if (attributes.get(name) == null) {
            return null;
        }
        Object value = attributes.get(name).get();
        return value == null ? null : value.toString();
    }
}
