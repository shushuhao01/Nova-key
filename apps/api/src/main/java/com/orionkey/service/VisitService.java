package com.orionkey.service;

import com.orionkey.dto.TrackRequest;

public interface VisitService {

    /**
     * 记录一次前台访问。
     *
     * @param ip          访客 IP
     * @param userAgent   User-Agent
     * @param request     上报数据（可为 null）
     * @param backendUser 是否为后台用户（管理员/员工）自身访问
     */
    void track(String ip, String userAgent, TrackRequest request, boolean backendUser);
}
