package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.DataOrigin;
import behzoddev.hotelpulse.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.hotelId = :hotelId and p.paidAt >= :from and p.paidAt < :toExclusive
            """)
    BigDecimal sumPaidBetween(@Param("hotelId") Long hotelId,
                              @Param("from") LocalDateTime from,
                              @Param("toExclusive") LocalDateTime toExclusive);

    @Modifying
    @Query("delete from Payment p where p.hotelId = :hotelId and p.origin = :origin")
    int deleteByHotelIdAndOrigin(@Param("hotelId") Long hotelId, @Param("origin") DataOrigin origin);
}
