package es.ing.icenterprise.arthur.adapters.inbound.rest;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.inbound.ExtractDataHrUseCase;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.directory.SearchControls;
import org.springframework.ldap.core.NameClassPairCallbackHandler;

/**
 * REST controller for handling LDAP queries related to HR data.
 *
 * <p>Exposes endpoints to retrieve HR-related information from LDAP and return it as REST-friendly
 * DTOs.
 *
 * <p><strong>Endpoint:</strong>
 *
 * <ul>
 *   <li>{@code GET /api/ingesta/ldap/hr} - Retrieves HR data from LDAP and returns a list of
 *       responses.
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ingesta/ldap")
@AllArgsConstructor
public class LdapQueryController {

    private final ExtractDataHrUseCase extractDataHrUseCase;
    private final PersonLdapMapper mapper;
    private final ContextSource contextSource;
    private final LdapTemplate ldapTemplate;

    /**
     * Retrieves HR-related LDAP data and returns it as a list of {@link PersonLdapResponse} DTOs.
     *
     * @return a {@link ResponseEntity} containing the list of LDAP responses
     */
    @GetMapping("/hr")
    public ResponseEntity<List<PersonLdapResponse>> search() {
        if (contextSource instanceof LdapContextSource lcs) {
            log.info("LDAP URL: {}", String.join(", ", lcs.getUrls()));
            log.info("Base DN: {}", lcs.getBaseLdapPathAsString());
        }

        List<PersonLdap> result = extractDataHrUseCase.extractDataHr();
        List<PersonLdapResponse> response = mapper.toResponseList(result);
        return ResponseEntity.ok().body(response);
    }

    @GetMapping("/ok")
    public ResponseEntity<String> searchOk(@RequestParam String base) {
        return ResponseEntity.ok().body(isLdapHealthy(base) ? "OK" : "FAIL");
    }

    private boolean isLdapHealthy(String base) {
        try {
            String filter = "(objectClass=*)";
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            NameClassPairCallbackHandler handler = nameClassPair -> {};
            ldapTemplate.search(base, filter, controls, handler);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
