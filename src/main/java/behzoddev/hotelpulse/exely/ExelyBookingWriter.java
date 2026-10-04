package behzoddev.hotelpulse.exely;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Hotel;
import behzoddev.hotelpulse.entity.Payment;
import behzoddev.hotelpulse.repository.BookingRepository;
import behzoddev.hotelpulse.repository.HotelRepository;
import behzoddev.hotelpulse.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
