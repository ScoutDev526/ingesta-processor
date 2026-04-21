package es.ing.icenterprise.arthur.core.domain.model;

import lombok.Data;

/**
 * Represents a person entry retrieved from an LDAP directory.
 *
 * <p>This class encapsulates common LDAP attributes for a user, such as account name, manager,
 * department, and personal details. Lombok's {@code @Data} annotation automatically generates
 * getters, setters, {@code toString()}, {@code equals()}, and {@code hashCode()} methods.
 *
 * <p><strong>Fields:</strong>
 *
 * <ul>
 *   <li>{@code samAccountName} - The unique SAM account name of the user.
 *   <li>{@code manager} - The distinguished name or identifier of the user's manager.
 *   <li>{@code department} - The department to which the user belongs.
 *   <li>{@code mail} - The user's email address.
 *   <li>{@code givenName} - The user's first name.
 *   <li>{@code lastName} - The user's last name.
 *   <li>{@code title} - The user's job title.
 * </ul>
 */
@Data
public class PersonLdap {
    private String samAccountName;
    private String manager;
    private String department;
    private String mail;
    private String givenName;
    private String lastName;
    private String title;
}
