package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import es.ing.icenterprise.arthur.core.domain.model.ImportDataHrPerson;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Inserts HR rows produced by the LDAP ingestion into the CONTROLOFFICE.HR table. */
@Mapper
public interface ImportDataHrMapper {

    /**
     * Insert batch.
     *
     * @param p the person
     */
    @Insert(
            "INSERT INTO hr "
                    + "(id, name, mail, department, ckmanager, title, timestamp) "
                    + "VALUES "
                    + "(#{p.samAccountName}, #{p.fullName}, #{p.mail}, #{p.department}, "
                    + "#{p.manager}, #{p.title}, #{p.timestamp,jdbcType=TIMESTAMP})")
    int insertOne(@Param("p") ImportDataHrPerson p);
}
