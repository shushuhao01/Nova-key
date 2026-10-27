package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 手机短信验证码（落库）：
 * <ul>
 *   <li>场景由 scene 区分：REGISTER（注册）/ LOGIN（验证码登录）/ PASSWORD_RESET（找回密码）/ BIND_PHONE（绑定手机号）；</li>
 *   <li>与邮箱验证码同理需持久化：验证码跨越发送与回填的分钟级过程，且多实例部署时内存无法共享；</li>
 *   <li>一次性消费：校验通过后 used=true；</li>
 *   <li>错误次数限制：attemptCount 达到上限（5 次）后作废，需重新获取。</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sms_verification_codes")
public class SmsVerificationCode extends BaseEntity {

    /** 接收手机号（中国大陆 11 位） */
    @Column(nullable = false, length = 20)
    private String phone;

    /** 场景：REGISTER / LOGIN / PASSWORD_RESET / BIND_PHONE */
    @Column(nullable = false, length = 32)
    private String scene;

    /** 6 位数字验证码 */
    @Column(nullable = false, length = 10)
    private String code;

    /** 过期时间 */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 是否已使用（一次性消费） */
    @Column(name = "is_used", nullable = false)
    private boolean used = false;

    /** 校验失败次数，达到上限后作废 */
    @Column(nullable = false)
    private int attemptCount = 0;

    /** 请求来源 IP（频控与审计用） */
    @Column(length = 64)
    private String ip;
}
