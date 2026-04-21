package es.ing.icenterprise.arthur.core.domain.exception;

/**
 * Exception thrown when an error occurs during an LDAP search operation.
 *
 * <p>This runtime exception is typically used to indicate issues while querying LDAP directories,
 * such as invalid filters, connection problems, or unexpected search results.
 */
public class LdapSearchException extends RuntimeException {

    /**
     * Constructs a new {@code LdapSearchException} with the specified detail message.
     *
     * @param message the detail message describing the cause of the exception
     */
    public LdapSearchException(String message) {
        super(message);
    }
}
