package behzoddev.hotelpulse.kpi;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Bronlar ro'yxatidan davr bo'yicha xona-kecha ko'rsatkichlarini hisoblaydi.
 * Bazaga bog'liq emas — sof funksiya.
 *
 * Qoidalar (sanoat standarti):
 * - Bron narxi kechalar bo'yicha teng taqsimlanadi; davrga tushgan kechalargina hisobga olinadi.
 * - Exely PMS xizmatlar hisoboti qamragan kunlarda daromad o'shandan olinadi (Exely ta'rifi):
 *   yashash (kind 0) — xona daromadi, qolganlari (nonushta va h.k.) — xizmatlar daromadi.
 * - Faqat faol bronlar (CONFIRMED, CHECKED_IN, CHECKED_OUT) xona band qiladi va daromad beradi.
 * - Bandlik = sotilgan xona-kechalar / (xonalar soni × kunlar).
 * - ADR = yashash daromadi / sotilgan xona-kechalar; RevPAR = yashash daromadi / mavjud xona-kechalar.
 */
public final class KpiCalculator {

    private KpiCalculator() {
    }

    /**
     * Exely xizmatlar hisoboti: kunlik [yashash, nonushta, boshqa xizmatlar] (yoki [yashash, xizmatlar]) summalari
     * va u qamragan sanalar [from, until].
     * Qamrovdagi, lekin byDate'da yo'q kun — daromad 0 (o'sha kuni xizmat bo'lmagan).
     */
    public record ServiceDays(LocalDate from, LocalDate until, Map<LocalDate, BigDecimal[]> byDate) {

        boolean covers(LocalDate d) {
            return !d.isBefore(from) && !d.isAfter(until);
        }
    }

    public static StayMetrics calculate(Collection<Booking> bookings, int roomsCount, Period period) {
        return calculate(bookings, roomsCount, period, null);
    }

    public static StayMetrics calculate(Collection<Booking> bookings, int roomsCount, Period period, ServiceDays services) {
        int days = period.days();
        LocalDate from = period.from();
        LocalDate to = period.to();

        int[] roomsSold = new int[days];
        BigDecimal[] revenue = new BigDecimal[days];
        BigDecimal[] extras = new BigDecimal[days];
        Arrays.fill(revenue, BigDecimal.ZERO);
        Arrays.fill(extras, BigDecimal.ZERO);
        BigDecimal[] meals = new BigDecimal[days];
        Arrays.fill(meals, BigDecimal.ZERO);

        Map<String, long[]> sourceNights = new HashMap<>();
        Map<String, BigDecimal> sourceRevenue = new HashMap<>();
        long noShows = 0;
        long losNights = 0;
        long losCount = 0;

        for (Booking b : bookings) {
            LocalDate arrival = b.getArrivalDate();
            boolean arrivesInPeriod = !arrival.isBefore(from) && !arrival.isAfter(to);

            if (b.getStatus() == BookingStatus.NO_SHOW && arrivesInPeriod) {
                noShows++;
            }
            if (!b.getStatus().isActive()) {
                continue;
            }
            if (arrivesInPeriod) {
                losNights += b.getNights();
                losCount++;
            }

            BigDecimal nightly = b.getTotalAmount()
                    .divide(BigDecimal.valueOf(b.getNights()), 2, RoundingMode.HALF_UP);
            LocalDate start = arrival.isBefore(from) ? from : arrival;
            LocalDate lastNight = b.getDepartureDate().minusDays(1);
            LocalDate end = lastNight.isAfter(to) ? to : lastNight;

            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                int i = (int) ChronoUnit.DAYS.between(from, d);
                roomsSold[i] += b.getRooms();
                if (services == null || !services.covers(d)) {
                    revenue[i] = revenue[i].add(nightly);
                }
                sourceNights.computeIfAbsent(b.getSource(), k -> new long[1])[0] += b.getRooms();
                sourceRevenue.merge(b.getSource(), nightly, BigDecimal::add);
            }
        }

        if (services != null) {
            for (int i = 0; i < days; i++) {
                LocalDate d = from.plusDays(i);
                if (services.covers(d)) {
                    BigDecimal[] v = services.byDate().get(d);
                    revenue[i] = v == null ? BigDecimal.ZERO : v[0];
                    extras[i] = v == null ? BigDecimal.ZERO : (v.length > 2 ? v[1].add(v[2]) : v[1]);
                    meals[i] = v == null || v.length < 3 ? BigDecimal.ZERO : v[1];
                }
            }
        }

        long sold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalExtras = BigDecimal.ZERO;
        BigDecimal totalMeals = BigDecimal.ZERO;
        List<StayMetrics.DailyPoint> daily = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            sold += roomsSold[i];
            totalRevenue = totalRevenue.add(revenue[i]);
            totalExtras = totalExtras.add(extras[i]);
            totalMeals = totalMeals.add(meals[i]);
            daily.add(new StayMetrics.DailyPoint(from.plusDays(i), roomsSold[i],
                    ratio(roomsSold[i], roomsCount), revenue[i].add(extras[i]), revenue[i], meals[i]));
        }
        long available = (long) roomsCount * days;

        // Manbalar ulushi bronlardan; summasi yashash daromadiga moslab ko'rsatiladi.
        BigDecimal bookingTotal = sourceRevenue.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revenueTotal = totalRevenue;
        List<StayMetrics.SourceShare> sources = sourceRevenue.entrySet().stream()
                .map(e -> {
                    double share = bookingTotal.signum() == 0 ? 0 : e.getValue().doubleValue() / bookingTotal.doubleValue();
                    BigDecimal amount = services == null ? e.getValue()
                            : revenueTotal.multiply(BigDecimal.valueOf(share)).setScale(2, RoundingMode.HALF_UP);
                    return new StayMetrics.SourceShare(e.getKey(), sourceNights.get(e.getKey())[0], amount, share);
                })
                .sorted(Comparator.comparing(StayMetrics.SourceShare::revenue).reversed())
                .toList();

        return new StayMetrics(
                available,
                sold,
                totalRevenue,
                totalExtras,
                totalMeals,
                ratio(sold, available),
                divide(totalRevenue, sold),
                divide(totalRevenue, available),
                noShows,
                losCount == 0 ? 0 : (double) losNights / losCount,
                daily,
                sources);
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static BigDecimal divide(BigDecimal amount, long by) {
        return by == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(by), 0, RoundingMode.HALF_UP);
    }
}
