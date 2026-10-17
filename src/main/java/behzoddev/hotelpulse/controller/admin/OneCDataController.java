package behzoddev.hotelpulse.controller.admin;

import behzoddev.hotelpulse.agent.AgentService;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 1С:Отель agenti yuborgan jadvallar (agent_tables, 1C XDTO JSON): jadval bo'yicha ro'yxat, qidiruv, sahifalash va qator
 * tafsiloti. Havolalar (CatalogRef) ma'lumotnomalardagi nomi bilan ko'rsatiladi.
 */
@Controller
@RequestMapping("/admin/hotels/{id}/onec/data")
@RequiredArgsConstructor
public class OneCDataController {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_COLUMNS = 12;
    static final String RECENT = "Recent.";
    static final String EMPTY_REF = "00000000-0000-0000-0000-000000000000";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Set<String> HIDDEN = Set.of("Ref", "DataVersion", "Predefined", "PredefinedDataName", "PointInTime");
    /** Jadvalda birinchi ko'rsatiladigan ustunlar. */
    private static final List<String> FIRST = List.of("Number", "Date", "Period", "Code", "Description", "Posted",
            "DeletionMark", "GuestFullName", "Room", "RoomType", "CheckInDate", "CheckOutDate", "Customer", "Service",
            "Sales", "Sum", "Amount");

    /** Asosiy jadvallar (birinchi, shu tartibda ko'rsatiladi). */
    static final List<String> MAIN = List.of("Document.Accommodation", "Document.Reservation", "Document.Charge",
            "Document.Payment", "Document.Folio", "Document.Settlement", "Document.CloseOfCashRegisterDay",
            "Document.ForeignerRegistryRecord", "AccumulationRegister.Sales", "AccumulationRegister.Payments",
            "AccumulationRegister.RoomInventory", "AccumulationRegister.Accounts", "AccumulationRegister.CurrentAccountsReceivable",
            "AccumulationRegister.AccountsReceivable", "AccumulationRegister.CustomerAccounts", "AccumulationRegister.CashInCashRegisters",
            "Catalog.Clients", "Catalog.Customers", "Catalog.Rooms", "Catalog.RoomTypes", "Catalog.Services", "Catalog.PaymentMethods",
            "Catalog.SourcesOfBusiness", "Catalog.AccommodationStatuses", "Catalog.ReservationStatuses", "Catalog.GuestGroups",
            "Catalog.Employees");

    /** Kirillcha nomlar (onec-labels.properties): table.*, section.*, column.*. */
    private static final java.util.Properties NAMES = new java.util.Properties();

    static {
        try (var in = OneCDataController.class.getResourceAsStream("/onec-labels.properties")) {
            if (in != null) {
                NAMES.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("onec-labels.properties o'qilmadi", e);
        }
    }

    /** Jadval nomi: "Document.Accommodation" → "Яшашлар", "Document.Accommodation.Services" → "Яшашлар — хизматлар". */
    static String tableLabel(String table) {
        String label = NAMES.getProperty("table." + table);
        if (label != null) {
            return label;
        }
        int last = table.lastIndexOf('.');
        if (last > 0 && table.indexOf('.') != last) {
            String parent = NAMES.getProperty("table." + table.substring(0, last));
            String section = NAMES.getProperty("section." + table.substring(last + 1));
            if (parent != null) {
                return parent + " — " + (section != null ? section : table.substring(last + 1));
            }
        }
        return shortName(table);
    }

    static String columnLabel(String column) {
        return NAMES.getProperty("column." + column, column);
    }

    /** Guruhlar: 1C nom prefiksi → sarlavha. */
    static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("Document.", "Ҳужжатлар");
        GROUPS.put("AccumulationRegister.", "Жамғариш регистрлари");
        GROUPS.put("InformationRegister.", "Маълумот регистрлари");
        GROUPS.put("Catalog.", "Маълумотномалар");
        GROUPS.put("", "Бошқа");
    }

    private final HotelService hotelService;
    private final AgentService agents;

    /** Ref → nom keshi (mehmonxona bo'yicha; ma'lumotnomalar yangilansa — qayta quriladi). */
    private final Map<Long, Names> names = new ConcurrentHashMap<>();

    private record Names(LocalDateTime version, Map<String, String> byRef) {
    }

    public record Column(String name, String label, boolean numeric) {
    }

    public record RowView(int index, List<String> cells, String json) {
    }

    public record Tile(String table, String label, String name, long rows, boolean main) {
    }

    public record Group(String title, List<Tile> tiles) {
    }

    /** 1C jadvallari guruhlab (kartochkalar uchun); 1C jadvallari bo'lmasa — bo'sh. */
    public static List<Group> groups(List<AgentService.TableInfo> tables) {
        Map<String, Long> rows = new HashMap<>();
        for (AgentService.TableInfo t : tables) {
            String base = t.name().startsWith(RECENT) ? t.name().substring(RECENT.length()) : t.name();
            if (base.startsWith("Enum.") || !base.contains(".")) {
                continue;   // sanab o'tishlar va Shelter jadvallari (nuqtasiz)
            }
            if (!t.name().startsWith(RECENT) || !rows.containsKey(base)) {
                rows.put(base, t.rows());
            }
        }
        List<Group> out = new ArrayList<>();
        Set<String> used = new java.util.HashSet<>();
        for (Map.Entry<String, String> g : GROUPS.entrySet()) {
            List<Tile> tiles = rows.keySet().stream()
                    .filter(n -> !used.contains(n) && n.startsWith(g.getKey()))
                    .map(n -> new Tile(n, tableLabel(n), n, rows.get(n), MAIN.contains(n)))
                    .sorted(Comparator.comparing((Tile t) -> !t.main())
                            .thenComparing(t -> t.main() ? MAIN.indexOf(t.table()) : 0)
                            .thenComparing(Tile::label))
                    .toList();
            tiles.forEach(t -> used.add(t.table()));
            if (!tiles.isEmpty()) {
                out.add(new Group(g.getValue(), tiles));
            }
        }
        return out;
    }

    private static String shortName(String table) {
        int dot = table.indexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    @GetMapping("/{table}")
    public String list(@PathVariable Long id, @PathVariable String table,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "full") String src,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        Hotel hotel = hotelService.getById(id);
        List<AgentService.TableInfo> tables = agents.tables(id);
        Map<String, AgentService.TableInfo> info = new HashMap<>();
        tables.forEach(t -> info.put(t.name(), t));
        boolean hasFull = info.containsKey(table), hasRecent = info.containsKey(RECENT + table);
        if (!hasFull && !hasRecent) {
            throw new ResponseStatusException(NOT_FOUND, "Номаълум жадвал");
        }
        boolean recent = hasRecent && (!hasFull || "recent".equals(src));
        String stored = recent ? RECENT + table : table;
        Map<String, String> refNames = names(id, tables);

        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        int from = (Math.max(page, 1) - 1) * PAGE_SIZE;
        List<String> allColumns = new ArrayList<>();
        Map<String, Boolean> numeric = new HashMap<>();
        List<Map<String, JsonNode>> slice = new ArrayList<>();
        long[] total = {0};
        agents.forEachRow(id, stored, line -> {
            JsonNode doc = JSON.readTree(line);
            JsonNode v = doc.has("#value") ? doc.get("#value") : doc;
            List<String> cols = new ArrayList<>();
            for (JsonNode c : v.path("column")) {
                JsonNode n = c.path("Name");
                String name = n.isObject() ? n.path("#value").asString() : n.asString();
                cols.add(name);
                if (!allColumns.contains(name)) {
                    allColumns.add(name);
                }
            }
            for (JsonNode r : v.path("row")) {
                Map<String, JsonNode> m = new LinkedHashMap<>();
                int i = 0;
                for (JsonNode cell : r) {
                    if (i < cols.size()) {
                        m.put(cols.get(i), cell);
                        JsonNode val = cell.isObject() ? cell.get("#value") : cell;
                        if (val != null && val.isNumber()) {
                            numeric.put(cols.get(i), true);
                        }
                    }
                    i++;
                }
                if (needle != null && !matches(m, refNames, needle)) {
                    continue;
                }
                if (total[0] >= from && slice.size() < PAGE_SIZE) {
                    slice.add(m);
                }
                total[0]++;
            }
        });

        List<String> shown = new ArrayList<>();
        FIRST.stream().filter(allColumns::contains).forEach(shown::add);
        allColumns.stream().filter(c -> !shown.contains(c) && !HIDDEN.contains(c)).forEach(shown::add);
        List<String> visible = shown.subList(0, Math.min(MAX_COLUMNS, shown.size()));
        List<Column> columns = visible.stream().map(c -> new Column(c, columnLabel(c), numeric.containsKey(c))).toList();

        List<RowView> rows = new ArrayList<>();
        int i = 0;
        for (Map<String, JsonNode> m : slice) {
            List<String> cells = new ArrayList<>();
            for (String c : visible) {
                cells.add(format(m.get(c), refNames));
            }
            ObjectNode detail = JSON.createObjectNode();
            m.forEach((k, cell) -> detail.put(columnLabel(k), detailText(cell, refNames)));
            rows.add(new RowView(i++, cells, PRETTY.writeValueAsString(detail)));
        }
        int pages = (int) Math.max(1, (total[0] + PAGE_SIZE - 1) / PAGE_SIZE);
        model.addAttribute("hotel", hotel);
        model.addAttribute("table", table);
        model.addAttribute("title", tableLabel(table));
        model.addAttribute("groups", groups(tables));
        model.addAttribute("columns", columns);
        model.addAttribute("rows", rows);
        model.addAttribute("total", total[0]);
        model.addAttribute("page", Math.min(Math.max(page, 1), pages));
        model.addAttribute("pages", pages);
        model.addAttribute("q", q);
        model.addAttribute("src", recent ? "recent" : "full");
        model.addAttribute("hasFull", hasFull);
        model.addAttribute("hasRecent", hasRecent);
        model.addAttribute("fullAt", hasFull ? info.get(table).uploadedAt() : null);
        model.addAttribute("recentAt", hasRecent ? info.get(RECENT + table).uploadedAt() : null);
        return "admin/onec-data";
    }

    private static boolean matches(Map<String, JsonNode> row, Map<String, String> refNames, String needle) {
        for (JsonNode cell : row.values()) {
            String s = format(cell, refNames);
            if (!s.isEmpty() && s.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
            JsonNode val = cell == null ? null : cell.isObject() ? cell.get("#value") : cell;
            if (val != null && val.isString() && val.asString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Katak qiymati: havola — nomi, sana — dd.MM.yyyy, mantiqiy — Ҳа/Йўқ, son — bo'shliq bilan. */
    static String format(JsonNode cell, Map<String, String> refNames) {
        if (cell == null || cell.isNull()) {
            return "";
        }
        String type = cell.isObject() ? cell.path("#type").asString("") : "";
        JsonNode val = cell.isObject() ? cell.get("#value") : cell;
        if (val == null || val.isNull()) {
            return "";
        }
        if (val.isBoolean()) {
            return val.asBoolean() ? "Ҳа" : "Йўқ";
        }
        if (val.isNumber()) {
            return number(val.decimalValue());
        }
        if (!val.isString()) {
            return "";
        }
        String s = val.asString();
        if (s.equals(EMPTY_REF) || s.startsWith("0001-01-01")) {
            return "";
        }
        if (type.equals("jxs:dateTime") || (s.length() == 19 && s.charAt(10) == 'T' && s.charAt(4) == '-')) {
            try {
                LocalDateTime dt = LocalDateTime.parse(s);
                return dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? dt.format(DAY) : dt.format(TIME);
            } catch (RuntimeException e) {
                return s;
            }
        }
        String name = refNames.get(s);
        return name != null ? name : s;
    }

    private static String detailText(JsonNode cell, Map<String, String> refNames) {
        String text = format(cell, refNames);
        JsonNode val = cell == null ? null : cell.isObject() ? cell.get("#value") : cell;
        String type = cell != null && cell.isObject() ? cell.path("#type").asString("") : "";
        if (val != null && val.isString() && refNames.containsKey(val.asString())) {
            return text + "  [" + type.replace("jcfg:", "") + " " + val.asString() + "]";
        }
        return text;
    }

    private static String number(BigDecimal v) {
        return new DecimalFormat("#,##0.##", new DecimalFormatSymbols(Locale.ROOT) {{
            setGroupingSeparator(' ');
            setDecimalSeparator(',');
        }}).format(v);
    }

    /** Barcha ma'lumotnomalar (Catalog.*): Ref → Description (bo'lmasa Code). */
    private Map<String, String> names(long hotelId, List<AgentService.TableInfo> tables) {
        LocalDateTime version = tables.stream().filter(t -> isCatalog(t.name())).map(AgentService.TableInfo::uploadedAt)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        Names cached = names.get(hotelId);
        if (cached != null && java.util.Objects.equals(cached.version(), version)) {
            return cached.byRef();
        }
        Map<String, String> m = new HashMap<>();
        Set<String> present = new java.util.HashSet<>();
        tables.forEach(t -> present.add(t.name()));
        for (AgentService.TableInfo t : tables) {
            if (!isCatalog(t.name()) || (!t.name().startsWith(RECENT) && present.contains(RECENT + t.name()))) {
                continue;   // tezkor nusxa bor bo'lsa — u yangiroq
            }
            agents.forEachRow(hotelId, t.name(), line -> {
                JsonNode doc = JSON.readTree(line);
                JsonNode v = doc.has("#value") ? doc.get("#value") : doc;
                int ref = -1, desc = -1, code = -1, i = 0;
                for (JsonNode c : v.path("column")) {
                    JsonNode n = c.path("Name");
                    String name = n.isObject() ? n.path("#value").asString() : n.asString();
                    switch (name) {
                        case "Ref" -> ref = i;
                        case "Description" -> desc = i;
                        case "Code" -> code = i;
                        default -> { }
                    }
                    i++;
                }
                if (ref < 0) {
                    return;
                }
                for (JsonNode r : v.path("row")) {
                    String key = text(r.get(ref));
                    String label = desc >= 0 ? text(r.get(desc)) : "";
                    if (label.isBlank() && code >= 0) {
                        label = text(r.get(code));
                    }
                    if (!key.isEmpty() && !label.isBlank()) {
                        m.put(key, label.trim());
                    }
                }
            });
        }
        names.put(hotelId, new Names(version, m));
        return m;
    }

    /** Ma'lumotnomaning o'zi (jadval qismi — "Catalog.X.Y" emas: undagi Ref egasiga ishora qiladi). */
    private static boolean isCatalog(String name) {
        String base = name.startsWith(RECENT) ? name.substring(RECENT.length()) : name;
        return base.startsWith("Catalog.") && base.indexOf('.') == base.lastIndexOf('.');
    }

    private static String text(JsonNode cell) {
        if (cell == null || cell.isNull()) {
            return "";
        }
        JsonNode val = cell.isObject() ? cell.get("#value") : cell;
        return val == null || val.isNull() || !val.isValueNode() ? "" : val.asString();
    }
}
