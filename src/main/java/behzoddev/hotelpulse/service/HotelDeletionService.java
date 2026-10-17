package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.exely.ExelySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mehmonxonani butunlay o'chirish (Бошқарув → Меҳмонхоналар). Barcha bog'liq ma'lumotlar (bronlar, to'lovlar, xizmatlar,
 * agentlar va ularning jadvallari, ESF, topshiriqlar, bo'limlar, foydalanuvchi biriktirishlari) baza darajasida
 * (ON DELETE CASCADE) birga o'chadi. Qaytarib bo'lmaydi — tasdiq uchun mehmonxona nomi to'liq yozilishi shart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelDeletionService {

    /** O'chishdan oldin ko'rsatiladigan ma'lumotlar: jadval → nomi. */
    private static final Map<String, String> DATA = new LinkedHashMap<>();

    static {
        DATA.put("bookings", "брон ва яшаш");
        DATA.put("payments", "тўлов");
        DATA.put("service_revenue", "хизмат қатори");
        DATA.put("agents", "агент");
        DATA.put("agent_tables", "агент жадвали қисми");
        DATA.put("faktura_documents", "ЭСФ ҳужжати");
        DATA.put("tasks", "топшириқ");
        DATA.put("departments", "бўлим");
        DATA.put("usali_expenses", "USALI харажати");
        DATA.put("user_hotels", "фойдаланувчи бириктируви");
    }

    private final JdbcTemplate jdbc;
    private final HotelService hotels;
    private final ExelySyncService exelySync;

    /** Nima o'chishi: nomi → soni (0 bo'lganlari ko'rsatilmaydi). */
    @Transactional(readOnly = true)
    public Map<String, Long> summary(long hotelId) {
        Map<String, Long> m = new LinkedHashMap<>();
        DATA.forEach((table, label) -> {
            Long n = jdbc.queryForObject("select count(*) from " + table + " where hotel_id = ?", Long.class, hotelId);
            if (n != null && n > 0) {
                m.put(label, n);
            }
        });
        return m;
    }

    /** Chorvoq Stay sayti bilan bog'langan mehmonxona — o'chirib bo'lmaydi (sayt uni darhol qayta yaratadi). */
    public boolean isStayHotel(long hotelId) {
        Integer n = jdbc.queryForObject("select count(*) from stay_settings where hotel_id = ?", Integer.class, hotelId);
        return n != null && n > 0;
    }

    /**
     * @param confirmName foydalanuvchi qayta yozgan nom (tasodifiy o'chirishga qarshi)
     * @return o'chirilgan mehmonxona nomi
     */
    @Transactional
    public String delete(long hotelId, String confirmName, String by) {
        Hotel hotel = hotels.getById(hotelId);
        if (confirmName == null || !confirmName.trim().equals(hotel.getName().trim())) {
            throw new IllegalArgumentException("Тасдиқлаш учун меҳмонхона номини айнан ёзинг: «" + hotel.getName() + "»");
        }
        if (isStayHotel(hotelId)) {
            throw new IllegalArgumentException("Бу меҳмонхона chorvoqstay.uz сайти билан боғланган — уни ўчириб бўлмайди");
        }
        if (exelySync.isRunning(hotelId)) {
            throw new IllegalArgumentException("Ҳозир синхронланмоқда — тугагач қайта уриниб кўринг");
        }
        Map<String, Long> what = summary(hotelId);
        if (jdbc.update("delete from hotels where id = ?", hotelId) == 0) {
            throw new IllegalArgumentException("Меҳмонхона топилмади");
        }
        log.warn("Mehmonxona o'chirildi: {} (id {}) — {}; o'chirgan: {}", hotel.getName(), hotelId, what, by);
        return hotel.getName();
    }
}
