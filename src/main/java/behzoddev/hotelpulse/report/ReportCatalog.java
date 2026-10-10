package behzoddev.hotelpulse.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hisobotlar katalogi: Exely PMS'dagi hisobotlar (exely.com/ru/help/281619) va USALI.
 * "Hisobotlar" menyusi, mehmonxona ekraniga blok qo'shish va standart tarkib shu ro'yxatdan olinadi.
 */
public final class ReportCatalog {

    private ReportCatalog() {
    }

    public enum Kind {
        /** Maxsus blok (kartalar, grafik) — mehmonxona ekranida. */
        WIDGET,
        /** Jadval hisobot — umumiy shablon, Excel eksport. */
        TABLE,
        /** Exely API bu ma'lumotni bermaydi — katalogda ko'rinadi, qo'shib bo'lmaydi. */
        UNAVAILABLE
    }

    /**
     * @param exely  Exely'dagi (yoki USALI'dagi) asl nomi; bizniki bo'lsa — null
     * @param size   ekrandagi kengligi: "full", "wide" (2/3), "narrow" (1/3)
     */
    public record ReportDef(String key, String group, String title, String exely, String description, Kind kind,
                            String size) {
        public boolean isAvailable() {
            return kind != Kind.UNAVAILABLE;
        }

        public boolean isTable() {
            return kind == Kind.TABLE;
        }
    }

    public static final String OPS = "Кунлик операция";
    public static final String REVENUE = "Даромад ва бандлик";
    public static final String SOURCES = "Мижозлар ва манбалар";
    public static final String FINANCE = "Молия";
    public static final String USALI = "USALI";

    public static final List<String> GROUPS = List.of(OPS, REVENUE, SOURCES, FINANCE, USALI);

    /** Mehmonxona ekranining standart tarkibi (hozirgi sahifa). */
    public static final List<String> DEFAULT_LAYOUT = List.of("today", "kpi", "flow", "daily-chart", "sources-chart");

    private static final Map<String, ReportDef> ALL = new LinkedHashMap<>();

    private static void add(String key, String group, String title, String exely, String description, Kind kind, String size) {
        ALL.put(key, new ReportDef(key, group, title, exely, description, kind, size));
    }

    static {
        // ---- Kunlik operatsiya
        add("today", OPS, "Бугун", "Сводная статистика (на сегодня)",
                "Бугунги бандлик, келадиган ва кетадиганлар, қарздорлик", Kind.WIDGET, "full");
        add("arrivals", OPS, "Келишлар", "Заезды",
                "Даврда келадиган меҳмонлар: брон, хона тури, хона, кечалар, сумма, қолдиқ", Kind.TABLE, "full");
        add("departures", OPS, "Кетишлар", "Выезды",
                "Даврда кетадиган меҳмонлар ва уларнинг қолдиғи", Kind.TABLE, "full");
        add("inhouse", OPS, "Банд хоналар", "Занятые номера",
                "Танланган кунда яшаётган меҳмонлар: хона, келиш-кетиш, қолдиқ", Kind.TABLE, "full");
        add("guests", OPS, "Меҳмонлар рўйхати", "Список гостей",
                "Даврда яшаган ва брон қилган меҳмонлар: ташрифлар, кечалар, сумма", Kind.TABLE, "full");
        add("meals", OPS, "Овқатланиш", "Отчет по питанию",
                "Кунлар бўйича меҳмонлар сони ва сотилган овқатланиш (нонушта) суммаси", Kind.TABLE, "full");
        add("housekeeping", OPS, "Хоналарни тозалаш", "Обслуживание номеров",
                "Exely API housekeeping маълумотини бермайди", Kind.UNAVAILABLE, "full");
        add("activity-log", OPS, "Фойдаланувчилар ҳаракати журнали", "Журнал активности пользователей",
                "Exely API фойдаланувчилар ҳаракати журналини бермайди", Kind.UNAVAILABLE, "full");

        // ---- Daromad va bandlik
        add("kpi", REVENUE, "Асосий кўрсаткичлар", "Сводная статистика",
                "Бандлик, ADR, RevPAR, даромад, тўловлар, бронлар, бекор қилинганлар, ўртача яшаш", Kind.WIDGET, "full");
        add("flow", REVENUE, "Даромад ва тушум", null,
                "Давр даромади, тушган тўловлар ва улар орасидаги фарқ", Kind.WIDGET, "full");
        add("daily-chart", REVENUE, "Кунлик бандлик ва даромад (график)", "Доходность и загрузка",
                "Кунлар бўйича бандлик ва даромад графиги", Kind.WIDGET, "wide");
        add("daily", REVENUE, "Кунлик даромад ва бандлик (жадвал)", "Доходность и загрузка",
                "Ҳар кун: сотилган хоналар, бандлик, ADR, RevPAR, даромад", Kind.TABLE, "full");
        add("manager-period", REVENUE, "Менежер ҳисоботи (давр)", "Отчет менеджера за период",
                "Асосий кўрсаткичлар: давр, олдинги давр ва ўзгариш", Kind.TABLE, "full");
        add("flash", REVENUE, "Менежер ҳисоботи (сана)", "Отчет менеджера на дату",
                "Flash report: кун, ой бошидан ва йил бошидан кўрсаткичлар", Kind.TABLE, "full");
        add("room-types", REVENUE, "Хона турлари бўйича даромад", "Доходность по тарифам",
                "Хона турлари: сотилган кечалар, бандлик, ADR, даромад улуши (тариф режаси API'да йўқ)", Kind.TABLE, "full");
        add("rate-plans", REVENUE, "Тарифлар бўйича даромад", "Доходность по тарифам",
                "Exely API броннинг тариф режасини бермайди — ўрнига «Хона турлари бўйича даромад»", Kind.UNAVAILABLE, "full");
        add("history-forecast", REVENUE, "Тарих ва прогноз", "История и прогноз",
                "Ойлар бўйича: ўтган ойлар — ҳақиқий, келгуси ойлар — ҳозиргача брон қилингани", Kind.TABLE, "full");
        add("demand-calendar", REVENUE, "Талаб календари", "Календарь спроса",
                "Келгуси 60 кун: сотилган ва бўш хоналар, бандлик", Kind.TABLE, "full");
        add("demand-intensity", REVENUE, "Талаб динамикаси (pickup)", "Оценка интенсивности спроса",
                "Келгуси кунлар учун охирги 1 ва 7 кунда қанча хона сотилди (кунлик суратлар асосида)", Kind.TABLE, "full");
        add("financial", REVENUE, "Молиявий ҳисобот", "Финансовый отчет",
                "Кунлар бўйича даромад моддалари (яшаш, овқатланиш, бошқа) ва тушум; олдинги давр билан", Kind.TABLE, "full");

        // ---- Mijozlar va manbalar
        add("sources-chart", SOURCES, "Савдо каналлари (график)", "Заказчики и источники",
                "Даромаднинг савдо каналлари бўйича улуши", Kind.WIDGET, "narrow");
        add("sources", SOURCES, "Манбалар — умумий", "Заказчики и источники — сводный",
                "Манба бўйича: бронлар, кечалар, даромад, ADR, бекор қилиш", Kind.TABLE, "full");
        add("sources-detail", SOURCES, "Манбалар — батафсил", "Заказчики и источники — детальный",
                "Ҳар бир манба бўйича бронлар рўйхати", Kind.TABLE, "full");
        add("clients", SOURCES, "Мижозлар (тўловчилар)", "Заказчики и источники",
                "Энг кўп даромад келтирган мижозлар ва компаниялар", Kind.TABLE, "full");
        add("cancellations", SOURCES, "Бекор қилишлар", "Отчет по отменам",
                "Манба бўйича бронлар, бекор қилинганлар ва улуши, йўқотилган даромад", Kind.TABLE, "full");
        add("cancel-window", SOURCES, "Бекор қилиш ойнаси", "Окно аннуляций",
                "Келишдан неча кун олдин бекор қилинади — манбалар бўйича", Kind.TABLE, "full");
        add("agents", SOURCES, "Агентлар (OTA)", "Отчет по агентам",
                "Агентлар бўйича даромад ва тўланган комиссия", Kind.TABLE, "full");
        add("managers", SOURCES, "Менежерлар самарадорлиги", "Эффективность работы менеджеров",
                "Ходимлар қабул қилган тўловлар (бронни ким яратгани API'да йўқ)", Kind.TABLE, "full");
        add("tags", SOURCES, "Теглар", "Теги",
                "Exely API яшаш тегларини бермайди", Kind.UNAVAILABLE, "full");

        // ---- Moliya
        add("payments", FINANCE, "Тўловлар", "Платежи",
                "Даврдаги барча тўловлар ва қайтаришлар", Kind.TABLE, "full");
        add("payment-methods", FINANCE, "Тўлов усуллари", "Способы оплаты",
                "Тўловлар усул бўйича: нақд, карта, ўтказма, ...", Kind.TABLE, "full");
        add("deposit", FINANCE, "Депозит", "Депозит",
                "Exely API депозит операцияларини алоҳида ажратмайди", Kind.UNAVAILABLE, "full");
        add("services-summary", FINANCE, "Қўшимча хизматлар — умумий", "Допуслуги — сводный отчет",
                "Хизматлар бўйича сони ва суммаси", Kind.TABLE, "full");
        add("services-detail", FINANCE, "Қўшимча хизматлар — батафсил", "Допуслуги — детальный отчет",
                "Хизматлар кунлар бўйича", Kind.TABLE, "full");
        add("balances", FINANCE, "Бронлар баланси (қарздорлик)", "Балансы бронирований",
                "Қарз тоифалари ва энг катта қарздорлар; батафсил — Қарздорлик саҳифасида", Kind.TABLE, "full");

        // ---- USALI
        add("usali-summary", USALI, "Summary Operating Statement", "USALI — Summary Operating Statement",
                "Даромад, бўлим харажатлари, GOP, бошқарув ҳақи, EBITDA (харажатлар — ойма-ой киритилади)", Kind.TABLE, "full");
        add("usali-rooms", USALI, "Rooms бўлими", "USALI — Rooms Schedule",
                "Яшаш даромади сегментлар бўйича, харажатлар ва бўлим фойдаси", Kind.TABLE, "full");
        add("usali-fb", USALI, "Food & Beverage бўлими", "USALI — F&B Schedule",
                "Овқатланиш даромади, таннарх ва бўлим фойдаси", Kind.TABLE, "full");
        add("usali-other", USALI, "Бошқа бўлимлар ва даромадлар", "USALI — Other Operated Departments, Misc. Income",
                "Қўшимча хизматлар даромади ва харажати", Kind.TABLE, "full");
        add("usali-stats", USALI, "Операцион статистика", "USALI — Operating Statistics",
                "Occupancy, ADR, RevPAR, TRevPAR, ALOS, GOPPAR, CPOR ва бошқалар", Kind.TABLE, "full");
    }

    public static List<ReportDef> all() {
        return List.copyOf(ALL.values());
    }

    public static Optional<ReportDef> find(String key) {
        return Optional.ofNullable(ALL.get(key));
    }

    public static List<ReportDef> group(String group) {
        return ALL.values().stream().filter(r -> r.group().equals(group)).toList();
    }

    /** Ekranga qo'shsa bo'ladigan (mavjud) hisobot kaliti. */
    public static boolean isPlaceable(String key) {
        ReportDef d = ALL.get(key);
        return d != null && d.isAvailable();
    }
}
