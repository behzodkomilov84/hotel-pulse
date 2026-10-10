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

    public static final String OPS = "Kunlik operatsiya";
    public static final String REVENUE = "Daromad va bandlik";
    public static final String SOURCES = "Mijozlar va manbalar";
    public static final String FINANCE = "Moliya";
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
        add("today", OPS, "Bugun", "Сводная статистика (на сегодня)",
                "Bugungi bandlik, keladigan va ketadiganlar, qarzdorlik", Kind.WIDGET, "full");
        add("arrivals", OPS, "Kelishlar", "Заезды",
                "Davrda keladigan mehmonlar: bron, xona turi, xona, kechalar, summa, qoldiq", Kind.TABLE, "full");
        add("departures", OPS, "Ketishlar", "Выезды",
                "Davrda ketadigan mehmonlar va ularning qoldig'i", Kind.TABLE, "full");
        add("inhouse", OPS, "Band xonalar", "Занятые номера",
                "Tanlangan kunda yashayotgan mehmonlar: xona, kelish-ketish, qoldiq", Kind.TABLE, "full");
        add("guests", OPS, "Mehmonlar ro'yxati", "Список гостей",
                "Davrda yashagan va bron qilgan mehmonlar: tashriflar, kechalar, summa", Kind.TABLE, "full");
        add("meals", OPS, "Ovqatlanish", "Отчет по питанию",
                "Kunlar bo'yicha mehmonlar soni va sotilgan ovqatlanish (nonushta) summasi", Kind.TABLE, "full");
        add("housekeeping", OPS, "Xonalarni tozalash", "Обслуживание номеров",
                "Exely API housekeeping ma'lumotini bermaydi", Kind.UNAVAILABLE, "full");
        add("activity-log", OPS, "Foydalanuvchilar harakati jurnali", "Журнал активности пользователей",
                "Exely API foydalanuvchilar harakati jurnalini bermaydi", Kind.UNAVAILABLE, "full");

        // ---- Daromad va bandlik
        add("kpi", REVENUE, "Asosiy ko'rsatkichlar", "Сводная статистика",
                "Bandlik, ADR, RevPAR, daromad, to'lovlar, bronlar, bekor qilinganlar, o'rtacha yashash", Kind.WIDGET, "full");
        add("flow", REVENUE, "Daromad va tushum", null,
                "Davr daromadi, tushgan to'lovlar va ular orasidagi farq", Kind.WIDGET, "full");
        add("daily-chart", REVENUE, "Kunlik bandlik va daromad (grafik)", "Доходность и загрузка",
                "Kunlar bo'yicha bandlik va daromad grafigi", Kind.WIDGET, "wide");
        add("daily", REVENUE, "Kunlik daromad va bandlik (jadval)", "Доходность и загрузка",
                "Har kun: sotilgan xonalar, bandlik, ADR, RevPAR, daromad", Kind.TABLE, "full");
        add("manager-period", REVENUE, "Menejer hisoboti (davr)", "Отчет менеджера за период",
                "Asosiy ko'rsatkichlar: davr, oldingi davr va o'zgarish", Kind.TABLE, "full");
        add("flash", REVENUE, "Menejer hisoboti (sana)", "Отчет менеджера на дату",
                "Flash report: kun, oy boshidan va yil boshidan ko'rsatkichlar", Kind.TABLE, "full");
        add("room-types", REVENUE, "Xona turlari bo'yicha daromad", "Доходность по тарифам",
                "Xona turlari: sotilgan kechalar, bandlik, ADR, daromad ulushi (tarif rejasi API'da yo'q)", Kind.TABLE, "full");
        add("rate-plans", REVENUE, "Tariflar bo'yicha daromad", "Доходность по тарифам",
                "Exely API bronning tarif rejasini bermaydi — o'rniga «Xona turlari bo'yicha daromad»", Kind.UNAVAILABLE, "full");
        add("history-forecast", REVENUE, "Tarix va prognoz", "История и прогноз",
                "Oylar bo'yicha: o'tgan oylar — haqiqiy, kelgusi oylar — hozirgacha bron qilingani", Kind.TABLE, "full");
        add("demand-calendar", REVENUE, "Talab kalendari", "Календарь спроса",
                "Kelgusi 60 kun: sotilgan va bo'sh xonalar, bandlik", Kind.TABLE, "full");
        add("demand-intensity", REVENUE, "Talab dinamikasi (pickup)", "Оценка интенсивности спроса",
                "Kelgusi kunlar uchun oxirgi 1 va 7 kunda qancha xona sotildi (kunlik suratlar asosida)", Kind.TABLE, "full");
        add("financial", REVENUE, "Moliyaviy hisobot", "Финансовый отчет",
                "Kunlar bo'yicha daromad moddalari (yashash, ovqatlanish, boshqa) va tushum; oldingi davr bilan", Kind.TABLE, "full");

        // ---- Mijozlar va manbalar
        add("sources-chart", SOURCES, "Savdo kanallari (grafik)", "Заказчики и источники",
                "Daromadning savdo kanallari bo'yicha ulushi", Kind.WIDGET, "narrow");
        add("sources", SOURCES, "Manbalar — umumiy", "Заказчики и источники — сводный",
                "Manba bo'yicha: bronlar, kechalar, daromad, ADR, bekor qilish", Kind.TABLE, "full");
        add("sources-detail", SOURCES, "Manbalar — batafsil", "Заказчики и источники — детальный",
                "Har bir manba bo'yicha bronlar ro'yxati", Kind.TABLE, "full");
        add("clients", SOURCES, "Mijozlar (to'lovchilar)", "Заказчики и источники",
                "Eng ko'p daromad keltirgan mijozlar va kompaniyalar", Kind.TABLE, "full");
        add("cancellations", SOURCES, "Bekor qilishlar", "Отчет по отменам",
                "Manba bo'yicha bronlar, bekor qilinganlar va ulushi, yo'qotilgan daromad", Kind.TABLE, "full");
        add("cancel-window", SOURCES, "Bekor qilish oynasi", "Окно аннуляций",
                "Kelishdan necha kun oldin bekor qilinadi — manbalar bo'yicha", Kind.TABLE, "full");
        add("agents", SOURCES, "Agentlar (OTA)", "Отчет по агентам",
                "Agentlar bo'yicha daromad va to'langan komissiya", Kind.TABLE, "full");
        add("managers", SOURCES, "Menejerlar samaradorligi", "Эффективность работы менеджеров",
                "Xodimlar qabul qilgan to'lovlar (bronni kim yaratgani API'da yo'q)", Kind.TABLE, "full");
        add("tags", SOURCES, "Teglar", "Теги",
                "Exely API yashash teglarini bermaydi", Kind.UNAVAILABLE, "full");

        // ---- Moliya
        add("payments", FINANCE, "To'lovlar", "Платежи",
                "Davrdagi barcha to'lovlar va qaytarishlar", Kind.TABLE, "full");
        add("payment-methods", FINANCE, "To'lov usullari", "Способы оплаты",
                "To'lovlar usul bo'yicha: naqd, karta, o'tkazma, ...", Kind.TABLE, "full");
        add("deposit", FINANCE, "Depozit", "Депозит",
                "Exely API depozit operatsiyalarini alohida ajratmaydi", Kind.UNAVAILABLE, "full");
        add("services-summary", FINANCE, "Qo'shimcha xizmatlar — umumiy", "Допуслуги — сводный отчет",
                "Xizmatlar bo'yicha soni va summasi", Kind.TABLE, "full");
        add("services-detail", FINANCE, "Qo'shimcha xizmatlar — batafsil", "Допуслуги — детальный отчет",
                "Xizmatlar kunlar bo'yicha", Kind.TABLE, "full");
        add("balances", FINANCE, "Bronlar balansi (qarzdorlik)", "Балансы бронирований",
                "Qarz toifalari va eng katta qarzdorlar; batafsil — Qarzdorlik sahifasida", Kind.TABLE, "full");

        // ---- USALI
        add("usali-summary", USALI, "Summary Operating Statement", "USALI — Summary Operating Statement",
                "Daromad, bo'lim xarajatlari, GOP, boshqaruv haqi, EBITDA (xarajatlar — oyma-oy kiritiladi)", Kind.TABLE, "full");
        add("usali-rooms", USALI, "Rooms bo'limi", "USALI — Rooms Schedule",
                "Yashash daromadi segmentlar bo'yicha, xarajatlar va bo'lim foydasi", Kind.TABLE, "full");
        add("usali-fb", USALI, "Food & Beverage bo'limi", "USALI — F&B Schedule",
                "Ovqatlanish daromadi, tannarx va bo'lim foydasi", Kind.TABLE, "full");
        add("usali-other", USALI, "Boshqa bo'limlar va daromadlar", "USALI — Other Operated Departments, Misc. Income",
                "Qo'shimcha xizmatlar daromadi va xarajati", Kind.TABLE, "full");
        add("usali-stats", USALI, "Operatsion statistika", "USALI — Operating Statistics",
                "Occupancy, ADR, RevPAR, TRevPAR, ALOS, GOPPAR, CPOR va boshqalar", Kind.TABLE, "full");
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
