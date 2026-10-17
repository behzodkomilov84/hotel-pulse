package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

    List<Hotel> findAllByOrderByNameAsc();

    @Query("select h from User u join u.hotels h where u.id = :userId order by h.name")
    List<Hotel> findAllByUserId(@Param("userId") Long userId);

    @Query("select count(u) > 0 from User u join u.hotels h where u.id = :userId and h.id = :hotelId")
    boolean isUserLinked(@Param("userId") Long userId, @Param("hotelId") Long hotelId);

    /** Mehmonxonada tasdiqlangan PMS agenti (UCS Shelter yoki 1С:Отель) bor — u Exely Connect'dan ustun. */
    @Query(value = "select count(*) from agents where hotel_id = :hotelId and kind in ('SHELTER', '1C') and approved_at is not null",
            nativeQuery = true)
    long countPmsAgents(@Param("hotelId") Long hotelId);

    default boolean hasPmsAgent(Long hotelId) {
        return countPmsAgents(hotelId) > 0;
    }
}
