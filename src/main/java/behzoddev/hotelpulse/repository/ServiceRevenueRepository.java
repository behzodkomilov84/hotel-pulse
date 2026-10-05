package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.ServiceRevenue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ServiceRevenueRepository extends JpaRepository<ServiceRevenue, Long> {

    /** [from, to] (ikkala chegara ham kiradi) oynasidagi xizmatlar — qayta olinadigan oyna uchun. */
    @Modifying
    @Query("delete from ServiceRevenue s where s.hotelId = :hotelId and s.serviceDate >= :from and s.serviceDate <= :to")
    int deleteInWindow(@Param("hotelId") Long hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Kunlar bo'yicha: [sana, yashash summasi, xizmatlar summasi]. */
    @Query("""
            select s.serviceDate,
                   coalesce(sum(case when s.kind = 0 then s.amount else 0 end), 0),
                   coalesce(sum(case when s.kind <> 0 then s.amount else 0 end), 0)
            from ServiceRevenue s
            where s.hotelId = :hotelId and s.serviceDate >= :from and s.serviceDate <= :to
            group by s.serviceDate
            """)
    List<Object[]> dailyTotals(@Param("hotelId") Long hotelId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
