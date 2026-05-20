package com.aigp.demo.service;

import com.aigp.demo.domain.enums.PushDevicePlatform;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.UserPushDevice;
import com.aigp.demo.repository.UserPushDeviceRepository;
import com.aigp.demo.web.user.dto.PushTokenRegisteredResponse;
import com.aigp.demo.web.user.dto.UpsertPushTokenRequest;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户 uni-push 设备 clientId（push_clientid）的注册与注销（仅当前登录用户，防越权）。
 */
@Service
@RequiredArgsConstructor
public class UserPushDeviceService {

	private final UserPushDeviceRepository userPushDeviceRepository;
	private final AppUserService appUserService;

	/**
	 * 注册或更新本机推送 token（按 userId + deviceId 幂等覆盖）。
	 */
	@Transactional
	public PushTokenRegisteredResponse upsert(Long userId, UpsertPushTokenRequest body) {
		AppUser user = appUserService.requireActive(userId);
		PushDevicePlatform platform = parsePlatform(body.platform());
		String deviceId = body.deviceId().trim();
		String token = body.token().trim();

		UserPushDevice device = userPushDeviceRepository
				.findByUser_IdAndDeviceId(userId, deviceId)
				.orElseGet(() -> {
					UserPushDevice d = new UserPushDevice();
					d.setUser(user);
					d.setDeviceId(deviceId);
					return d;
				});
		device.setPlatform(platform);
		device.setPushToken(token);
		userPushDeviceRepository.save(device);
		return new PushTokenRegisteredResponse(deviceId, platform.name());
	}

	/**
	 * 注销推送：指定 deviceId 删一条；未传则删除该用户全部设备（登出常用）。
	 */
	@Transactional
	public void remove(Long userId, String deviceId) {
		appUserService.requireActive(userId);
		if (StringUtils.hasText(deviceId)) {
			userPushDeviceRepository.deleteByUser_IdAndDeviceId(userId, deviceId.trim());
		} else {
			userPushDeviceRepository.deleteByUser_Id(userId);
		}
	}

	@Transactional(readOnly = true)
	public List<UserPushDevice> listDevices(Long userId) {
		appUserService.requireActive(userId);
		return userPushDeviceRepository.findByUser_Id(userId);
	}

	private static PushDevicePlatform parsePlatform(String raw) {
		if (!StringUtils.hasText(raw)) {
			throw new IllegalArgumentException("platform 不能为空");
		}
		String v = raw.trim().toUpperCase(Locale.ROOT);
		try {
			return PushDevicePlatform.valueOf(v);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("platform 须为 ANDROID 或 IOS");
		}
	}
}
