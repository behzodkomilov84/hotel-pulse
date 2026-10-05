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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
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
    private final ExelyRawStore raw;
    private final ExelyVerifyService verifyService;

    /**
     * Bronlar ro'yxati so'raladigan oraliq (API 365 kungacha ruxsat beradi, lekin katta mehmonxonada
     * uzun oraliq javobi 60 soniyadan oshib ketishi kuzatildi — kichikroq bo'laklar ishonchliroq).
     */
    static final int BOOKING_WINDOW_DAYS = 90;
    /** Xizmatlar (kunlik daromad) shuncha kun oldinga ham olinadi (Exely chegarasi ~1000 kun). */
    static final int SERVICES_AHEAD_DAYS = 990;
    /** PMS ma'lumotlari shu sanadan boshlab olinadi (butun tarix). */
    static final LocalDate PMS_HISTORY_FROM = LocalDate.of(2020, 1, 1);

    /** Hozir sinxronlanayotgan (yoki solishtirilayotgan) mehmonxonalar — bir vaqtda ikki marta ishga tushmasligi uchun. */
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

    /** Fonda: avval sinxronlaydi, keyin Exely bilan solishtiradi. false — allaqachon ishlayapti. */
    public boolean startVerifyAsync(Long hotelId) {
        if (running.contains(hotelId)) {
            return false;
        }
        exelySyncExecutor.submit(() -> syncAndVerify(hotelId));
        return true;
    }

    /** Har kuni: PMS ulangan mehmonxonalarni sinxronlab, Exely bilan solishtiradi. */
    @Scheduled(cron = "${app.exely.verify-cron:0 30 4 * * *}", zone = "${app.zone:Asia/Tashkent}")
    public void verifyAll() {
        if (!props.schedulerEnabled()) {
            return;
        }
        hotelRepository.findAll().stream()
                .filter(h -> h.isActive() && h.hasPmsKey())
                .map(Hotel::getId)
                .toList()
                .forEach(this::syncAndVerify);
    }

    void syncAndVerify(Long hotelId) {
        sync(hotelId);
        if (!running.add(hotelId)) {
            return;
        }
        try {
            verifyService.verify(hotelId);
        } catch (RuntimeException e) {
            log.error("Exely solishtirish xatosi (mehmonxona {})", hotelId, e);
        } finally {
            running.remove(hotelId);
        }
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
        // Birinchi to'liq sinxronlash Exely'dagi butun tarixni oladi (bo'sh yillar uchun so'rovlar arzon).
        LocalDateTime initial = PMS_HISTORY_FROM.atStartOfDay();
        StringBuilder note = new StringBuilder();

        // OTA bronlari (Booking.com, Trip.com) ko'pincha USD'da keladi — mehmonxona valyutasiga o'giriladi.
        String hotelCurrency = hotel.getCurrency();
        MoneyConverter money = (amount, currency, date) -> rates.convert(amount, currency, hotelCurrency, date);

        // --- Ma'lumotnomalar: xonalar va kompaniyalar ---
        syncRooms(hotel, key, note);
        optional(hotelId, "kompaniyalar", () -> {
            String json = pmsClient.companiesJson(key);
            if (json != null) {
                raw.replaceAll(hotelId, ExelyRawStore.COMPANY, ExelyRawRows.list(json, "id"));
            }
        });

        int purged = writer.purgeNonPmsData(hotelId);
        if (purged > 0) {
            note.append(", ").append(purged).append(" ta eski (demo/Read Reservation) bron o'chirildi");
        }

        // --- Bronlar (+ hisob-fakturalar va mehmonlar) ---
        LocalDateTime from = hotel.getPmsBookingsSyncedUntil() != null
                ? hotel.getPmsBookingsSyncedUntil().minusMinutes(10)   // chegaradagi o'zgarishlar tushib qolmasin
                : initial;
        int bookings = 0;
        int failed = 0;
        Set<String> guestsSeen = new HashSet<>();
        while (from.isBefore(now)) {
            LocalDateTime to = from.plusDays(BOOKING_WINDOW_DAYS).isBefore(now) ? from.plusDays(BOOKING_WINDOW_DAYS) : now;
            Set<String> numbers = new LinkedHashSet<>();
            numbers.addAll(pmsClient.modifiedBookings(key, "Active", from, to));
            numbers.addAll(pmsClient.modifiedBookings(key, "Cancelled", from, to));
            for (String number : numbers) {
                pause();
                try {
                    String json = pmsClient.bookingJson(key, number);
                    writer.upsertPms(hotelId, ExelyPmsClient.parse(json, ExelyPmsApi.Booking.class), money);
                    raw.upsert(hotelId, ExelyRawStore.BOOKING, ExelyRawRows.booking(number, json));
                    archiveBookingDetails(hotelId, key, number, json, guestsSeen);
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
            String json = pmsClient.paymentsJson(key, payFrom, to);
            ExelyPmsApi.PaymentsResponse r = json == null ? null : ExelyPmsClient.parse(json, ExelyPmsApi.PaymentsResponse.class);
            List<ExelyPmsApi.Payment> list = r == null || r.data() == null || r.data().payments() == null
                    ? List.of() : r.data().payments();
            payments += writer.replacePmsPayments(hotelId, payFrom, to, list, money);
            if (json != null) {
                raw.upsertAll(hotelId, ExelyRawStore.PAYMENT, ExelyRawRows.payments(json));
            }
            writer.savePmsCursors(hotelId, null, to);
            payFrom = to;
        }

        // --- Xizmatlar (kunlik daromad: yashash, nonushta va h.k.) + bekor qilingan bronlarniki ---
        // Birinchi marta — initial-days kun oldindan; keyin oxirgi 31 kun qayta olinadi (tuzatishlar uchun),
        // oldinga — SERVICES_AHEAD_DAYS kun (kelajakdagi bronlar daromadi).
        LocalDate today = now.toLocalDate();
        LocalDate svcFrom = hotel.getPmsServicesUntil() == null ? initial.toLocalDate()
                : (hotel.getPmsServicesUntil().isBefore(today) ? hotel.getPmsServicesUntil() : today).minusDays(31);
        LocalDate svcTo = today.plusDays(SERVICES_AHEAD_DAYS);
        int services = 0;
        while (!svcFrom.isAfter(svcTo)) {
            LocalDate end = svcFrom.plusDays(30).isBefore(svcTo) ? svcFrom.plusDays(30) : svcTo;
            pause();
            String json = pmsClient.servicesJson(key, svcFrom, end, false);
            services += writer.replacePmsServices(hotelId, hotel.getCurrency(), svcFrom, end, servicesData(json), money);
            archiveServices(hotelId, ExelyRawStore.SERVICE, svcFrom, end, json);

            LocalDate cancelledFrom = svcFrom;
            LocalDate cancelledTo = end;
            optional(hotelId, "bekor qilingan xizmatlar", () -> {
                pause();
                archiveServices(hotelId, ExelyRawStore.SERVICE_CANCELLED, cancelledFrom, cancelledTo,
                        pmsClient.servicesJson(key, cancelledFrom, cancelledTo, true));
            });
            svcFrom = end.plusDays(1);
        }
        if (services > 0) {
            note.append(", ").append(services).append(" ta xizmat qatori");
        }

        String message = (bookings == 0 && payments == 0 && failed == 0 && purged == 0)
                ? "Yangi o'zgarish yo'q (Exely PMS)"
                : "Exely PMS: " + bookings + " ta bron, " + payments + " ta to'lov yangilandi"
                  + (failed > 0 ? ", " + failed + " tasi o'tkazib yuborildi" : "") + note;
        writer.saveStatus(hotelId, true, message);
        log.info("Exely PMS sinxronlash (mehmonxona {}): {}", hotelId, message);
        return new SyncResult(true, bookings, message);
    }

    private static ExelyPmsApi.ServicesData servicesData(String json) {
        ExelyPmsApi.ServicesResponse r = json == null ? null : ExelyPmsClient.parse(json, ExelyPmsApi.ServicesResponse.class);
        if (r == null || r.data() == null) {
            return new ExelyPmsApi.ServicesData(List.of(), List.of());
        }
        return new ExelyPmsApi.ServicesData(
                r.data().services() == null ? List.of() : r.data().services(),
                r.data().reservations() == null ? List.of() : r.data().reservations());
    }

    /** Bron hisob-fakturalari va mehmonlar profillari — xom arxivga (xatosi bronni to'xtatmaydi). */
    private void archiveBookingDetails(Long hotelId, String key, String number, String bookingJson, Set<String> guestsSeen) {
        optional(hotelId, number + " hisob-fakturalari", () -> {
            pause();
            String invoices = pmsClient.invoicesJson(key, number);
            if (invoices != null) {
                raw.upsert(hotelId, ExelyRawStore.INVOICES, new ExelyRawStore.Row(number, number, null, invoices));
            }
        });
        for (String guestId : ExelyRawRows.guestIds(bookingJson)) {
            if (!guestsSeen.add(guestId)) {
                continue;
            }
            optional(hotelId, "mehmon " + guestId, () -> {
                pause();
                String guest = pmsClient.guestJson(key, guestId);
                if (guest != null) {
                    raw.upsert(hotelId, ExelyRawStore.GUEST, new ExelyRawStore.Row(guestId, number, null, guest));
                }
            });
        }
    }

    /** Xizmatlar oynasi: xizmat qatorlari almashtiriladi, yashashlar/to'lovchilar/agentlar/xona turlari yangilanadi. */
    private void archiveServices(Long hotelId, String kind, LocalDate from, LocalDate to, String json) {
        if (json == null) {
            raw.replaceWindow(hotelId, kind, from, to, List.of());
            return;
        }
        ExelyRawRows.Services s = ExelyRawRows.services(json);
        raw.replaceWindow(hotelId, kind, from, to, s.services());
        raw.upsertAll(hotelId, ExelyRawStore.RESERVATION, s.reservations());
        raw.upsertAll(hotelId, ExelyRawStore.CUSTOMER, s.customers());
        raw.upsertAll(hotelId, ExelyRawStore.AGENT, s.agents());
        raw.upsertAll(hotelId, ExelyRawStore.ROOM_TYPE, s.roomTypes());
    }

    /** Xonalar ro'yxati — arxivga; xonalar soni (admin qo'lda belgilamagan bo'lsa) shundan. */
    private void syncRooms(Hotel hotel, String key, StringBuilder note) {
        optional(hotel.getId(), "xonalar ro'yxati", () -> {
            String json = pmsClient.roomsJson(key);
            if (json == null) {
                return;
            }
            List<ExelyRawStore.Row> rooms = ExelyRawRows.list(json, "id");
            raw.replaceAll(hotel.getId(), ExelyRawStore.ROOM, rooms);
            if (!hotel.isRoomsCountManual() && !rooms.isEmpty() && rooms.size() != hotel.getRoomsCount()) {
                writer.saveRoomsCount(hotel.getId(), rooms.size());
                note.append(", xonalar soni: ").append(hotel.getRoomsCount()).append(" → ").append(rooms.size());
            }
        });
    }

    /** Qo'shimcha ma'lumot: xatosi (limitdan tashqari) sinxronlashni to'xtatmaydi. */
    private void optional(Long hotelId, String what, Runnable action) {
        try {
            action.run();
        } catch (ExelyException e) {
            if (e.isRateLimited()) {
                throw e;
            }
            log.warn("Exely PMS: {} olinmadi (mehmonxona {}): {}", what, hotelId, e.getMessage());
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
