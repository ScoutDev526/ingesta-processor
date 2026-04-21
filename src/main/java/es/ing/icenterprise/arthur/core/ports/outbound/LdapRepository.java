package es.ing.icenterprise.arthur.core.ports.outbound;

import java.util.List;
import org.springframework.ldap.core.AttributesMapper;

/**
 * Repository interface for performing LDAP search operations.
 *
 * <p>This interface defines methods for querying LDAP directories using a given {@link
 * SearchCriteria} and mapping the results to domain objects via {@link AttributesMapper}.
 */
public interface LdapRepository {

    /**
     * Searches the LDAP directory based on the provided criteria and maps the results.
     *
     * @param <T> the type of object to map LDAP attributes to
     * @param criteria the search criteria specifying filters and base DN
     * @param mapper the mapper used to convert LDAP attributes into objects of type {@code T}
     * @return a list of mapped objects matching the search criteria
     */
    <T> List<T> search(SearchCriteria criteria, AttributesMapper<T> mapper);

    /**
     * Searches the LDAP directory for a single entry based on the provided criteria and maps it.
     *
     * @param <T> the type of object to map LDAP attributes to
     * @param criteria the search criteria specifying filters and base DN
     * @param mapper the mapper used to convert LDAP attributes into an object of type {@code T}
     * @return the mapped object if found, or {@code null} if no entry matches the criteria
     */
    <T> T searchOne(SearchCriteria criteria, AttributesMapper<T> mapper);
}
