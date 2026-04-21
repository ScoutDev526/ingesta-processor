package es.ing.icenterprise.arthur.adapters.outbound.ldap;

import es.ing.icenterprise.arthur.core.ports.outbound.LdapRepository;
import es.ing.icenterprise.arthur.core.ports.outbound.SearchCriteria;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;

/**
 * Adapter implementation of {@link LdapRepository} that uses Spring LDAP's {@link LdapTemplate} to
 * perform LDAP queries.
 *
 * <p>This class acts as an outbound adapter in a hexagonal architecture, bridging the domain layer
 * with the LDAP infrastructure.
 *
 * <p><strong>Responsibilities:</strong>
 *
 * <ul>
 *   <li>Executes LDAP searches using {@link LdapTemplate}.
 *   <li>Maps LDAP entries to domain objects via {@link AttributesMapper}.
 *   <li>Handles scenarios where no entries or multiple entries are found.
 * </ul>
 */
@Service
@AllArgsConstructor
public class LdapQueryAdapter implements LdapRepository {

    private final LdapTemplate ldapTemplate;

    /** Exception thrown when no LDAP entry matches the search criteria. */
    private static class LdapEntryNotFoundException extends RuntimeException {
        public LdapEntryNotFoundException(String s) {
            super(s);
        }
    }

    /** Exception thrown when multiple LDAP entries match the search criteria. */
    private static class LdapMultipleEntriesException extends RuntimeException {
        public LdapMultipleEntriesException(String s) {
            super(s);
        }
    }

    /**
     * Searches the LDAP directory based on the provided criteria and maps the results.
     *
     * @param <T> the type of object to map LDAP attributes to
     * @param searchCriteria the search criteria specifying base DN and filter
     * @param mapper the mapper used to convert LDAP attributes into objects of type {@code T}
     * @return a list of mapped objects matching the search criteria
     */
    @Override
    public <T> List<T> search(SearchCriteria searchCriteria, AttributesMapper<T> mapper) {
        return ldapTemplate.search(
                LdapQueryBuilder.query().base(searchCriteria.base()).filter(searchCriteria.criteria()),
                mapper);
    }

    /**
     * Searches the LDAP directory for a single entry based on the provided criteria and maps it.
     *
     * @param <T> the type of object to map LDAP attributes to
     * @param searchCriteria the search criteria specifying base DN and filter
     * @param mapper the mapper used to convert LDAP attributes into an object of type {@code T}
     * @return the mapped object if found
     * @throws LdapEntryNotFoundException if no entry matches the criteria
     * @throws LdapMultipleEntriesException if multiple entries match the criteria
     */
    @Override
    public <T> T searchOne(SearchCriteria searchCriteria, AttributesMapper<T> mapper) {
        List<T> results = search(searchCriteria, mapper);

        if (results.isEmpty()) {
            throw new LdapEntryNotFoundException(
                    "No entry found for criteria: " + searchCriteria.criteria());
        }

        if (results.size() > 1) {
            throw new LdapMultipleEntriesException(
                    "Multiple entries found for criteria: " + searchCriteria.criteria());
        }

        return results.get(0);
    }
}
