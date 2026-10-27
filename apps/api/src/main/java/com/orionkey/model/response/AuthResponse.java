package com.orionkey.model.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private UserProfileResponse user;
    /** 是否需要强制修改密码（默认密码登录后为 true，前端弹不可关闭的改密窗口） */
    private boolean mustChangePassword;
    /** 是否已绑定邮箱 */
    private boolean hasEmail;
    /** 是否已绑定手机号 */
    private boolean hasPhone;

    /** 兼容旧调用：补充登录态附加标记，避免各调用点重复赋值 */
    public AuthResponse(String token, UserProfileResponse user) {
        this.token = token;
        this.user = user;
        this.mustChangePassword = user != null && user.isMustChangePassword();
        this.hasEmail = user != null && user.isHasEmail();
        this.hasPhone = user != null && user.isHasPhone();
    }
}
