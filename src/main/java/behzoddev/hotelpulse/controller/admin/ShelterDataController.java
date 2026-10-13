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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * UCS Shelter agenti yuborgan jadvallar (agent_tables): jadval bo'yicha ro'yxat, qidiruv, sahifalash va qator tafsiloti.
 * Shelter sanalari (Delphi TDateTime) o'qiladigan ko'rinishda; holat kodlari — nomi bilan.
 */
@Controller
@RequestMapping("/admin/hotels/{id}/shelter/data")
@RequiredArgsConstructor
public class ShelterDataController {

    private static final int PAGE_SIZE = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    /** Delphi TDateTime boshlanishi. */
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    private final HotelService hotelService;
    private final AgentService agents;

    enum Type { TEXT, NUM, MONEY, DATE, STATE }

    public record Column(String label, String field, Type type) {

        public boolean numeric() {
            return type == Type.NUM || type == Type.MONEY;
        }
    }

    public record Table(String key, String title, List<Column> columns) {
    }

    public record RowView(int index, List<String> cells, String json) {
    }

    static final Map<String, Table> TABLES = new LinkedHashMap<>();

    static {
        add("ROOM_SCHEDULE", "Бронлар ва яшашлар", c("ID", "ID", Type.NUM), c("Брон", "RESID", Type.NUM),
                c("Ҳолат", "STATE", Type.STATE), c("Келиш", "ARRIVAL", Type.DATE), c("Кетиш", "DEPARTURE", Type.DATE),
                c("Хона ID", "IDROOM", Type.NUM), c("Хона тури", "ROOMKIND", Type.NUM), c("Меҳмон ID", "GUEST", Type.NUM),
                c("Кишилар", "Q_GUESTS", Type.NUM), c("Нарх", "COST", Type.MONEY), c("Фолио", "FOLIO", Type.NUM),
                c("Яратилган", "CREATIONDATE", Type.DATE));
        add("GROUP_SCHEDULE", "Гуруҳ бронлари", c("ID", "ID", Type.NUM), c("Код", "CODE", Type.TEXT),
                c("Рақам", "NUMBER", Type.TEXT), c("Келиш", "ARRIVAL", Type.DATE), c("Кетиш", "DEPARTURE", Type.DATE),
                c("Катталар", "ADULT", Type.NUM), c("Болалар", "CHILD", Type.NUM), c("Сумма", "SUMMA", Type.MONEY),
                c("Тўланган", "SUMMAPAY", Type.MONEY), c("Фолио", "FOLIO", Type.NUM), c("Изоҳ", "INFO", Type.TEXT));
        add("GUESTS", "Меҳмонлар ва тўловчилар", c("ID", "ID", Type.NUM), c("Тўлиқ номи", "FULLNAME", Type.TEXT),
                c("Фамилия / ном", "NAME", Type.TEXT), c("Исм", "NAME1", Type.TEXT), c("Отасининг исми", "NAME2", Type.TEXT),
                c("Тури", "GUESTTYPE", Type.NUM), c("Телефон", "PHONES", Type.TEXT), c("Email", "EMAIL", Type.TEXT));
        add("ENTERPRISEDEBTOR", "Корхоналар (агентлар)", c("ID", "ID", Type.NUM), c("Корхона (меҳмон ID)", "OWNER", Type.NUM),
                c("Фолио", "FOLIO", Type.NUM), c("Ҳолат", "STATE", Type.NUM), c("Комиссия", "COMMISSION", Type.NUM),
                c("Фаол", "ENABLED", Type.NUM));
        add("COMPANY_CONTRACTS", "Шартномалар");
        add("FOLIO", "Фолиолар (ҳисоблар)", c("ID", "ID", Type.NUM), c("Тури", "KIND", Type.NUM), c("Эгаси (ID)", "OWNER", Type.NUM),
                c("Хизматлар", "SERVICEBALANCE", Type.MONEY), c("Тўловлар", "PAYMENTBALANCE", Type.MONEY),
                c("Қолдиқ", "BALANCE", Type.MONEY), c("Очилган", "CREATIONDATE", Type.DATE), c("Ёпилган", "CLOSINGDATE", Type.DATE));
        add("FOLIO_CONTENTS", "Хизматлар ва тўловлар", c("ID", "ID", Type.NUM), c("Фолио", "FOLIO", Type.NUM),
                c("Операция", "OPERATION", Type.NUM), c("Хизмат", "ISSERVICE", Type.NUM), c("Нарх", "COST", Type.MONEY),
                c("Чегирмадан кейин", "COSTALL", Type.MONEY), c("Кун", "SHIFTDATE", Type.DATE),
                c("Киритилган", "CREATIONDATE", Type.DATE), c("Бекор", "CANCELED", Type.NUM), c("Изоҳ", "INFO", Type.TEXT));
        add("INVOICES", "Касса чеклари", c("ID", "ID", Type.NUM), c("Рақам", "EXTNO", Type.TEXT), c("Фолио", "FOLIO", Type.NUM),
                c("Тўлов", "PAYMENTSUM", Type.MONEY), c("Хизмат", "SERVICESUM", Type.MONEY), c("Сана", "CREATIONDATE", Type.DATE),
                c("Ҳолат", "STATUS", Type.NUM), c("Изоҳ", "INFO", Type.TEXT));
        add("CASH_DOCUMENTS", "Касса ҳужжатлари", c("ID", "ID", Type.NUM), c("Рақам", "EXTNO", Type.TEXT),
                c("Тури", "KIND", Type.NUM), c("Фолио", "FOLIO", Type.NUM), c("Сумма", "PAYMENT", Type.MONEY),
                c("Сана", "CREATIONDATE", Type.DATE), c("Асос", "BASE", Type.TEXT));
        add("ROOMS", "Хоналар", c("ID", "ID", Type.NUM), c("Рақам", "NUMBER", Type.TEXT), c("Номи", "NAME", Type.TEXT),
                c("Тури", "ROOMKIND", Type.NUM), c("Қават", "FLOOR", Type.TEXT), c("Ўринлар", "QPLACES", Type.NUM),
                c("Фаол", "ENABLED", Type.NUM));
        add("ROOM_KINDS", "Хона турлари", c("ID", "ID", Type.NUM), c("Код", "CODE", Type.TEXT), c("Номи", "NAME", Type.TEXT),
                c("Хоналар", "AMOUNTOFROOMS", Type.NUM), c("Каравотлар", "BEDS", Type.NUM), c("Фаол", "ENABLED", Type.NUM));
        add("OPERATIONS", "Хизмат турлари", c("ID", "ID", Type.NUM), c("Код", "CODE", Type.TEXT), c("Номи", "NAME", Type.TEXT),
                c("Тоифа", "CATEGORY", Type.NUM), c("Хизмат", "ISSERVICE", Type.NUM), c("Нарх", "COST", Type.MONEY),
                c("Фаол", "ENABLED", Type.NUM));
    }

    /** ROOM_SCHEDULE.STATE nomlari. */
    static final Map<Long, String> STATES = Map.of(50L, "Бронь (якка)", 51L, "Сўров (хонасиз)", 52L, "Бронь (гуруҳ)",
            70L, "Брон сарлавҳаси (якка)", 72L, "Брон сарлавҳаси (гуруҳ)", 100L, "Хона блоки", 300L, "Яшаяпти",
            305L, "Заселение бекор", 400L, "Кетган");

    private static Column c(String label, String field, Type type) {
        return new Column(label, field, type);
    }

    private static void add(String key, String title, Column... columns) {
        TABLES.put(key, new Table(key, title, List.of(columns)));
    }

    @GetMapping("/{table}")
    public String list(@PathVariable Long id, @PathVariable String table,
                       @RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        Hotel hotel = hotelService.getById(id);
        Table t = TABLES.get(table);
        if (t == null) {
            throw new ResponseStatusException(NOT_FOUND, "Номаълум жадвал");
        }
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        int from = (Math.max(page, 1) - 1) * PAGE_SIZE;
        List<String> slice = new ArrayList<>();
        long[] total = {0};
        agents.forEachRow(id, table, line -> {
            if (needle != null && !line.toLowerCase(Locale.ROOT).contains(needle)) {
                return;
            }
            if (total[0] >= from && slice.size() < PAGE_SIZE) {
                slice.add(line);
            }
            total[0]++;
        });
        int pages = (int) Math.max(1, (total[0] + PAGE_SIZE - 1) / PAGE_SIZE);
        List<Column> columns = t.columns();
        List<RowView> rows = new ArrayList<>();
        int i = 0;
        for (String line : slice) {
            JsonNode node = JSON.readTree(line);
            if (columns.isEmpty()) {
                columns = firstColumns(node);
            }
            List<String> cells = new ArrayList<>();
            for (Column c : columns) {
                cells.add(format(node, c));
            }
            rows.add(new RowView(i++, cells, PRETTY.writeValueAsString(readable(node))));
        }
        Map<String, Long> counts = new HashMap<>();
        agents.tables(id).forEach(x -> counts.put(x.name(), x.rows()));
        model.addAttribute("hotel", hotel);
        model.addAttribute("table", t);
        model.addAttribute("columns", columns);
        model.addAttribute("tables", TABLES.values().stream().filter(x -> counts.containsKey(x.key())).toList());
        model.addAttribute("counts", counts);
        model.addAttribute("rows", rows);
        model.addAttribute("total", total[0]);
        model.addAttribute("page", Math.min(Math.max(page, 1), pages));
        model.addAttribute("pages", pages);
        model.addAttribute("q", q);
        return "admin/shelter-data";
    }

    /** Ustunlari belgilanmagan jadval — birinchi qatordagi dastlabki 10 maydon. */
    private static List<Column> firstColumns(JsonNode node) {
        List<Column> cols = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (cols.size() >= 10) {
                break;
            }
            Type type = isDateField(e.getKey()) ? Type.DATE : e.getValue().isNumber() ? Type.NUM : Type.TEXT;
            cols.add(new Column(e.getKey(), e.getKey(), type));
        }
        return cols;
    }

    static String format(JsonNode node, Column c) {
        JsonNode v = node.get(c.field());
        if (v == null || v.isNull()) {
            return "";
        }
        return switch (c.type()) {
            case DATE -> date(v);
            case MONEY -> v.isNumber() ? money(v.asDouble()) : v.asString();
            case STATE -> {
                String name = STATES.get(v.asLong());
                yield name == null ? v.asString() : name + " (" + v.asLong() + ")";
            }
            default -> v.isNumber() && v.asDouble() == Math.rint(v.asDouble()) ? String.valueOf(v.asLong()) : v.asString();
        };
    }

    /** Delphi TDateTime → "dd.MM.yyyy" (vaqti bo'lsa — "dd.MM.yyyy HH:mm"); 0 — bo'sh. */
    static String date(JsonNode v) {
        if (!v.isNumber() || v.asDouble() < 1) {
            return v.isNumber() ? "" : v.asString();
        }
        double x = v.asDouble();
        long days = (long) Math.floor(x);
        long seconds = Math.round((x - days) * 86_400);
        LocalDateTime dt = EPOCH.plusDays(days).atStartOfDay().plusSeconds(seconds);
        return seconds == 0 ? dt.toLocalDate().format(DAY) : dt.format(TIME);
    }

    /** Tafsilot uchun: sana maydonlari yonida o'qiladigan qiymat ("ARRIVAL": "13.10.2026 14:00 (46308.58)"). */
    private static JsonNode readable(JsonNode node) {
        tools.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode v = e.getValue();
            if (isDateField(e.getKey()) && v.isNumber() && v.asDouble() > 1) {
                out.put(e.getKey(), date(v) + " (" + v.asString() + ")");
            } else {
                out.set(e.getKey(), v);
            }
        }
        return out;
    }

    private static boolean isDateField(String name) {
        return name.endsWith("DATE") || name.equals("ARRIVAL") || name.equals("DEPARTURE") || name.endsWith("_DT");
    }

    private static String money(double v) {
        return new DecimalFormat("#,##0.##", new DecimalFormatSymbols(Locale.ROOT) {{
            setGroupingSeparator(' ');
            setDecimalSeparator(',');
        }}).format(v);
    }

    /** Admin kartochkalari uchun: jadval kalitlari (nom → kalit). */
    public static Map<String, String> keysByTitle() {
        Map<String, String> m = new LinkedHashMap<>();
        TABLES.values().forEach(t -> m.put(t.title(), t.key()));
        return m;
    }
}
