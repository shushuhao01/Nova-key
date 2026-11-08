package com.orionkey.service;

import com.orionkey.constant.UserMessageCategory;
import com.orionkey.entity.Order;
import com.orionkey.entity.UserMessage;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserMessageService {

    /** 发送用户消息（写库 + 可选邮件同步） */
    void sendUserMessage(UUID userId, String email, String templateCode, Map<String, Object> vars);

    /** 直接写入站内消息（不依赖通知模板开关，用于系统/管理员操作通知） */
    void sendDirectMessage(UUID userId, String email, UserMessageCategory category, String title, String content);

    /** 订单支付成功站内信（仅发给订单归属用户，匿名订单不产生站内信） */
    void sendOrderPaidNotice(Order order, String paymentMethodLabel);

    /** 订单发货完成站内信（仅发给订单归属用户，匿名订单不产生站内信） */
    void sendOrderDeliveredNotice(Order order, String product, int quantity);

    /** 未读数 */
    long unreadCount(UUID userId);

    /** 最近消息（铃铛下拉） */
    List<UserMessage> recentMessages(UUID userId, int limit);

    /** 消息列表（分页） */
    Page<UserMessage> listMessages(UUID userId, String category, boolean unreadOnly, int page, int pageSize);

    /** 标记已读 */
    void markRead(UUID userId, UUID messageId);

    /** 全部已读 */
    int markAllRead(UUID userId);

    /** 清空 */
    void clearAll(UUID userId);
}
