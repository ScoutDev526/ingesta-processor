package es.ing.icenterprise.arthur.core.ports.outbound;

import java.util.Objects;

/**
 * Represents the search criteria for an LDAP query.
 *
 * <p>This record encapsulates the base distinguished name (DN) and the LDAP filter used to perform
 * search operations.
 *
 * @param base the base DN from which the search should start (must not be {@code null})
 * @param criteria the LDAP filter expression defining the search conditions (must not be {@code
 *     null})
 */
public record SearchCriteria(String base, String criteria) {

    /**
     * Compact constructor that validates non-null values for {@code base} and {@code criteria}.
     *
     * @throws NullPointerException if {@code base} or {@code criteria} is {@code null}
     */
    public SearchCriteria {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(criteria, "criteria must not be null");
    }
}
