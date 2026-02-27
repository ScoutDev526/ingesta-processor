package es.ing.icenterprise.arthur.core.ports.outbound;

import java.nio.file.Path;
import java.util.List;

/**
 * Returns the list of paths where YAML job definitions are located.
 */
public interface YamlScannerPort {
    List<Path> scanJobDefinitions();
}
