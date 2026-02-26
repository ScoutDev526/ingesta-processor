package es.ing.icenterprise.arthur.core.ports;

import es.ing.icenterprise.arthur.core.domain.model.Action;

import java.util.List;
import java.util.Map;

/**
 * Port for inserting/checking data against the target database.
 */
public interface PersistencePort {

    void insertData(List<Action> data, Map<String, Object> parameters);

    Object check(Object data, Map<String, Object> parameters);

    void truncate(Map<String, Object> parameters);
}
