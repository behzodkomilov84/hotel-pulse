package behzoddev.hotelpulse.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Jadval ko'rinishidagi hisobot: ustunlar, qatorlar, jami qatori va izohlar.
 * Barcha jadval hisobotlari bir xil shablon (reports/blocks :: table) va bir xil Excel eksport bilan chiqadi.
 */
public final class ReportTable {

    /** @param num raqamli ustun (o'ngga tekislanadi) */
    public record Column(String label, boolean num) {
    }

    /** @param cls katak uslubi (masalan "heat-3", "neg", "pos"); null — oddiy */
    public record Cell(String text, String cls) {
    }

    /** @param kind qator turi: null — oddiy, "section" — bo'lim sarlavhasi, "subtotal" — oraliq jami */
    public record Row(List<Cell> cells, String kind) {
    }

    private final List<Column> columns = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private Row total;

    public ReportTable col(String label) {
        columns.add(new Column(label, false));
        return this;
    }

    public ReportTable num(String label) {
        columns.add(new Column(label, true));
        return this;
    }

    public ReportTable row(Object... cells) {
        rows.add(new Row(cells(cells), null));
        return this;
    }

    /** Qator turi bilan: "section", "subtotal". */
    public ReportTable rowOf(String kind, Object... cells) {
        rows.add(new Row(cells(cells), kind));
        return this;
    }

    public ReportTable total(Object... cells) {
        total = new Row(cells(cells), "total");
        return this;
    }

    public ReportTable note(String text) {
        notes.add(text);
        return this;
    }

    private List<Cell> cells(Object[] values) {
        List<Cell> list = new ArrayList<>(values.length);
        for (Object v : values) {
            list.add(v instanceof Cell c ? c : new Cell(v == null ? "" : v.toString(), null));
        }
        while (list.size() < columns.size()) {
            list.add(new Cell("", null));
        }
        return list;
    }

    public static Cell cell(String text, String cls) {
        return new Cell(text, cls);
    }

    public List<Column> getColumns() {
        return columns;
    }

    public List<Row> getRows() {
        return rows;
    }

    public Row getTotal() {
        return total;
    }

    public List<String> getNotes() {
        return notes;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Ekranda qisqa ko'rinish (blok sifatida) — birinchi n qator. */
    public List<Row> head(int n) {
        return rows.subList(0, Math.min(n, rows.size()));
    }

    /** Excel uchun CSV (UTF-8 BOM, ";"). */
    public String toCsv() {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(";", columns.stream().map(c -> csv(c.label())).toList())).append('\n');
        List<Row> all = new ArrayList<>(rows);
        if (total != null) {
            all.add(total);
        }
        for (Row r : all) {
            sb.append(String.join(";", r.cells().stream().map(c -> csv(plain(c.text()))).toList())).append('\n');
        }
        for (String n : notes) {
            sb.append('\n').append(csv(n));
        }
        return sb.toString();
    }

    /** Excel raqamni o'qishi uchun: "1 234 567 so'm" → "1234567"; "12,5%" o'zgarmaydi. */
    static String plain(String s) {
        String t = s.replace(' ', ' ').replace(' ', ' ');
        if (t.matches("[+−-]?[0-9 ]+( so'm| UZS| USD)?")) {
            return t.replace("−", "-").replaceAll("[^0-9-]", "");
        }
        return t;
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r", " ").replace("\n", " ");
        return v.contains(";") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    @Override
    public String toString() {
        return "ReportTable" + Arrays.toString(columns.toArray()) + " rows=" + rows.size();
    }
}
