package com.example.ingesta.core.port.inbound;

import com.example.ingesta.core.domain.model.ProcessReport;

public interface ExecuteProcessUseCase {

    ProcessReport execute(ExecuteCommand command);
}
