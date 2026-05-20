package com.aigp.demo.domain.user;

import com.aigp.demo.domain.enums.PushDevicePlatform;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 用户移动推送标识（uni-push 2.0 为 {@code push_clientid}，存于列 fcm_token）；多设备用 device_id 区分。
 */
@Entity
@Table(
		name = "user_push_devices",
		uniqueConstraints = @UniqueConstraint(name = "uk_push_user_device", columnNames = {"user_id", "device_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UserPushDevice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	/** 客户端设备标识（重装 App 可换新，但同一次安装应保持稳定） */
	@Column(name = "device_id", nullable = false, length = 64)
	private String deviceId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PushDevicePlatform platform;

	/** uni.getPushClientId() 返回值（个推 CID） */
	@Column(name = "fcm_token", nullable = false, length = 512)
	private String pushToken;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
