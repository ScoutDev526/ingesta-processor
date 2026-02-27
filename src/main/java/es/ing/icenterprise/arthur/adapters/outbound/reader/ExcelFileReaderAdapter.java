package es.ing.icenterprise.arthur.adapters.outbound.reader;

import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ExcelFileReaderAdapter implements FileReaderPort {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileReaderAdapter.class);

    @Override
    public Stream<Map<String, Object>> read(Path filePath) {
        log.info("Reading Excel file: {}", filePath);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                Map<Integer, Object> rowData = new LinkedHashMap<>();
                for (Cell cell : row) {
                    rowData.put(cell.getColumnIndex(), extractCellValue(cell));
                }

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

                Map<String, Object> mappedRow = new LinkedHashMap<>();
                for (Map.Entry<Integer, Object> entry : rowData.entrySet()) {
                    int idx = entry.getKey();
                    String header = idx < headers.size() ? headers.get(idx) : "column_" + idx;
                    mappedRow.put(header, entry.getValue());
                }
                rows.add(mappedRow);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read Excel file: " + filePath, e);
        }

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

    private Object extractCellValue(Cell cell) {
        if (cell == null) return null;

        CellType effectiveType = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        return switch (effectiveType) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                yield cell.getNumericCellValue();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            default      -> null;
        };
    }
}
