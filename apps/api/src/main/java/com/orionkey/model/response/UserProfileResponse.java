package com.orionkey.model.response;

import com.orionkey.entity.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class UserProfileResponse {
    private UUID id;
    private String username;
    private String email;
    /** 手机号（脱敏展示，如 138****8000） */
    private String phone;
    /** 是否已绑定手机号 */
    private boolean hasPhone;
    /** 是否已绑定邮箱 */
    private boolean hasEmail;
    /** 是否需要强制修改密码（手机号自动注册的默认密码账号） */
    private boolean mustChangePassword;
    private String role;
    private int points;
    private LocalDateTime createdAt;
    /** 拥有的后台权限码（RBAC，前端按此渲染菜单/按钮） */
    private List<String> permissions = List.of();

    public static UserProfileResponse from(User user) {
        UserProfileResponse r = new UserProfileResponse();
        r.setId(user.getId());
        r.setUsername(user.getUsername());
        r.setEmail(user.getEmail());
        r.setPhone(maskPhone(user.getPhone()));
        r.setHasPhone(user.getPhone() != null && !user.getPhone().isBlank());
        r.setHasEmail(user.getEmail() != null && !user.getEmail().isBlank());
        r.setMustChangePassword(user.isMustChangePassword());
        r.setRole(user.getRole().name());
        r.setPoints(user.getPoints());
        r.setCreatedAt(user.getCreatedAt());
        return r;
    }

    public static UserProfileResponse from(User user, List<String> permissions) {
        UserProfileResponse r = from(user);
        r.setPermissions(permissions == null ? List.of() : permissions);
        return r;
    }

    /** 手机号脱敏：11 位 → 前 3 + **** + 后 4；其他长度原样保留 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        if (phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        return phone;
    }
}
