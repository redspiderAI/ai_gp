package com.aigp.demo.service.push;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.user.UserPushDevice;
import com.aigp.demo.repository.UserPushDeviceRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 直连 Firebase Admin 推送（非 uni-app 场景可选）；uni-app 请用 {@link UniPushCloudSender}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmMobilePushSender {

	private static final String FIREBASE_APP_NAME = "aigp-mobile-push";

	private final AppProperties appProperties;
	private final UserPushDeviceRepository userPushDeviceRepository;

	private volatile boolean initAttempted;
	private volatile boolean initSucceeded;

	public void sendToDevices(
			Long userId, List<UserPushDevice> devices, String title, String body, Map<String, String> data) {
		if (!ensureFirebaseReady()) {
			return;
		}
		FirebaseMessaging messaging = FirebaseMessaging.getInstance(FirebaseApp.getInstance(FIREBASE_APP_NAME));
		for (UserPushDevice device : devices) {
			Message message = Message.builder()
					.setToken(device.getPushToken())
					.setNotification(Notification.builder().setTitle(title).setBody(body).build())
					.putAllData(data)
					.build();
			try {
				messaging.send(message);
			} catch (FirebaseMessagingException e) {
				MessagingErrorCode code = e.getMessagingErrorCode();
				if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
					log.info("移除失效 FCM token userId={} deviceId={}", userId, device.getDeviceId());
					userPushDeviceRepository.delete(device);
				} else {
					log.warn("FCM 发送失败 userId={} deviceId={}", userId, device.getDeviceId(), e);
				}
			}
		}
	}

	private boolean ensureFirebaseReady() {
		if (initSucceeded) {
			return true;
		}
		if (initAttempted) {
			return false;
		}
		synchronized (this) {
			if (initAttempted) {
				return initSucceeded;
			}
			initAttempted = true;
			String path = appProperties.getMobilePush().getCredentialsPath();
			if (!StringUtils.hasText(path)) {
				log.warn("mobile-push.provider=fcm 但未配置 credentials-path");
				return false;
			}
			Path cred = Path.of(path.trim());
			if (!Files.isRegularFile(cred)) {
				log.warn("FCM 凭证文件不存在: {}", cred.toAbsolutePath());
				return false;
			}
			try (FileInputStream in = new FileInputStream(cred.toFile())) {
				FirebaseOptions options = FirebaseOptions.builder()
						.setCredentials(GoogleCredentials.fromStream(in))
						.build();
				if (FirebaseApp.getApps().stream().noneMatch(a -> FIREBASE_APP_NAME.equals(a.getName()))) {
					FirebaseApp.initializeApp(options, FIREBASE_APP_NAME);
				}
				initSucceeded = true;
				log.info("FCM 移动推送已初始化");
				return true;
			} catch (IOException e) {
				log.error("FCM 凭证加载失败", e);
				return false;
			}
		}
	}
}
