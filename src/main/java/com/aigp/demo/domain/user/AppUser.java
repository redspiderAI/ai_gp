package com.aigp.demo.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

	/**
	 * 内部主键，由数据库自增生成（非 Java 手写）。
	 * 生产库建议自增起点为 {@code 100000000}，见 {@code md文档/数据库.md} 建表语句或 {@code scripts/mysql-users-autoincrement.sql}。
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 16)
	private String uid;

	@Column(length = 50)
	private String nickname;

	/** 已绑定手机号（11 位大陆号码，与 {@code user_identities.phone} 同步，可用于登录） */
	@Column(length = 11, unique = true)
	private String phone;

	@Column(name = "avatar_url", length = 500)
	private String avatarUrl;

	/**
	 * 每周可投入小时数（0–40），与库表 {@code TINYINT UNSIGNED} 一致；未设置可为 {@code null}（库默认 0）。
	 */
	@Column(name = "weekly_hours")
	private Byte weeklyHours;

	@Column(nullable = false)
	private Byte status = 1;

	@Column(nullable = false, length = 50)
	private String timezone = "Asia/Shanghai";

	@Column(nullable = false, length = 10)
	private String language = "zh-CN";

	/** 年龄（周岁，首次登录画像） */
	@Column(name = "profile_age")
	private Byte profileAge;

	/** 职业（首次登录画像） */
	@Column(name = "profile_occupation", length = 200)
	private String profileOccupation;

	/** 爱好（自由文本，可逗号或换行分隔） */
	@Column(name = "profile_hobbies", columnDefinition = "TEXT")
	private String profileHobbies;

	/** 希望探索的专业方向等 */
	@Column(name = "profile_exploration", columnDefinition = "TEXT")
	private String profileExploration;

	@Column(name = "onboarding_completed", nullable = false)
	private Boolean onboardingCompleted = Boolean.FALSE;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
