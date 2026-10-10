package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.Task;
import behzoddev.hotelpulse.entity.TaskStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @EntityGraph(attributePaths = {"hotel", "assignee", "assignedBy"})
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findFull(@Param("id") Long id);

    /** Foydalanuvchi ko'radigan topshiriqlar: o'ziga berilganlar + nazorat qiladigan mehmonxonalaridagilar. */
    @EntityGraph(attributePaths = {"hotel", "assignee", "assignedBy"})
    @Query("select t from Task t where t.assignee.id = :userId or t.hotel.id in :hotelIds order by t.createdAt desc")
    List<Task> findVisible(@Param("userId") Long userId, @Param("hotelIds") Collection<Long> hotelIds);

    /** Eslatma uchun: ochiq, muddati bugun yoki o'tgan. */
    @EntityGraph(attributePaths = {"hotel", "assignee", "assignedBy"})
    List<Task> findAllByStatusInAndDueDateLessThanEqual(Collection<TaskStatus> statuses, LocalDate day);
}
