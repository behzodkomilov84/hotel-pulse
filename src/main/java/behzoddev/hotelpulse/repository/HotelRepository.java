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
}
