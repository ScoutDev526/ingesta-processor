package es.ing.icenterprise.arthur.adapters.inbound.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.inbound.ExtractDataHrUseCase;
import java.util.List;
import javax.naming.directory.SearchControls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.NameClassPairCallbackHandler;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LdapQueryController.class)
class LdapQueryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ExtractDataHrUseCase extractDataHrUseCase;
    @MockBean PersonLdapMapper mapper;
    @MockBean ContextSource contextSource;
    @MockBean LdapTemplate ldapTemplate;

    @Test
    @DisplayName("GET /api/ingesta/ldap/hr returns the list of PersonLdapResponse")
    void getHrReturnsList() throws Exception {
        PersonLdap p = new PersonLdap();
        p.setSamAccountName("XT5ORI");
        PersonLdapResponse resp =
                new PersonLdapResponse("XT5ORI", "CN=NT25YI", "HR", "m@i.com", "Maria", "Fuentes", "CFO");

        when(extractDataHrUseCase.extractDataHr()).thenReturn(List.of(p));
        when(mapper.toResponseList(List.of(p))).thenReturn(List.of(resp));

        mockMvc.perform(get("/api/ingesta/ldap/hr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].samAccountName").value("XT5ORI"))
                .andExpect(jsonPath("$[0].department").value("HR"));
    }

    @Test
    @DisplayName("GET /api/ingesta/ldap/ok returns OK when LDAP is reachable")
    void okEndpointReturnsOk() throws Exception {
        doNothing()
                .when(ldapTemplate)
                .search(
                        anyString(),
                        anyString(),
                        any(SearchControls.class),
                        any(NameClassPairCallbackHandler.class));

        mockMvc.perform(get("/api/ingesta/ldap/ok").param("base", "DC=ad,DC=ing,DC=net"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    @Test
    @DisplayName("GET /api/ingesta/ldap/ok returns FAIL when LDAP fails")
    void okEndpointReturnsFail() throws Exception {
        doThrow(new RuntimeException("ldap down"))
                .when(ldapTemplate)
                .search(
                        anyString(),
                        anyString(),
                        any(SearchControls.class),
                        any(NameClassPairCallbackHandler.class));

        mockMvc.perform(get("/api/ingesta/ldap/ok").param("base", "DC=ad"))
                .andExpect(status().isOk())
                .andExpect(content().string("FAIL"));
    }
}
