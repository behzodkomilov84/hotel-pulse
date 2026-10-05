package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.exely.ExelyRawStore.Row;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exely javoblaridan (JSON daraxti) xom arxiv qatorlarini yasaydi. Sof funksiyalar. */
final class ExelyRawRows {

    private ExelyRawRows() {
    }

    /** Bronning xom qatori: kalit — bron raqami, sana — eng erta kelish kuni. */
    static Row booking(String number, String json) {
        JsonNode root = ExelyPmsClient.tree(json);
        LocalDate first = null;
        for (JsonNode rs : root.path("roomStays")) {
            LocalDate d = date(text(rs, "checkInDateTime"));
            if (d != null && (first == null || d.isBefore(first))) {
                first = d;
            }
        }
        return new Row(number, number, first, json);
    }

    /** Bron yashashlaridagi mehmonlar identifikatorlari (guestsIds / guestIds). */
    static Set<String> guestIds(String bookingJson) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode rs : ExelyPmsClient.tree(bookingJson).path("roomStays")) {
            for (String field : List.of("guestsIds", "guestIds")) {
                for (JsonNode id : rs.path(field)) {
                    String v = id.isValueNode() ? id.asString() : null;
                    if (v != null && !v.isBlank()) {
                        ids.add(v);
                    }
                }
            }
        }
        return ids;
    }

    /** Ro'yxat (massiv) — har bir element alohida qator, kalit — idField. */
    static List<Row> list(String json, String idField) {
        List<Row> rows = new ArrayList<>();
        JsonNode root = ExelyPmsClient.tree(json);
        JsonNode items = root.isArray() ? root : firstArray(root);
        for (JsonNode n : items) {
            String id = text(n, idField);
            if (id != null) {
                rows.add(new Row(id, null, null, n.toString()));
            }
        }
        return rows;
    }

    /** /analytics/payments — har bir to'lov; sana — to'lov kuni. */
    static List<Row> payments(String json) {
        List<Row> rows = new ArrayList<>();
        for (JsonNode p : ExelyPmsClient.tree(json).path("data").path("payments")) {
            String id = text(p, "id");
            if (id == null) {
                continue;
            }
            String when = text(p, "paymentDateTime") != null ? text(p, "paymentDateTime") : text(p, "dateTime");
            rows.add(new Row(id, text(p, "bookingNumber"), date(when), p.toString()));
        }
        return rows;
    }

    /** /analytics/services javobi bo'laklari. */
    record Services(List<Row> services, List<Row> reservations, List<Row> customers, List<Row> agents,
                    List<Row> roomTypes) {
    }

    static Services services(String json) {
        JsonNode data = ExelyPmsClient.tree(json).path("data");
        Map<String, String> bookingByReservation = new HashMap<>();
        List<Row> reservations = new ArrayList<>();
        for (JsonNode r : data.path("reservations")) {
            String id = text(r, "id");
            if (id == null) {
                continue;
            }
            bookingByReservation.put(id, text(r, "bookingNumber"));
            reservations.add(new Row(id, text(r, "bookingNumber"), date(text(r, "checkInDateTime")), r.toString()));
        }
        List<Row> services = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (JsonNode s : data.path("services")) {
            String id = text(s, "id");
            String day = text(s, "date");
            if (id == null || date(day) == null) {
                continue;
            }
            String reservation = text(s, "reservationId");
            // Exely bir kunda bitta yashashga bir xil id'li xizmatni bir necha marta berishi mumkin — hammasi saqlanadi.
            String base = id + ":" + reservation + ":" + day;
            int n = occurrences.merge(base, 1, Integer::sum);
            String key = n == 1 ? base : base + "#" + n;
            services.add(new Row(key, bookingByReservation.get(reservation), date(day), s.toString()));
        }
        return new Services(services, reservations, plain(data.path("customers")), plain(data.path("agents")),
                plain(data.path("roomTypes")));
    }

    private static List<Row> plain(JsonNode items) {
        List<Row> rows = new ArrayList<>();
        for (JsonNode n : items) {
            String id = text(n, "id");
            if (id != null) {
                rows.add(new Row(id, null, null, n.toString()));
            }
        }
        return rows;
    }

    private static JsonNode firstArray(JsonNode root) {
        for (JsonNode n : root) {
            if (n.isArray()) {
                return n;
            }
            for (JsonNode inner : n) {
                if (inner.isArray()) {
                    return inner;
                }
            }
        }
        return ExelyPmsClient.tree("[]");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.isValueNode() ? v.asString() : v.toString();
        return s == null || s.isBlank() ? null : s;
    }

    /** "yyyyMMdd...", "yyyy-MM-dd..." — sana qismi. */
    static LocalDate date(String s) {
        if (s == null || s.length() < 8) {
            return null;
        }
        try {
            if (s.length() >= 10 && s.charAt(4) == '-') {
                return LocalDate.parse(s.substring(0, 10));
            }
            return LocalDate.parse(s.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
