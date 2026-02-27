package es.ing.icenterprise.arthur.core.ports.inbound;

import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;

public interface ExecuteProcessUseCase {

    ProcessReport execute(ExecuteCommand command);
}
