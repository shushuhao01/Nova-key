package com.orionkey.constant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台权限点定义（RBAC：角色 → 权限码集合）。
 * 权限码同时用于后端接口校验（hasAuthority）与前端菜单渲染过滤。
 */
public final class PermissionConst {

    private PermissionConst() {
    }

    /** 后台访问总开关：不勾选则无法登录/访问管理后台任何接口 */
    public static final String BACKEND_ACCESS = "BACKEND_ACCESS";
    public static final String DASHBOARD = "DASHBOARD";
    public static final String CATEGORY_MANAGE = "CATEGORY_MANAGE";
    public static final String PRODUCT_MANAGE = "PRODUCT_MANAGE";
    public static final String CARDKEY_MANAGE = "CARDKEY_MANAGE";
    public static final String ORDER_MANAGE = "ORDER_MANAGE";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";
    public static final String MARKETING_MANAGE = "MARKETING_MANAGE";
    public static final String PAYMENT_MANAGE = "PAYMENT_MANAGE";
    public static final String SITE_CONFIG_MANAGE = "SITE_CONFIG_MANAGE";
    public static final String RISK_MANAGE = "RISK_MANAGE";
    public static final String TXID_REVIEW = "TXID_REVIEW";
    public static final String LOG_VIEW = "LOG_VIEW";
    public static final String SYSTEM_MANAGE = "SYSTEM_MANAGE";

    /** 全部权限码（超级管理员拥有） */
    public static final List<String> ALL = List.of(
            BACKEND_ACCESS, DASHBOARD, CATEGORY_MANAGE, PRODUCT_MANAGE, CARDKEY_MANAGE,
            ORDER_MANAGE, CUSTOMER_MANAGE, MARKETING_MANAGE, PAYMENT_MANAGE,
            SITE_CONFIG_MANAGE, RISK_MANAGE, TXID_REVIEW, LOG_VIEW, SYSTEM_MANAGE);

    /** 权限清单（码 + 名称），供角色管理页勾选展示 */
    public static final List<Map<String, String>> CATALOG = List.of(
            perm(BACKEND_ACCESS, "后台访问"),
            perm(DASHBOARD, "数据看板"),
            perm(CATEGORY_MANAGE, "分类管理"),
            perm(PRODUCT_MANAGE, "商品管理"),
            perm(CARDKEY_MANAGE, "卡密管理"),
            perm(ORDER_MANAGE, "订单管理"),
            perm(CUSTOMER_MANAGE, "客户管理"),
            perm(MARKETING_MANAGE, "营销管理"),
            perm(PAYMENT_MANAGE, "支付渠道"),
            perm(SITE_CONFIG_MANAGE, "网站设置"),
            perm(RISK_MANAGE, "风控管理"),
            perm(TXID_REVIEW, "USDT 核销审核"),
            perm(LOG_VIEW, "操作日志"),
            perm(SYSTEM_MANAGE, "系统管理（用户/角色）"));

    private static Map<String, String> perm(String code, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        return m;
    }
}
