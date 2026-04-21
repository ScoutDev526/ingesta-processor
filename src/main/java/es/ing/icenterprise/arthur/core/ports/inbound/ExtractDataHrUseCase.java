package es.ing.icenterprise.arthur.core.ports.inbound;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.util.List;

/**
 * Use case interface for extracting HR-related data from an LDAP directory.
 *
 * <p>This interface defines the contract for retrieving a list of LDAP person entries that contain
 * HR information such as department, manager, and personal details.
 */
public interface ExtractDataHrUseCase {

    /**
     * Extracts HR-related data from the LDAP directory.
     *
     * @return a list of {@link PersonLdap} objects representing LDAP entries with HR information
     */
    List<PersonLdap> extractDataHr();
}
