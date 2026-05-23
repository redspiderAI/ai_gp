package com.aigp.demo.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 MySQL 行的简易分布式锁，供定时提醒等单飞任务使用。
 */
@Slf4j
@Service
public class SchedulerLockService {

	private static final String LOCK_TASK_REMINDER = "assistant_task_reminder";
	private static final String LOCK_COMPANION_WEEKLY = "companion_weekly_memory";
	private static final String LOCK_GROWTH_TASK_EXECUTION = "growth_task_execution";

	private final JdbcTemplate jdbcTemplate;
	private final String ownerId = UUID.randomUUID().toString().substring(0, 8);

	public SchedulerLockService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * 尝试获取助手任务提醒 tick 锁。
	 *
	 * @param holdFor 锁持有时长，应略大于单次扫描耗时
	 * @return true 表示本实例获得锁
	 */
	public boolean tryAcquireTaskReminderLock(Duration holdFor) {
		return tryAcquire(LOCK_TASK_REMINDER, holdFor);
	}

	/**
	 * 尝试获取每周陪伴记忆总结任务锁。
	 */
	public boolean tryAcquireCompanionWeeklyLock(Duration holdFor) {
		return tryAcquire(LOCK_COMPANION_WEEKLY, holdFor);
	}

	/** 成长计划任务：自动完成 / 跨日未完成。 */
	public boolean tryAcquireGrowthTaskExecutionLock(Duration holdFor) {
		return tryAcquire(LOCK_GROWTH_TASK_EXECUTION, holdFor);
	}

	private boolean tryAcquire(String lockName, Duration holdFor) {
		Instant until = Instant.now().plus(holdFor);
		Timestamp untilTs = Timestamp.from(until);
		int updated = jdbcTemplate.update(
				"""
				UPDATE scheduler_lock
				SET locked_until = ?, locked_by = ?, updated_at = UTC_TIMESTAMP(3)
				WHERE lock_name = ? AND locked_until < UTC_TIMESTAMP(3)
				""",
				untilTs,
				ownerId,
				lockName);
		if (updated > 0) {
			return true;
		}
		try {
			jdbcTemplate.update(
					"""
					INSERT INTO scheduler_lock (lock_name, locked_until, locked_by)
					VALUES (?, ?, ?)
					""",
					lockName,
					untilTs,
					ownerId);
			return true;
		} catch (DuplicateKeyException ex) {
			return false;
		}
	}
}
