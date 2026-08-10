package com.orionkey.service;

import com.orionkey.constant.UserMessageCategory;
import com.orionkey.entity.UserMessage;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserMessageService {

    /** 发送用户消息（写库 + 可选邮件同步） */
    void sendUserMessage(UUID userId, String email, String templateCode, Map<String, Object> vars);

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
