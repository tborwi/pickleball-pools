package org.pickleballpools.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tiny Excel (.xlsx) writer: text and number cells, a few fixed styles, column widths, row heights, merged cells
 * and print setup. Enough for printable tournament sheets, with no library.
 */
public final class Xlsx {
    // Cell styles (index into styles.xml cellXfs)
    public static final int PLAIN = 0, TITLE = 1, HEADER = 2, BOX = 3, YELLOW = 4, BOLD_BOX = 5, LEFT_BOX = 6,
            NAME_BOX = 7, GREY = 8, NOTE = 9, BOLD = 10;

    public static final class Sheet {
        final String name;
        final TreeMap<Integer, TreeMap<Integer, Object[]>> rows = new TreeMap<>(); // row -> col -> {value, style}
        final Map<Integer, Double> widths = new TreeMap<>(), heights = new TreeMap<>();
        final List<String> merges = new ArrayList<>();
        public boolean landscape;

        Sheet(String name) { this.name = name; }

        /** Sets a cell (0-based row and column). value is a String, a Number or null (empty, styled). */
        public Sheet set(int row, int col, Object value, int style) {
            rows.computeIfAbsent(row, k -> new TreeMap<>()).put(col, new Object[]{value, style});
            return this;
        }

        public Sheet width(int col, double chars) { widths.put(col, chars); return this; }

        public Sheet height(int row, double points) { heights.put(row, points); return this; }

        /** Merges a block and styles every cell in it (so borders show all around). */
        public Sheet merge(int r1, int c1, int r2, int c2, Object value, int style) {
            for (int r = r1; r <= r2; r++) for (int c = c1; c <= c2; c++) set(r, c, r == r1 && c == c1 ? value : null, style);
            if (r1 != r2 || c1 != c2) merges.add(ref(r1, c1) + ":" + ref(r2, c2));
            return this;
        }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    /** Adds a sheet; the name is cleaned up and made unique (Excel: 31 characters, no []:*?/\). */
    public Sheet sheet(String name) {
        String n = name.replaceAll("[\\[\\]:*?/\\\\]", "-").trim();
        if (n.isEmpty()) n = "Sheet";
        if (n.length() > 28) n = n.substring(0, 28);
        String base = n;
        for (int i = 2; exists(n); i++) n = base + " " + i;
        Sheet s = new Sheet(n);
        sheets.add(s);
        return s;
    }

    private boolean exists(String n) {
        for (Sheet s : sheets) if (s.name.equalsIgnoreCase(n)) return true;
        return false;
    }

    public static String ref(int row, int col) {
        StringBuilder s = new StringBuilder();
        for (int c = col + 1; c > 0; c = (c - 1) / 26) s.insert(0, (char) ('A' + (c - 1) % 26));
        return s.append(row + 1).toString();
    }

    public void write(OutputStream os) throws IOException {
        ZipOutputStream z = new ZipOutputStream(os);
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        for (int i = 1; i <= sheets.size(); i++) {
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            wb.append("<sheet name=\"").append(esc(sheets.get(i - 1).name)).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        put(z, "[Content_Types].xml", ct.append("</Types>").toString());
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        put(z, "xl/workbook.xml", wb.append("</sheets></workbook>").toString());
        put(z, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
        put(z, "xl/styles.xml", STYLES);
        for (int i = 0; i < sheets.size(); i++) put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
        z.finish();
    }

    private static String sheetXml(Sheet s) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        if (!s.widths.isEmpty()) {
            x.append("<cols>");
            for (Map.Entry<Integer, Double> w : s.widths.entrySet())
                x.append("<col min=\"").append(w.getKey() + 1).append("\" max=\"").append(w.getKey() + 1)
                        .append("\" width=\"").append(w.getValue()).append("\" customWidth=\"1\"/>");
            x.append("</cols>");
        }
        x.append("<sheetData>");
        TreeMap<Integer, TreeMap<Integer, Object[]>> rows = new TreeMap<>(s.rows);
        for (Integer r : s.heights.keySet()) rows.putIfAbsent(r, new TreeMap<>());
        for (Map.Entry<Integer, TreeMap<Integer, Object[]>> row : rows.entrySet()) {
            int r = row.getKey();
            x.append("<row r=\"").append(r + 1).append('"');
            Double h = s.heights.get(r);
            if (h != null) x.append(" ht=\"").append(h).append("\" customHeight=\"1\"");
            x.append('>');
            for (Map.Entry<Integer, Object[]> c : row.getValue().entrySet()) {
                Object v = c.getValue()[0];
                x.append("<c r=\"").append(ref(r, c.getKey())).append("\" s=\"").append(c.getValue()[1]).append('"');
                if (v instanceof Number) x.append("><v>").append(v).append("</v></c>");
                else if (v != null && !v.toString().isEmpty())
                    x.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(v.toString())).append("</t></is></c>");
                else x.append("/>");
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        if (!s.merges.isEmpty()) {
            x.append("<mergeCells count=\"").append(s.merges.size()).append("\">");
            for (String m : s.merges) x.append("<mergeCell ref=\"").append(m).append("\"/>");
            x.append("</mergeCells>");
        }
        x.append("<printOptions horizontalCentered=\"1\"/>")
                .append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup orientation=\"").append(s.landscape ? "landscape" : "portrait")
                .append("\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>");
        return x.toString();
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '&') b.append("&amp;");
            else if (ch == '<') b.append("&lt;");
            else if (ch == '>') b.append("&gt;");
            else if (ch == '"') b.append("&quot;");
            else if (ch < 0x20 && ch != '\n' && ch != '\t') b.append(' ');
            else b.append(ch);
        }
        return b.toString();
    }

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"4\">"
            + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"13\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFFF00\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFBFBFBF\"/></patternFill></fill></fills>"
            + "<borders count=\"3\"><border/>"
            + "<border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/></border>"
            + "<border><left style=\"medium\"/><right style=\"medium\"/><top style=\"medium\"/><bottom style=\"medium\"/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"11\">"
            + "<xf/>"                                                                                                       // PLAIN
            + "<xf fontId=\"2\" applyFont=\"1\"/>"                                                                          // TITLE
            + "<xf fontId=\"1\" borderId=\"2\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" // HEADER
            + "<xf borderId=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" // BOX
            + "<xf fillId=\"2\" borderId=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" // YELLOW
            + "<xf fontId=\"1\" borderId=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" // BOLD_BOX
            + "<xf borderId=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>" // LEFT_BOX
            + "<xf fontId=\"3\" borderId=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" // NAME_BOX
            + "<xf fillId=\"3\" borderId=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"                                         // GREY
            + "<xf applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"                                 // NOTE
            + "<xf fontId=\"1\" applyFont=\"1\"/>"                                                                          // BOLD
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
}
