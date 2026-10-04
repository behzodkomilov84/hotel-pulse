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
 * - Faqat faol bronlar (CONFIRMED, CHECKED_IN, CHECKED_OUT) xona band qiladi va daromad beradi.
 * - Bandlik = sotilgan xona-kechalar / (xonalar soni × kunlar).
 * - ADR = daromad / sotilgan xona-kechalar; RevPAR = daromad / mavjud xona-kechalar.
 */
public final class KpiCalculator {

    private KpiCalculator() {
    }

    public static StayMetrics calculate(Collection<Booking> bookings, int roomsCount, Period period) {
        int days = period.days();
        LocalDate from = period.from();
        LocalDate to = period.to();

        int[] roomsSold = new int[days];
        BigDecimal[] revenue = new BigDecimal[days];
        Arrays.fill(revenue, BigDecimal.ZERO);

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
                revenue[i] = revenue[i].add(nightly);
                sourceNights.computeIfAbsent(b.getSource(), k -> new long[1])[0] += b.getRooms();
                sourceRevenue.merge(b.getSource(), nightly, BigDecimal::add);
            }
        }

        long sold = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        List<StayMetrics.DailyPoint> daily = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            sold += roomsSold[i];
            totalRevenue = totalRevenue.add(revenue[i]);
            daily.add(new StayMetrics.DailyPoint(from.plusDays(i), roomsSold[i],
                    ratio(roomsSold[i], roomsCount), revenue[i]));
        }
        long available = (long) roomsCount * days;

        BigDecimal revenueTotal = totalRevenue;
        List<StayMetrics.SourceShare> sources = sourceRevenue.entrySet().stream()
                .map(e -> new StayMetrics.SourceShare(e.getKey(), sourceNights.get(e.getKey())[0], e.getValue(),
                        revenueTotal.signum() == 0 ? 0 : e.getValue().doubleValue() / revenueTotal.doubleValue()))
                .sorted(Comparator.comparing(StayMetrics.SourceShare::revenue).reversed())
                .toList();

        return new StayMetrics(
                available,
                sold,
                totalRevenue,
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
