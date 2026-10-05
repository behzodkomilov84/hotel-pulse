package behzoddev.hotelpulse.exely;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Xom arxivni (exely_raw) ko'rsatish: har bir tur uchun nomi va jadval ustunlari (JSON yo'llari),
 * Excel eksporti uchun esa JSON'ni to'liq "yassilash" (har bir barg maydon — alohida ustun).
 */
public final class ExelyRawView {

    private ExelyRawView() {
    }

    public enum Type { TEXT, DATE, MONEY, COUNT }

    /**
     * @param path JSON yo'li: "a.b", "a[0].b"; maxsus: "@booking" (bron raqami ustuni), "@key" (kalit),
     *             "a b" — bir nechta yo'l bo'sh joy bilan (qiymatlar qo'shib yoziladi)
     */
    public record Column(String label, String path, Type type) {
    }

    public record Kind(String key, String title, List<Column> columns) {
    }

    private static Column text(String label, String path) {
        return new Column(label, path, Type.TEXT);
    }

    private static Column date(String label, String path) {
        return new Column(label, path, Type.DATE);
    }

    private static Column money(String label, String path) {
        return new Column(label, path, Type.MONEY);
    }

    public static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    static {
        add(ExelyRawStore.BOOKING, "Bronlar", List.of(
                text("Bron raqami", "number"), text("Mijoz", "customer.lastName customer.firstName"),
                text("Manba", "source.value"), text("Valyuta", "currencyId"),
                new Column("Xonalar", "roomStays", Type.COUNT),
                date("Kelish", "roomStays[0].checkInDateTime"), date("Ketish", "roomStays[0].checkOutDateTime"),
                text("Holat", "roomStays[0].status"), money("Narx (1-xona)", "roomStays[0].totalPrice.amount"),
                date("O'zgargan", "lastModified")));
        add(ExelyRawStore.RESERVATION, "Yashashlar", List.of(
                text("Bron", "bookingNumber"), text("Xona", "roomNumber"), text("Mehmon", "guestName"),
                text("Mehmonlar", "guestCount"), date("Kelish", "checkInDateTime"), date("Ketish", "checkOutDateTime"),
                money("Jami", "total"), money("To'langan", "paid"), money("Qoldiq", "balance"),
                text("Valyuta", "currency"), text("Kurs", "currencyRate"), text("Manba", "bookingSource")));
        add(ExelyRawStore.SERVICE, "Xizmatlar (kunlik)", serviceColumns());
        add(ExelyRawStore.SERVICE_CANCELLED, "Bekor qilingan xizmatlar", serviceColumns());
        add(ExelyRawStore.PAYMENT, "To'lovlar", List.of(
                date("Sana", "paymentDateTime"), text("Bron", "bookingNumber"), money("Summa", "amount"),
                text("Valyuta", "currency"), text("Turi (actionKind)", "actionKind"), text("Usul", "paymentMethod"),
                text("Tizim", "paymentSystem"), text("Folio", "folioNumber"), text("Xodim", "username"),
                date("Bekor qilingan", "cancellationDateTime"), text("Izoh", "comment")));
        add(ExelyRawStore.INVOICES, "Exely hisoblari (folio, bronlar bo'yicha)", List.of(
                text("Bron", "@booking"), new Column("Hisoblar soni", "", Type.COUNT),
                text("Raqam (1-si)", "[0].number"), text("To'lovchi (1-si)", "[0].payer.name"),
                text("Qatorlar (1-si)", "[0].items")));
        add(ExelyRawStore.GUEST, "Mehmonlar", List.of(
                text("Familiya", "lastName"), text("Ism", "firstName"), text("Otasining ismi", "middleName"),
                text("Fuqarolik", "citizenshipCode"), text("Jinsi", "gender"), date("Tug'ilgan", "birthDate"),
                text("Telefon", "phones"), text("Email", "emails"), text("Bron", "@booking")));
        add(ExelyRawStore.CUSTOMER, "To'lovchilar", List.of(
                text("Nomi", "name"), text("Turi (0 — kompaniya, 1 — shaxs)", "customerKind"), text("Telefon", "phone"),
                text("Email", "email"), text("INN", "inn"), text("Yuridik manzil", "legalAddress")));
        add(ExelyRawStore.AGENT, "Agentlar", List.of(
                text("Nomi", "name"), text("Yuridik manzil", "legalAddress"), text("Pochta manzili", "mailingAddress")));
        add(ExelyRawStore.COMPANY, "Kompaniyalar", List.of(text("Nomi", "name"), text("Turi", "type"), text("ID", "id")));
        add(ExelyRawStore.ROOM_TYPE, "Xona turlari", List.of(text("Nomi", "name"), text("ID", "id")));
        add(ExelyRawStore.ROOM, "Xonalar", List.of(
                text("Xona", "name"), text("Xona turi ID", "roomTypeId"), text("Qavat ID", "floorId"), text("ID", "id")));
    }

    private static List<Column> serviceColumns() {
        return List.of(date("Sana", "date"), text("Bron", "@booking"), text("Xizmat", "name"), text("Turi (kind)", "kind"),
                text("Toifa", "optionCategory"), money("Summa", "amount"), text("Soni", "quantity"),
                money("Chegirma", "discount"), text("Narxga kiritilgan", "isIncluded"), text("Yashash ID", "reservationId"));
    }

    private static void add(String key, String title, List<Column> columns) {
        KINDS.put(key, new Kind(key, title, columns));
    }

    // ------------------------------------------------------------------ qiymatlarni olish

    /** Ustun qiymati (ko'rsatish uchun matn). */
    public static String value(Column c, JsonNode root, String bookingNumber, String key) {
        if (c.path().equals("@booking")) {
            return bookingNumber == null ? "" : bookingNumber;
        }
        if (c.path().equals("@key")) {
            return key;
        }
        if (c.type() == Type.COUNT) {
            JsonNode n = c.path().isEmpty() ? root : node(root, c.path());
            return n != null && n.isArray() ? String.valueOf(n.size()) : "";
        }
        List<String> parts = new ArrayList<>();
        for (String p : c.path().split(" ")) {
            String v = scalar(node(root, p));
            if (!v.isEmpty()) {
                parts.add(v);
            }
        }
        String v = String.join(" ", parts);
        return c.type() == Type.DATE ? formatDate(v) : v;
    }

    /** "a.b[0].c" bo'yicha tugun (yo'q bo'lsa — null). "[0].x" — massivning o'zi ildiz bo'lsa. */
    static JsonNode node(JsonNode root, String path) {
        JsonNode cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            String name = seg;
            Integer index = null;
            int b = seg.indexOf('[');
            if (b >= 0) {
                name = seg.substring(0, b);
                index = Integer.parseInt(seg.substring(b + 1, seg.indexOf(']')));
            }
            if (!name.isEmpty()) {
                cur = cur.get(name);
            }
            if (cur != null && index != null) {
                cur = cur.isArray() && cur.size() > index ? cur.get(index) : null;
            }
        }
        return cur == null || cur.isNull() || cur.isMissingNode() ? null : cur;
    }

    /** Tugun matni: oddiy qiymat; oddiy qiymatlar massivi — vergul bilan; murakkab — JSON. */
    static String scalar(JsonNode n) {
        if (n == null) {
            return "";
        }
        if (n.isValueNode()) {
            return n.asString();
        }
        if (n.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode i : n) {
                items.add(i.isValueNode() ? i.asString() : i.toString());
            }
            return String.join(", ", items);
        }
        return n.toString();
    }

    private static final DateTimeFormatter OUT_DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter OUT_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Exely sana formatlari: yyyyMMdd, yyyyMMddHHmm, yyyy-MM-dd, yyyy-MM-ddTHH:mm[:ss][Z] → dd.MM.yyyy[ HH:mm]. */
    static String formatDate(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        try {
            if (s.matches("\\d{12}")) {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMddHHmm")).format(OUT_TIME);
            }
            if (s.matches("\\d{8}")) {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).format(OUT_DAY);
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s).format(OUT_DAY);
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*")) {
                return LocalDateTime.parse(s.substring(0, 16)).format(OUT_TIME);
            }
        } catch (DateTimeParseException e) {
            return s;
        }
        return s;
    }

    // ------------------------------------------------------------------ eksport uchun yassilash

    /** JSON'ni barg maydonlarga yoyadi: {"a":{"b":1},"c":[{"d":2}]} → a.b=1, c[0].d=2. Tartib saqlanadi. */
    public static Map<String, String> flatten(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", root, out);
        return out;
    }

    private static void flatten(String prefix, JsonNode n, Map<String, String> out) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            if (!prefix.isEmpty()) {
                out.put(prefix, "");
            }
            return;
        }
        if (n.isValueNode()) {
            out.put(prefix.isEmpty() ? "value" : prefix, n.asString());
            return;
        }
        if (n.isArray()) {
            boolean simple = true;
            for (JsonNode i : n) {
                simple &= i.isValueNode();
            }
            if (simple) {
                out.put(prefix.isEmpty() ? "value" : prefix, scalar(n));
                return;
            }
            int idx = 0;
            for (JsonNode i : n) {
                flatten(prefix + "[" + idx++ + "]", i, out);
            }
            return;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
        }
    }
}
