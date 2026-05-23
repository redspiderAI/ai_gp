package com.aigp.demo.repository;

import com.aigp.demo.domain.enums.TaskStatus;
import com.aigp.demo.domain.task.Task;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

	List<Task> findByUser_IdAndScheduledDateOrderByCreatedAtAsc(Long userId, LocalDate scheduledDate);

	List<Task> findByUser_IdAndScheduledDateBetweenOrderByScheduledDateAscCreatedAtAsc(
			Long userId, LocalDate from, LocalDate to);

	Optional<Task> findByIdAndUser_Id(Long id, Long userId);

	List<Task> findByStatusIn(Collection<TaskStatus> statuses);

	@Query(
			"""
			SELECT t FROM Task t JOIN FETCH t.user u
			WHERE t.status IN :statuses
			  AND t.scheduledDate >= :fromDate
			  AND t.scheduledDate <= :toDate
			ORDER BY t.scheduledDate ASC, t.createdAt ASC
			""")
	List<Task> findByStatusInAndScheduledDateBetween(
			@Param("statuses") Collection<TaskStatus> statuses,
			@Param("fromDate") LocalDate fromDate,
			@Param("toDate") LocalDate toDate);
}
