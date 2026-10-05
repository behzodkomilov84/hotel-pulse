package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Exely → HotelPulse sinxronizatsiyasi (Read Reservation API).
 *
 * Oqim: bronlar ro'yxati sahifalab olinadi (birinchi marta — oxirgi
 * app.exely.initial-days kun ichida o'zgarganlar, keyin — saqlangan
 * continueToken'dan boshlab faqat yangi o'zgarishlar), har bir bron uchun
 * tafsilotlar so'raladi va bazaga yoziladi. Token har sahifadan keyin
 * saqlanadi — uzilib qolsa, keyingi safar shu joydan davom etadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExelySyncService {

    private final ExelyClient client;
    private final ExelyPmsClient pmsClient;
    private final ExelyBookingWriter writer;
    private final HotelRepository hotelRepository;
    private final ExelyProperties props;
    private final ExecutorService exelySyncExecutor;
    private final Clock clock;
    private final CurrencyRates rates;

    /** Hozir sinxronlanayotgan mehmonxonalar — bir vaqtda ikki marta ishga tushmasligi uchun. */
    /**
     * Bronlar ro'yxati so'raladigan oraliq (API 365 kungacha ruxsat beradi, lekin katta mehmonxonada
     * uzun oraliq javobi 60 soniyadan oshib ketishi kuzatildi — kichikroq bo'laklar ishonchliroq).
     */
    static final int BOOKING_WINDOW_DAYS = 90;

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public boolean isRunning(Long hotelId) {
        return running.contains(hotelId);
    }

    /** Fonda ishga tushiradi. false — allaqachon ishlayapti. */
    public boolean startAsync(Long hotelId) {
        if (running.contains(hotelId)) {
            return false;
        }
        exelySyncExecutor.submit(() -> sync(hotelId));
        return true;
    }

    /** Davriy avtomatik sinxronlash — barcha faol, Exely ulangan mehmonxonalar. */
    @Scheduled(fixedDelayString = "${app.exely.sync-interval:PT30M}", initialDelayString = "${app.exely.initial-delay:PT1M}")
    public void syncAll() {
        if (!props.schedulerEnabled()) {
            return;
        }
        List<Long> ids = hotelRepository.findAll().stream()
                .filter(h -> h.isActive() && h.isExelyConnected())
                .map(Hotel::getId)
                .toList();
        for (Long id : ids) {
            sync(id);
        }
    }

    /** Bitta mehmonxonani sinxronlaydi. Natija hotels jadvalidagi holat maydonlariga yoziladi. */
    public SyncResult sync(Long hotelId) {
        if (!running.add(hotelId)) {
            return new SyncResult(false, 0, "Sinxronlash allaqachon ishlayapti");
        }
        try {
            Hotel hotel = writer.load(hotelId);
            if (hotel == null || !hotel.isExelyConnected()) {
                return new SyncResult(false, 0, "Exely ulanmagan");
            }
            if (hotel.hasPmsKey()) {
                // Tavsiya etilgan yo'l: PMS Universal API (to'lovlar va qarzdorlik bilan).
                return syncPms(hotel);
            }
            ExelyClient.Credentials creds = new ExelyClient.Credentials(
                    hotel.getExelyPropertyId().trim(), hotel.getExelyClientId().trim(), hotel.getExelyClientSecret());

            String token = hotel.getExelyContinueToken();
            Instant since = Instant.now(clock).minus(Duration.ofDays(props.initialDays()));
            int processed = 0;
            int failed = 0;
            boolean more = true;

            while (more) {
                ExelyApi.BookingSummaries page = client.listBookings(creds, since, token, props.pageSize());
                if (page == null) {
                    break;
                }
                List<ExelyApi.BookingSummary> summaries = page.bookingSummaries() == null ? List.of() : page.bookingSummaries();
                for (ExelyApi.BookingSummary s : summaries) {
                    pause();
                    try {
                        writer.upsert(hotelId, client.getBooking(creds, s.number()));
                        processed++;
                    } catch (ExelyException e) {
                        // Bitta bron xatosi butun sinxronlashni to'xtatmasin (masalan, 404).
                        failed++;
                        log.warn("Exely: {} broni o'tkazib yuborildi (mehmonxona {}): {}", s.number(), hotelId, e.getMessage());
                        if (e.isRateLimited()) {
                            throw e;
                        }
                    }
                }
                if (page.continueToken() != null) {
                    token = page.continueToken();
                    writer.saveContinueToken(hotelId, token);
                }
                more = page.hasMoreData() && !summaries.isEmpty();
                if (more) {
                    pause();
                }
            }

            String message = processed == 0 && failed == 0
                    ? "Yangi o'zgarish yo'q"
                    : processed + " ta bron yangilandi" + (failed > 0 ? ", " + failed + " tasi o'tkazib yuborildi" : "");
            writer.saveStatus(hotelId, true, message);
            log.info("Exely sinxronlash (mehmonxona {}): {}", hotelId, message);
            return new SyncResult(true, processed, message);
        } catch (ExelyException e) {
            writer.saveStatus(hotelId, false, e.getMessage());
            log.warn("Exely sinxronlash xatosi (mehmonxona {}): {}", hotelId, e.getMessage());
            return new SyncResult(false, 0, e.getMessage());
        } catch (RuntimeException e) {
            writer.saveStatus(hotelId, false, "Kutilmagan xatolik: " + e.getClass().getSimpleName());
            log.error("Exely sinxronlash kutilmagan xatosi (mehmonxona {})", hotelId, e);
            return new SyncResult(false, 0, e.getMessage());
        } finally {
            running.remove(hotelId);
        }
    }

    /**
     * Exely PMS Universal API orqali sinxronlash.
     * Bronlar: oxirgi kursordan (birinchi marta — initial-days kun oldindan) hozirgacha o'zgarganlar,
     * BOOKING_WINDOW_DAYS kunlik oynalarda (API cheklovi ≤ 365); har oynadan keyin kursor saqlanadi.
     * To'lovlar: ≤ 30 kunlik oynalarda; har safar oxirgi 30 kun qayta olinadi — keyin bekor
     * qilingan to'lovlar ham to'g'rilanadi.
     */
    private SyncResult syncPms(Hotel hotel) {
        Long hotelId = hotel.getId();
        String key = hotel.getExelyPmsKey().trim();
        LocalDateTime now = LocalDateTime.now(clock).withSecond(0).withNano(0);
        LocalDateTime initial = now.minusDays(props.initialDays());
        StringBuilder note = new StringBuilder();

        // OTA bronlari (Booking.com, Trip.com) ko'pincha USD'da keladi — mehmonxona valyutasiga o'giriladi.
        String hotelCurrency = hotel.getCurrency();
        MoneyConverter money = (amount, currency, date) -> rates.convert(amount, currency, hotelCurrency, date);

        if (!hotel.isRoomsCountManual()) {
            syncRoomsCount(hotel, key, note);
        }

        int purged = writer.purgeNonPmsData(hotelId);
        if (purged > 0) {
            note.append(", ").append(purged).append(" ta eski (demo/Read Reservation) bron o'chirildi");
        }

        // --- Bronlar ---
        LocalDateTime from = hotel.getPmsBookingsSyncedUntil() != null
                ? hotel.getPmsBookingsSyncedUntil().minusMinutes(10)   // chegaradagi o'zgarishlar tushib qolmasin
                : initial;
        int bookings = 0;
        int failed = 0;
        while (from.isBefore(now)) {
            LocalDateTime to = from.plusDays(BOOKING_WINDOW_DAYS).isBefore(now) ? from.plusDays(BOOKING_WINDOW_DAYS) : now;
            Set<String> numbers = new LinkedHashSet<>();
            numbers.addAll(pmsClient.modifiedBookings(key, "Active", from, to));
            numbers.addAll(pmsClient.modifiedBookings(key, "Cancelled", from, to));
            for (String number : numbers) {
                pause();
                try {
                    writer.upsertPms(hotelId, pmsClient.booking(key, number), money);
                    bookings++;
                } catch (ExelyException e) {
                    if (e.isRateLimited()) {
                        throw e;
                    }
                    failed++;
                    log.warn("Exely PMS: {} broni o'tkazib yuborildi (mehmonxona {}): {}", number, hotelId, e.getMessage());
                }
            }
            writer.savePmsCursors(hotelId, to, null);
            from = to;
        }

        // --- To'lovlar ---
        LocalDateTime payFrom = hotel.getPmsPaymentsSyncedUntil() == null ? initial
                : (hotel.getPmsPaymentsSyncedUntil().isBefore(now.minusDays(30)) ? hotel.getPmsPaymentsSyncedUntil() : now.minusDays(30));
        int payments = 0;
        while (payFrom.isBefore(now)) {
            LocalDateTime to = payFrom.plusDays(30).isBefore(now) ? payFrom.plusDays(30) : now;
            pause();
            payments += writer.replacePmsPayments(hotelId, payFrom, to, pmsClient.payments(key, payFrom, to), money);
            writer.savePmsCursors(hotelId, null, to);
            payFrom = to;
        }

        String message = (bookings == 0 && payments == 0 && failed == 0 && purged == 0)
                ? "Yangi o'zgarish yo'q (Exely PMS)"
                : "Exely PMS: " + bookings + " ta bron, " + payments + " ta to'lov yangilandi"
                  + (failed > 0 ? ", " + failed + " tasi o'tkazib yuborildi" : "") + note;
        writer.saveStatus(hotelId, true, message);
        log.info("Exely PMS sinxronlash (mehmonxona {}): {}", hotelId, message);
        return new SyncResult(true, bookings, message);
    }

    /** Xonalar soni Exely'dagi xonalar ro'yxatidan (admin qo'lda belgilamagan bo'lsa). */
    private void syncRoomsCount(Hotel hotel, String key, StringBuilder note) {
        try {
            int rooms = pmsClient.rooms(key).size();
            if (rooms > 0 && rooms != hotel.getRoomsCount()) {
                writer.saveRoomsCount(hotel.getId(), rooms);
                note.append(", xonalar soni: ").append(hotel.getRoomsCount()).append(" → ").append(rooms);
            }
        } catch (ExelyException e) {
            if (e.isRateLimited()) {
                throw e;
            }
            log.warn("Exely PMS: xonalar ro'yxatini olib bo'lmadi (mehmonxona {}): {}", hotel.getId(), e.getMessage());
        }
    }

    /** PMS kalitini tekshiradi (faqat o'qiydigan so'rov). */
    public void testPmsKey(String key) {
        pmsClient.testKey(key.trim());
    }

    /** Kirish ma'lumotlarini tekshiradi (saqlamasdan oldin ham chaqirish mumkin). */
    public void testConnection(String propertyId, String clientId, String clientSecret) {
        client.testConnection(new ExelyClient.Credentials(propertyId.trim(), clientId.trim(), clientSecret));
    }

    private void pause() {
        long ms = props.requestDelay().toMillis();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExelyException("Sinxronlash to'xtatildi");
        }
    }

    public record SyncResult(boolean ok, int processed, String message) {
    }
}
