package com.aigp.demo.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "user_notification_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserNotificationSettings {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private AppUser user;

	@Column(name = "daily_task_reminder", nullable = false)
	private boolean dailyTaskReminder = true;

	/** 用户本地日历日：上次已投递每日任务摘要的日期（幂等） */
	@Column(name = "daily_briefing_last_sent_date")
	private LocalDate dailyBriefingLastSentDate;

	@Column(name = "weekly_companion_digest", nullable = false)
	private boolean weeklyCompanionDigest = true;

	@Column(name = "conflict_alert", nullable = false)
	private boolean conflictAlert = true;

	@Column(name = "milestone_celebration", nullable = false)
	private boolean milestoneCelebration = true;

	@Column(name = "lagging_warning", nullable = false)
	private boolean laggingWarning = true;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
