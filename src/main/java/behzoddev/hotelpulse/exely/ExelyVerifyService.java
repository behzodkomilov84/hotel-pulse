package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.ServiceRevenue;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import behzoddev.hotelpulse.repository.ServiceRevenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * "Exely bilan solishtirish": saytdagi bazani Exely PMS'dan hozir olingan ma'lumot bilan solishtiradi —
 * bronlar (raqamlar ro'yxati), kunlik xizmatlar (soni va summasi), to'lovlar (oylar bo'yicha soni va summasi),
 * hamda bazaning ichki yaxlitligi (xom bronlardagi yashashlar = bookings jadvali).
 * Faqat o'qiydi; natija mehmonxona yozuviga saqlanadi va admin sahifasida ko'rinadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExelyVerifyService {

    private static final int MAX_DETAILS = 30;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExelyPmsClient pmsClient;
    private final ExelyRawStore raw;
    private final HotelRepository hotelRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final ServiceRevenueRepository serviceRevenueRepository;
    private final CurrencyRates rates;
    private final Clock clock;

    /** @param details farqlar (eng ko'pi MAX_DETAILS ta) */
    public record Check(String name, boolean ok, String summary, List<String> details) {
    }

    public record Report(LocalDateTime at, boolean ok, List<Check> checks, String error) {
    }

    public Report verify(Long hotelId) {
        Hotel hotel = hotelRepository.findById(hotelId).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock).withSecond(0).withNano(0);
        Report report;
        if (!hotel.hasPmsKey()) {
            report = new Report(now, false, List.of(), "Exely PMS kaliti kiritilmagan");
        } else {
            try {
                String key = hotel.getExelyPmsKey().trim();
                List<Check> checks = new ArrayList<>();
                checks.add(bookings(hotel, key, now));
                checks.add(integrity(hotel));
                checks.add(services(hotel, key));
                checks.add(payments(hotel, key, now));
                report = new Report(now, checks.stream().allMatch(Check::ok), checks, null);
            } catch (ExelyException e) {
                report = new Report(now, false, List.of(), e.getMessage());
            }
        }
        save(hotelId, report);
        log.info("Exely solishtirish (mehmonxona {}): {}", hotelId, report.ok() ? "mos" : "farq bor");
        return report;
    }

    /** Exely'dagi barcha bron raqamlari (Active + Cancelled) va saytda saqlanganlari. */
    private Check bookings(Hotel hotel, String key, LocalDateTime now) {
        Set<String> exely = new LinkedHashSet<>();
        LocalDateTime from = ExelySyncService.PMS_HISTORY_FROM.atStartOfDay();
        while (from.isBefore(now)) {
            LocalDateTime to = from.plusDays(ExelySyncService.BOOKING_WINDOW_DAYS).isBefore(now)
                    ? from.plusDays(ExelySyncService.BOOKING_WINDOW_DAYS) : now;
            exely.addAll(pmsClient.modifiedBookings(key, "Active", from, to));
            exely.addAll(pmsClient.modifiedBookings(key, "Cancelled", from, to));
            from = to;
        }
        Set<String> ours = raw.externalIds(hotel.getId(), ExelyRawStore.BOOKING);
        List<String> details = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>(exely);
        missing.removeAll(ours);
        Set<String> extra = new LinkedHashSet<>(ours);
        extra.removeAll(exely);
        missing.stream().limit(MAX_DETAILS).forEach(n -> details.add("Saytda yo'q: " + n));
        extra.stream().limit(MAX_DETAILS).forEach(n -> details.add("Exely'da yo'q: " + n));
        boolean ok = missing.isEmpty() && extra.isEmpty();
        return new Check("Bronlar", ok, "Exely: " + exely.size() + " ta, saytda: " + ours.size() + " ta"
                + (ok ? "" : " (yetishmaydi: " + missing.size() + ", ortiqcha: " + extra.size() + ")"), details);
    }

    /** Bazaning ichki yaxlitligi: xom bronlardagi yashashlar soni = bookings jadvalidagi PMS qatorlari. */
    private Check integrity(Hotel hotel) {
        long rawStays = raw.bookingRoomStays(hotel.getId());
        long rows = bookingRepository.countByHotelIdAndOrigin(hotel.getId(), DataOrigin.EXELY_PMS);
        boolean ok = rawStays == rows;
        return new Check("Yashashlar (ichki)", ok, "Exely bronlarida: " + rawStays + " ta, hisob jadvalida: " + rows + " ta",
                List.of());
    }

    /** Kunlik xizmatlar: Exely'dagi har kunning qatorlari soni va summasi (so'mda) = saytdagi. */
    private Check services(Hotel hotel, String key) {
        if (hotel.getPmsServicesFrom() == null || hotel.getPmsServicesUntil() == null) {
            return new Check("Xizmatlar (daromad)", false, "Xizmatlar hali olinmagan", List.of());
        }
        MoneyConverter money = (amount, currency, date) -> rates.convert(amount, currency, hotel.getCurrency(), date);
        Map<LocalDate, BigDecimal[]> exely = new TreeMap<>();
        LocalDate from = hotel.getPmsServicesFrom();
        LocalDate until = hotel.getPmsServicesUntil();
        while (!from.isAfter(until)) {
            LocalDate end = from.plusDays(30).isBefore(until) ? from.plusDays(30) : until;
            String json = pmsClient.servicesJson(key, from, end, false);
            ExelyPmsApi.ServicesResponse r = json == null ? null : ExelyPmsClient.parse(json, ExelyPmsApi.ServicesResponse.class);
            if (r != null && r.data() != null) {
                ExelyPmsApi.ServicesData data = new ExelyPmsApi.ServicesData(
                        r.data().services() == null ? List.of() : r.data().services(),
                        r.data().reservations() == null ? List.of() : r.data().reservations());
                for (ServiceRevenue s : ExelyPmsMapper.toServices(data, hotel.getId(), hotel.getCurrency(), money)) {
                    if (s.getServiceDate().isBefore(from) || s.getServiceDate().isAfter(end)) {
                        continue;
                    }
                    BigDecimal[] v = exely.computeIfAbsent(s.getServiceDate(), d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    v[0] = v[0].add(BigDecimal.ONE);
                    v[1] = v[1].add(s.getAmount());
                }
            }
            from = end.plusDays(1);
        }
        Map<LocalDate, BigDecimal[]> ours = new HashMap<>();
        for (Object[] row : serviceRevenueRepository.dailyCountsAndSums(hotel.getId(), hotel.getPmsServicesFrom(), until)) {
            ours.put((LocalDate) row[0], new BigDecimal[]{BigDecimal.valueOf(((Number) row[1]).longValue()), (BigDecimal) row[2]});
        }
        Set<LocalDate> days = new TreeSet<>(exely.keySet());
        days.addAll(ours.keySet());
        List<String> details = new ArrayList<>();
        int bad = 0;
        BigDecimal exelyTotal = BigDecimal.ZERO;
        BigDecimal ourTotal = BigDecimal.ZERO;
        for (LocalDate d : days) {
            BigDecimal[] e = exely.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] o = ours.getOrDefault(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            exelyTotal = exelyTotal.add(e[1]);
            ourTotal = ourTotal.add(o[1]);
            if (e[0].compareTo(o[0]) != 0 || e[1].subtract(o[1]).abs().compareTo(BigDecimal.ONE) > 0) {
                bad++;
                if (details.size() < MAX_DETAILS) {
                    details.add(d + ": Exely " + e[0] + " ta / " + money(e[1]) + ", saytda " + o[0] + " ta / " + money(o[1]));
                }
            }
        }
        boolean ok = bad == 0;
        return new Check("Xizmatlar (daromad)", ok, hotel.getPmsServicesFrom() + " — " + until + ": Exely " + money(exelyTotal)
                + ", saytda " + money(ourTotal) + (ok ? ", har kun mos" : ", " + bad + " kunda farq"), details);
    }

    /** To'lovlar: oylik oynalarda soni va summasi (bekor qilinganlarsiz, qaytarishlar manfiy). */
    private Check payments(Hotel hotel, String key, LocalDateTime now) {
        MoneyConverter money = (amount, currency, date) -> rates.convert(amount, currency, hotel.getCurrency(), date);
        LocalDateTime from = ExelySyncService.PMS_HISTORY_FROM.atStartOfDay();
        List<String> details = new ArrayList<>();
        int bad = 0;
        long exelyCount = 0;
        long ourCount = 0;
        while (from.isBefore(now)) {
            LocalDateTime to = from.plusDays(30).isBefore(now) ? from.plusDays(30) : now;
            Set<String> seen = new HashSet<>();
            long eCount = 0;
            BigDecimal eSum = BigDecimal.ZERO;
            for (ExelyPmsApi.Payment p : pmsClient.payments(key, from, to)) {
                behzoddev.hotelpulse.entity.Payment pay = ExelyPmsMapper.toPayment(p, hotel.getId(), money);
                if (pay != null && !pay.getPaidAt().isBefore(from) && pay.getPaidAt().isBefore(to) && seen.add(pay.getExternalId())) {
                    eCount++;
                    eSum = eSum.add(pay.getAmount());
                }
            }
            Object[] o = paymentRepository.countAndSum(hotel.getId(), DataOrigin.EXELY_PMS, from, to).get(0);
            long oCount = ((Number) o[0]).longValue();
            BigDecimal oSum = (BigDecimal) o[1];
            exelyCount += eCount;
            ourCount += oCount;
            if (eCount != oCount || eSum.subtract(oSum).abs().compareTo(BigDecimal.ONE) > 0) {
                bad++;
                if (details.size() < MAX_DETAILS) {
                    details.add(from.toLocalDate() + " — " + to.toLocalDate() + ": Exely " + eCount + " ta / " + money(eSum)
                            + ", saytda " + oCount + " ta / " + money(oSum));
                }
            }
            from = to;
        }
        boolean ok = bad == 0;
        return new Check("To'lovlar", ok, "Exely: " + exelyCount + " ta, saytda: " + ourCount + " ta"
                + (ok ? ", har oy mos" : ", " + bad + " oyda farq"), details);
    }

    @Transactional
    void save(Long hotelId, Report report) {
        hotelRepository.findById(hotelId).ifPresent(h -> {
            h.setExelyVerifiedAt(report.at());
            h.setExelyVerifyOk(report.ok());
            h.setExelyVerifyReport(JSON.writeValueAsString(report));
            hotelRepository.save(h);
        });
    }

    /** Saqlangan oxirgi natija (yo'q bo'lsa — null). */
    public static Report read(Hotel hotel) {
        if (hotel.getExelyVerifyReport() == null) {
            return null;
        }
        try {
            return JSON.readValue(hotel.getExelyVerifyReport(), Report.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String money(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
