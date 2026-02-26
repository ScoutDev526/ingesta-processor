package com.example.ingesta.core.port.outbound;

import java.nio.file.Path;
import java.util.List;

/**
 * Returns the list of paths where YAML job definitions are located.
 */
public interface YamlScannerPort {
    List<Path> scanJobDefinitions();
}
