package es.ing.icenterprise.arthur.adapters.inbound.rest;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mapper component for converting {@link PersonLdap} domain objects to REST response DTOs.
 *
 * <p>This class provides methods to transform LDAP domain models into {@link PersonLdapResponse}
 * objects for use in the REST layer.
 */
@Component
public class PersonLdapMapper {

    /**
     * Converts a list of {@link PersonLdap} objects into a list of {@link PersonLdapResponse} DTOs.
     *
     * @param people the list of LDAP domain objects to convert
     * @return a list of {@link PersonLdapResponse} objects
     */
    public List<PersonLdapResponse> toResponseList(List<PersonLdap> people) {
        return people.stream().map(this::toResponse).toList();
    }

    /**
     * Converts a {@link PersonLdap} object into a {@link PersonLdapResponse}.
     *
     * @param personLdap the LDAP domain object to convert
     * @return a {@link PersonLdapResponse} containing the mapped data
     */
    public PersonLdapResponse toResponse(PersonLdap personLdap) {
        return new PersonLdapResponse(
                personLdap.getSamAccountName(),
                personLdap.getManager(),
                personLdap.getDepartment(),
                personLdap.getMail(),
                personLdap.getGivenName(),
                personLdap.getLastName(),
                personLdap.getTitle());
    }
}
