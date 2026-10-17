package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.telegram.TelegramGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chorvoq Stay agentlik xizmatiga qo'shilmoqchi bo'lgan xonadon mulkdorlari reyestri: saytdagi so'rovnomadan avtomatik
 * yoziladi (yoki qo'lda qo'shiladi), holat bosqichlari bilan yuritiladi. Har yangi ariza va holat o'zgarishi Telegram
 * botga yuboriladi; har kuni ertalab — reyestr hisoboti (javobsiz qolganlar bilan).
 */
@Slf4j
@Service
public class StayOwnerService {

    /** Bosqichlar (tartib bilan) → nomi. */
    public static final Map<String, String> STATUSES = new LinkedHashMap<>();

    static {
        STATUSES.put("NEW", "Янги");
        STATUSES.put("CONTACTED", "Боғланилди");
        STATUSES.put("VIEWING", "Кўрик");
        STATUSES.put("AGREED", "Келишилди");
        STATUSES.put("CONNECTED", "Уланди");
        STATUSES.put("REJECTED", "Рад этилди");
    }

    /** Shuncha vaqt "Янги" turgan ariza hisobotda "javobsiz" deb ajratiladi. */
    static final Duration STALE = Duration.ofHours(24);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final JdbcTemplate jdbc;
    private final TelegramGateway telegram;
    private final StayRequestService requests;
    private final StayCatalogService catalog;
    private final Clock clock;
    private final boolean digestEnabled;

    public StayOwnerService(JdbcTemplate jdbc, TelegramGateway telegram, StayRequestService requests, StayCatalogService catalog,
                            Clock clock, @Value("${app.exely.scheduler-enabled:true}") boolean digestEnabled) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.requests = requests;
        this.catalog = catalog;
        this.clock = clock;
        this.digestEnabled = digestEnabled;
    }

    public record Owner(long id, String name, String phone, String building, String apartment, BigDecimal area,
                        String renovation, String note, String lang, String status, String comment, Long requestId,
                        Long unitId, String unitLabel, LocalDateTime createdAt, LocalDateTime statusChangedAt, String updatedBy) {

        public String statusName() {
            return STATUSES.getOrDefault(status, status);
        }

        public String apartmentLabel() {
            if (building == null && apartment == null) {
                return null;
            }
            return (building == null ? "?" : building) + "-уй, " + (apartment == null ? "?" : apartment) + "-кв.";
        }

        public boolean open() {
            return !"CONNECTED".equals(status) && !"REJECTED".equals(status);
        }
    }

    // ---------------------------------------------------------------- yozish

    /** Saytdagi rezident so'rovnomasi → reyestr (o'sha telefon va kvartira ochiq arizada bo'lsa — yangilanadi). */
    @Transactional
    public long fromRequest(long requestId, Map<String, String> fields, String lang) {
        String name = first(fields, "F.I.Sh.", "ФИО", "Ism");
        String phone = first(fields, "Telefon", "Телефон");
        String building = cut(first(fields, "Uy raqami"), 20);
        String apartment = cut(first(fields, "Kvartira raqami"), 20);
        BigDecimal area = decimal(first(fields, "Maydon, m²"));
        String renovation = cut(cyrillic(first(fields, "Remont holati")), 100);
        String note = first(fields, "Izoh");
        if ("—".equals(note)) {
            note = null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> existing = jdbc.queryForList("""
                select id from stay_owners where phone = ? and coalesce(building, '') = coalesce(?, '')
                  and coalesce(apartment, '') = coalesce(?, '') and status not in ('CONNECTED', 'REJECTED')
                order by id desc limit 1""", Long.class, phone, building, apartment);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            jdbc.update("""
                    update stay_owners set name = ?, area = coalesce(?, area), renovation = coalesce(?, renovation),
                        note = coalesce(?, note), request_id = ?, updated_at = ? where id = ?""",
                    cut(name, 150), area, renovation, cut(note, 1000), requestId, Timestamp.valueOf(now), id);
            return id;
        }
        return insert(name, phone, building, apartment, area, renovation, note, lang, requestId, null, now);
    }

    /** Qo'lda qo'shish (telefon orqali kelgan mulkdor). */
    @Transactional
    public long add(String name, String phone, String building, String apartment, BigDecimal area, String renovation,
                    String note, String by) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Мулкдор исмини киритинг");
        }
        if (phone == null || phone.replaceAll("\\D", "").length() < 9) {
            throw new IllegalArgumentException("Телефон рақамини киритинг");
        }
        long id = insert(name, phone, building, apartment, area, renovation, note, null, null, by, LocalDateTime.now(clock));
        notifyNew(get(id), "қўлда қўшилди" + (by == null ? "" : " (" + by + ")"));
        return id;
    }

    private long insert(String name, String phone, String building, String apartment, BigDecimal area, String renovation,
                        String note, String lang, Long requestId, String by, LocalDateTime now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into stay_owners (name, phone, building, apartment, area, renovation, note, lang, status, request_id,
                        created_at, updated_at, status_changed_at, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cut(name == null || name.isBlank() ? "—" : name.trim(), 150));
            ps.setString(2, cut(blank(phone), 40));
            ps.setString(3, cut(blank(building), 20));
            ps.setString(4, cut(blank(apartment), 20));
            ps.setBigDecimal(5, area);
            ps.setString(6, cut(blank(renovation), 100));
            ps.setString(7, cut(blank(note), 1000));
            ps.setString(8, cut(lang, 8));
            ps.setObject(9, requestId);
            Timestamp t = Timestamp.valueOf(now);
            ps.setTimestamp(10, t);
            ps.setTimestamp(11, t);
            ps.setTimestamp(12, t);
            ps.setString(13, cut(by, 100));
            return ps;
        }, key);
        return key.getKey() == null ? 0 : key.getKey().longValue();
    }

    /**
     * Holat va izoh. "Уланди" bo'lganda kvartira (uy + kvartira raqami bo'yicha) stay_units'ga bog'lanadi — bo'lmasa
     * "Тайёрланмоқда" holatida yaratiladi (toifa — maydon bo'yicha). Holat o'zgarsa — botga xabar.
     */
    @Transactional
    public void update(long id, String status, String comment, String by) {
        if (!STATUSES.containsKey(status)) {
            throw new IllegalArgumentException("Ҳолатни танланг");
        }
        Owner before = get(id);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean changed = !before.status().equals(status);
        jdbc.update("""
                update stay_owners set status = ?, comment = ?, updated_at = ?, updated_by = ?,
                    status_changed_at = case when status = ? then status_changed_at else ? end
                where id = ?""", status, cut(blank(comment), 1000), Timestamp.valueOf(now), cut(by, 100), status,
                Timestamp.valueOf(now), id);
        if ("CONNECTED".equals(status) && before.unitId() == null) {
            connectUnit(before);
        }
        if (before.requestId() != null && !"NEW".equals(status)) {
            requests.markDone(before.requestId(), by);
        }
        if (changed) {
            notifyStatus(get(id), before.statusName(), by);
        }
    }

    private void connectUnit(Owner o) {
        if (o.building() == null || o.apartment() == null) {
            throw new IllegalArgumentException("«Уланди» учун уй ва квартира рақами керак — аввал уларни киритинг");
        }
        List<Long> unit = jdbc.queryForList("select id from stay_units where building = ? and number = ?", Long.class,
                o.building(), o.apartment());
        long unitId;
        if (!unit.isEmpty()) {
            unitId = unit.get(0);
            jdbc.update("update stay_units set owner_name = coalesce(owner_name, ?), owner_phone = coalesce(owner_phone, ?) where id = ?",
                    o.name(), o.phone(), unitId);
        } else {
            String code = StayCatalogService.suggestCode(o.area());
            long categoryId = catalog.categories().stream().filter(c -> c.code().equals(code)).findFirst()
                    .orElse(catalog.categories().get(0)).id();
            unitId = catalog.saveUnit(null, categoryId, o.building(), o.apartment(), o.area(), null, "PREPARING",
                    o.name(), o.phone(), o.renovation());
        }
        jdbc.update("update stay_owners set unit_id = ? where id = ?", unitId, o.id());
    }

    /** Ma'lumotlarni tahrirlash (ism, telefon, kvartira, maydon). */
    @Transactional
    public void edit(long id, String name, String phone, String building, String apartment, BigDecimal area, String renovation,
                     String note, String by) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Мулкдор исмини киритинг");
        }
        if (jdbc.update("""
                update stay_owners set name = ?, phone = ?, building = ?, apartment = ?, area = ?, renovation = ?, note = ?,
                    updated_at = ?, updated_by = ? where id = ?""",
                cut(name.trim(), 150), cut(blank(phone), 40), cut(blank(building), 20), cut(blank(apartment), 20), area,
                cut(blank(renovation), 100), cut(blank(note), 1000), Timestamp.valueOf(LocalDateTime.now(clock)), cut(by, 100), id) == 0) {
            throw new IllegalArgumentException("Мулкдор топилмади");
        }
    }

    // ---------------------------------------------------------------- o'qish

    private static final String SELECT = """
            select o.id, o.name, o.phone, o.building, o.apartment, o.area, o.renovation, o.note, o.lang, o.status, o.comment,
                   o.request_id, o.unit_id, case when u.id is null then null else concat(u.building, '-уй, ', u.number, '-кв.') end,
                   o.created_at, o.status_changed_at, o.updated_by
            from stay_owners o left join stay_units u on u.id = o.unit_id""";

    public List<Owner> list(String status) {
        if (status == null) {
            return jdbc.query(SELECT + " order by field(o.status, 'NEW', 'CONTACTED', 'VIEWING', 'AGREED', 'CONNECTED', 'REJECTED'), o.created_at desc",
                    this::map);
        }
        return jdbc.query(SELECT + " where o.status = ? order by o.created_at desc", this::map, status);
    }

    public Owner get(long id) {
        List<Owner> l = jdbc.query(SELECT + " where o.id = ?", this::map, id);
        if (l.isEmpty()) {
            throw new IllegalArgumentException("Мулкдор топилмади");
        }
        return l.get(0);
    }

    /** Holat → soni (barcha bosqichlar, 0 bo'lsa ham). */
    public Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        STATUSES.keySet().forEach(s -> m.put(s, 0L));
        jdbc.query("select status, count(*) from stay_owners group by status", rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    private Owner map(ResultSet rs, int i) throws SQLException {
        return new Owner(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                (Long) rs.getObject(12, Long.class), (Long) rs.getObject(13, Long.class), rs.getString(14),
                rs.getTimestamp(15).toLocalDateTime(), rs.getTimestamp(16).toLocalDateTime(), rs.getString(17));
    }

    // ---------------------------------------------------------------- Telegram

    private void notifyNew(Owner o, String how) {
        send("🏠 <b>Chorvoq Stay — янги мулкдор</b> (" + esc(how) + ")\n\n" + card(o));
    }

    private void notifyStatus(Owner o, String from, String by) {
        send("🏠 <b>Мулкдор №" + o.id() + "</b>: " + esc(from) + " → <b>" + esc(o.statusName()) + "</b>"
                + (by == null ? "" : " · " + esc(by)) + "\n\n" + card(o));
    }

    static String card(Owner o) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(esc(o.name())).append("</b>");
        if (o.phone() != null) {
            sb.append(" · ").append(esc(o.phone()));
        }
        sb.append('\n');
        if (o.apartmentLabel() != null) {
            sb.append("🏢 ").append(esc(o.apartmentLabel()));
        }
        if (o.area() != null) {
            sb.append(" · ").append(o.area().stripTrailingZeros().toPlainString()).append(" м²");
        }
        if (o.renovation() != null) {
            sb.append(" · ").append(esc(o.renovation()));
        }
        sb.append('\n');
        if (o.note() != null) {
            sb.append("💬 ").append(esc(o.note())).append('\n');
        }
        if (o.comment() != null) {
            sb.append("📝 ").append(esc(o.comment())).append('\n');
        }
        if (o.unitLabel() != null) {
            sb.append("🔑 Квартиралар рўйхатида: ").append(esc(o.unitLabel())).append('\n');
        }
        return sb.toString();
    }

    /** Har kuni ertalab: bosqichlar bo'yicha soni va javobsiz (24 soatdan ortiq "Янги") arizalar. */
    @Scheduled(cron = "${app.stay.owners-digest-cron:0 0 9 * * *}", zone = "${app.zone:Asia/Tashkent}")
    public void dailyDigest() {
        if (digestEnabled) {
            String text = digest();
            if (text != null) {
                send(text);
            }
        }
    }

    /** Hisobot matni; ochiq ariza yo'q bo'lsa — null (bo'sh xabar yuborilmaydi). */
    String digest() {
        Map<String, Long> counts = counts();
        List<Owner> open = list(null).stream().filter(Owner::open).toList();
        if (open.isEmpty()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        StringBuilder sb = new StringBuilder("🏠 <b>Chorvoq Stay — мулкдорлар</b> (").append(now.format(TIME)).append(")\n\n");
        counts.forEach((k, v) -> {
            if (v > 0) {
                sb.append(STATUSES.get(k)).append(": <b>").append(v).append("</b>\n");
            }
        });
        List<Owner> stale = open.stream().filter(o -> "NEW".equals(o.status())
                && o.createdAt().isBefore(now.minus(STALE))).toList();
        if (!stale.isEmpty()) {
            sb.append("\n⚠️ <b>Жавобсиз (24 соатдан ортиқ):</b>\n");
            stale.stream().limit(15).forEach(o -> sb.append("• №").append(o.id()).append(' ').append(esc(o.name()))
                    .append(o.phone() == null ? "" : " · " + esc(o.phone()))
                    .append(o.apartmentLabel() == null ? "" : " · " + esc(o.apartmentLabel()))
                    .append(" · ").append(o.createdAt().format(TIME)).append('\n'));
        }
        return sb.toString();
    }

    private void send(String text) {
        String t = text.length() > 4000 ? text.substring(0, 4000) : text;
        for (Long chat : requests.recipients()) {
            try {
                telegram.sendMessage(chat, t, null);
            } catch (RuntimeException e) {
                log.warn("Chorvoq Stay mulkdor xabari Telegram'ga yuborilmadi: {}", e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- yordamchilar

    private static String first(Map<String, String> fields, String... keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** Sayt (lotin) qiymatlari → kirill (HotelPulse interfeysi faqat kirillcha). */
    private static final Map<String, String> RENOVATION = Map.of(
            "Remont bor, mebel va texnika yo'q", "Ремонт бор, мебел ва техника йўқ",
            "Remont va mebel bor", "Ремонт ва мебел бор",
            "Qisman remont", "Қисман ремонт",
            "Remontsiz (qora suvoq)", "Ремонтсиз (қора сувоқ)");

    static String cyrillic(String renovation) {
        return renovation == null ? null : RENOVATION.getOrDefault(renovation, renovation);
    }

    static BigDecimal decimal(String s) {
        if (s == null) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s.trim().replace(',', '.'));
            return v.signum() > 0 && v.compareTo(BigDecimal.valueOf(10000)) < 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String esc(String s) {
        return StayRequestService.esc(s);
    }
}
