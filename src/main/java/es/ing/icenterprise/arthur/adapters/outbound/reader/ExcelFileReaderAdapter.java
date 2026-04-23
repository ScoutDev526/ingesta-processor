package es.ing.icenterprise.arthur.adapters.outbound.reader;

import com.github.pjfanning.xlsx.StreamingReader;
import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Component
public class ExcelFileReaderAdapter implements FileReaderPort {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileReaderAdapter.class);

    private static final int ROW_CACHE_SIZE = 100;
    private static final int BUFFER_SIZE = 4096;

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

        Workbook workbook = null;
        try {
            workbook = openStreamingWorkbook(filePath);
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Iterator<Row> rowIt = sheet.iterator();

            List<String> headers = readHeaders(rowIt);
            if (headers.isEmpty()) {
                workbook.close();
                log.info("Read 0 data rows from Excel file (sheet {})", sheetIndex);
                return Stream.empty();
            }
            log.debug("Headers detected ({} columns): {}", headers.size(), headers);

            final Workbook wbRef = workbook;
            Spliterator<Row> spliterator = Spliterators.spliteratorUnknownSize(
                    rowIt, Spliterator.ORDERED | Spliterator.NONNULL);
            return StreamSupport.stream(spliterator, false)
                    .map(row -> mapRowWithHeaders(row, headers))
                    .onClose(() -> closeQuietly(wbRef));
        } catch (Exception e) {
            closeQuietly(workbook);
            throw new RuntimeException("Failed to read Excel file: " + filePath, e);
        }
    }

    @Override
    public List<String> getSheetNames(Path filePath) {
        try (Workbook workbook = openStreamingWorkbook(filePath)) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }
            log.info("Found {} sheets in {}: {}", names.size(), filePath, names);
            return names;
        } catch (Exception e) {
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
        try (Workbook workbook = openStreamingWorkbook(filePath)) {
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

    private Workbook openStreamingWorkbook(Path filePath) throws IOException {
        return StreamingReader.builder()
                .rowCacheSize(ROW_CACHE_SIZE)
                .bufferSize(BUFFER_SIZE)
                .open(filePath.toFile());
    }

    /**
     * Advances the iterator through blank rows and captures the first non-blank row as headers.
     * Preserves the original behaviour: missing/blank header cells get the positional fallback
     * {@code column_i}, and leading blank rows are skipped silently.
     */
    private List<String> readHeaders(Iterator<Row> rowIt) {
        while (rowIt.hasNext()) {
            Map<Integer, Object> rowData = rowToIndexedMap(rowIt.next());
            boolean hasContent = rowData.values().stream()
                    .anyMatch(v -> v != null && !v.toString().isBlank());
            if (!hasContent) {
                log.debug("Skipping blank row before headers");
                continue;
            }

            int maxCol = rowData.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            List<String> headers = new ArrayList<>(maxCol + 1);
            for (int i = 0; i <= maxCol; i++) {
                Object val = rowData.get(i);
                String header = (val != null && !val.toString().isBlank())
                        ? val.toString().strip()
                        : "column_" + i;
                headers.add(header);
            }
            return headers;
        }
        return List.of();
    }

    private Map<Integer, Object> rowToIndexedMap(Row row) {
        Map<Integer, Object> rowData = new LinkedHashMap<>();
        for (Cell cell : row) {
            rowData.put(cell.getColumnIndex(), extractCellValue(cell));
        }
        return rowData;
    }

    private Map<String, Object> mapRowWithHeaders(Row row, List<String> headers) {
        Map<Integer, Object> rowData = rowToIndexedMap(row);
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : rowData.entrySet()) {
            int idx = entry.getKey();
            String header = idx < headers.size() ? headers.get(idx) : "column_" + idx;
            mapped.put(header, entry.getValue());
        }
        return mapped;
    }

    private void closeQuietly(Workbook workbook) {
        if (workbook == null) return;
        try {
            workbook.close();
        } catch (IOException e) {
            log.warn("Failed to close Excel workbook: {}", e.getMessage());
        }
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
