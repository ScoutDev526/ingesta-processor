package com.example.ingesta.adapter.outbound.reader;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.example.ingesta.core.domain.model.FileMetadata;
import com.example.ingesta.core.domain.model.FileType;
import com.example.ingesta.core.port.outbound.FileReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class ExcelFileReaderAdapter implements FileReaderPort {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileReaderAdapter.class);

    @Override
    public Stream<Map<String, Object>> read(Path filePath) {
        log.info("Reading Excel file: {}", filePath);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        EasyExcel.read(filePath.toFile(), new PageReadListener<Map<Integer, Object>>(dataList -> {
            for (Map<Integer, Object> rowData : dataList) {
                if (headers.isEmpty()) {
                    // First row: use as headers if they look like strings
                    boolean allStrings = rowData.values().stream()
                            .allMatch(v -> v instanceof String);
                    if (allStrings) {
                        rowData.values().forEach(v -> headers.add(v.toString()));
                        continue;
                    } else {
                        // Generate default headers
                        for (int i = 0; i < rowData.size(); i++) {
                            headers.add("column_" + i);
                        }
                    }
                }

                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<Integer, Object> entry : rowData.entrySet()) {
                    int idx = entry.getKey();
                    String header = idx < headers.size() ? headers.get(idx) : "column_" + idx;
                    row.put(header, entry.getValue());
                }
                rows.add(row);
            }
        })).sheet().doRead();

        log.info("Read {} rows from Excel file", rows.size());
        return rows.stream();
    }

    @Override
    public FileMetadata readFileMetadata(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            // Quick count: read and count rows
            long records = 0;
            try (Stream<Map<String, Object>> stream = read(filePath)) {
                records = stream.count();
            }
            return new FileMetadata(filePath.toString(), fileSize, records);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file metadata: " + filePath, e);
        }
    }

    @Override
    public boolean supports(FileType type) {
        return type == FileType.EXCEL;
    }
}
