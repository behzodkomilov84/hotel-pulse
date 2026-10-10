package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.*;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import behzoddev.hotelpulse.repository.ServiceRevenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KpiService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final ServiceRevenueRepository serviceRevenueRepository;
    private final Clock clock;

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    @Transactional(readOnly = true)
    public HotelKpi report(Hotel hotel, Period period) {
        Period prev = period.previous();
        return new HotelKpi(
                period,
                stays(hotel, period),
                paymentRepository.sumPaidBetween(hotel.getId(), period.from().atStartOfDay(), period.toExclusive().atStartOfDay()),
                bookingRepository.countBookedBetween(hotel.getId(), period.from().atStartOfDay(), period.toExclusive().atStartOfDay()),
                bookingRepository.countCancelledBetween(hotel.getId(), period.from().atStartOfDay(), period.toExclusive().atStartOfDay()),
                prev,
                stays(hotel, prev),
                paymentRepository.sumPaidBetween(hotel.getId(), prev.from().atStartOfDay(), prev.toExclusive().atStartOfDay()));
    }

    @Transactional(readOnly = true)
    public TodaySnapshot todaySnapshot(Hotel hotel) {
        LocalDate today = today();
        // departure >= bugun bo'lgan bronlar — bugun ketayotganlar ham kiradi.
        List<Booking> around = bookingRepository.findStaysOverlapping(hotel.getId(), today.minusDays(1), today.plusDays(1));
        long arrivals = 0;
        long departures = 0;
        long inHouse = 0;
        for (Booking b : around) {
            if (!b.getStatus().isActive()) {
                continue;
            }
            if (b.getArrivalDate().equals(today)) {
                arrivals++;
            }
            if (b.getDepartureDate().equals(today)) {
                departures++;
            }
            if (!b.getArrivalDate().isAfter(today) && b.getDepartureDate().isAfter(today)) {
                inHouse += b.getRooms();
            }
        }

        // 1) Qoldiq manbadan ma'lum bo'lmagan bronlar (demo, Read Reservation): narx − to'lovlar.
        BigDecimal debt = BigDecimal.ZERO;
        List<Object[]> unpaid = bookingRepository.findUnpaidStays(hotel.getId(), today);
        for (Object[] row : unpaid) {
            debt = debt.add(((BigDecimal) row[0]).subtract((BigDecimal) row[1]));
        }
        long debtors = unpaid.size();
        // 2) Exely PMS bergan haqiqiy qoldiq (balance_due).
        Object[] pms = bookingRepository.sumBalanceDue(hotel.getId(), today).get(0);
        debt = debt.add((BigDecimal) pms[0]);
        debtors += ((Number) pms[1]).longValue();

        double occupancy = hotel.getRoomsCount() == 0 ? 0 : (double) inHouse / hotel.getRoomsCount();
        return new TodaySnapshot(arrivals, departures, inHouse, occupancy, debt, debtors);
    }

    /**
     * Mehmonxonalar bo'yicha jami — valyuta bo'yicha alohida (odatda bitta: UZS).
     * Ma'lumoti yo'q mehmonxonalar hisobga olinmaydi.
     */
    @Transactional(readOnly = true)
    public List<PortfolioSummary> portfolio(List<Hotel> hotels, Period period) {
        Map<String, Object[]> acc = new LinkedHashMap<>();
        for (Hotel hotel : hotels) {
            if (!hasData(hotel)) {
                continue;
            }
            StayMetrics s = stays(hotel, period);
            BigDecimal paid = paymentRepository.sumPaidBetween(hotel.getId(),
                    period.from().atStartOfDay(), period.toExclusive().atStartOfDay());
            Object[] a = acc.computeIfAbsent(hotel.getCurrency(),
                    c -> new Object[]{0, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = (int) a[0] + 1;
            a[1] = (long) a[1] + s.soldRoomNights();
            a[2] = (long) a[2] + s.availableRoomNights();
            a[3] = ((BigDecimal) a[3]).add(s.roomRevenue());
            a[4] = ((BigDecimal) a[4]).add(s.totalRevenue());
            a[5] = ((BigDecimal) a[5]).add(paid);
        }
        List<PortfolioSummary> result = new java.util.ArrayList<>();
        acc.forEach((cur, a) -> result.add(new PortfolioSummary(cur, (int) a[0], (long) a[1], (long) a[2],
                (BigDecimal) a[3], (BigDecimal) a[4], (BigDecimal) a[5], ((BigDecimal) a[4]).subtract((BigDecimal) a[5]))));
        return result;
    }

    /** Bosh sahifadagi kartochkalar uchun: har bir mehmonxonaning shu oydagi qisqa ko'rsatkichlari. */
    @Transactional(readOnly = true)
    public Map<Long, StayMetrics> monthSummaries(List<Hotel> hotels) {
        Period month = Period.resolve("month", null, null, today());
        Map<Long, StayMetrics> result = new LinkedHashMap<>();
        for (Hotel hotel : hotels) {
            result.put(hotel.getId(), stays(hotel, month));
        }
        return result;
    }

    /**
     * To'lovlar to'liqmi: Exely Read Reservation API faqat oldindan to'lovni
     * beradi (joyida to'langani yo'q), shuning uchun Exely ma'lumotlarida
     * qarzdorlikni hisoblab bo'lmaydi — buning uchun Exely PMS API kerak.
     */
    @Transactional(readOnly = true)
    public boolean paymentsComplete(Hotel hotel) {
        return !bookingRepository.existsByHotelIdAndOrigin(hotel.getId(), DataOrigin.EXELY);
    }

    @Transactional(readOnly = true)
    public boolean hasData(Hotel hotel) {
        return bookingRepository.existsByHotelId(hotel.getId());
    }

    /** Davr bo'yicha yashash ko'rsatkichlari (hisobotlar uchun). */
    @Transactional(readOnly = true)
    public StayMetrics metrics(Hotel hotel, Period period) {
        return stays(hotel, period);
    }

    private StayMetrics stays(Hotel hotel, Period period) {
        List<Booking> bookings = bookingRepository.findStaysOverlapping(hotel.getId(), period.from(), period.toExclusive());
        return KpiCalculator.calculate(bookings, hotel.getRoomsCount(), period, serviceDays(hotel, period));
    }

    /** Exely PMS xizmatlar hisoboti (bo'lsa) — davrga tushgan kunlar bo'yicha [yashash, xizmatlar]. */
    private KpiCalculator.ServiceDays serviceDays(Hotel hotel, Period period) {
        if (hotel.getPmsServicesFrom() == null || hotel.getPmsServicesUntil() == null) {
            return null;
        }
        Map<LocalDate, BigDecimal[]> byDate = new HashMap<>();
        for (Object[] r : serviceRevenueRepository.dailyTotals(hotel.getId(), period.from(), period.to())) {
            byDate.put((LocalDate) r[0], new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2], (BigDecimal) r[3]});
        }
        return new KpiCalculator.ServiceDays(hotel.getPmsServicesFrom(), hotel.getPmsServicesUntil(), byDate);
    }
}
