package com.orionkey.repository;

import com.orionkey.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    /** 最近一条验证码（发送间隔限流用，不区分是否已使用） */
    Optional<EmailVerificationCode> findTopByEmailAndSceneOrderByCreatedAtDesc(String email, String scene);

    /** 最近一条未使用的验证码（校验用） */
    Optional<EmailVerificationCode> findTopByEmailAndSceneAndUsedFalseOrderByCreatedAtDesc(String email, String scene);

    /** 统计某时间点之后该邮箱在指定场景的发送次数（每日上限用） */
    long countByEmailAndSceneAndCreatedAtGreaterThanEqual(String email, String scene, LocalDateTime since);

    /** 清理已过期的验证码（发新码时顺带清理，避免表无限增长） */
    void deleteByEmailAndSceneAndExpiresAtBefore(String email, String scene, LocalDateTime time);
}
