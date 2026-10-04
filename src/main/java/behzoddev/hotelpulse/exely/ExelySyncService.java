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
    private final ExelyBookingWriter writer;
    private final HotelRepository hotelRepository;
    private final ExelyProperties props;
    private final ExecutorService exelySyncExecutor;
    private final Clock clock;

    /** Hozir sinxronlanayotgan mehmonxonalar — bir vaqtda ikki marta ishga tushmasligi uchun. */
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
