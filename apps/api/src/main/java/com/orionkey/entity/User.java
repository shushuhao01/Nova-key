package com.orionkey.entity;

import com.orionkey.constant.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    private int points = 0;

    private int isDeleted = 0;

    /** 内部人员绑定的角色 ID（system_roles.id）；普通客户为 null */
    private UUID roleId;

    /** 连续登录失败次数 */
    private int failedLoginAttempts = 0;

    /** 账户锁定截止时间（null 表示未锁定） */
    private LocalDateTime lockUntil;

    /** 公众号 openid（微信服务号绑定，用于接收服务通知） */
    @Column(length = 64)
    private String mpOpenid;

    /** 公众号关注状态：SUBSCRIBED / UNSUBSCRIBED（null 表示未知/未获取） */
    @Column(length = 20)
    private String mpSubscribe;

    /** 公众号昵称（微信用户信息） */
    @Column(length = 64)
    private String mpNickname;

    /** 公众号头像 URL（微信用户信息，可能为空，前端用默认头像兜底） */
    @Column(length = 512)
    private String mpAvatar;

    /** 公众号关注状态最近更新时间 */
    private LocalDateTime mpSubscribeChangedAt;
}
