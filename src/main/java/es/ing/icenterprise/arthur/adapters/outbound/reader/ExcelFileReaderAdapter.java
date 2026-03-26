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
        return readInternal(filePath, 0);
    }

    @Override
    public Stream<Map<String, Object>> read(Path filePath, Map<String, Object> params) {
        int sheetIndex = ((Number) params.getOrDefault("sheetIndex", 0)).intValue();
        return readInternal(filePath, sheetIndex);
    }

    private Stream<Map<String, Object>> readInternal(Path filePath, int sheetIndex) {
        log.info("Reading Excel file: {} (sheet {})", filePath, sheetIndex);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);

            for (Row row : sheet) {
                Map<Integer, Object> rowData = new LinkedHashMap<>();
                for (Cell cell : row) {
                    rowData.put(cell.getColumnIndex(), extractCellValue(cell));
                }

                if (headers.isEmpty()) {
                    // Skip blank rows before the header row (title rows, empty leading rows, etc.)
                    boolean hasContent = rowData.values().stream()
                            .anyMatch(v -> v != null && !v.toString().isBlank());
                    if (!hasContent) {
                        log.debug("Skipping blank row before headers");
                        continue;
                    }

                    // First non-blank row: use as headers (null/blank cells get a positional fallback)
                    int maxCol = rowData.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                    for (int i = 0; i <= maxCol; i++) {
                        Object val = rowData.get(i);
                        String header = (val != null && !val.toString().isBlank())
                                ? val.toString().strip()
                                : "column_" + i;
                        headers.add(header);
                    }
                    log.debug("Headers detected (row skipped, {} columns): {}", headers.size(), headers);
                    continue;
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

        log.info("Read {} data rows from Excel file (sheet {})", rows.size(), sheetIndex);
        return rows.stream();
    }

    @Override
    public List<String> getSheetNames(Path filePath) {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            log.info("Found {} sheets in {}: {}", names.size(), filePath, names);
            return names;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read sheet names from: " + filePath, e);
        }
    }

    @Override
    public FileMetadata readFileMetadata(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            long records = countDataRows(filePath, 0);
            return new FileMetadata(filePath.toString(), fileSize, records);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file metadata: " + filePath, e);
        }
    }

    /** Counts data rows (excluding the header) in a single workbook pass — no double read. */
    private long countDataRows(Path filePath, int sheetIndex) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            long count = 0;
            boolean headerFound = false;
            for (Row row : sheet) {
                if (!headerFound) {
                    boolean hasContent = false;
                    for (Cell cell : row) {
                        Object val = extractCellValue(cell);
                        if (val != null && !val.toString().isBlank()) {
                            hasContent = true;
                            break;
                        }
                    }
                    if (hasContent) headerFound = true;
                } else {
                    count++;
                }
            }
            return count;
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
                    yield java.sql.Timestamp.valueOf(cell.getLocalDateTimeCellValue());
                }
                yield cell.getNumericCellValue();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            default      -> null;
        };
    }
}
