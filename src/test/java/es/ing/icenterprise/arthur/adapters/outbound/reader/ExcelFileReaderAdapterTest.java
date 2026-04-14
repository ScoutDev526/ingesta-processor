package es.ing.icenterprise.arthur.adapters.outbound.reader;

import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelFileReaderAdapterTest {

    private final ExcelFileReaderAdapter adapter = new ExcelFileReaderAdapter();

    @TempDir
    Path tempDir;

    // ── supports ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("supports returns true for EXCEL file type")
    void supportsExcelFileType() {
        assertThat(adapter.supports(FileType.EXCEL)).isTrue();
    }

    @Test
    @DisplayName("supports returns false for non-EXCEL file type")
    void supportsReturnsFalseForXml() {
        assertThat(adapter.supports(FileType.XML)).isFalse();
    }

    // ── read(Path) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("read returns one row per data row with correct header mapping")
    void readReturnsRowsWithHeaderMapping() throws IOException {
        Path file = createExcel("test.xlsx", List.of("Name", "Age"), List.of("Alice", "30"));

        List<Map<String, Object>> rows;
        try (Stream<Map<String, Object>> stream = adapter.read(file)) {
            rows = stream.toList();
        }

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Name", "Alice");
        // numeric cells come back as double
        assertThat(rows.get(0).get("Age")).isEqualTo(30.0);
    }

    @Test
    @DisplayName("read skips blank rows before the header")
    void readSkipsBlankRowsBeforeHeader() throws IOException {
        Path file = createExcelWithLeadingBlankRow("test2.xlsx");

        List<Map<String, Object>> rows;
        try (Stream<Map<String, Object>> stream = adapter.read(file)) {
            rows = stream.toList();
        }

        // One blank row before header, then one data row
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsKey("Col1");
    }

    @Test
    @DisplayName("read returns empty stream for file with header only (no data rows)")
    void readReturnsEmptyForHeaderOnlyFile() throws IOException {
        Path file = createExcelHeaderOnly("header-only.xlsx", List.of("A", "B", "C"));

        List<Map<String, Object>> rows;
        try (Stream<Map<String, Object>> stream = adapter.read(file)) {
            rows = stream.toList();
        }

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("read throws RuntimeException for non-existent file")
    void readThrowsForMissingFile() {
        Path missing = tempDir.resolve("nonexistent.xlsx");
        assertThatThrownBy(() -> adapter.read(missing).toList())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read Excel file");
    }

    // ── read(Path, Map) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("read with params reads the specified sheetIndex")
    void readWithParamsUsesSheetIndex() throws IOException {
        Path file = createExcelMultiSheet("multi.xlsx");

        // Sheet 0 has "Sheet0Col", sheet 1 has "Sheet1Col"
        List<Map<String, Object>> sheet1Rows;
        try (Stream<Map<String, Object>> stream = adapter.read(file, Map.of("sheetIndex", 1))) {
            sheet1Rows = stream.toList();
        }

        assertThat(sheet1Rows).hasSize(1);
        assertThat(sheet1Rows.get(0)).containsKey("Sheet1Col");
    }

    @Test
    @DisplayName("read with params defaults to sheet 0 when sheetIndex absent")
    void readWithParamsDefaultsToSheet0() throws IOException {
        Path file = createExcelMultiSheet("multi2.xlsx");

        List<Map<String, Object>> rows;
        try (Stream<Map<String, Object>> stream = adapter.read(file, Map.of())) {
            rows = stream.toList();
        }

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsKey("Sheet0Col");
    }

    // ── getSheetNames ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSheetNames returns all sheet names in order")
    void getSheetNamesReturnsAllSheets() throws IOException {
        Path file = createExcelMultiSheet("names.xlsx");

        List<String> names = adapter.getSheetNames(file);

        assertThat(names).containsExactly("Sheet0", "Sheet1");
    }

    @Test
    @DisplayName("getSheetNames throws RuntimeException for non-existent file")
    void getSheetNamesThrowsForMissingFile() {
        Path missing = tempDir.resolve("ghost.xlsx");
        assertThatThrownBy(() -> adapter.getSheetNames(missing))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read sheet names");
    }

    // ── readFileMetadata ──────────────────────────────────────────────────────

    @Test
    @DisplayName("readFileMetadata returns correct record count (excluding header)")
    void readFileMetadataReturnsCorrectRecordCount() throws IOException {
        Path file = createExcel("meta.xlsx", List.of("A", "B"),
                List.of("v1", "v2"), List.of("v3", "v4"), List.of("v5", "v6"));

        FileMetadata meta = adapter.readFileMetadata(file);

        assertThat(meta.records()).isEqualTo(3);
        assertThat(meta.filePath()).contains("meta.xlsx");
        assertThat(meta.fileSize()).isGreaterThan(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Creates a .xlsx with one header row and one or more data rows. */
    @SafeVarargs
    private Path createExcel(String fileName, List<String> headers, List<String>... dataRows)
            throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            // header
            Row hdr = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) hdr.createCell(i).setCellValue(headers.get(i));
            // data
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                List<String> cols = dataRows[r];
                for (int c = 0; c < cols.size(); c++) {
                    String val = cols.get(c);
                    try { row.createCell(c).setCellValue(Double.parseDouble(val)); }
                    catch (NumberFormatException e) { row.createCell(c).setCellValue(val); }
                }
            }
            Path path = tempDir.resolve(fileName);
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) { wb.write(fos); }
            return path;
        }
    }

    /** Creates a .xlsx with one leading blank row, then header "Col1" and one data row "val". */
    private Path createExcelWithLeadingBlankRow(String fileName) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("S");
            sheet.createRow(0); // blank
            sheet.createRow(1).createCell(0).setCellValue("Col1"); // header
            sheet.createRow(2).createCell(0).setCellValue("val"); // data
            Path path = tempDir.resolve(fileName);
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) { wb.write(fos); }
            return path;
        }
    }

    /** Creates a .xlsx with only a header row and no data. */
    private Path createExcelHeaderOnly(String fileName, List<String> headers) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("S");
            Row hdr = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) hdr.createCell(i).setCellValue(headers.get(i));
            Path path = tempDir.resolve(fileName);
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) { wb.write(fos); }
            return path;
        }
    }

    /**
     * Creates a .xlsx with two sheets:
     * - Sheet0: header "Sheet0Col" + one data row
     * - Sheet1: header "Sheet1Col" + one data row
     */
    private Path createExcelMultiSheet(String fileName) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (int s = 0; s < 2; s++) {
                Sheet sheet = wb.createSheet("Sheet" + s);
                sheet.createRow(0).createCell(0).setCellValue("Sheet" + s + "Col");
                sheet.createRow(1).createCell(0).setCellValue("data" + s);
            }
            Path path = tempDir.resolve(fileName);
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) { wb.write(fos); }
            return path;
        }
    }
}
