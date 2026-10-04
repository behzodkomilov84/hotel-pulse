package behzoddev.hotelpulse.service;

import behzoddev.hotelpulse.entity.*;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Exely ulanmaguncha panelni sinab ko'rish uchun real ko'rinishdagi sinov
 * ma'lumotlari: o'tgan 180 kun va kelgusi 45 kun. Barcha yozuvlar
 * origin=DEMO bilan belgilanadi va bitta tugma bilan o'chiriladi.
 * Bir xil mehmonxona uchun natija har safar bir xil (seed = hotel id).
 */
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private static final int PAST_DAYS = 180;
    private static final int FUTURE_DAYS = 45;

    private static final String[] SOURCES = {"Booking.com", "To'g'ridan-to'g'ri", "Sayt", "Ostrovok", "Expedia"};
    private static final double[] SOURCE_WEIGHTS = {0.45, 0.25, 0.15, 0.08, 0.07};
    /** OTA komissiyasini qoplash uchun kanal narxi koeffitsienti. */
    private static final double[] SOURCE_PRICE = {1.10, 0.95, 1.00, 1.08, 1.12};
    private static final String[] METHODS = {"Naqd", "Karta", "Bank o'tkazmasi"};
    private static final String[] NAMES = {"Aliyev", "Karimova", "Smith", "Ivanov", "Müller", "Rashidov",
            "Tursunova", "Kim", "Yusupov", "Schmidt", "Petrova", "Nazarov", "Garcia", "Saidova", "Chen"};

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final Clock clock;

    @Transactional
    public int generate(Hotel hotel) {
        if (hotel.getRoomsCount() <= 0) {
            throw new IllegalArgumentException("Avval mehmonxonaning xonalar sonini kiriting");
        }
        delete(hotel);

        Random rnd = new Random(hotel.getId() * 7919L);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate start = today.minusDays(PAST_DAYS);
        LocalDate end = today.plusDays(FUTURE_DAYS);
        int totalDays = (int) ChronoUnit.DAYS.between(start, end) + 1;
        int rooms = hotel.getRoomsCount();
        int[] occupied = new int[totalDays + 15];
        double baseRate = 350_000 + rnd.nextInt(10) * 60_000;

        List<Booking> bookings = new ArrayList<>();
        List<double[]> paymentPlans = new ArrayList<>();
        int seq = 0;

        for (int day = 0; day < totalDays; day++) {
            LocalDate date = start.plusDays(day);
            double target = targetOccupancy(date, today, rnd);
            int attempts = 0;
            while (occupied[day] < Math.round(target * rooms) && attempts++ < rooms * 3) {
                int nights = 1 + (int) Math.min(6, Math.abs(rnd.nextGaussian() * 2));
                int roomsInBooking = rnd.nextDouble() < 0.12 ? 2 : 1;
                if (!fits(occupied, day, nights, roomsInBooking, rooms)) {
                    continue;
                }
                int src = pickSource(rnd);
                Booking b = newBooking(hotel, ++seq, src, date, nights, roomsInBooking, baseRate, now, rnd);

                boolean past = date.isBefore(today);
                if (past && rnd.nextDouble() < 0.03) {
                    b.setStatus(BookingStatus.NO_SHOW);
                } else {
                    for (int n = 0; n < nights; n++) {
                        occupied[day + n] += roomsInBooking;
                    }
                    b.setStatus(statusFor(b, today));
                }
                bookings.add(b);
                paymentPlans.add(paymentPlan(b, today, rnd));

                // Har ~9 bronga bitta bekor qilingan bron (xona band qilmaydi).
                if (rnd.nextDouble() < 0.11) {
                    Booking c = newBooking(hotel, ++seq, pickSource(rnd), date, nights, 1, baseRate, now, rnd);
                    c.setStatus(BookingStatus.CANCELLED);
                    LocalDateTime cancelledAt = c.getBookedAt().plusHours(2 + rnd.nextInt(24 * 10));
                    LocalDateTime arrivalStart = c.getArrivalDate().atStartOfDay();
                    if (cancelledAt.isAfter(arrivalStart)) {
                        cancelledAt = arrivalStart.minusHours(3);
                    }
                    c.setCancelledAt(notAfter(cancelledAt, now, c.getBookedAt()));
                    bookings.add(c);
                    paymentPlans.add(new double[]{0, 0});
                }
            }
        }

        bookingRepository.saveAll(bookings);

        List<Payment> payments = new ArrayList<>();
        for (int i = 0; i < bookings.size(); i++) {
            Booking b = bookings.get(i);
            double[] plan = paymentPlans.get(i);
            if (plan[0] > 0) {
                payments.add(payment(hotel, b, "p" + b.getExternalId() + "a", plan[0],
                        notAfter(b.getBookedAt().plusHours(1 + rnd.nextInt(48)), now, b.getBookedAt()), rnd));
            }
            if (plan[1] > 0) {
                LocalDateTime paidAt = b.getDepartureDate().isAfter(today)
                        ? b.getArrivalDate().atTime(15, rnd.nextInt(60))
                        : b.getDepartureDate().atTime(10, rnd.nextInt(60));
                payments.add(payment(hotel, b, "p" + b.getExternalId() + "b", plan[1],
                        notAfter(paidAt, now, b.getBookedAt()), rnd));
            }
        }
        paymentRepository.saveAll(payments);
        return bookings.size();
    }

    @Transactional
    public void delete(Hotel hotel) {
        paymentRepository.deleteByHotelIdAndOrigin(hotel.getId(), DataOrigin.DEMO);
        bookingRepository.deleteByHotelIdAndOrigin(hotel.getId(), DataOrigin.DEMO);
    }

    /** Bandlik maqsadi: dam olish kunlari yuqori, yil davomida mavsumiy to'lqin, kelajak hali to'lmagan. */
    private static double targetOccupancy(LocalDate date, LocalDate today, Random rnd) {
        double t = 0.58;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY) {
            t += 0.17;
        }
        t += 0.12 * Math.sin(2 * Math.PI * (date.getDayOfYear() - 80) / 365.0);
        t += rnd.nextGaussian() * 0.06;
        long ahead = ChronoUnit.DAYS.between(today, date);
        if (ahead > 0) {
            // Kelajak sanalar uchun hozircha bron qilingan qism ("on the books").
            t *= Math.max(0.15, 1 - ahead / 50.0);
        }
        return Math.max(0.05, Math.min(0.98, t));
    }

    private static boolean fits(int[] occupied, int day, int nights, int need, int rooms) {
        for (int n = 0; n < nights; n++) {
            if (occupied[day + n] + need > rooms) {
                return false;
            }
        }
        return true;
    }

    private static int pickSource(Random rnd) {
        double r = rnd.nextDouble();
        double acc = 0;
        for (int i = 0; i < SOURCE_WEIGHTS.length; i++) {
            acc += SOURCE_WEIGHTS[i];
            if (r < acc) {
                return i;
            }
        }
        return 0;
    }

    private static Booking newBooking(Hotel hotel, int seq, int src, LocalDate arrival, int nights,
                                      int rooms, double baseRate, LocalDateTime now, Random rnd) {
        Booking b = new Booking();
        b.setHotelId(hotel.getId());
        b.setOrigin(DataOrigin.DEMO);
        b.setExternalId("demo-" + seq);
        b.setSource(SOURCES[src]);
        b.setGuestName(NAMES[rnd.nextInt(NAMES.length)]);
        b.setArrivalDate(arrival);
        b.setDepartureDate(arrival.plusDays(nights));
        b.setRooms(rooms);
        b.setGuests(rooms + rnd.nextInt(rooms + 1));

        double total = 0;
        for (int n = 0; n < nights; n++) {
            DayOfWeek dow = arrival.plusDays(n).getDayOfWeek();
            double weekend = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY ? 1.15 : 1.0;
            total += baseRate * weekend * SOURCE_PRICE[src] * (0.9 + rnd.nextDouble() * 0.2);
        }
        total *= rooms;
        b.setTotalAmount(BigDecimal.valueOf(Math.round(total / 1000.0) * 1000L).setScale(2, RoundingMode.UNNECESSARY));

        int leadDays = (int) Math.min(90, Math.abs(rnd.nextGaussian() * 20));
        LocalDateTime bookedAt = arrival.minusDays(leadDays).atTime(8 + rnd.nextInt(14), rnd.nextInt(60));
        // Kelajakdagi bron ham allaqachon (hozirgacha) qilingan bo'lishi kerak.
        b.setBookedAt(bookedAt.isAfter(now) ? now.minusMinutes(1 + rnd.nextInt(60 * 24 * 7)) : bookedAt);
        return b;
    }

    private static BookingStatus statusFor(Booking b, LocalDate today) {
        if (!b.getDepartureDate().isAfter(today)) {
            return BookingStatus.CHECKED_OUT;
        }
        if (!b.getArrivalDate().isAfter(today)) {
            return BookingStatus.CHECKED_IN;
        }
        return BookingStatus.CONFIRMED;
    }

    /** [oldindan to'lov, qolgan to'lov] summalari. */
    private static double[] paymentPlan(Booking b, LocalDate today, Random rnd) {
        double total = b.getTotalAmount().doubleValue();
        double prepay = rnd.nextDouble() < 0.35 ? round(total * 0.3) : 0;
        double rest = total - prepay;
        switch (b.getStatus()) {
            case CHECKED_OUT -> {
                double r = rnd.nextDouble();
                // Eski qarzlar odatda undirilgan bo'ladi — qarz faqat oxirgi ~30 kunda qoladi.
                if (b.getDepartureDate().isBefore(today.minusDays(30))) {
                    r = 1;
                }
                if (r < 0.06) {
                    rest = round(rest * 0.5);       // qisman to'langan — qarz qoladi
                } else if (r < 0.08) {
                    rest = 0;                       // umuman to'lanmagan
                }
            }
            case CHECKED_IN -> {
                double r = rnd.nextDouble();
                if (r < 0.3) {
                    rest = round(rest * 0.5);
                } else if (r < 0.45) {
                    rest = 0;
                }
            }
            default -> rest = 0;                    // hali kelmagan yoki kelmadi
        }
        if (b.getStatus() == BookingStatus.NO_SHOW) {
            prepay = 0;
        }
        return new double[]{prepay, rest};
    }

    private static Payment payment(Hotel hotel, Booking b, String externalId, double amount,
                                   LocalDateTime paidAt, Random rnd) {
        Payment p = new Payment();
        p.setHotelId(hotel.getId());
        p.setBookingId(b.getId());
        p.setOrigin(DataOrigin.DEMO);
        p.setExternalId(externalId);
        p.setAmount(BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
        p.setMethod(METHODS[rnd.nextInt(METHODS.length)]);
        p.setPaidAt(paidAt);
        return p;
    }

    /** Vaqtni "hozir"dan oshirmaydi, lekin pastki chegaradan (bron vaqtidan) oldinga ham tushirmaydi. */
    private static LocalDateTime notAfter(LocalDateTime t, LocalDateTime now, LocalDateTime floor) {
        if (!t.isAfter(now)) {
            return t;
        }
        return floor.isAfter(now) ? floor : now;
    }

    private static double round(double v) {
        return Math.round(v / 1000.0) * 1000.0;
    }
}
