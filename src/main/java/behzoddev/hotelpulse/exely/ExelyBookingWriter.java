package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Payment;
import behzoddev.hotelpulse.entity.ServiceRevenue;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import behzoddev.hotelpulse.repository.ServiceRevenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Har bir Exely bronini alohida (qisqa) tranzaksiyada yozadi — uzun
 * sinxronlash o'rtasida xato bo'lsa, oldin yozilganlari saqlanib qoladi.
 */
@Component
@RequiredArgsConstructor
public class ExelyBookingWriter {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final HotelRepository hotelRepository;
    private final ServiceRevenueRepository serviceRevenueRepository;
    private final Clock clock;

    /** Bronning eski qatorlarini o'chirib, eng so'nggi versiyasini yozadi (upsert). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(Long hotelId, ExelyApi.Booking src) {
        String prefix = ExelyBookingMapper.externalPrefix(src.number());
        paymentRepository.deleteByExternalPrefix(hotelId, DataOrigin.EXELY, prefix);
        bookingRepository.deleteByExternalPrefix(hotelId, DataOrigin.EXELY, prefix);

        List<Booking> rows = ExelyBookingMapper.toBookings(src, hotelId, clock.getZone(), LocalDate.now(clock));
        if (rows.isEmpty()) {
            return;
        }
        bookingRepository.saveAll(rows);

        BigDecimal prepaid = ExelyBookingMapper.prepaid(src);
        if (prepaid.signum() > 0) {
            Booking first = rows.get(0);
            Payment p = new Payment();
            p.setHotelId(hotelId);
            p.setBookingId(first.getId());
            p.setOrigin(DataOrigin.EXELY);
            p.setExternalId(prefix + "prepaid");
            p.setAmount(prepaid);
            p.setMethod("Oldindan to'lov");
            p.setPaidAt(first.getBookedAt());
            paymentRepository.save(p);
        }
    }

    /** PMS broni: eski qatorlarini o'chirib, yangisini yozadi; birinchi ko'rilgan vaqt (bookedAt) saqlanadi. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertPms(Long hotelId, ExelyPmsApi.Booking src, MoneyConverter money) {
        String prefix = ExelyPmsMapper.externalPrefix(src.number());
        Map<String, LocalDateTime> known = new HashMap<>();
        for (Booking old : bookingRepository.findByHotelIdAndOriginAndExternalIdStartingWith(hotelId, DataOrigin.EXELY_PMS, prefix)) {
            known.put(old.getExternalId(), old.getBookedAt());
        }
        bookingRepository.deleteByExternalPrefix(hotelId, DataOrigin.EXELY_PMS, prefix);
        List<Booking> rows = ExelyPmsMapper.toBookings(src, hotelId, clock.getZone(), known, money);
        if (!rows.isEmpty()) {
            bookingRepository.saveAll(rows);
        }
    }

    /** [from, to) oynasidagi PMS to'lovlarini to'liq almashtiradi (keyin bekor qilinganlari ham to'g'rilanadi). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int replacePmsPayments(Long hotelId, LocalDateTime from, LocalDateTime to, List<ExelyPmsApi.Payment> src,
                                  MoneyConverter money) {
        paymentRepository.deleteInWindow(hotelId, DataOrigin.EXELY_PMS, from, to);
        List<Payment> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ExelyPmsApi.Payment p : src) {
            Payment pay = ExelyPmsMapper.toPayment(p, hotelId, money);
            if (pay != null && !pay.getPaidAt().isBefore(from) && pay.getPaidAt().isBefore(to) && seen.add(pay.getExternalId())) {
                rows.add(pay);
            }
        }
        paymentRepository.saveAll(rows);
        return rows.size();
    }

    /**
     * Mehmonxona PMS'ga o'tganda boshqa manbalarning (demo, Read Reservation) yozuvlari
     * o'chiriladi — aks holda bronlar ikki marta sanalardi. @return o'chirilgan bronlar soni.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeNonPmsData(Long hotelId) {
        int removed = 0;
        for (DataOrigin o : List.of(DataOrigin.DEMO, DataOrigin.EXELY)) {
            paymentRepository.deleteByHotelIdAndOrigin(hotelId, o);
            removed += bookingRepository.deleteByHotelIdAndOrigin(hotelId, o);
        }
        return removed;
    }

    /**
     * [from, to] kunlaridagi PMS xizmatlarini (kunlik daromad) to'liq almashtiradi va qamrovni kengaytiradi.
     * @return yozilgan qatorlar soni
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int replacePmsServices(Long hotelId, String hotelCurrency, LocalDate from, LocalDate to,
                                  ExelyPmsApi.ServicesData src, MoneyConverter money) {
        Map<String, ExelyPmsMapper.BookingMoney> foreign = new HashMap<>();
        for (Booking b : bookingRepository.findForeignCurrency(hotelId, DataOrigin.EXELY_PMS,
                hotelCurrency == null ? "UZS" : hotelCurrency)) {
            String number = ExelyPmsMapper.bookingNumber(b.getExternalId());
            foreign.merge(number, new ExelyPmsMapper.BookingMoney(b.getCurrency(), b.getTotalAmount()),
                    (a, c) -> new ExelyPmsMapper.BookingMoney(a.currency(), a.totalInHotelCurrency().add(c.totalInHotelCurrency())));
        }
        serviceRevenueRepository.deleteInWindow(hotelId, from, to);
        Set<String> seen = new HashSet<>();
        List<ServiceRevenue> rows = ExelyPmsMapper.toServices(src, hotelId, foreign, money).stream()
                .filter(s -> !s.getServiceDate().isBefore(from) && !s.getServiceDate().isAfter(to))
                .filter(s -> seen.add(s.getExternalId() + "@" + s.getServiceDate()))
                .toList();
        serviceRevenueRepository.saveAll(rows);
        hotelRepository.findById(hotelId).ifPresent(h -> {
            if (h.getPmsServicesFrom() == null || from.isBefore(h.getPmsServicesFrom())) {
                h.setPmsServicesFrom(from);
            }
            if (h.getPmsServicesUntil() == null || to.isAfter(h.getPmsServicesUntil())) {
                h.setPmsServicesUntil(to);
            }
        });
        return rows.size();
    }

    /** Xonalar soni Exely PMS'dagi xonalar ro'yxatidan. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveRoomsCount(Long hotelId, int rooms) {
        hotelRepository.findById(hotelId).ifPresent(h -> h.setRoomsCount(rooms));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePmsCursors(Long hotelId, LocalDateTime bookingsUntil, LocalDateTime paymentsUntil) {
        hotelRepository.findById(hotelId).ifPresent(h -> {
            if (bookingsUntil != null) {
                h.setPmsBookingsSyncedUntil(bookingsUntil);
            }
            if (paymentsUntil != null) {
                h.setPmsPaymentsSyncedUntil(paymentsUntil);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveContinueToken(Long hotelId, String token) {
        hotelRepository.findById(hotelId).ifPresent(h -> h.setExelyContinueToken(token));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveStatus(Long hotelId, boolean ok, String message) {
        hotelRepository.findById(hotelId).ifPresent(h -> {
            h.setExelyLastSyncAt(LocalDateTime.now(clock));
            h.setExelyLastSyncOk(ok);
            h.setExelyLastSyncMessage(message == null ? null : message.length() > 500 ? message.substring(0, 500) : message);
        });
    }

    @Transactional(readOnly = true)
    public Hotel load(Long hotelId) {
        return hotelRepository.findById(hotelId).orElse(null);
    }
}
