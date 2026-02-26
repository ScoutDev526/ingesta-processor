package es.ing.icenterprise.arthur.core.ports;

import es.ing.icenterprise.arthur.core.domain.enums.NotificationType;
import es.ing.icenterprise.arthur.core.domain.model.ProcessReport;

/**
 * Sends the ProcessReport to different notification channels.
 */
public interface NotificationPort {

    void notify(ProcessReport report, NotificationType type);
}
