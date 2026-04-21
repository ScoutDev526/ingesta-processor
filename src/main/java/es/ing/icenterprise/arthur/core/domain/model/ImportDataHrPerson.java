package es.ing.icenterprise.arthur.core.domain.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;

/** The type Import data hr person. */
@Data
@AllArgsConstructor
public class ImportDataHrPerson {
    private String samAccountName;
    private String fullName;
    private String mail;
    private String department;
    private String manager;
    private String title;
    private LocalDateTime timestamp;
}
