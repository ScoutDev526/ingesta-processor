package es.ing.icenterprise.arthur.adapters.outbound.reader;

import es.ing.icenterprise.arthur.core.domain.enums.FileType;
import es.ing.icenterprise.arthur.core.domain.model.FileMetadata;
import es.ing.icenterprise.arthur.core.ports.outbound.FileReaderPort;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * XLSX reader that parses sheets with POI's SAX-based {@link XSSFReader} and exposes rows
 * through a lazy {@link Stream}. Memory footprint stays bounded by the StAX cursor and the
 * shared-strings table — the full workbook DOM is never materialised.
 */
@Component
public class ExcelFileReaderAdapter implements FileReaderPort {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileReaderAdapter.class);

    private static final XMLInputFactory XML_FACTORY;
    static {
        XMLInputFactory f = XMLInputFactory.newInstance();
        // Defence-in-depth against XXE — xlsx sheet XML never needs DTDs or external entities.
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XML_FACTORY = f;
    }

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

        OPCPackage pkg = null;
        InputStream sheetStream = null;
        XMLStreamReader xml = null;
        try {
            pkg = OPCPackage.open(filePath.toFile(), PackageAccess.READ);
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings sst = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();

            sheetStream = openSheetStream(reader, sheetIndex);
            xml = XML_FACTORY.createXMLStreamReader(sheetStream);

            Iterator<Map<Integer, Object>> rowIt = new StaxRowIterator(xml, sst, styles);

            List<String> headers = readHeaders(rowIt);
            if (headers.isEmpty()) {
                closeQuietly(xml, sheetStream, pkg);
                log.info("Read 0 data rows from Excel file (sheet {})", sheetIndex);
                return Stream.empty();
            }
            log.debug("Headers detected ({} columns): {}", headers.size(), headers);

            final OPCPackage pkgRef = pkg;
            final InputStream streamRef = sheetStream;
            final XMLStreamReader xmlRef = xml;
            Spliterator<Map<Integer, Object>> sp = Spliterators.spliteratorUnknownSize(
                    rowIt, Spliterator.ORDERED | Spliterator.NONNULL);
            return StreamSupport.stream(sp, false)
                    .map(row -> mapRowWithHeaders(row, headers))
                    .onClose(() -> closeQuietly(xmlRef, streamRef, pkgRef));
        } catch (Exception e) {
            closeQuietly(xml, sheetStream, pkg);
            throw new RuntimeException("Failed to read Excel file: " + filePath, e);
        }
    }

    @Override
    public List<String> getSheetNames(Path filePath) {
        try (OPCPackage pkg = OPCPackage.open(filePath.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            List<String> names = new ArrayList<>();
            while (it.hasNext()) {
                try (InputStream s = it.next()) {
                    names.add(it.getSheetName());
                }
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

    /** Counts data rows (excluding the header) in a single streaming pass. */
    private long countDataRows(Path filePath, int sheetIndex) {
        try (Stream<Map<String, Object>> stream = readInternal(filePath, sheetIndex)) {
            return stream.count();
        }
    }

    @Override
    public boolean supports(FileType type) {
        return type == FileType.EXCEL;
    }

    /** Advances {@link XSSFReader.SheetIterator} to the requested index and returns that sheet's XML stream. */
    private InputStream openSheetStream(XSSFReader reader, int sheetIndex) throws IOException {
        try {
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            int i = 0;
            while (it.hasNext()) {
                InputStream s = it.next();
                if (i == sheetIndex) return s;
                s.close();
                i++;
            }
            throw new IllegalArgumentException("Sheet index " + sheetIndex + " out of range (found " + i + " sheets)");
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IOException("Invalid xlsx format", e);
        }
    }

    /**
     * Preserves the original header-detection rules verbatim:
     * <ul>
     *   <li>Skip leading rows that are entirely blank.</li>
     *   <li>Treat the first non-blank row as headers.</li>
     *   <li>For columns {@code 0..maxCol}, fall back to {@code column_i} when the header cell is null/blank.</li>
     * </ul>
     */
    private List<String> readHeaders(Iterator<Map<Integer, Object>> rowIt) {
        while (rowIt.hasNext()) {
            Map<Integer, Object> rowData = rowIt.next();
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

    private Map<String, Object> mapRowWithHeaders(Map<Integer, Object> rowData, List<String> headers) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<Integer, Object> entry : rowData.entrySet()) {
            int idx = entry.getKey();
            String header = idx < headers.size() ? headers.get(idx) : "column_" + idx;
            mapped.put(header, entry.getValue());
        }
        return mapped;
    }

    private void closeQuietly(XMLStreamReader xml, InputStream stream, OPCPackage pkg) {
        if (xml != null) {
            try { xml.close(); } catch (XMLStreamException e) { log.warn("Failed to close XML reader: {}", e.getMessage()); }
        }
        if (stream != null) {
            try { stream.close(); } catch (IOException e) { log.warn("Failed to close sheet stream: {}", e.getMessage()); }
        }
        if (pkg != null) {
            try { pkg.close(); } catch (IOException e) { log.warn("Failed to close OPC package: {}", e.getMessage()); }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    //  Lazy StAX → row iterator. Pull-based so Stream consumers drive parsing.
    // ────────────────────────────────────────────────────────────────────────────

    private static final class StaxRowIterator implements Iterator<Map<Integer, Object>> {

        private final XMLStreamReader xml;
        private final SharedStrings sst;
        private final StylesTable styles;
        private Map<Integer, Object> nextRow;
        private boolean exhausted;

        StaxRowIterator(XMLStreamReader xml, SharedStrings sst, StylesTable styles) {
            this.xml = xml;
            this.sst = sst;
            this.styles = styles;
        }

        @Override
        public boolean hasNext() {
            if (nextRow != null) return true;
            if (exhausted) return false;
            nextRow = readNextRow();
            if (nextRow == null) exhausted = true;
            return nextRow != null;
        }

        @Override
        public Map<Integer, Object> next() {
            if (!hasNext()) throw new NoSuchElementException();
            Map<Integer, Object> r = nextRow;
            nextRow = null;
            return r;
        }

        private Map<Integer, Object> readNextRow() {
            try {
                while (xml.hasNext()) {
                    int evt = xml.next();
                    if (evt == XMLStreamConstants.START_ELEMENT && "row".equals(xml.getLocalName())) {
                        return parseRow();
                    }
                }
                return null;
            } catch (XMLStreamException e) {
                throw new RuntimeException("Failed to parse sheet XML", e);
            }
        }

        private Map<Integer, Object> parseRow() throws XMLStreamException {
            Map<Integer, Object> row = new LinkedHashMap<>();
            while (xml.hasNext()) {
                int evt = xml.next();
                if (evt == XMLStreamConstants.START_ELEMENT && "c".equals(xml.getLocalName())) {
                    parseCell(row);
                } else if (evt == XMLStreamConstants.END_ELEMENT && "row".equals(xml.getLocalName())) {
                    return row;
                }
            }
            return row;
        }

        /**
         * Consumes a {@code <c>} element and, if it carries a value, adds the typed result to {@code row}.
         * Understands: shared strings ({@code t="s"}), inline strings ({@code t="inlineStr"}),
         * formula string results ({@code t="str"}), booleans ({@code t="b"}), errors ({@code t="e"}),
         * and numeric (default / {@code t="n"}) including date detection via the style table.
         * For formula cells we read the cached {@code <v>} value and ignore the {@code <f>} formula — same
         * semantics as the previous {@code cell.getCachedFormulaResultType()} path.
         */
        private void parseCell(Map<Integer, Object> row) throws XMLStreamException {
            String cellRef = xml.getAttributeValue(null, "r");
            String cellType = xml.getAttributeValue(null, "t");
            String styleStr = xml.getAttributeValue(null, "s");
            int styleIdx = styleStr != null ? Integer.parseInt(styleStr) : -1;
            int col = cellRef != null ? columnFromRef(cellRef) : -1;

            String rawValue = null;
            StringBuilder inlineBuf = null;

            while (xml.hasNext()) {
                int evt = xml.next();
                if (evt == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName();
                    if ("v".equals(name)) {
                        rawValue = xml.getElementText();
                    } else if ("t".equals(name)) {
                        // Text nodes only appear inside <is> (inline strings) within a <c>.
                        if (inlineBuf == null) inlineBuf = new StringBuilder();
                        inlineBuf.append(xml.getElementText());
                    }
                    // Other children (<f>, <is>, <r>, <rPh>) are traversed but their value children above handle what we need.
                } else if (evt == XMLStreamConstants.END_ELEMENT && "c".equals(xml.getLocalName())) {
                    break;
                }
            }

            if (col < 0) return;

            Object extracted;
            if ("inlineStr".equals(cellType)) {
                extracted = inlineBuf != null && inlineBuf.length() > 0 ? inlineBuf.toString() : null;
            } else {
                extracted = extractTypedValue(cellType, styleIdx, rawValue);
            }
            if (extracted != null) row.put(col, extracted);
        }

        private Object extractTypedValue(String type, int styleIdx, String raw) {
            if (raw == null || raw.isEmpty()) return null;
            if ("s".equals(type)) {
                int idx = Integer.parseInt(raw);
                return sst.getItemAt(idx).getString();
            }
            if ("str".equals(type)) return raw;
            if ("b".equals(type)) return !"0".equals(raw);
            if ("e".equals(type)) return null;

            // Default / "n": numeric. Apply date detection from the style.
            double num;
            try {
                num = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return null;
            }
            if (styleIdx >= 0 && isDateStyle(styleIdx)) {
                return Timestamp.valueOf(DateUtil.getLocalDateTime(num));
            }
            return num;
        }

        private boolean isDateStyle(int styleIdx) {
            try {
                XSSFCellStyle style = styles.getStyleAt(styleIdx);
                if (style == null) return false;
                short formatIdx = style.getDataFormat();
                String formatStr = style.getDataFormatString();
                return DateUtil.isADateFormat(formatIdx, formatStr);
            } catch (Exception e) {
                return false;
            }
        }

        /** Translates an Excel cell reference like {@code "AA12"} into the zero-based column index. */
        private static int columnFromRef(String ref) {
            int col = 0;
            for (int i = 0; i < ref.length(); i++) {
                char c = ref.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    col = col * 26 + (c - 'A' + 1);
                } else {
                    break;
                }
            }
            return col - 1;
        }
    }
}
