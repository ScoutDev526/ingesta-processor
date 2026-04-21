package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.adapters.outbound.ldap.PersonLdapAttributesMapper;
import es.ing.icenterprise.arthur.core.domain.exception.LdapSearchException;
import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.inbound.ExtractDataHrUseCase;
import es.ing.icenterprise.arthur.core.ports.inbound.ImportDataHrUseCase;
import es.ing.icenterprise.arthur.core.ports.outbound.LdapRepository;
import es.ing.icenterprise.arthur.core.ports.outbound.SearchCriteria;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** The type Extract data hr service. */
@Service
@Slf4j
@AllArgsConstructor
public class ExtractDataHrService implements ExtractDataHrUseCase {

    private final LdapRepository ldapRepository;
    private final ImportDataHrUseCase importDataHrService;

    /**
     * Extracts HR-related data from multiple LDAP organizational units.
     *
     * <p>Performs searches in predefined OUs and aggregates the results into a single list.
     *
     * @return a combined list of {@link PersonLdap} objects retrieved from LDAP
     * @throws LdapSearchException if any LDAP search operation fails
     */
    @Override
    public List<PersonLdap> extractDataHr() {
        SearchCriteria searchCriteria1 =
                new SearchCriteria(
                        "OU=E3,OU=Production,OU=Users,OU=Users,OU=INGUsers,DC=ad,DC=ing,DC=net",
                        "(objectCategory=person)");

        SearchCriteria searchCriteria2 =
                new SearchCriteria(
                        "OU=D2,OU=Production,OU=Users,OU=Users,OU=INGUsers,DC=ad,DC=ing,DC=net",
                        "(&(objectCategory=person)(sAMAccountname=MP79FX))");

        SearchCriteria searchCriteria3 =
                new SearchCriteria(
                        "OU=E2,OU=Production,OU=Users,OU=Users,OU=INGUsers,DC=ad,DC=ing,DC=net",
                        "(objectCategory=person)");

        SearchCriteria searchCriteria4 =
                new SearchCriteria(
                        "OU=R2,OU=Production,OU=Users,OU=Users,OU=INGUsers,DC=ad,DC=ing,DC=net",
                        "(&(objectCategory=person)(sAMAccountname=WN73LB))");

        log.info("Ejecutando queries...");

        List<PersonLdap> resultE3 = safeSearch(searchCriteria1);
        log.info("Resultado query 1: {}", resultE3);

        List<PersonLdap> resultD2 = safeSearch(searchCriteria2);
        log.info("Resultado query 2: {}", resultD2);

        List<PersonLdap> resultE2 = safeSearch(searchCriteria3);
        log.info("Resultado query 3: {}", resultE2);

        List<PersonLdap> resultR2 = safeSearch(searchCriteria4);
        log.info("Resultado query 4: {}", resultR2);

        List<PersonLdap> combined = new ArrayList<>();
        combined.addAll(resultE3);
        combined.addAll(resultD2);
        combined.addAll(resultE2);
        combined.addAll(resultR2);

        importDataHrService.importDataHr(combined);

        return combined;
    }

    private List<PersonLdap> safeSearch(SearchCriteria criteria) {
        try {
            return ldapRepository.search(criteria, new PersonLdapAttributesMapper());
        } catch (LdapSearchException e) {
            log.warn(
                    "LDAP query fallo. base='{}' criteria='{}'. Motivo: {}",
                    criteria.base(),
                    criteria.criteria(),
                    e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn(
                    "LDAP query fallo con excepcion inesperada. base='{}' criteria='{}'. Motivo: {}",
                    criteria.base(),
                    criteria.criteria(),
                    e.toString());
            return Collections.emptyList();
        }
    }
}
