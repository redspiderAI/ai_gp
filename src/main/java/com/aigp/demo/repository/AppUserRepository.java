package com.aigp.demo.repository;

import com.aigp.demo.domain.user.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByUid(String uid);

	Optional<AppUser> findByPhone(String phone);
}
