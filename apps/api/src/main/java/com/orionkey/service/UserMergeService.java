package com.orionkey.service;

import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.CartItem;
import com.orionkey.entity.CustomerBinding;
import com.orionkey.entity.MarketingRecipient;
import com.orionkey.entity.OperationLog;
import com.orionkey.entity.Order;
import com.orionkey.entity.PointsLog;
import com.orionkey.entity.User;
import com.orionkey.entity.UserCoupon;
import com.orionkey.entity.UserMessage;
import com.orionkey.exception.BusinessException;
import com.orionkey.model.request.BindEmailRequest;
import com.orionkey.model.request.BindPhoneRequest;
import com.orionkey.repository.CartItemRepository;
import com.orionkey.repository.CustomerBindingRepository;
import com.orionkey.repository.DistributorRepository;
import com.orionkey.repository.MarketingRecipientRepository;
import com.orionkey.repository.OperationLogRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.PointsLogRepository;
import com.orionkey.repository.UserCouponRepository;
import com.orionkey.repository.UserMessageRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 绑定手机号 / 邮箱 + 用户合并（P5）。
 *
 * <p>绑定本质是「空位互补」：申请方被绑定字段必须为空；目标账号不得是
 * 「手机号 + 邮箱」都已绑定的完整账号（无可互补空位）。校验顺序固定，见开发计划 P5。</p>
 *
 * <p>拒绝日志 {@code operation_logs(action=user.merge.rejected)} 必须在业务异常抛出的同时落库，
 * 因此事务声明为 {@code noRollbackFor = BusinessException.class}（所有 BusinessException 均在数据变更前抛出）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMergeService {

    private static final String TYPE_PHONE = "PHONE";
    private static final String TYPE_EMAIL = "EMAIL";

    private static final String ACTION_BIND = "user.bind";
    private static final String ACTION_MERGE = "user.merge";
    private static final String ACTION_REJECTED = "user.merge.rejected";

    private static final String KEY_MERGE_ENABLED = "account_merge_enabled";

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final CustomerBindingRepository customerBindingRepository;
    private final DistributorRepository distributorRepository;
    private final CartItemRepository cartItemRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserMessageRepository userMessageRepository;
    private final MarketingRecipientRepository marketingRecipientRepository;
    private final PointsLogRepository pointsLogRepository;
    private final OperationLogRepository operationLogRepository;
    private final OperationLogService operationLogService;
    private final PasswordEncoder passwordEncoder;
    private final SiteConfigService siteConfigService;

    /** 绑定手机号（已被占用则合并目标账号） */
    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> bindPhone(UUID currentUserId, BindPhoneRequest request) {
        String phone = PhoneUtils.normalize(request.getPhone());
        if (!PhoneUtils.isValidCnMobile(phone)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }
        return bind(currentUserId, TYPE_PHONE, phone, request.getPassword());
    }

    /** 绑定邮箱（已被占用则合并目标账号） */
    @Transactional(noRollbackFor = BusinessException.class)
    public Map<String, Object> bindEmail(UUID currentUserId, BindEmailRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (email.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        return bind(currentUserId, TYPE_EMAIL, email, request.getPassword());
    }

    // ══════════════════════ 校验主流程（顺序不可调整） ══════════════════════

    private Map<String, Object> bind(UUID currentUserId, String type, String value, String password) {
        boolean phoneType = TYPE_PHONE.equals(type);
        User current = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));

        // 1. 校验当前登录密码
        if (password == null || !passwordEncoder.matches(password, current.getPasswordHash())) {
            recordRejected(current, type, value, "OLD_PASSWORD_WRONG");
            throw new BusinessException(ErrorCode.OLD_PASSWORD_WRONG, "登录密码错误");
        }

        // 2a. 当前账号被绑定字段已有值
        String currentValue = phoneType ? current.getPhone() : current.getEmail();
        if (isNotBlank(currentValue)) {
            if (currentValue.equalsIgnoreCase(value)) {
                recordLog(current, ACTION_MERGE, "idempotent;type=" + type + ";target=" + maskValue(type, value));
                return result(false, true);
            }
            recordRejected(current, type, value, "BIND_ALREADY_BOUND");
            throw new BusinessException(ErrorCode.BIND_ALREADY_BOUND, boundMessage(phoneType));
        }

        Optional<User> targetOpt = phoneType
                ? userRepository.findByPhone(value)
                : userRepository.findByEmail(value);

        // 5. 目标标识无归属用户 → 直接绑定
        if (targetOpt.isEmpty()) {
            return bindDirectly(current, type, value);
        }

        User target = targetOpt.get();

        // 4. 目标标识属于自己 → 幂等成功
        if (target.getId().equals(current.getId())) {
            recordLog(current, ACTION_MERGE, "idempotent;type=" + type + ";target=" + maskValue(type, value));
            return result(false, true);
        }

        // 2b. 目标是「完整账号」（手机号 + 邮箱都有）→ 无空位可互补，一律拒绝
        if (isNotBlank(target.getPhone()) && isNotBlank(target.getEmail())) {
            recordRejected(current, type, value, "TARGET_ACCOUNT_COMPLETE");
            throw new BusinessException(ErrorCode.TARGET_ACCOUNT_COMPLETE,
                    "该手机号/邮箱所属账号已同时绑定手机号与邮箱，无法绑定合并，请直接登录该账号或联系客服");
        }

        // 3. 后台关闭账号合并 → 只允许绑定空字段
        if (!siteConfigService.isEnabled(KEY_MERGE_ENABLED)) {
            recordRejected(current, type, value, "MERGE_DISABLED");
            throw new BusinessException(ErrorCode.MERGE_DISABLED, "该手机号/邮箱已被占用，请联系客服");
        }

        // 6. 双方均为推广员 → 硬拦截
        boolean currentIsDistributor = distributorRepository.findByUserId(current.getId()).isPresent();
        boolean targetIsDistributor = distributorRepository.findByUserId(target.getId()).isPresent();
        if (currentIsDistributor && targetIsDistributor) {
            recordRejected(current, type, value, "MERGE_DUAL_DISTRIBUTOR");
            throw new BusinessException(ErrorCode.MERGE_DUAL_DISTRIBUTOR,
                    "双方账号均为推广员，无法绑定合并，请联系管理员处理");
        }

        // 7. 加锁 current / target → 锁后复查 → 执行合并
        User[] locked = lockPair(current.getId(), target.getId());
        User lockedCurrent = locked[0];
        User lockedTarget = locked[1];

        if (lockedCurrent.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        String lockedValue = phoneType ? lockedCurrent.getPhone() : lockedCurrent.getEmail();
        if (isNotBlank(lockedValue)) {
            if (lockedValue.equalsIgnoreCase(value)) {
                return result(false, true);
            }
            throw new BusinessException(ErrorCode.BIND_ALREADY_BOUND, boundMessage(phoneType));
        }
        if (lockedTarget.getIsDeleted() == 1) {
            // 目标账号已被并发请求合并掉 → 退化为直接绑定空字段
            return bindLocked(lockedCurrent, type, value);
        }
        if (isNotBlank(lockedTarget.getPhone()) && isNotBlank(lockedTarget.getEmail())) {
            throw new BusinessException(ErrorCode.TARGET_ACCOUNT_COMPLETE,
                    "该手机号/邮箱所属账号已同时绑定手机号与邮箱，无法绑定合并，请直接登录该账号或联系客服");
        }

        mergeAccounts(lockedCurrent, lockedTarget, type, value, targetIsDistributor);
        return result(true, false);
    }

    // ══════════════════════ 直接绑定 ══════════════════════

    private Map<String, Object> bindDirectly(User current, String type, String value) {
        User locked = userRepository.findByIdForUpdate(current.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        return bindLocked(locked, type, value);
    }

    private Map<String, Object> bindLocked(User locked, String type, String value) {
        boolean phoneType = TYPE_PHONE.equals(type);
        String existing = phoneType ? locked.getPhone() : locked.getEmail();
        if (isNotBlank(existing)) {
            if (existing.equalsIgnoreCase(value)) {
                return result(false, true);
            }
            recordRejected(locked, type, value, "BIND_ALREADY_BOUND");
            throw new BusinessException(ErrorCode.BIND_ALREADY_BOUND, boundMessage(phoneType));
        }
        if (phoneType) {
            locked.setPhone(value);
            if (locked.getPhoneRegisteredAt() == null) {
                locked.setPhoneRegisteredAt(LocalDateTime.now());
            }
        } else {
            locked.setEmail(value);
        }
        userRepository.save(locked);
        recordLog(locked, ACTION_BIND, "type=" + type + ";target=" + maskValue(type, value));
        return result(false, false);
    }

    // ══════════════════════ 11 张表合并 ══════════════════════

    private void mergeAccounts(User current, User target, String type, String value, boolean targetIsDistributor) {
        UUID currentId = current.getId();
        UUID targetId = target.getId();
        boolean phoneType = TYPE_PHONE.equals(type);
        List<String> details = new ArrayList<>();

        // orders：user_id target → current
        List<Order> orders = orderRepository.findByUserId(targetId);
        if (!orders.isEmpty()) {
            for (Order o : orders) {
                o.setUserId(currentId);
            }
            orderRepository.saveAll(orders);
            details.add("orders=" + orders.size());
        }

        // customer_bindings：按 (customer_email, distributor_id) 去重后迁 customer_user_id
        List<CustomerBinding> targetBindings = customerBindingRepository.findByCustomerUserId(targetId);
        if (!targetBindings.isEmpty()) {
            Set<String> existingKeys = new HashSet<>();
            for (CustomerBinding b : customerBindingRepository.findByCustomerUserId(currentId)) {
                existingKeys.add(bindingKey(b));
            }
            List<CustomerBinding> keep = new ArrayList<>();
            List<CustomerBinding> duplicated = new ArrayList<>();
            for (CustomerBinding b : targetBindings) {
                if (existingKeys.contains(bindingKey(b))) {
                    duplicated.add(b);
                } else {
                    existingKeys.add(bindingKey(b));
                    b.setCustomerUserId(currentId);
                    keep.add(b);
                }
            }
            if (!duplicated.isEmpty()) {
                customerBindingRepository.deleteAll(duplicated);
            }
            if (!keep.isEmpty()) {
                customerBindingRepository.saveAll(keep);
            }
            details.add("customer_bindings=" + keep.size() + "/dup=" + duplicated.size());
        }

        // distributors：仅 target 是推广员 → user_id = current（双推广员已在校验阶段拦截）
        if (targetIsDistributor) {
            distributorRepository.findByUserId(targetId).ifPresent(d -> {
                d.setUserId(currentId);
                distributorRepository.save(d);
            });
            details.add("distributor=moved");
        }

        // cart_items：按 (product_id, spec_id) 合并数量
        List<CartItem> targetCarts = cartItemRepository.findByUserId(targetId);
        if (!targetCarts.isEmpty()) {
            List<CartItem> removed = new ArrayList<>();
            for (CartItem item : targetCarts) {
                Optional<CartItem> existing = cartItemRepository
                        .findByUserIdAndProductIdAndSpecId(currentId, item.getProductId(), item.getSpecId());
                if (existing.isPresent()) {
                    CartItem merged = existing.get();
                    merged.setQuantity(merged.getQuantity() + item.getQuantity());
                    cartItemRepository.save(merged);
                    removed.add(item);
                } else {
                    item.setUserId(currentId);
                    cartItemRepository.save(item);
                }
            }
            if (!removed.isEmpty()) {
                cartItemRepository.deleteAll(removed);
            }
            details.add("cart_items=" + targetCarts.size());
        }

        // user_coupons：迁移，按券模板去重（仅同活动下未使用的券去重，已使用券全部保留）
        List<UserCoupon> targetCoupons = userCouponRepository.findByUserIdOrderByCreatedAtDesc(targetId);
        if (!targetCoupons.isEmpty()) {
            Set<UUID> currentClaimedCampaigns = new HashSet<>();
            for (UserCoupon c : userCouponRepository.findByUserIdOrderByCreatedAtDesc(currentId)) {
                if (isClaimed(c) && c.getCampaignId() != null) {
                    currentClaimedCampaigns.add(c.getCampaignId());
                }
            }
            List<UserCoupon> keep = new ArrayList<>();
            List<UserCoupon> duplicated = new ArrayList<>();
            for (UserCoupon c : targetCoupons) {
                if (isClaimed(c) && c.getCampaignId() != null && currentClaimedCampaigns.contains(c.getCampaignId())) {
                    duplicated.add(c);
                } else {
                    if (isClaimed(c) && c.getCampaignId() != null) {
                        currentClaimedCampaigns.add(c.getCampaignId());
                    }
                    c.setUserId(currentId);
                    keep.add(c);
                }
            }
            if (!duplicated.isEmpty()) {
                userCouponRepository.deleteAll(duplicated);
            }
            if (!keep.isEmpty()) {
                userCouponRepository.saveAll(keep);
            }
            details.add("user_coupons=" + keep.size() + "/dup=" + duplicated.size());
        }

        // user_messages：迁移，按业务键去重
        List<UserMessage> targetMessages = userMessageRepository.findAllByUserId(targetId);
        if (!targetMessages.isEmpty()) {
            Set<String> existingKeys = new HashSet<>();
            for (UserMessage m : userMessageRepository.findAllByUserId(currentId)) {
                existingKeys.add(messageKey(m));
            }
            List<UserMessage> keep = new ArrayList<>();
            List<UserMessage> duplicated = new ArrayList<>();
            for (UserMessage m : targetMessages) {
                if (existingKeys.add(messageKey(m))) {
                    m.setUserId(currentId);
                    keep.add(m);
                } else {
                    duplicated.add(m);
                }
            }
            if (!duplicated.isEmpty()) {
                userMessageRepository.deleteAll(duplicated);
            }
            if (!keep.isEmpty()) {
                userMessageRepository.saveAll(keep);
            }
            details.add("user_messages=" + keep.size() + "/dup=" + duplicated.size());
        }

        // marketing_recipients：迁移，按 (campaign_id, email) 去重
        List<MarketingRecipient> targetRecipients = marketingRecipientRepository.findByUserId(targetId);
        if (!targetRecipients.isEmpty()) {
            Set<String> existingKeys = new HashSet<>();
            for (MarketingRecipient r : marketingRecipientRepository.findByUserId(currentId)) {
                existingKeys.add(recipientKey(r));
            }
            List<MarketingRecipient> keep = new ArrayList<>();
            List<MarketingRecipient> duplicated = new ArrayList<>();
            for (MarketingRecipient r : targetRecipients) {
                if (existingKeys.add(recipientKey(r))) {
                    r.setUserId(currentId);
                    keep.add(r);
                } else {
                    duplicated.add(r);
                }
            }
            if (!duplicated.isEmpty()) {
                marketingRecipientRepository.deleteAll(duplicated);
            }
            if (!keep.isEmpty()) {
                marketingRecipientRepository.saveAll(keep);
            }
            details.add("marketing_recipients=" + keep.size() + "/dup=" + duplicated.size());
        }

        // points_logs：迁移；users.points 累加并写一条 MERGE 日志
        List<PointsLog> targetPointsLogs = pointsLogRepository.findByUserId(targetId);
        if (!targetPointsLogs.isEmpty()) {
            for (PointsLog p : targetPointsLogs) {
                p.setUserId(currentId);
            }
            pointsLogRepository.saveAll(targetPointsLogs);
        }
        int mergedPoints = target.getPoints();
        if (mergedPoints != 0) {
            current.setPoints(current.getPoints() + mergedPoints);
            PointsLog mergeLog = new PointsLog();
            mergeLog.setUserId(currentId);
            mergeLog.setChangeAmount(mergedPoints);
            mergeLog.setBalanceAfter(current.getPoints());
            mergeLog.setReason("MERGE");
            pointsLogRepository.save(mergeLog);
            details.add("points=" + mergedPoints);
        }

        // operation_logs：迁移（保留审计）
        List<OperationLog> targetLogs = operationLogRepository.findByUserId(targetId);
        if (!targetLogs.isEmpty()) {
            for (OperationLog l : targetLogs) {
                l.setUserId(currentId);
            }
            operationLogRepository.saveAll(targetLogs);
            details.add("operation_logs=" + targetLogs.size());
        }

        // 字段回填 + 互补继承
        if (phoneType) {
            current.setPhone(value);
            if (current.getPhoneRegisteredAt() == null) {
                current.setPhoneRegisteredAt(target.getPhoneRegisteredAt());
            }
            if (!isNotBlank(current.getEmail()) && isNotBlank(target.getEmail())) {
                current.setEmail(target.getEmail());
            }
        } else {
            current.setEmail(value);
            if (!isNotBlank(current.getPhone()) && isNotBlank(target.getPhone())) {
                current.setPhone(target.getPhone());
                current.setPhoneRegisteredAt(target.getPhoneRegisteredAt());
            }
        }
        current.setMustChangePassword(false);
        userRepository.save(current);

        // users：target 软删除并释放唯一键
        String suffix = "_merged_" + targetId.toString().substring(0, 8);
        target.setUsername(mergedUsername(target.getUsername(), suffix));
        target.setEmail(null);
        target.setPhone(null);
        target.setIsDeleted(1);
        userRepository.save(target);

        recordLog(current, ACTION_MERGE,
                "type=" + type + ";target=" + maskValue(type, value) + ";target_user=" + targetId + ";"
                        + String.join(",", details));
        log.info("账号合并完成 current={} target={} type={} detail={}", currentId, targetId, type, details);
    }

    // ══════════════════════ 工具方法 ══════════════════════

    /** 按 UUID 字符串排序加锁，避免并发交叉绑定死锁；返回顺序与入参一致 [current, target] */
    private User[] lockPair(UUID currentId, UUID targetId) {
        boolean currentFirst = currentId.toString().compareTo(targetId.toString()) < 0;
        UUID firstId = currentFirst ? currentId : targetId;
        UUID secondId = currentFirst ? targetId : currentId;
        User first = userRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        User second = userRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        return currentFirst ? new User[]{first, second} : new User[]{second, first};
    }

    private void recordRejected(User current, String type, String value, String reason) {
        recordLog(current, ACTION_REJECTED,
                "reason=" + reason + ";type=" + type + ";target=" + maskValue(type, value));
    }

    private void recordLog(User user, String action, String detail) {
        operationLogService.log(user.getId(), user.getUsername(), action, "USER", user.getId().toString(), detail, null);
    }

    private Map<String, Object> result(boolean merged, boolean idempotent) {
        Map<String, Object> map = new HashMap<>();
        map.put("merged", merged);
        map.put("idempotent", idempotent);
        return map;
    }

    private String boundMessage(boolean phoneType) {
        return phoneType
                ? "当前账号已绑定手机号，不可重复绑定，如需更换请联系客服"
                : "当前账号已绑定邮箱，不可重复绑定，如需更换请联系客服";
    }

    private boolean isClaimed(UserCoupon coupon) {
        return "CLAIMED".equals(coupon.getStatus());
    }

    private String bindingKey(CustomerBinding binding) {
        String email = binding.getCustomerEmail() == null ? "" : binding.getCustomerEmail().toLowerCase(Locale.ROOT);
        return email + "|" + binding.getDistributorId();
    }

    private String messageKey(UserMessage message) {
        return nullSafe(message.getTemplateCode()) + "|" + nullSafe(message.getMetadata()) + "|" + nullSafe(message.getTitle());
    }

    private String recipientKey(MarketingRecipient recipient) {
        return recipient.getCampaignId() + "|"
                + (recipient.getEmail() == null ? "" : recipient.getEmail().toLowerCase(Locale.ROOT));
    }

    private String mergedUsername(String username, String suffix) {
        String base = username == null ? "user" : username;
        return base + suffix;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 日志脱敏：手机号 138****8000；邮箱 ab***@xx.com */
    private static String maskValue(String type, String value) {
        if (value == null) {
            return "-";
        }
        if (TYPE_PHONE.equals(type) && value.length() == 11) {
            return value.substring(0, 3) + "****" + value.substring(7);
        }
        if (TYPE_EMAIL.equals(type)) {
            int at = value.indexOf('@');
            if (at > 1) {
                return value.substring(0, 2) + "***" + value.substring(at);
            }
        }
        return value;
    }
}
