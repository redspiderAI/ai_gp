package com.aigp.demo.repository;

import com.aigp.demo.domain.chat.UserAssistantTask;
import com.aigp.demo.domain.enums.UserAssistantTaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAssistantTaskRepository extends JpaRepository<UserAssistantTask, Long> {

	List<UserAssistantTask> findByUser_IdOrderByUpdatedAtDesc(Long userId);

	List<UserAssistantTask> findByUser_IdAndStatusOrderByUpdatedAtDesc(Long userId, UserAssistantTaskStatus status);

	/**
	 * 按用户与「到期日」查询助手待办：{@code due_date = date} 或 {@code due_at} 落在该自然日。
	 */
	@Query(
			"""
			SELECT t FROM UserAssistantTask t
			WHERE t.user.id = :userId
			  AND (
			    t.dueDate = :date
			    OR (t.dueAt >= :dayStart AND t.dueAt < :dayEnd)
			  )
			ORDER BY t.dueAt ASC, t.dueDate ASC, t.createdAt ASC
			""")
	List<UserAssistantTask> findByUser_IdAndDueOnDate(
			@Param("userId") Long userId,
			@Param("date") LocalDate date,
			@Param("dayStart") LocalDateTime dayStart,
			@Param("dayEnd") LocalDateTime dayEnd);

	Optional<UserAssistantTask> findByIdAndUser_Id(Long id, Long userId);

	@Query("SELECT t FROM UserAssistantTask t JOIN FETCH t.user u WHERE t.id = :id")
	Optional<UserAssistantTask> findByIdWithUser(@Param("id") Long id);

	/**
	 * 提醒扫描候选集（粗筛）：再由 {@link TaskReminderDueEvaluator} 按用户时区精确判断。
	 * <p>含 {@code due_at} 已逾期且 {@code reminder_sent_at} 为空的历史任务（补发），避免仅 36h 窗口漏扫。
	 */
	@Query(
			"""
			SELECT t FROM UserAssistantTask t JOIN FETCH t.user u
			WHERE t.status = :status
			  AND (
			    (t.dueAt IS NOT NULL AND t.dueAt <= :dueAtCeiling
			      AND (t.dueAt >= :dueAtFloor OR t.reminderSentAt IS NULL))
			    OR (t.dueAt IS NULL AND t.dueDate IS NOT NULL AND t.dueDate <= :dueDateCeiling)
			  )
			ORDER BY t.dueAt ASC, t.dueDate ASC, t.id ASC
			""")
	List<UserAssistantTask> findOpenTasksReminderCandidates(
			@Param("status") UserAssistantTaskStatus status,
			@Param("dueAtFloor") LocalDateTime dueAtFloor,
			@Param("dueAtCeiling") LocalDateTime dueAtCeiling,
			@Param("dueDateCeiling") LocalDate dueDateCeiling);

	@Query(
			"""
			SELECT t FROM UserAssistantTask t
			WHERE t.user.id = :userId
			  AND t.updatedAt >= :from AND t.updatedAt < :to
			ORDER BY t.updatedAt ASC
			""")
	List<UserAssistantTask> findByUser_IdAndUpdatedAtBetween(
			@Param("userId") Long userId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	/**
	 * 每日摘要粗筛：OPEN 且 due_date 或 due_at 落在窗口内（再由服务按用户本地「今天」过滤）。
	 */
	@Query(
			"""
			SELECT t FROM UserAssistantTask t JOIN FETCH t.user u
			WHERE t.status = :status
			  AND (
			    (t.dueDate IS NOT NULL AND t.dueDate >= :fromDate AND t.dueDate <= :toDate)
			    OR (t.dueAt IS NOT NULL AND t.dueAt >= :dueAtFrom AND t.dueAt < :dueAtTo)
			  )
			ORDER BY t.dueAt ASC, t.dueDate ASC, t.id ASC
			""")
	List<UserAssistantTask> findOpenTasksWithDueDateBetween(
			@Param("status") UserAssistantTaskStatus status,
			@Param("fromDate") LocalDate fromDate,
			@Param("toDate") LocalDate toDate,
			@Param("dueAtFrom") LocalDateTime dueAtFrom,
			@Param("dueAtTo") LocalDateTime dueAtTo);
}
