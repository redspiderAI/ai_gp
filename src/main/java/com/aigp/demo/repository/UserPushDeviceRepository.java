package com.aigp.demo.repository;

import com.aigp.demo.domain.user.UserPushDevice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPushDeviceRepository extends JpaRepository<UserPushDevice, Long> {

	List<UserPushDevice> findByUser_Id(Long userId);

	Optional<UserPushDevice> findByUser_IdAndDeviceId(Long userId, String deviceId);

	void deleteByUser_IdAndDeviceId(Long userId, String deviceId);

	void deleteByUser_Id(Long userId);
}
