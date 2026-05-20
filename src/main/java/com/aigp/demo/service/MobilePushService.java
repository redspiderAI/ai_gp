package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.user.UserPushDevice;
import com.aigp.demo.repository.UserPushDeviceRepository;
import com.aigp.demo.service.push.FcmMobilePushSender;
import com.aigp.demo.service.push.UniPushCloudSender;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 系统级移动推送门面：默认 uni-push 2.0（云函数 URL + DCloud 托管 Firebase）；可选直连 FCM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobilePushService {

	private final AppProperties appProperties;
	private final UserPushDeviceRepository userPushDeviceRepository;
	private final UniPushCloudSender uniPushCloudSender;
	private final FcmMobilePushSender fcmMobilePushSender;

	/**
	 * 向用户全部已注册设备发送系统通知；未开启或凭证缺失时静默跳过。
	 *
	 * @param data 自定义数据（字符串键值），用于点击跳转
	 */
	public void sendToUser(Long userId, String title, String body, Map<String, String> data) {
		if (!appProperties.getMobilePush().isEnabled()) {
			return;
		}
		if (!StringUtils.hasText(title) && !StringUtils.hasText(body)) {
			return;
		}
		List<UserPushDevice> devices = userPushDeviceRepository.findByUser_Id(userId);
		if (devices.isEmpty()) {
			return;
		}
		String safeTitle = truncate(title, 200);
		String safeBody = truncate(body, 500);
		Map<String, String> payload = data == null ? Map.of() : new LinkedHashMap<>(data);
		payload.putIfAbsent("title", safeTitle);
		payload.putIfAbsent("body", safeBody);

		String provider = appProperties.getMobilePush().getProvider();
		if ("fcm".equalsIgnoreCase(provider)) {
			fcmMobilePushSender.sendToDevices(userId, devices, safeTitle, safeBody, payload);
		} else {
			uniPushCloudSender.sendToDevices(userId, devices, safeTitle, safeBody, payload);
		}
	}

	private static String truncate(String s, int max) {
		if (!StringUtils.hasText(s)) {
			return "";
		}
		String t = s.trim();
		return t.length() <= max ? t : t.substring(0, max);
	}
}
