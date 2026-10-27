package com.orionkey.service;

import com.orionkey.common.PageResult;
import com.orionkey.model.request.BindEmailRequest;
import com.orionkey.model.request.BindPhoneRequest;
import com.orionkey.model.request.ChangePasswordRequest;
import com.orionkey.model.response.UserProfileResponse;

import java.util.Map;
import java.util.UUID;

public interface UserService {

    UserProfileResponse getProfile(UUID userId);

    void changePassword(UUID userId, ChangePasswordRequest request);

    /** 绑定手机号（已被占用则合并目标账号），返回 { merged, idempotent } */
    Map<String, Object> bindPhone(UUID userId, BindPhoneRequest request);

    /** 绑定邮箱（已被占用则合并目标账号），返回 { merged, idempotent } */
    Map<String, Object> bindEmail(UUID userId, BindEmailRequest request);

    PageResult<?> getOrders(UUID userId, String status, int page, int pageSize);

    Map<String, Object> getPoints(UUID userId, int page, int pageSize);
}
