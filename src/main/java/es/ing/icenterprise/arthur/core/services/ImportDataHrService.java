package es.ing.icenterprise.arthur.core.services;

import es.ing.icenterprise.arthur.adapters.outbound.persistence.ImportDataHrDeleteMapper;
import es.ing.icenterprise.arthur.adapters.outbound.persistence.ImportDataHrMapper;
import es.ing.icenterprise.arthur.core.domain.model.ImportDataHrPerson;
import es.ing.icenterprise.arthur.core.domain.model.PersonLdap;
import es.ing.icenterprise.arthur.core.ports.inbound.ImportDataHrUseCase;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The type Import data hr service. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportDataHrService implements ImportDataHrUseCase {

    private final ImportDataHrDeleteMapper importDataHrDeleteMapper;
    private final ImportDataHrMapper importDataHrMapper;

    @Override
    @Transactional
    public void importDataHr(List<PersonLdap> ldapPersons) {
        LocalDateTime timestamp = LocalDate.now().atStartOfDay();

        log.info("Cleaning HR tables for {}...", timestamp);

        int deleted = importDataHrDeleteMapper.deleteByTimestamp(timestamp);

        log.info("Deleted {} rows from HR for timestamp {}", deleted, timestamp);

        log.info("Loading HR for {}...", timestamp);

        List<ImportDataHrPerson> hrRows =
                ldapPersons.stream().map(p -> toHrRowNormalized(p, timestamp)).toList();

        hrRows.stream().forEach(p -> importDataHrMapper.insertOne(p));

        log.info("{} rows loaded. Finished successfully.", hrRows.size());
    }

    private ImportDataHrPerson toHrRowNormalized(PersonLdap p, LocalDateTime timestamp) {
        String ck = cleanLdapValue(p.getSamAccountName()); // "XT5ORI"
        String dept = cleanLdapValue(p.getDepartment()); // "HR Expatriados Retail"
        String mail = cleanLdapValue(p.getMail()); // "Maria.Fuentes.Artola@ing.com"
        String title = cleanLdapValue(p.getTitle()); // "CFO VIII"
        String given = cleanLdapValue(p.getGivenName()); // "Maria"
        String last = cleanLdapValue(p.getLastName()); // "Fuentes Artola"
        String fullName = (given + " " + last).trim();

        // Manager comes as DN: "CN=NT25YI,OU=D2,ou=Production..."
        String managerDn = cleanLdapValue(p.getManager());
        String managerCk = extractCn(managerDn); // "NT25YI"

        return new ImportDataHrPerson(ck, fullName, mail, dept, managerCk, title, timestamp);
    }

    /** Quita prefijos tipo "mail: " o "sn: " etc. */
    private static String cleanLdapValue(String raw) {
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf(':');
        if (idx >= 0) {
            return raw.substring(idx + 1).trim();
        }
        return raw.trim();
    }

    /** Extrae CN=xxxxx de un DN. Si no hay CN, devuelve el string limpio. */
    private static String extractCn(String dn) {
        if (dn == null) {
            return null;
        }
        int cn = dn.indexOf("CN=");
        if (cn < 0) {
            return dn.trim();
        }
        int start = cn + 3;
        int end = dn.indexOf(',', start);
        return (end > start ? dn.substring(start, end) : dn.substring(start)).trim();
    }
}
