package com.orionkey.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 前台访问上报请求体（全部字段可选，前端静默采集失败不影响用户体验）。
 */
@Getter
@Setter
public class TrackRequest {

    /** 当前访问路径 */
    private String path;

    /** 来源页 */
    private String referer;

    /** 访客标识（前端持久化的匿名 ID） */
    private String visitorId;

    /** 屏幕分辨率，如 1920x1080 */
    private String screen;

    /** 浏览器语言，如 zh-CN */
    private String lang;
}
