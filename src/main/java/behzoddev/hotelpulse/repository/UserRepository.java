package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(Role role);

    @EntityGraph(attributePaths = "hotels")
    List<User> findAllByOrderByIdAsc();

    Optional<User> findByTelegramChatId(Long chatId);

    /** Mehmonxonaga biriktirilgan, faol, berilgan roldagi foydalanuvchilar (topshiriq beriladigan xodimlar). */
    @EntityGraph(attributePaths = "departments")
    @Query("select u from User u join u.hotels h where h.id = :hotelId and u.role = :role and u.enabled = true "
            + "order by coalesce(u.fullName, u.username)")
    List<User> findActiveByHotelAndRole(@Param("hotelId") Long hotelId, @Param("role") Role role);

    /** Mehmonxonalardan biriga biriktirilgan xodimlar (bo'limlari va mehmonxonalari bilan). */
    @EntityGraph(attributePaths = {"hotels", "departments", "departments.hotel"})
    @Query("select distinct u from User u join u.hotels h where u.role = :role and h.id in :hotelIds")
    List<User> findAllByRoleAndHotelIds(@Param("role") Role role, @Param("hotelIds") java.util.Collection<Long> hotelIds);

    @EntityGraph(attributePaths = {"hotels", "departments", "departments.hotel"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithTeam(@Param("id") Long id);

    /** Kunlik hisobot oladiganlar: ulangan, faol va hisobotni o'chirmaganlar. */
    List<User> findAllByTelegramChatIdIsNotNullAndTelegramDailyReportTrueAndEnabledTrue();
}
