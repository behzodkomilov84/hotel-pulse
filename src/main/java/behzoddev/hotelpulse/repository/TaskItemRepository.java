package behzoddev.hotelpulse.repository;

import behzoddev.hotelpulse.entity.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findAllByTaskIdOrderByPositionAsc(Long taskId);

    /** Topshiriqlar bo'yicha ilova: [task id, qatorlar soni, bajarilganlari, jami qarz]. */
    @Query("select i.task.id, count(i), sum(case when i.done = true then 1 else 0 end), sum(i.debt) "
            + "from TaskItem i where i.task.id in :taskIds group by i.task.id")
    List<Object[]> summarize(@Param("taskIds") Collection<Long> taskIds);
}
