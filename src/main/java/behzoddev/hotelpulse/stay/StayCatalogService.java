package behzoddev.hotelpulse.stay;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.service.HotelService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chorvoq Stay katalogi: toifalar (saytdagi xona turlari), narx davrlari va kvartiralar.
 * Qoidalar (joylashtirish shartnomasi): narx mulkdorlar bilan kelishilgan minimal narxdan past bo'lmaydi;
 * bir toifada narx davrlari ustma-ust tushmaydi. Chorvoq Stay HotelPulse'da mehmonxona sifatida turadi —
 * xonalar soni = sotuvdagi (ACTIVE) kvartiralar.
 */
@Service
@RequiredArgsConstructor
public class StayCatalogService {

    public static final Set<String> STATUSES = Set.of("ACTIVE", "PREPARING", "INACTIVE");
    static final String HOTEL_NAME = "Chorvoq Stay";

    private final JdbcTemplate jdbc;
    private final HotelService hotelService;
    private final Clock clock;

    /** @param activeUnits sotuvdagi kvartiralar, units — barchasi */
    public record Category(long id, String code, String name, String areaText, int capacity, int extraBeds,
                           BigDecimal basePrice, BigDecimal minPrice, boolean active, int sort,
                           long activeUnits, long units) {
    }

    public record RatePeriod(long id, long categoryId, String categoryName, LocalDate from, LocalDate to,
                             BigDecimal price, String note) {

        public long nights() {
            return ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    public record Unit(long id, long categoryId, String categoryName, String building, String number,
                       BigDecimal area, Integer floor, String status, String ownerName, String ownerPhone, String note) {

        public String label() {
            return building + "-uy, " + number + "-kv.";
        }
    }

    // ---------------------------------------------------------------- Toifalar

    public List<Category> categories() {
        return jdbc.query("""
                select c.id, c.code, c.name, c.area_text, c.capacity, c.extra_beds, c.base_price, c.min_price, c.active, c.sort,
                       (select count(*) from stay_units u where u.category_id = c.id and u.status = 'ACTIVE'),
                       (select count(*) from stay_units u where u.category_id = c.id)
                from stay_categories c order by c.sort, c.id""",
                (rs, i) -> new Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBoolean(9), rs.getInt(10),
                        rs.getLong(11), rs.getLong(12)));
    }

    public Category category(long id) {
        return categories().stream().filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Тоифа топилмади"));
    }

    @Transactional
    public void updateCategory(long id, String name, String areaText, int capacity, int extraBeds,
                               BigDecimal basePrice, BigDecimal minPrice, boolean active) {
        category(id);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Тоифа номини киритинг");
        }
        if (capacity < 1 || capacity > 30 || extraBeds < 0 || extraBeds > 10) {
            throw new IllegalArgumentException("Сиғим 1–30, қўшимча ўрин 0–10 бўлиши керак");
        }
        if (basePrice == null || basePrice.signum() <= 0) {
            throw new IllegalArgumentException("Кечалик нархни киритинг");
        }
        if (minPrice != null && minPrice.signum() < 0) {
            throw new IllegalArgumentException("Минимал нарх манфий бўлмайди");
        }
        if (minPrice != null && basePrice.compareTo(minPrice) < 0) {
            throw new IllegalArgumentException("Кечалик нарх минимал нархдан (" + money(minPrice) + ") паст бўлмаслиги керак");
        }
        if (minPrice != null) {
            List<String> below = jdbc.queryForList("""
                    select concat(date_format(date_from, '%d.%m.%Y'), '–', date_format(date_to, '%d.%m.%Y'))
                    from stay_rate_periods where category_id = ? and price < ? and date_to >= ?""",
                    String.class, id, minPrice, Date.valueOf(today()));
            if (!below.isEmpty()) {
                throw new IllegalArgumentException("Бу даврлардаги нарх янги минимал нархдан паст: " + String.join(", ", below)
                        + " — аввал уларни ўзгартиринг");
            }
        }
        jdbc.update("""
                update stay_categories set name = ?, area_text = ?, capacity = ?, extra_beds = ?, base_price = ?, min_price = ?,
                    active = ? where id = ?""",
                name.trim(), blank(areaText), capacity, extraBeds, basePrice, minPrice, active, id);
    }

    // ---------------------------------------------------------------- Narx davrlari

    /** Davrlar: o'tganlari (30 kundan eski) ko'rsatilmaydi. */
    public List<RatePeriod> periods() {
        return jdbc.query("""
                select p.id, p.category_id, c.name, p.date_from, p.date_to, p.price, p.note
                from stay_rate_periods p join stay_categories c on c.id = p.category_id
                where p.date_to >= ? order by p.date_from, c.sort""",
                (rs, i) -> new RatePeriod(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate(),
                        rs.getDate(5).toLocalDate(), rs.getBigDecimal(6), rs.getString(7)),
                Date.valueOf(today().minusDays(30)));
    }

    @Transactional
    public void addPeriod(long categoryId, LocalDate from, LocalDate to, BigDecimal price, String note) {
        Category c = category(categoryId);
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("Даврнинг бошланиши ва тугашини тўғри киритинг");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Давр бир йилдан узун бўлмаслиги керак");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Кечалик нархни киритинг");
        }
        if (c.minPrice() != null && price.compareTo(c.minPrice()) < 0) {
            throw new IllegalArgumentException(c.name() + ": нарх минимал нархдан (" + money(c.minPrice()) + ") паст бўлмаслиги керак");
        }
        List<String> overlap = jdbc.queryForList("""
                select concat(date_format(date_from, '%d.%m.%Y'), '–', date_format(date_to, '%d.%m.%Y'))
                from stay_rate_periods where category_id = ? and date_from <= ? and date_to >= ?""",
                String.class, categoryId, Date.valueOf(to), Date.valueOf(from));
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(c.name() + ": бу кунларда нарх аллақачон белгиланган (" + String.join(", ", overlap)
                    + ") — аввал ўшани ўчиринг");
        }
        jdbc.update("insert into stay_rate_periods (category_id, date_from, date_to, price, note, created_at) values (?, ?, ?, ?, ?, ?)",
                categoryId, Date.valueOf(from), Date.valueOf(to), price, blank(note), Timestamp.valueOf(LocalDateTime.now(clock)));
    }

    public void deletePeriod(long id) {
        jdbc.update("delete from stay_rate_periods where id = ?", id);
    }

    /** Shu kun kechasining narxi: davr bo'lsa — davr narxi, aks holda toifaning kechalik narxi. */
    public BigDecimal priceFor(long categoryId, LocalDate night) {
        List<BigDecimal> p = jdbc.queryForList("""
                select price from stay_rate_periods where category_id = ? and date_from <= ? and date_to >= ? limit 1""",
                BigDecimal.class, categoryId, Date.valueOf(night), Date.valueOf(night));
        return p.isEmpty() ? category(categoryId).basePrice() : p.get(0);
    }

    /** Saytdagi "kechasi ... so'mdan": oddiy narx va kelgusi davrlar narxlarining eng kichigi. */
    Map<Long, BigDecimal> fromPrices() {
        Map<Long, BigDecimal> result = categories().stream()
                .collect(Collectors.toMap(Category::id, Category::basePrice));
        jdbc.query("select category_id, min(price) from stay_rate_periods where date_to >= ? group by category_id",
                rs -> {
                    BigDecimal min = rs.getBigDecimal(2);
                    result.computeIfPresent(rs.getLong(1), (k, base) -> base.min(min));
                }, Date.valueOf(today()));
        return result;
    }

    // ---------------------------------------------------------------- Kvartiralar

    public List<Unit> units() {
        return jdbc.query("""
                select u.id, u.category_id, c.name, u.building, u.number, u.area, u.floor, u.status, u.owner_name, u.owner_phone, u.note
                from stay_units u join stay_categories c on c.id = u.category_id
                order by u.building + 0, u.building, u.number + 0, u.number""",
                (rs, i) -> new Unit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBigDecimal(6), (Integer) rs.getObject(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11)));
    }

    /** @param id null — yangi kvartira */
    @Transactional
    public long saveUnit(Long id, long categoryId, String building, String number, BigDecimal area, Integer floor,
                         String status, String ownerName, String ownerPhone, String note) {
        category(categoryId);
        if (building == null || building.isBlank() || number == null || number.isBlank()) {
            throw new IllegalArgumentException("Уй ва квартира рақамини киритинг");
        }
        if (area != null && (area.compareTo(BigDecimal.TEN) < 0 || area.compareTo(BigDecimal.valueOf(400)) > 0)) {
            throw new IllegalArgumentException("Майдон 10–400 м² бўлиши керак");
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Ҳолатни танланг");
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        try {
            if (id == null) {
                jdbc.update("""
                        insert into stay_units (category_id, building, number, area, floor, status, owner_name, owner_phone, note,
                            created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        categoryId, building.trim(), number.trim(), area, floor, status, blank(ownerName), blank(ownerPhone),
                        blank(note), now, now);
                id = jdbc.queryForObject("select id from stay_units where building = ? and number = ?", Long.class,
                        building.trim(), number.trim());
            } else if (jdbc.update("""
                    update stay_units set category_id = ?, building = ?, number = ?, area = ?, floor = ?, status = ?, owner_name = ?,
                        owner_phone = ?, note = ?, updated_at = ? where id = ?""",
                    categoryId, building.trim(), number.trim(), area, floor, status, blank(ownerName), blank(ownerPhone),
                    blank(note), now, id) == 0) {
                throw new IllegalArgumentException("Квартира топилмади");
            }
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(building.trim() + "-уй, " + number.trim() + "-квартира аллақачон қўшилган");
        }
        syncHotelRooms();
        return id;
    }

    @Transactional
    public void deleteUnit(long id) {
        jdbc.update("delete from stay_units where id = ?", id);
        syncHotelRooms();
    }

    /** Maydon bo'yicha taxminiy toifa (saytdagi rezident so'rovnomasi bilan bir xil chegaralar). */
    public static String suggestCode(BigDecimal area) {
        if (area == null) {
            return null;
        }
        double a = area.doubleValue();
        return a < 32 ? "studio" : a < 52 ? "one" : a < 75 ? "two" : a < 106 ? "family" : "grand";
    }

    // ---------------------------------------------------------------- HotelPulse'dagi mehmonxona

    /** Chorvoq Stay mehmonxonasi (yo'q bo'lsa — yaratiladi): bronlar, KPI va dashboard shu yerda. */
    @Transactional
    public Hotel hotel() {
        Long id = jdbc.queryForObject("select hotel_id from stay_settings where id = 1", Long.class);
        if (id != null) {
            Long exists = jdbc.queryForObject("select count(*) from hotels where id = ?", Long.class, id);
            if (exists != null && exists > 0) {
                return hotelService.getById(id);
            }
        }
        Hotel h = hotelService.saveBasic(null, HOTEL_NAME, "Chorvoq", 0, true, "UZS", true, null);
        jdbc.update("update stay_settings set hotel_id = ? where id = 1", h.getId());
        syncHotelRooms(h.getId());
        return h;
    }

    void syncHotelRooms() {
        syncHotelRooms(hotel().getId());
    }

    private void syncHotelRooms(long hotelId) {
        jdbc.update("""
                update hotels set rooms_count = (select count(*) from stay_units where status = 'ACTIVE'), rooms_count_manual = true
                where id = ?""", hotelId);
    }

    // ---------------------------------------------------------------- yordamchilar

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String money(BigDecimal v) {
        return String.format("%,d", v.longValue()).replace(',', ' ') + " сўм";
    }
}
