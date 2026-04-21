package es.ing.icenterprise.arthur.adapters.inbound.rest;

/**
 * Data Transfer Object (DTO) representing the LDAP response for a person.
 *
 * <p>This record is typically used in REST responses to expose selected LDAP attributes.
 *
 * @param mail the email address of the person retrieved from LDAP
 */
public record PersonLdapResponse(
        String samAccountName,
        String manager,
        String department,
        String mail,
        String givenName,
        String lastName,
        String title) {}
