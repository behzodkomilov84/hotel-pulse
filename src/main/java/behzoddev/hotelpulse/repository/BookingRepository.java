package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Booking;
import behzoddev.hotelpulse.entity.DataOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** [from, toExclusive) oralig'iga kamida bitta kechasi tushadigan bronlar. */
    @Query("""
            select b from Booking b
            where b.hotelId = :hotelId and b.arrivalDate < :toExclusive and b.departureDate > :from
            """)
    List<Booking> findStaysOverlapping(@Param("hotelId") Long hotelId,
                                       @Param("from") LocalDate from,
                                       @Param("toExclusive") LocalDate toExclusive);

    @Query("""
            select count(b) from Booking b
            where b.hotelId = :hotelId and b.bookedAt >= :from and b.bookedAt < :toExclusive
            """)
    long countBookedBetween(@Param("hotelId") Long hotelId,
                            @Param("from") LocalDateTime from,
                            @Param("toExclusive") LocalDateTime toExclusive);

    @Query("""
            select count(b) from Booking b
            where b.hotelId = :hotelId and b.cancelledAt >= :from and b.cancelledAt < :toExclusive
            """)
    long countCancelledBetween(@Param("hotelId") Long hotelId,
                               @Param("from") LocalDateTime from,
                               @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * Mehmon kelgan (kelish sanasi bugun yoki undan oldin) va to'liq to'lanmagan
     * bronlar: har bir qator [bron narxi, to'langan summa].
     */
    @Query("""
            select b.totalAmount, coalesce(sum(p.amount), 0) from Booking b
            left join Payment p on p.bookingId = b.id
            where b.hotelId = :hotelId and b.arrivalDate <= :today and b.balanceDue is null
              and b.status in (behzoddev.hotelpulse.entity.BookingStatus.CONFIRMED,
                               behzoddev.hotelpulse.entity.BookingStatus.CHECKED_IN,
                               behzoddev.hotelpulse.entity.BookingStatus.CHECKED_OUT)
            group by b.id, b.totalAmount
            having b.totalAmount > coalesce(sum(p.amount), 0)
            """)
    List<Object[]> findUnpaidStays(@Param("hotelId") Long hotelId, @Param("today") LocalDate today);

    boolean existsByHotelId(Long hotelId);

    boolean existsByHotelIdAndOrigin(Long hotelId, DataOrigin origin);

    List<Booking> findByHotelIdAndOriginAndExternalIdStartingWith(Long hotelId, DataOrigin origin, String prefix);

    /**
     * PMS bergan qoldiq bo'yicha qarzdorlik: faqat haqiqatan zaselenie qilingan (yashayotgan
     * yoki ketgan) mehmonlar, balance_due > 0. Sanasi o'tib, zaselenie qilinmagan (CONFIRMED)
     * bronlar qarz emas — PMS haqiqiy holatni bergani uchun ular chiqarib tashlanadi.
     * Natija: [jami qoldiq, bronlar soni].
     */
    @Query("""
            select coalesce(sum(b.balanceDue), 0), count(b) from Booking b
            where b.hotelId = :hotelId and b.arrivalDate <= :today and b.balanceDue > 0
              and b.status in (behzoddev.hotelpulse.entity.BookingStatus.CHECKED_IN,
                               behzoddev.hotelpulse.entity.BookingStatus.CHECKED_OUT)
            """)
    List<Object[]> sumBalanceDue(@Param("hotelId") Long hotelId, @Param("today") LocalDate today);

    /** Exely bronining barcha xona-yashash qatorlari ("{raqam}#0", "{raqam}#1", ...). */
    @Modifying
    @Query("delete from Booking b where b.hotelId = :hotelId and b.origin = :origin and b.externalId like concat(:prefix, '%')")
    int deleteByExternalPrefix(@Param("hotelId") Long hotelId, @Param("origin") DataOrigin origin,
                               @Param("prefix") String prefix);

    @Modifying
    @Query("delete from Booking b where b.hotelId = :hotelId and b.origin = :origin")
    int deleteByHotelIdAndOrigin(@Param("hotelId") Long hotelId, @Param("origin") DataOrigin origin);
}
