package com.example.ingesta.core.port.outbound;

import com.example.ingesta.core.domain.model.NotificationType;
import com.example.ingesta.core.domain.model.ProcessReport;

/**
 * Sends the ProcessReport to different notification channels.
 */
public interface NotificationPort {

    void notify(ProcessReport report, NotificationType type);
}
