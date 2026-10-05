package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.BookingStatus;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.DebtReport;
import behzoddev.hotelpulse.kpi.DebtReport.Category;
import behzoddev.hotelpulse.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Qarzdorlik bo'yicha batafsil hisobot.
 * Exely PMS bronlarida qarz — PMS bergan qoldiq (balance_due), boshqa manbalarda — narx − to'lovlar.
 */
@Service
@RequiredArgsConstructor
public class DebtService {

    private final BookingRepository bookingRepository;
    private final Clock clock;

    /**
     * @param category null — hammasi
     * @param query    mehmon ismi yoki bron raqami bo'yicha qidiruv (bo'sh — hammasi)
     * @param sort     debt (standart) | age | arrival | guest
     */
    @Transactional(readOnly = true)
    public DebtReport report(Hotel hotel, Category category, String query, String sort) {
        LocalDate today = LocalDate.now(clock);
        List<DebtReport.Row> all = new ArrayList<>();

        for (Booking b : bookingRepository.findPmsDebtors(hotel.getId(), today)) {
            BigDecimal debt = b.getBalanceDue();
            all.add(row(b, b.getTotalAmount().subtract(debt), debt, pmsCategory(b, today), today));
        }
        for (Object[] r : bookingRepository.findPaymentBasedDebtors(hotel.getId(), today)) {
            Booking b = (Booking) r[0];
            BigDecimal paid = (BigDecimal) r[1];
            Category c = b.getDepartureDate().isAfter(today) ? Category.IN_HOUSE : Category.CHECKED_OUT;
            all.add(row(b, paid, b.getTotalAmount().subtract(paid), c, today));
        }

        DebtReport.Summary summary = summary(all);
        List<DebtReport.AgingBucket> aging = aging(all, summary.total());

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<DebtReport.Row> rows = all.stream()
                .filter(r -> category == null || r.category() == category)
                .filter(r -> q.isEmpty()
                        || (r.guestName() != null && r.guestName().toLowerCase(Locale.ROOT).contains(q))
                        || r.bookingNumber().toLowerCase(Locale.ROOT).contains(q))
                .sorted(comparator(sort))
                .toList();
        return new DebtReport(rows, summary, aging);
    }

    /** PMS haqiqiy holati: ketish sanasi o'tgan, lekin CheckedIn — vyselenie qilinmagan. */
    static Category pmsCategory(Booking b, LocalDate today) {
        if (b.getStatus() == BookingStatus.CHECKED_OUT) {
            return Category.CHECKED_OUT;
        }
        return b.getDepartureDate().isAfter(today) ? Category.IN_HOUSE : Category.NOT_CHECKED_OUT;
    }

    private static DebtReport.Row row(Booking b, BigDecimal paid, BigDecimal debt, Category c, LocalDate today) {
        long age = c == Category.IN_HOUSE ? 0 : Math.max(0, ChronoUnit.DAYS.between(b.getDepartureDate(), today));
        return new DebtReport.Row(bookingNumber(b), b.getGuestName(), b.getSource(),
                b.getArrivalDate(), b.getDepartureDate(), b.getNights(),
                c, b.getTotalAmount(), paid.max(BigDecimal.ZERO), debt, age);
    }

    /** "pms:20261001-508098-1001#rs1" → "20261001-508098-1001"; Read Reservation "N#0" → "N". */
    static String bookingNumber(Booking b) {
        String id = b.getExternalId();
        if (b.getOrigin() == DataOrigin.EXELY_PMS && id.startsWith("pms:")) {
            id = id.substring(4);
        }
        int hash = id.lastIndexOf('#');
        return hash > 0 && b.getOrigin() != DataOrigin.DEMO ? id.substring(0, hash) : id;
    }

    private static DebtReport.Summary summary(List<DebtReport.Row> rows) {
        BigDecimal total = BigDecimal.ZERO, in = BigDecimal.ZERO, out = BigDecimal.ZERO, not = BigDecimal.ZERO;
        long inN = 0, outN = 0, notN = 0;
        for (DebtReport.Row r : rows) {
            total = total.add(r.debt());
            switch (r.category()) {
                case IN_HOUSE -> { in = in.add(r.debt()); inN++; }
                case CHECKED_OUT -> { out = out.add(r.debt()); outN++; }
                case NOT_CHECKED_OUT -> { not = not.add(r.debt()); notN++; }
            }
        }
        return new DebtReport.Summary(total, rows.size(), in, inN, out, outN, not, notN);
    }

    private static List<DebtReport.AgingBucket> aging(List<DebtReport.Row> rows, BigDecimal total) {
        String[] labels = {"0–30 kun", "31–60 kun", "61–90 kun", "90+ kun"};
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        long[] counts = new long[4];
        for (DebtReport.Row r : rows) {
            int i = r.ageDays() <= 30 ? 0 : r.ageDays() <= 60 ? 1 : r.ageDays() <= 90 ? 2 : 3;
            sums[i] = sums[i].add(r.debt());
            counts[i]++;
        }
        List<DebtReport.AgingBucket> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double share = total.signum() == 0 ? 0 : sums[i].doubleValue() / total.doubleValue();
            result.add(new DebtReport.AgingBucket(labels[i], sums[i], counts[i], share));
        }
        return result;
    }

    private static Comparator<DebtReport.Row> comparator(String sort) {
        Comparator<DebtReport.Row> byDebt = Comparator.comparing(DebtReport.Row::debt).reversed();
        if (sort == null) {
            return byDebt;
        }
        return switch (sort) {
            case "age" -> Comparator.comparingLong(DebtReport.Row::ageDays).reversed().thenComparing(byDebt);
            case "arrival" -> Comparator.comparing(DebtReport.Row::arrival).reversed();
            case "guest" -> Comparator.comparing(r -> r.guestName() == null ? "￿" : r.guestName().toLowerCase(Locale.ROOT));
            default -> byDebt;
        };
    }
}
