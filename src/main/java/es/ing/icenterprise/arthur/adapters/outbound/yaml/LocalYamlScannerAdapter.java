package es.ing.icenterprise.arthur.adapters.outbound.yaml;

import es.ing.icenterprise.arthur.core.ports.outbound.YamlScannerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class LocalYamlScannerAdapter implements YamlScannerPort {

    private static final Logger log = LoggerFactory.getLogger(LocalYamlScannerAdapter.class);

    @Value("${ingesta.jobs.path:classpath:jobs}")
    private String jobsPath;

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Override
    public List<Path> scanJobDefinitions() {
        try {
            String pattern = buildPattern();
            log.info("Scanning for YAML definitions with pattern: {}", pattern);

            Resource[] resources = resolver.getResources(pattern);

            if (resources.length == 0) {
                log.warn("No YAML files found for pattern: {}", pattern);
                return List.of();
            }

            List<Path> yamlFiles = Arrays.stream(resources)
                    .map(r -> {
                        try {
                            return Path.of(r.getURI());
                        } catch (IOException e) {
                            log.warn("Could not resolve URI for resource: {}", r.getFilename());
                            return null;
                        }
                    })
                    .filter(p -> p != null)
                    .sorted()
                    .toList();

            log.info("Found {} YAML file(s)", yamlFiles.size());
            return yamlFiles;
        } catch (IOException e) {
            log.error("Error scanning YAML definitions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String buildPattern() {
        String base = jobsPath.endsWith("/") ? jobsPath : jobsPath + "/";
        return base + "*.{yml,yaml}";
    }
}
