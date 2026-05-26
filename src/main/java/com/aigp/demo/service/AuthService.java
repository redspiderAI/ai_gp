package com.aigp.demo.service;

import com.aigp.demo.config.AppProperties;
import com.aigp.demo.domain.user.AppUser;
import com.aigp.demo.domain.user.IdentityType;
import com.aigp.demo.domain.user.UserIdentity;
import com.aigp.demo.domain.user.UserSession;
import com.aigp.demo.exception.ConflictException;
import com.aigp.demo.exception.UnauthorizedException;
import com.aigp.demo.repository.AppUserRepository;
import com.aigp.demo.repository.UserIdentityRepository;
import com.aigp.demo.repository.UserSessionRepository;
import com.aigp.demo.support.TokenHasher;
import com.aigp.demo.support.auth.EmailOrPhoneAccount;
import com.aigp.demo.web.security.JwtTokenService;
import com.aigp.demo.web.security.JwtUserClaims;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证相关业务：登录、注册（验证码）、找回密码（验证码）、刷新令牌、修改密码、登出。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final String LOGIN_FAILED_MSG = "账号或密码错误";

	/**
	 * 每用户固定一条「账号密码登录」会话槽位对应的 {@code user_sessions.device_id}，由服务端生成，不依赖客户端设备指纹，换机仍可登录与刷新。
	 */
	private static String syntheticSessionDeviceId(Long userId) {
		return "builtin-" + userId;
	}

	private final AppProperties appProperties;
	private final JwtTokenService jwtTokenService;
	private final UserSessionRepository userSessionRepository;
	private final UserSessionService userSessionService;
	private final AppUserService appUserService;
	private final AppUserRepository appUserRepository;
	private final UserIdentityRepository userIdentityRepository;
	private final UserIdentityService userIdentityService;
	private final VerificationCodeService verificationCodeService;
	private final PasswordEncoder passwordEncoder;

	/**
	 * 账号密码登录：{@code account} 支持邮箱、手机号，或 16 位对外 {@code users.uid}（以 U 开头）。仅需账号与密码，会话设备键由服务端生成。
	 */
	@Transactional
	public IssuedTokens login(String account, String password, String ipAddress) {
		String acc = account == null ? "" : account.trim();
		if (!StringUtils.hasText(acc) || !StringUtils.hasText(password)) {
			throw new UnauthorizedException(LOGIN_FAILED_MSG);
		}

		Optional<UserIdentity> idRow = resolveLoginIdentity(acc);
		UserIdentity identity =
				idRow.orElseThrow(() -> new UnauthorizedException(LOGIN_FAILED_MSG));
		String stored = identity.getCredential();
		if (stored == null || !passwordEncoder.matches(password, stored)) {
			throw new UnauthorizedException(LOGIN_FAILED_MSG);
		}

		AppUser user = appUserService.requireActive(identity.getUser().getId());
		return issueSessionTokens(user, ipAddress);
	}

	/**
	 * 发送注册验证码：仅支持邮箱；须未注册；同一邮箱 60 秒内不可重复发送。
	 */
	public VerificationCodeService.IssueResult sendRegisterVerificationCode(String email) {
		String norm = EmailOrPhoneAccount.requireEmailIdentifier(email);
		if (userIdentityRepository.findByIdentityTypeAndIdentifier(IdentityType.email, norm).isPresent()) {
			throw new ConflictException("该邮箱已注册");
		}
		return verificationCodeService.issue(VerificationCodeService.Purpose.REGISTER, norm);
	}

	/**
	 * 注册：仅邮箱 + 密码 + 昵称 + 邮箱验证码；校验通过后创建用户、绑定邮箱密码身份并自动登录。
	 */
	@Transactional
	public IssuedTokens register(
			String email, String password, String verificationCode, String nickname, String ipAddress) {
		String norm = EmailOrPhoneAccount.requireEmailIdentifier(email);
		if (userIdentityRepository.findByIdentityTypeAndIdentifier(IdentityType.email, norm).isPresent()) {
			throw new ConflictException("该邮箱已注册");
		}
		verificationCodeService.verifyAndConsume(
				VerificationCodeService.Purpose.REGISTER, norm, verificationCode);
		assertPasswordPolicy(password);

		AppUser user = appUserService.registerNewUser(nickname.strip());
		String hash = passwordEncoder.encode(password);
		userIdentityService.linkIdentity(
				user, IdentityType.email, norm, hash, true, LocalDateTime.now());

		return issueSessionTokens(appUserService.requireActive(user.getId()), ipAddress);
	}

	/**
	 * 发送找回密码验证码：账号须已注册且已设置密码。
	 */
	public VerificationCodeService.IssueResult sendPasswordResetVerificationCode(String account) {
		EmailOrPhoneAccount norm = EmailOrPhoneAccount.parse(account);
		UserIdentity id =
				userIdentityRepository
						.findByIdentityTypeAndIdentifier(norm.identityType(), norm.identifier())
						.orElseThrow(() -> new IllegalArgumentException("账号不存在"));
		if (id.getCredential() == null) {
			throw new IllegalArgumentException("该账号未设置密码登录，无法通过此方式找回");
		}
		return verificationCodeService.issue(VerificationCodeService.Purpose.PASSWORD_RESET, norm.identifier());
	}

	/**
	 * 重置密码：校验验证码后更新密码哈希，并撤销该用户全部会话（需重新登录）。
	 */
	@Transactional
	public void resetPasswordWithCode(String account, String verificationCode, String newPassword) {
		EmailOrPhoneAccount norm = EmailOrPhoneAccount.parse(account);
		verificationCodeService.verifyAndConsume(
				VerificationCodeService.Purpose.PASSWORD_RESET, norm.identifier(), verificationCode);

		UserIdentity id =
				userIdentityRepository
						.findByIdentityTypeAndIdentifier(norm.identityType(), norm.identifier())
						.orElseThrow(() -> new IllegalArgumentException("账号不存在"));
		if (id.getCredential() == null) {
			throw new IllegalArgumentException("该账号未设置密码登录");
		}
		assertPasswordPolicy(newPassword);
		String encoded = passwordEncoder.encode(newPassword);
		syncLoginPasswordForUser(id.getUser().getId(), encoded);
		userIdentityRepository.save(id);

		userSessionService.revokeAllForUser(id.getUser().getId());
	}

	/**
	 * 为已认证用户创建/刷新会话并签发访问令牌与明文刷新令牌。
	 */
	private IssuedTokens issueSessionTokens(AppUser user, String ipAddress) {
		String deviceId = syntheticSessionDeviceId(user.getId());
		String refreshPlain = randomRefreshToken();
		String refreshHash = TokenHasher.sha256Hex(refreshPlain);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime exp = now.plusDays(appProperties.getJwt().getRefreshTokenExpireDays());

		UserSession session =
				userSessionService.upsertSession(user, deviceId, refreshHash, exp, ipAddress, null, false);

		String access = jwtTokenService.createAccessToken(user.getId(), user.getUid(), session.getId());
		long expiresInSec = appProperties.getJwt().getAccessTokenExpireMinutes() * 60L;
		return new IssuedTokens(access, refreshPlain, "Bearer", expiresInSec, user.getUid());
	}

	/**
	 * 使用刷新令牌换取新的访问令牌与刷新令牌（滚动更新会话表中的哈希与过期时间）。
	 */
	@Transactional
	public IssuedTokens refresh(String refreshTokenPlain, String ipAddress) {
		String hash = TokenHasher.sha256Hex(refreshTokenPlain);
		LocalDateTime now = LocalDateTime.now();
		UserSession session = userSessionRepository
				.findActiveByRefreshTokenHash(hash, now)
				.orElseThrow(() -> new UnauthorizedException("刷新令牌无效或已过期"));

		AppUser user = appUserService.requireActive(session.getUser().getId());

		String newRefreshPlain = randomRefreshToken();
		String newRefreshHash = TokenHasher.sha256Hex(newRefreshPlain);
		LocalDateTime exp = now.plusDays(appProperties.getJwt().getRefreshTokenExpireDays());

		session.setRefreshToken(newRefreshHash);
		session.setExpiresAt(exp);
		session.setIpAddress(ipAddress);
		userSessionRepository.save(session);

		String access = jwtTokenService.createAccessToken(user.getId(), user.getUid(), session.getId());
		long expiresInSec = appProperties.getJwt().getAccessTokenExpireMinutes() * 60L;
		return new IssuedTokens(access, newRefreshPlain, "Bearer", expiresInSec, user.getUid());
	}

	/**
	 * 校验原密码后更新哈希；默认保留当前会话，撤销该用户其他设备的刷新会话。
	 */
	@Transactional
	public void changePassword(JwtUserClaims claims, String oldPassword, String newPassword) {
		assertPasswordPolicy(newPassword);
		AppUser user = appUserService.requireActive(claims.userId());
		UserIdentity row = findPasswordIdentity(user.getId())
				.orElseThrow(() -> new IllegalArgumentException("当前账号未绑定支持密码登录的邮箱或手机"));

		String stored = row.getCredential();
		if (stored == null || !passwordEncoder.matches(oldPassword, stored)) {
			throw new UnauthorizedException("原密码不正确");
		}
		String encoded = passwordEncoder.encode(newPassword);
		syncLoginPasswordForUser(user.getId(), encoded);
		userIdentityRepository.save(row);

		revokeOtherSessions(claims.userId(), claims.sessionId());
	}

	/**
	 * 登出：默认仅撤销当前 JWT 对应会话；{@code allDevices=true} 时撤销该用户全部会话。
	 */
	@Transactional
	public void logout(JwtUserClaims claims, boolean allDevices) {
		if (allDevices) {
			userSessionService.revokeAllForUser(claims.userId());
			return;
		}
		Long sid = claims.sessionId();
		if (sid != null) {
			userSessionService.revokeSession(claims.userId(), sid);
		} else {
			userSessionService.revokeAllForUser(claims.userId());
		}
	}

	private void revokeOtherSessions(Long userId, Long keepSessionId) {
		LocalDateTime now = LocalDateTime.now();
		List<UserSession> list = userSessionRepository.findByUser_Id(userId);
		for (UserSession s : list) {
			if (s.getRevokedAt() != null) {
				continue;
			}
			if (keepSessionId != null && keepSessionId.equals(s.getId())) {
				continue;
			}
			s.setRevokedAt(now);
		}
	}

	private Optional<UserIdentity> resolveLoginIdentity(String acc) {
		if (acc.contains("@")) {
			return userIdentityRepository
					.findByIdentityTypeAndIdentifier(IdentityType.email, acc.toLowerCase(Locale.ROOT))
					.filter(id -> id.getCredential() != null);
		}
		if (looksLikePublicUid(acc)) {
			String uidKey = acc.toUpperCase(Locale.ROOT);
			return appUserRepository
					.findByUid(uidKey)
					.flatMap(u -> findPasswordIdentity(u.getId()));
		}
		try {
			EmailOrPhoneAccount parsed = EmailOrPhoneAccount.parse(acc);
			if (parsed.identityType() == IdentityType.phone) {
				return userIdentityRepository
						.findByIdentityTypeAndIdentifier(IdentityType.phone, parsed.identifier())
						.filter(id -> id.getCredential() != null);
			}
		} catch (IllegalArgumentException ignored) {
			// 非合法手机号格式
		}
		return Optional.empty();
	}

	/** 邮箱/手机登录密码共用：改密或重置后同步到所有已设密码的邮箱、手机身份。 */
	private void syncLoginPasswordForUser(Long userId, String encodedPassword) {
		for (UserIdentity identity : userIdentityRepository.findByUser_Id(userId)) {
			if (identity.getCredential() == null) {
				continue;
			}
			if (identity.getIdentityType() == IdentityType.email
					|| identity.getIdentityType() == IdentityType.phone) {
				identity.setCredential(encodedPassword);
			}
		}
	}

	private static boolean looksLikePublicUid(String acc) {
		return acc.length() == 16 && (acc.charAt(0) == 'U' || acc.charAt(0) == 'u');
	}

	private Optional<UserIdentity> findPasswordIdentity(Long userId) {
		Optional<UserIdentity> email = userIdentityRepository.findByUser_IdAndIdentityType(userId, IdentityType.email);
		if (email.isPresent() && email.get().getCredential() != null) {
			return email;
		}
		Optional<UserIdentity> phone = userIdentityRepository.findByUser_IdAndIdentityType(userId, IdentityType.phone);
		if (phone.isPresent() && phone.get().getCredential() != null) {
			return phone;
		}
		return Optional.empty();
	}

	private static void assertPasswordPolicy(String newPassword) {
		if (newPassword == null || newPassword.length() < 8) {
			throw new IllegalArgumentException("密码长度至少 8 位");
		}
	}

	private static String randomRefreshToken() {
		byte[] buf = new byte[36];
		SECURE_RANDOM.nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}

	/**
	 * 登录成功或刷新后返回给前端的令牌载体。
	 */
	public record IssuedTokens(
			String accessToken, String refreshToken, String tokenType, long expiresInSeconds, String uid) {}
}
