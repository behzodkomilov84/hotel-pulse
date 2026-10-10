package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Department;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    @EntityGraph(attributePaths = "hotel")
    @Query("select d from Department d where d.hotel.id in :hotelIds order by d.hotel.name, d.name")
    List<Department> findAllByHotelIds(@Param("hotelIds") Collection<Long> hotelIds);

    @EntityGraph(attributePaths = "hotel")
    @Query("select d from Department d where d.id = :id")
    Optional<Department> findFull(@Param("id") Long id);

    @Query("select d from Department d where d.hotel.id = :hotelId and lower(d.name) = lower(:name)")
    Optional<Department> findByHotelAndName(@Param("hotelId") Long hotelId, @Param("name") String name);

    /** Bo'limlar bo'yicha faol xodimlar soni: [department id, soni]. */
    @Query("select d.id, count(u) from User u join u.departments d where d.hotel.id in :hotelIds group by d.id")
    List<Object[]> countMembers(@Param("hotelIds") Collection<Long> hotelIds);
}
