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

    /** 匿名客户分页列表（orders.user_id IS NULL 的邮箱去重，含消费统计） */
    PageResult<?> listAnonymous(String keyword, int page, int pageSize);

    /** 注册客户详情（基本信息 + 订单记录分页） */
    Map<String, Object> registeredDetail(UUID id, int page, int pageSize);

    /** 匿名客户详情（邮箱信息 + 订单记录分页） */
    Map<String, Object> anonymousDetail(String email, int page, int pageSize);

    /** 封禁 / 解禁注册客户（isDeleted 0/1） */
    void toggleRegistered(UUID id, int isDeleted);
}
