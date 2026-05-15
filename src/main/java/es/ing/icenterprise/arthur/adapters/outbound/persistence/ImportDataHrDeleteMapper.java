package es.ing.icenterprise.arthur.adapters.outbound.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Deletes HR rows for a given day-string ("dd-MMM-yy") before the LDAP ingestion reinserts. */
@Mapper
public interface ImportDataHrDeleteMapper {

    /**
     * Delete by day string int.
     *
     * @param day the day
     * @return the int
     */
    @Delete(
            "DELETE FROM hr "
                    + "WHERE timestamp = TO_TIMESTAMP("
                    + "#{day}, "
                    + "'DD-MON-RR', "
                    + "'NLS_DATE_LANGUAGE=ENGLISH')")
    int deleteByDayString(@Param("day") String day);
}
