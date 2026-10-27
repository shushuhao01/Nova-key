package com.orionkey.repository;

import com.orionkey.entity.SmsVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SmsVerificationCodeRepository extends JpaRepository<SmsVerificationCode, UUID> {

    /** 最近一条验证码（发送间隔限流用，不区分是否已使用） */
    Optional<SmsVerificationCode> findTopByPhoneAndSceneOrderByCreatedAtDesc(String phone, String scene);

    /** 最近一条未使用的验证码（校验用） */
    Optional<SmsVerificationCode> findTopByPhoneAndSceneAndUsedFalseOrderByCreatedAtDesc(String phone, String scene);

    /** 统计某时间点之后该手机号在指定场景的发送次数（每日上限用） */
    long countByPhoneAndSceneAndCreatedAtGreaterThanEqual(String phone, String scene, LocalDateTime since);

    /** 统计某时间点之后该 IP 在指定场景的发送次数（IP 维度兜底限流用） */
    long countByIpAndSceneAndCreatedAtGreaterThanEqual(String ip, String scene, LocalDateTime since);

    /** 清理已过期的验证码（发新码时顺带清理，避免表无限增长） */
    void deleteByPhoneAndSceneAndExpiresAtBefore(String phone, String scene, LocalDateTime time);
}
