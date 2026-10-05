package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.ServiceRevenue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ServiceRevenueRepository extends JpaRepository<ServiceRevenue, Long> {

    /** Bitta yashashning (roomStay) barcha kunlik xizmatlari. */
    List<ServiceRevenue> findByHotelIdAndReservationIdOrderByServiceDate(Long hotelId, Long reservationId);

    /** Tekshiruv uchun: kunlar bo'yicha [sana, qatorlar soni, summa]. */
    @Query("""
            select s.serviceDate, count(s), coalesce(sum(s.amount), 0)
            from ServiceRevenue s
            where s.hotelId = :hotelId and s.serviceDate >= :from and s.serviceDate <= :to
            group by s.serviceDate
            """)
    List<Object[]> dailyCountsAndSums(@Param("hotelId") Long hotelId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    /** [from, to] (ikkala chegara ham kiradi) oynasidagi xizmatlar — qayta olinadigan oyna uchun. */
    @Modifying
    @Query("delete from ServiceRevenue s where s.hotelId = :hotelId and s.serviceDate >= :from and s.serviceDate <= :to")
    int deleteInWindow(@Param("hotelId") Long hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Kunlar bo'yicha (Exely DRR qatorlari kabi): [sana, yashash, nonushta/ovqatlanish, boshqa xizmatlar].
     * Yashash — kind 0 (yashash), 3 (erta kirish), 4 (kech chiqish); nonushta — Meals / Food service toifasi.
     */
    @Query("""
            select s.serviceDate,
                   coalesce(sum(case when s.kind in (0, 3, 4) then s.amount else 0 end), 0),
                   coalesce(sum(case when s.kind not in (0, 3, 4) and s.category in ('Meals', 'Food service')
                                     then s.amount else 0 end), 0),
                   coalesce(sum(case when s.kind not in (0, 3, 4)
                                      and (s.category is null or s.category not in ('Meals', 'Food service'))
                                     then s.amount else 0 end), 0)
            from ServiceRevenue s
            where s.hotelId = :hotelId and s.serviceDate >= :from and s.serviceDate <= :to
            group by s.serviceDate
            """)
    List<Object[]> dailyTotals(@Param("hotelId") Long hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
