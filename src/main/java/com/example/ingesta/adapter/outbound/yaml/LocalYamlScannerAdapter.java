package com.example.ingesta.adapter.outbound.yaml;

import com.example.ingesta.core.port.outbound.YamlScannerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

@Component
public class LocalYamlScannerAdapter implements YamlScannerPort {

    private static final Logger log = LoggerFactory.getLogger(LocalYamlScannerAdapter.class);

    @Value("${ingesta.jobs.path:classpath:jobs}")
    private String jobsPath;

    @Override
    public List<Path> scanJobDefinitions() {
        try {
            Path directory = resolveJobsDirectory();
            log.info("Scanning for YAML definitions in: {}", directory);

            if (!Files.exists(directory)) {
                log.warn("Jobs directory does not exist: {}", directory);
                return List.of();
            }

            try (Stream<Path> paths = Files.walk(directory, 1)) {
                List<Path> yamlFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".yml") || name.endsWith(".yaml");
                        })
                        .sorted()
                        .toList();

                log.info("Found {} YAML file(s)", yamlFiles.size());
                return yamlFiles;
            }
        } catch (IOException e) {
            log.error("Error scanning YAML definitions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private Path resolveJobsDirectory() {
        if (jobsPath.startsWith("classpath:")) {
            String resourcePath = jobsPath.substring("classpath:".length());
            try {
                var resource = getClass().getClassLoader().getResource(resourcePath);
                if (resource != null) {
                    return Path.of(resource.toURI());
                }
            } catch (Exception e) {
                log.warn("Could not resolve classpath resource: {}", resourcePath);
            }
        }
        return Path.of(jobsPath);
    }
}
