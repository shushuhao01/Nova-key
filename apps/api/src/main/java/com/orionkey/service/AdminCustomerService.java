package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.util.Map;
import java.util.UUID;

/** 客户管理：注册客户 / 匿名客户（未注册仅留邮箱的购买者） */
public interface AdminCustomerService {

    /** 汇总卡片：客户总数 / 新增 / 成交客户 / 未成交客户 */
    Map<String, Object> overview();

    /** 注册客户分页列表（非管理员，含消费统计） */
    PageResult<?> listRegistered(String keyword, int page, int pageSize);

    /** 匿名客户分页列表（orders.user_id IS NULL，按手机号优先、无手机号回落到邮箱去重，含消费统计） */
    PageResult<?> listAnonymous(String keyword, int page, int pageSize);

    /** 注册客户详情（基本信息 + 订单记录分页） */
    Map<String, Object> registeredDetail(UUID id, int page, int pageSize);

    /** 匿名客户详情（联系方式 + 订单记录分页；contact 为手机号或邮箱） */
    Map<String, Object> anonymousDetail(String contact, int page, int pageSize);

    /** 封禁 / 解禁注册客户（isDeleted 0/1） */
    void toggleRegistered(UUID id, int isDeleted);

    /** 管理员解绑客户联系方式（field 仅支持 email / phone），reason 为必填解绑理由 */
    void unbindContact(UUID id, String field, String reason);

    /**
     * 管理员重置客户密码：随机 12 位新密码，旧 token 全部失效并解锁账号；
     * 应答一次性返回新密码（不落库明文），并尽力通过邮件/短信通知用户。
     */
    Map<String, Object> resetPassword(UUID userId);
}
