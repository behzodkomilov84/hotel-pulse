package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class HotelService {

    public static final List<String> SUPPORTED_CURRENCIES = List.of("UZS", "USD", "EUR");

    private final HotelRepository hotelRepository;
    private final behzoddev.hotelpulse.repository.DepartmentRepository departmentRepository;

    /** OWNER — hamma mehmonxonalar; qolganlar — faqat o'ziga biriktirilganlari. */
    @Transactional(readOnly = true)
    public List<Hotel> accessibleHotels(CustomUserDetails user) {
        return user.isOwner()
                ? hotelRepository.findAllByOrderByNameAsc()
                : hotelRepository.findAllByUserId(user.getId());
    }

    /** Mehmonxonaga kirish huquqini tekshiradi — boshqa mehmonxona id'sini URL'ga yozib ko'rishga qarshi. */
    @Transactional(readOnly = true)
    public Hotel getAccessible(CustomUserDetails user, Long hotelId) {
        if (!user.isOwner() && !hotelRepository.isUserLinked(user.getId(), hotelId)) {
            throw new AccessDeniedException("Bu mehmonxonaga ruxsat yo'q");
        }
        return getById(hotelId);
    }

    @Transactional(readOnly = true)
    public List<Hotel> findAll() {
        return hotelRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Hotel getById(Long id) {
        return hotelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Mehmonxona topilmadi"));
    }

    @Transactional
    public Hotel save(Long id, String name, String city, int roomsCount, String currency,
                      String exelyPropertyId, String exelyClientId, String exelyClientSecret,
                      String exelyPmsKey, boolean active) {
        return save(id, name, city, roomsCount, null, currency,
                exelyPropertyId, exelyClientId, exelyClientSecret, exelyPmsKey, active);
    }

    @Transactional
    public Hotel save(Long id, String name, String city, int roomsCount, Boolean roomsCountManual, String currency,
                      String exelyPropertyId, String exelyClientId, String exelyClientSecret,
                      String exelyPmsKey, boolean active) {
        return save(id, name, city, roomsCount, roomsCountManual, currency,
                exelyPropertyId, exelyClientId, exelyClientSecret, exelyPmsKey, active, null);
    }

    /**
     * @param roomsCountManual true — Exely sinxronlashi xonalar sonini o'zgartirmaydi; null — avvalgidek qoladi
     * @param dailyReportTime  kunlik Telegram hisobot vaqti (mehmonxona vaqti); null — avvalgidek qoladi
     */
    @Transactional
    public Hotel save(Long id, String name, String city, int roomsCount, Boolean roomsCountManual, String currency,
                      String exelyPropertyId, String exelyClientId, String exelyClientSecret,
                      String exelyPmsKey, boolean active, LocalTime dailyReportTime) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mehmonxona nomi bo'sh bo'lmasligi kerak");
        }
        if (roomsCount < 0) {
            throw new IllegalArgumentException("Xonalar soni manfiy bo'lmasligi kerak");
        }
        Hotel hotel = id == null ? new Hotel() : getById(id);
        hotel.setName(name.trim());
        hotel.setCity(blankToNull(city));
        hotel.setRoomsCount(roomsCount);
        if (roomsCountManual != null) {
            hotel.setRoomsCountManual(roomsCountManual);
        }
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Valyutani tanlang");
        }
        hotel.setCurrency(currency);
        String newPropertyId = blankToNull(exelyPropertyId);
        String newClientId = blankToNull(exelyClientId);
        // Boshqa mehmonxona/akkauntga ulansa — sinxronlash boshidan boshlanishi kerak.
        if (!Objects.equals(newPropertyId, hotel.getExelyPropertyId())
                || !Objects.equals(newClientId, hotel.getExelyClientId())) {
            hotel.setExelyContinueToken(null);
        }
        hotel.setExelyPropertyId(newPropertyId);
        hotel.setExelyClientId(newClientId);
        // Secret maydoni bo'sh qoldirilsa — eskisi o'zgarmaydi (sahifada u
        // hech qachon ko'rsatilmaydi, shuning uchun har safar qayta kiritish shart emas).
        if (exelyClientSecret != null && !exelyClientSecret.isBlank()) {
            hotel.setExelyClientSecret(exelyClientSecret.trim());
        }
        // PMS kaliti ham xuddi shunday: bo'sh — eskisi qoladi; yangisi (boshqa mehmonxona
        // bo'lishi mumkin) kiritilsa — PMS sinxronlash boshidan boshlanadi.
        if (exelyPmsKey != null && !exelyPmsKey.isBlank()) {
            String key = exelyPmsKey.trim();
            if (!key.matches("[A-Za-z0-9-]{16,128}")) {
                throw new IllegalArgumentException("Exely PMS kaliti formati noto'g'ri (masalan: 6ac19413-0a62-...)");
            }
            if (!key.equals(hotel.getExelyPmsKey())) {
                hotel.setPmsBookingsSyncedUntil(null);
                hotel.setPmsPaymentsSyncedUntil(null);
                hotel.setPmsServicesFrom(null);
                hotel.setPmsServicesUntil(null);
            }
            hotel.setExelyPmsKey(key);
        }
        hotel.setActive(active);
        if (dailyReportTime != null) {
            hotel.setDailyReportTime(dailyReportTime.withSecond(0).withNano(0));
        }
        boolean created = hotel.getId() == null;
        Hotel saved = hotelRepository.save(hotel);
        if (created) {
            // Tayyor bo'limlar (tahlil tavsiyalaridagi nomlar) — egasi keyin o'zgartiradi.
            for (String deptName : behzoddev.hotelpulse.entity.Department.DEFAULTS) {
                behzoddev.hotelpulse.entity.Department d = new behzoddev.hotelpulse.entity.Department();
                d.setHotel(saved);
                d.setName(deptName);
                d.setCreatedAt(java.time.LocalDateTime.now());
                departmentRepository.save(d);
            }
        }
        return saved;
    }

    /** Exely ulanishini butunlay o'chiradi (allaqachon olingan bronlar qoladi). */
    @Transactional
    public void disconnectExely(Long id) {
        Hotel hotel = getById(id);
        hotel.setExelyClientId(null);
        hotel.setExelyClientSecret(null);
        hotel.setExelyContinueToken(null);
        hotel.setExelyPmsKey(null);
        hotel.setPmsBookingsSyncedUntil(null);
        hotel.setPmsPaymentsSyncedUntil(null);
        hotel.setPmsServicesFrom(null);
        hotel.setPmsServicesUntil(null);
    }

    /** Keyingi sinxronlash boshidan (oxirgi initial-days kun) qayta yuklaydi. */
    @Transactional
    public void resetExelySync(Long id) {
        Hotel hotel = getById(id);
        hotel.setExelyContinueToken(null);
        hotel.setPmsBookingsSyncedUntil(null);
        hotel.setPmsPaymentsSyncedUntil(null);
        hotel.setPmsServicesFrom(null);
        hotel.setPmsServicesUntil(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
