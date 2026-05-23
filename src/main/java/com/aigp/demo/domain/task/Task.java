package com.aigp.demo.domain.task;

import com.aigp.demo.domain.enums.TaskStatus;
import com.aigp.demo.domain.goal.Goal;
import com.aigp.demo.domain.goal.Milestone;
import com.aigp.demo.domain.plan.Plan;
import com.aigp.demo.domain.user.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
public class Task {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "plan_id", nullable = false)
	private Plan plan;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "goal_id", nullable = false)
	private Goal goal;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "milestone_id")
	private Milestone milestone;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 1000)
	private String description;

	@Column(name = "scheduled_date", nullable = false)
	private LocalDate scheduledDate;

	@Column(name = "estimated_minutes", nullable = false)
	private Integer estimatedMinutes = 0;

	@Column(name = "actual_minutes")
	private Integer actualMinutes;

	/**
	 * 用户自评完成质量（1–5），与库表 {@code TINYINT UNSIGNED} 一致；未评分为 {@code null}。
	 */
	@Column(name = "quality_score")
	private Byte qualityScore;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TaskStatus status = TaskStatus.PENDING;

	@Column(name = "skip_reason", length = 500)
	private String skipReason;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	/** 用户点击「开始执行」的时刻（用户本地语义由业务层按 timezone 解释） */
	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
