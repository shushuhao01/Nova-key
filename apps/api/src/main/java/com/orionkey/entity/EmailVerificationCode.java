package com.orionkey.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 邮箱验证码（落库）：
 * <ul>
 *   <li>用于「忘记密码」重置密码 与 「验证码登录」两个场景（scene 区分）；</li>
 *   <li>落库原因：验证码需跨越「发信 → 用户收信 → 回填」的过程（分钟级），
 *       且重启/多实例部署时内存 Map 无法共享，因此持久化存储；</li>
 *   <li>一次性消费：校验通过后 used=true，不可重复使用；</li>
 *   <li>错误次数限制：attemptCount 达到上限后作废，需重新获取。</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode extends BaseEntity {

    /** 收件邮箱（统一小写存储） */
    @Column(nullable = false, length = 255)
    private String email;

    /** 场景：PASSWORD_RESET（忘记密码）/ LOGIN（验证码登录） */
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

    /** 请求来源 IP（审计用） */
    @Column(length = 64)
    private String ip;
}
