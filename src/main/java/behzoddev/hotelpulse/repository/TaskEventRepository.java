package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.TaskEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    @EntityGraph(attributePaths = "user")
    List<TaskEvent> findAllByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);
}
