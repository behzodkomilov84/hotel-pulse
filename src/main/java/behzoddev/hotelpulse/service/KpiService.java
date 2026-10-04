package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.kpi.*;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KpiService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
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

        BigDecimal debt = BigDecimal.ZERO;
        List<Object[]> unpaid = bookingRepository.findUnpaidStays(hotel.getId(), today);
        for (Object[] row : unpaid) {
            debt = debt.add(((BigDecimal) row[0]).subtract((BigDecimal) row[1]));
        }

        double occupancy = hotel.getRoomsCount() == 0 ? 0 : (double) inHouse / hotel.getRoomsCount();
        return new TodaySnapshot(arrivals, departures, inHouse, occupancy, debt, unpaid.size());
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

    private StayMetrics stays(Hotel hotel, Period period) {
        List<Booking> bookings = bookingRepository.findStaysOverlapping(hotel.getId(), period.from(), period.toExclusive());
        return KpiCalculator.calculate(bookings, hotel.getRoomsCount(), period);
    }
}
