package es.ing.icenterprise.arthur.core.ports.inbound;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import java.util.List;

/** The interface Import data hr use case. */
public interface ImportDataHrUseCase {

    /**
     * Import data hr.
     *
     * @param ldapPersons the ldap persons
     */
    void importDataHr(List<PersonLdap> ldapPersons);
}
