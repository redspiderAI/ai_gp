package com.aigp.demo.service;

import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.IdentityType;
import com.aigp.demo.domain.user.UserIdentity;
import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.NotFoundException;
import com.aigp.demo.exception.UnauthorizedException;
import com.aigp.demo.repository.AppUserRepository;
import com.aigp.demo.repository.UserIdentityRepository;
import com.aigp.demo.support.UidGenerator;
import com.aigp.demo.web.user.dto.UserProfileResponse;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserService {

	private static final Pattern CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

	private final AppUserRepository appUserRepository;
	private final UserNotificationSettingsService userNotificationSettingsService;
	private final UserSessionService userSessionService;
	private final UserIdentityRepository userIdentityRepository;
	private final UserIdentityService userIdentityService;

	@Transactional(readOnly = true)
	public AppUser requireById(Long id) {
		return appUserRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found: id=" + id));
	}

	@Transactional(readOnly = true)
	public AppUser requireByUid(String uid) {
		return appUserRepository
				.findByUid(uid)
				.orElseThrow(() -> new NotFoundException("User not found: uid=" + uid));
	}

	/**
	 * 要求用户存在且状态为「正常」，否则抛出未授权异常（禁用/已注销账户不可继续操作）。
	 */
	@Transactional(readOnly = true)
	public AppUser requireActive(Long id) {
		AppUser user = requireById(id);
		if (user.getStatus() == null || user.getStatus() != 1) {
			throw new UnauthorizedException("账号已禁用或已注销，无法使用此功能");
		}
		return user;
	}

	/**
	 * Registers a minimal {@code users} row plus default {@code user_notification_settings}, per
	 * {@code md文档/数据库.md}.
	 */
	@Transactional
	public AppUser registerNewUser(String nickname) {
		AppUser user = new AppUser();
		user.setUid(UidGenerator.nextUid());
		user.setStatus((byte) 1);
		user.setTimezone("Asia/Shanghai");
		user.setLanguage("zh-CN");
		user.setWeeklyHours((byte) 0);
		if (org.springframework.util.StringUtils.hasText(nickname)) {
			user.setNickname(nickname.trim());
		} else {
			user.setNickname("新用户");
		}
		appUserRepository.save(user);
		userNotificationSettingsService.getOrCreate(user);
		return user;
	}

	@Transactional(readOnly = true)
	public UserProfileResponse toProfileResponse(AppUser user) {
		String phoneMasked = maskedPhoneForUser(user);
		return UserProfileResponse.fromEntity(user, phoneMasked);
	}

	private String maskedPhoneForUser(AppUser user) {
		if (StringUtils.hasText(user.getPhone())) {
			return maskPhone(user.getPhone());
		}
		return userIdentityRepository
				.findByUser_IdAndIdentityType(user.getId(), IdentityType.phone)
				.map(id -> maskPhone(id.getIdentifier()))
				.orElse(null);
	}

	private static String maskPhone(String phone) {
		if (phone == null || phone.length() < 11) {
			return phone;
		}
		return phone.substring(0, 3) + "****" + phone.substring(7);
	}

	@Transactional
	public AppUser updateProfile(Long userId, String nickname, String avatarUrl, Integer weeklyHours, String phone) {
		AppUser user = requireActive(userId);
		if (nickname != null) {
			user.setNickname(nickname);
		}
		if (avatarUrl != null) {
			user.setAvatarUrl(avatarUrl);
		}
		if (weeklyHours != null) {
			if (weeklyHours < 0 || weeklyHours > 40) {
				throw new IllegalArgumentException("weeklyHours must be between 0 and 40");
			}
			user.setWeeklyHours(weeklyHours.byteValue());
		}
		if (org.springframework.util.StringUtils.hasText(phone)) {
			String p = phone.trim();
			if (!CN_MOBILE.matcher(p).matches()) {
				throw new IllegalArgumentException("手机号须为 11 位中国大陆号码");
			}
			assertPhoneNotUsedByOther(user.getId(), p);
			String passwordHash = findExistingPasswordHash(user.getId())
					.orElseThrow(() -> new IllegalArgumentException("当前账号未设置密码，无法绑定可用于登录的手机号"));
			userIdentityService.linkIdentity(user, IdentityType.phone, p, passwordHash, false, null);
			user.setPhone(p);
			return appUserRepository.save(user);
		}
		return appUserRepository.save(user);
	}

	/**
	 * 校验手机号未被其他用户占用（{@code users.phone} 与 {@code user_identities} 均需唯一）。
	 */
	private void assertPhoneNotUsedByOther(Long userId, String phone) {
		appUserRepository.findByPhone(phone).ifPresent(other -> {
			if (!other.getId().equals(userId)) {
				throw new ConflictException("该手机号已被其他账号绑定");
			}
		});
	}

	/** 取当前用户可用于登录的密码哈希（邮箱优先，其次已绑定的手机身份）。 */
	private Optional<String> findExistingPasswordHash(Long userId) {
		Optional<UserIdentity> email =
				userIdentityRepository.findByUser_IdAndIdentityType(userId, IdentityType.email);
		if (email.isPresent() && StringUtils.hasText(email.get().getCredential())) {
			return Optional.of(email.get().getCredential());
		}
		Optional<UserIdentity> boundPhone =
				userIdentityRepository.findByUser_IdAndIdentityType(userId, IdentityType.phone);
		if (boundPhone.isPresent() && StringUtils.hasText(boundPhone.get().getCredential())) {
			return Optional.of(boundPhone.get().getCredential());
		}
		return Optional.empty();
	}

	/**
	 * 更新首次登录画像（年龄、职业、爱好、昵称、探索方向等）；字段为 null 表示不修改，空字符串会清空对应文本。
	 * 昵称仅在用户尚未设置时才会写入。
	 */
	@Transactional
	public AppUser updateOnboardingProfile(
			Long userId,
			Integer age,
			String occupation,
			String hobbies,
			String nickname,
			String explorationInterests,
			Boolean onboardingCompleted) {
		AppUser user = requireActive(userId);
		if (age != null) {
			if (age < 1 || age > 120) {
				throw new IllegalArgumentException("age 须在 1～120 之间");
			}
			user.setProfileAge(age.byteValue());
		}
		if (occupation != null) {
			user.setProfileOccupation(blankToNull(occupation));
		}
		if (hobbies != null) {
			user.setProfileHobbies(blankToNull(hobbies));
		}
		if (nickname != null && StringUtils.hasText(nickname)) {
			if (!StringUtils.hasText(user.getNickname())) {
				user.setNickname(nickname.trim());
			}
		}
		if (explorationInterests != null) {
			user.setProfileExploration(blankToNull(explorationInterests));
		}
		if (onboardingCompleted != null) {
			user.setOnboardingCompleted(onboardingCompleted);
		}
		return appUserRepository.save(user);
	}

	private static String blankToNull(String raw) {
		String t = raw.trim();
		return t.isEmpty() ? null : t;
	}

	/**
	 * 软注销：将 {@code users.status} 置为 3，并撤销全部登录会话。
	 */
	@Transactional
	public void markDeletedAccount(Long userId) {
		AppUser user = requireById(userId);
		user.setStatus((byte) 3);
		userSessionService.revokeAllForUser(userId);
	}
}
