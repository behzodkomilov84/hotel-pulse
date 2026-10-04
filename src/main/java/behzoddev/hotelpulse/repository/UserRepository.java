package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Role;
import behzoddev.hotelpulse.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(Role role);

    @EntityGraph(attributePaths = "hotels")
    List<User> findAllByOrderByIdAsc();
}
