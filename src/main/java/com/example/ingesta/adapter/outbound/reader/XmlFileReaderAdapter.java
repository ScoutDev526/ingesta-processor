package com.example.ingesta.adapter.outbound.reader;

import com.example.ingesta.core.domain.model.FileMetadata;
import com.example.ingesta.core.domain.model.FileType;
import com.example.ingesta.core.port.outbound.FileReaderPort;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class XmlFileReaderAdapter implements FileReaderPort {

    private static final Logger log = LoggerFactory.getLogger(XmlFileReaderAdapter.class);
    private final XmlMapper xmlMapper = new XmlMapper();

    @Override
    @SuppressWarnings("unchecked")
    public Stream<Map<String, Object>> read(Path filePath) {
        log.info("Reading XML file: {}", filePath);

        try {
            String content = Files.readString(filePath);
            Object result = xmlMapper.readValue(content, Object.class);

            List<Map<String, Object>> rows = new ArrayList<>();

            if (result instanceof Map<?, ?> map) {
                // If root contains a list, extract it
                for (Object value : map.values()) {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> itemMap) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                itemMap.forEach((k, v) -> row.put(k.toString(), v));
                                rows.add(row);
                            }
                        }
                    }
                }
                // If no list found, treat the map itself as a single row
                if (rows.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((k, v) -> row.put(k.toString(), v));
                    rows.add(row);
                }
            }

            log.info("Read {} rows from XML file", rows.size());
            return rows.stream();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read XML file: " + filePath, e);
        }
    }

    @Override
    public FileMetadata readFileMetadata(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            long records;
            try (Stream<Map<String, Object>> stream = read(filePath)) {
                records = stream.count();
            }
            return new FileMetadata(filePath.toString(), fileSize, records);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read metadata: " + filePath, e);
        }
    }

    @Override
    public boolean supports(FileType type) {
        return type == FileType.XML;
    }
}
