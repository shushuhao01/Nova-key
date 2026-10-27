package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.DistributorStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserMessageCategory;
import com.orionkey.constant.UserRole;
import com.orionkey.context.RequestContext;
import com.orionkey.entity.Distributor;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.SiteConfig;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.DistributorRepository;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.SiteConfigRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AdminCustomerService;
import com.orionkey.service.NotificationService;
import com.orionkey.service.UserMessageService;
import com.orionkey.service.WechatMpConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCustomerServiceImpl implements AdminCustomerService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final DistributorRepository distributorRepository;
    private final WechatMpConfigService wechatMpConfigService;
    private final SiteConfigRepository siteConfigRepository;
    private final NotificationService notificationService;
    private final UserMessageService userMessageService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrlDefault;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 新密码字符集：排除易混淆字符 0 / O / 1 / l / I */
    private static final char[] PWD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] PWD_LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] PWD_DIGIT = "23456789".toCharArray();
    private static final SecureRandom PWD_RANDOM = new SecureRandom();

    /** 重置密码频控：key = 管理员 / 目标用户，value = 命中时间戳队列 */
    private final Map<String, Deque<Long>> resetRateBuckets = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long totalRegistered = userRepository.countByRole(UserRole.USER);
        long totalAnonymous = orderRepository.countAnonymousContacts();
        long totalCustomers = totalRegistered + totalAnonymous;

        long newRegistered = userRepository.countByRoleAndCreatedAtGreaterThanEqual(UserRole.USER, monthStart);
        long newAnonymous = orderRepository.countNewAnonymousContacts(monthStart);

        long dealRegistered = orderRepository.countPaidRegisteredCustomers();
        long dealAnonymous = orderRepository.countPaidAnonymousCustomers();
        long dealCustomers = dealRegistered + dealAnonymous;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total_registered", totalRegistered);
        map.put("total_anonymous", totalAnonymous);
        map.put("total_customers", totalCustomers);
        map.put("new_registered", newRegistered);
        map.put("new_anonymous", newAnonymous);
        map.put("new_customers", newRegistered + newAnonymous);
        map.put("deal_registered", dealRegistered);
        map.put("deal_anonymous", dealAnonymous);
        map.put("deal_customers", dealCustomers);
        map.put("no_deal_customers", Math.max(0, totalCustomers - dealCustomers));
        return map;
    }

    @Override
    @Transactional
    public PageResult<?> listRegistered(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<User> userPage;
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            userPage = userRepository.findByRoleAndUsernameContainingOrRoleAndEmailContainingOrRoleAndPhoneContaining(
                    UserRole.USER, kw, UserRole.USER, kw, UserRole.USER, kw, pageable);
        } else {
            userPage = userRepository.findByRoleOrderByCreatedAtDesc(UserRole.USER, pageable);
        }
        // 懒同步微信资料：已绑定公众号但缺少昵称/头像的，调用微信接口拉取（每页最多同步 50 个，避免阻塞）
        Map<String, User> syncedByOpenid = new HashMap<>();
        int syncBudget = 50;
        for (User u : userPage.getContent()) {
            if (syncBudget <= 0) break;
            if (u.getMpOpenid() == null || u.getMpOpenid().isBlank()) continue;
            if (u.getMpNickname() != null && !u.getMpNickname().isBlank()
                    && u.getMpAvatar() != null && !u.getMpAvatar().isBlank()) continue;
            if (syncedByOpenid.containsKey(u.getMpOpenid())) continue;
            syncedByOpenid.put(u.getMpOpenid(), u);
            syncBudget--;
            try {
                Map<String, Object> profile = wechatMpConfigService.fetchUserProfile(u.getMpOpenid());
                if (profile != null) {
                    Object nick = profile.get("nickname");
                    Object avatar = profile.get("headimgurl");
                    if (nick != null && !nick.toString().isBlank()) u.setMpNickname(nick.toString());
                    if (avatar != null && !avatar.toString().isBlank()) u.setMpAvatar(avatar.toString());
                }
            } catch (Exception e) {
                log.warn("Sync wechat profile failed for user {}: {}", u.getId(), e.getMessage());
            }
        }
        if (!syncedByOpenid.isEmpty()) {
            userRepository.saveAll(userPage.getContent());
        }
        List<Map<String, Object>> list = userPage.getContent().stream().map(u -> {
            UUID uid = u.getId();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uid);
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("phone", u.getPhone());
            m.put("points", u.getPoints());
            m.put("is_banned", u.getIsDeleted() == 1);
            m.put("created_at", u.getCreatedAt());
            m.put("order_count", orderRepository.countByUserId(uid));
            m.put("paid_count", orderRepository.countPaidByUserId(uid));
            m.put("total_spent", orderRepository.sumPaidByUserId(uid));
            return m;
        }).toList();
        // 批量注入微信绑定信息与分销员等级（公众号 openid / 分销员微信绑定 / 一级二级推广）
        Set<UUID> userIds = userPage.getContent().stream().map(User::getId).collect(Collectors.toSet());
        Map<UUID, Distributor> distByUser = userIds.isEmpty() ? Map.of()
                : distributorRepository.findByUserIdIn(userIds).stream()
                        .collect(Collectors.toMap(Distributor::getUserId, d -> d, (a, b) -> a));
        Map<UUID, User> userById = userPage.getContent().stream().collect(Collectors.toMap(User::getId, u -> u));
        for (Map<String, Object> m : list) {
            UUID uid = (UUID) m.get("id");
            User u = userById.get(uid);
            Distributor d = distByUser.get(uid);
            boolean wechatBound = (u.getMpOpenid() != null && !u.getMpOpenid().isBlank())
                    || (d != null && d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
            m.put("wechat_customer", wechatBound);
            m.put("mp_subscribe", u.getMpSubscribe());
            m.put("mp_nickname", u.getMpNickname() != null && !u.getMpNickname().isBlank()
                    ? u.getMpNickname()
                    : (d != null ? d.getWechatNickname() : null));
            m.put("mp_avatar", u.getMpAvatar() != null && !u.getMpAvatar().isBlank() ? u.getMpAvatar() : null);
            // 推广员等级：已通过审核的一级（无上级）/ 二级（有上级）
            m.put("distributor_level", d != null && d.getStatus() == DistributorStatus.APPROVED
                    ? (d.getParentId() != null ? 2 : 1)
                    : null);
        }
        return PageResult.of(userPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listAnonymous(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<String> contactPage;
        if (keyword != null && !keyword.isBlank()) {
            contactPage = orderRepository.findAnonymousContactsByKeyword("%" + keyword.trim().toLowerCase() + "%", pageable);
        } else {
            contactPage = orderRepository.findAnonymousContacts(pageable);
        }
        List<String> contacts = contactPage.getContent();
        // 批量取出本页联系方式的全部订单，内存聚合（每页最多 100 个客户，可控）
        Map<String, List<Order>> ordersByContact = contacts.isEmpty()
                ? Map.of()
                : ordersByContactMap(orderRepository.findAnonymousOrdersByContacts(contacts));

        List<Map<String, Object>> list = contacts.stream().map(contact -> {
            List<Order> orders = ordersByContact.getOrDefault(contact, List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contact", contact);
            m.put("phone", orders.stream().map(Order::getPhone)
                    .filter(p -> p != null && !p.isBlank()).findFirst().orElse(null));
            m.put("email", orders.stream().map(Order::getEmail)
                    .filter(e -> e != null && !e.isBlank()).findFirst().orElse(null));
            long paidCount = orders.stream().filter(o -> isPaid(o)).count();
            BigDecimal totalSpent = orders.stream()
                    .filter(o -> isPaid(o))
                    .map(Order::getActualAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            m.put("order_count", orders.size());
            m.put("paid_count", paidCount);
            m.put("total_spent", totalSpent);
            m.put("first_order_at", orders.isEmpty() ? null : orders.get(orders.size() - 1).getCreatedAt());
            m.put("last_order_at", orders.isEmpty() ? null : orders.get(0).getCreatedAt());
            return m;
        }).toList();
        return PageResult.of(contactPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> registeredDetail(UUID id, int page, int pageSize) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<Order> orderPage = orderRepository.findByUserIdOrderByCreatedAtDesc(id, pageable);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("points", u.getPoints());
        m.put("is_banned", u.getIsDeleted() == 1);
        m.put("registered_at", u.getCreatedAt());
        m.put("order_count", orderRepository.countByUserId(id));
        m.put("paid_count", orderRepository.countPaidByUserId(id));
        m.put("total_spent", orderRepository.sumPaidByUserId(id));
        m.put("orders", PageResult.of(orderPage, orderPage.getContent().stream().map(this::toOrderMap).toList()));
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> anonymousDetail(String contact, int page, int pageSize) {
        if (contact == null || contact.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式不能为空");
        }
        String c = contact.trim();
        List<Order> all = orderRepository.findAnonymousOrdersByContact(c);
        if (all.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "匿名客户不存在");
        }
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        // 手工分页（订单量一般不大，直接内存切片）
        int from = (int) pageable.getOffset();
        int to = (int) Math.min(from + pageable.getPageSize(), all.size());
        List<Order> slice = from >= all.size() ? List.of() : all.subList(from, to);

        long paidCount = all.stream().filter(this::isPaid).count();
        BigDecimal totalSpent = all.stream()
                .filter(this::isPaid)
                .map(Order::getActualAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contact", c);
        m.put("phone", all.stream().map(Order::getPhone)
                .filter(p -> p != null && !p.isBlank()).findFirst().orElse(null));
        m.put("email", all.stream().map(Order::getEmail)
                .filter(e -> e != null && !e.isBlank()).findFirst().orElse(null));
        m.put("order_count", all.size());
        m.put("paid_count", paidCount);
        m.put("total_spent", totalSpent);
        m.put("first_order_at", all.get(all.size() - 1).getCreatedAt());
        m.put("last_order_at", all.get(0).getCreatedAt());
        m.put("orders", PageResult.of(slice.stream().map(this::toOrderMap).toList(), page, pageable.getPageSize(), all.size()));
        return m;
    }

    @Override
    @Transactional
    public void toggleRegistered(UUID id, int isDeleted) {
        if (isDeleted != 0 && isDeleted != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "is_deleted 参数只能为 0 或 1");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能封禁管理员账户");
        }
        user.setIsDeleted(isDeleted);
        userRepository.save(user);
    }

    // ═══════════ 辅助 ═══════════

    @Override
    @Transactional
    public void unbindContact(UUID id, String field, String reason) {
        String type = field == null ? "" : field.trim().toLowerCase();
        if (!"email".equals(type) && !"phone".equals(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "解绑类型仅支持邮箱或手机号");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写解绑理由");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
        if (user.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅支持解绑普通客户联系方式");
        }
        if ("email".equals(type)) {
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该客户未绑定邮箱");
            }
            String oldEmail = user.getEmail();
            user.setEmail(null);
            userRepository.save(user);
            notifyUnbind(user, "邮箱", oldEmail, reason.trim());
        } else {
            if (user.getPhone() == null || user.getPhone().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该客户未绑定手机号");
            }
            String oldPhone = user.getPhone();
            user.setPhone(null);
            user.setPhoneRegisteredAt(null);
            userRepository.save(user);
            notifyUnbind(user, "手机号", oldPhone, reason.trim());
        }
        log.info("Unbind {} for customer {} by {}, reason={}", type, user.getUsername(), RequestContext.getUserId(), reason.trim());
    }

    /** 解绑联系方式后向用户消息中心（前台铃铛）发送通知 */
    private void notifyUnbind(User user, String fieldLabel, String oldValue, String reason) {
        try {
            String title = "联系方式解绑通知";
            String content = "您的" + fieldLabel + "（" + oldValue + "）已被管理员解绑。解绑理由：" + reason
                    + "。如需帮助，请联系客服。";
            userMessageService.sendDirectMessage(user.getId(), user.getEmail(), UserMessageCategory.SYSTEM, title, content);
        } catch (Exception e) {
            // 通知失败不应影响解绑主流程
            log.warn("Failed to notify unbind message for user {}: {}", user.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> resetPassword(UUID userId) {
        // 频控：同一目标用户 5 分钟内仅 1 次；同一管理员 60 秒内 ≤ 10 次
        checkRateLimit("target:" + userId, 1, 5 * 60 * 1000L, "该用户近期已被重置过密码，请 5 分钟后再试");
        UUID adminId = RequestContext.getUserId();
        if (adminId != null) {
            checkRateLimit("admin:" + adminId, 10, 60 * 1000L, "重置操作过于频繁，请稍后再试");
        }

        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
        if (u.getRole() != UserRole.USER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅支持重置普通客户密码");
        }

        String newPassword = generatePassword();
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        // 密码版本 +1：已签发的旧 JWT 立即失效（所有设备需重新登录）
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        // 重置目的就是让用户能直接登录，不再强制改密
        u.setMustChangePassword(false);
        // 重置即解锁
        u.setFailedLoginAttempts(0);
        u.setLockUntil(null);
        userRepository.save(u);

        String email = u.getEmail();
        String phone = u.getPhone();
        String loginUrl = siteUrl() + "/login";
        List<String> channels = new ArrayList<>();
        // 邮件通知（收件人为用户本人；发送失败不影响重置结果）
        if (email != null && !email.isBlank()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("username", u.getUsername());
            vars.put("email", email);
            vars.put("phone", phone == null ? "" : phone);
            vars.put("new_password", newPassword);
            vars.put("login_url", loginUrl);
            try {
                notificationService.sendTemplateToEmail("ADMIN_RESET_PASSWORD", email, vars);
                channels.add("EMAIL");
            } catch (Exception ex) {
                log.warn("Send reset-password email failed for user {}: {}", userId, ex.getMessage());
            }
        }
        // 短信通知（方案 A：仅提示密码已重置，不含明文密码）
        if (phone != null && !phone.isBlank()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("username", u.getUsername());
            vars.put("login_url", loginUrl);
            if (notificationService.sendTemplateToSms("ADMIN_RESET_PASSWORD", phone, vars)) {
                channels.add("SMS");
            }
        }

        String notify = channels.isEmpty() ? "NONE" : String.join(",", channels);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", u.getUsername());
        result.put("email", email);
        result.put("phone", phone);
        result.put("new_password", newPassword);
        result.put("notify", notify);
        // 操作日志绝不记录密码
        log.info("Reset password for customer {} by {}, notify={}", u.getUsername(), adminId, notify);
        return result;
    }

    /** 频控：窗口期内命中次数超过上限则拒绝 */
    private void checkRateLimit(String key, int limit, long windowMillis, String message) {
        long now = System.currentTimeMillis();
        Deque<Long> bucket = resetRateBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() >= windowMillis) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, message);
            }
            bucket.addLast(now);
        }
    }

    /** 生成 12 位随机密码：大小写 + 数字，排除 0 / O / 1 / l / I 等易混淆字符 */
    private String generatePassword() {
        String all = new String(PWD_UPPER) + new String(PWD_LOWER) + new String(PWD_DIGIT);
        List<Character> chars = new ArrayList<>(12);
        chars.add(PWD_UPPER[PWD_RANDOM.nextInt(PWD_UPPER.length)]);
        chars.add(PWD_LOWER[PWD_RANDOM.nextInt(PWD_LOWER.length)]);
        chars.add(PWD_DIGIT[PWD_RANDOM.nextInt(PWD_DIGIT.length)]);
        while (chars.size() < 12) {
            chars.add(all.charAt(PWD_RANDOM.nextInt(all.length())));
        }
        Collections.shuffle(chars, PWD_RANDOM);
        StringBuilder sb = new StringBuilder(12);
        for (Character ch : chars) {
            sb.append(ch);
        }
        return sb.toString();
    }

    private String siteUrl() {
        return siteConfigRepository.findByConfigKey("mail_site_url")
                .map(SiteConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(baseUrlDefault);
    }

    private boolean isPaid(Order o) {
        return o.getStatus() != null && switch (o.getStatus()) {
            case PAID, DELIVERED, COMPLETED -> true;
            default -> false;
        };
    }

    private Map<String, List<Order>> ordersByContactMap(List<Order> orders) {
        Map<String, List<Order>> map = new LinkedHashMap<>();
        for (Order o : orders) {
            String contact = contactOf(o);
            if (contact == null) continue;
            map.computeIfAbsent(contact, k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    /** 匿名客户联系方式口径：优先手机号，无手机号回落到邮箱（与 SQL 中 COALESCE 一致） */
    private String contactOf(Order o) {
        String phone = o.getPhone();
        if (phone != null && !phone.isBlank()) return phone;
        String email = o.getEmail();
        return email == null || email.isBlank() ? null : email;
    }

    /** 订单摘要（含商品明细） */
    private Map<String, Object> toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("email", o.getEmail());
        m.put("phone", o.getPhone());
        m.put("status", o.getStatus() == null ? null : o.getStatus().name());
        m.put("payment_method", o.getPaymentMethod());
        m.put("total_amount", o.getTotalAmount());
        m.put("actual_amount", o.getActualAmount());
        m.put("coupon_code", o.getCouponCode());
        m.put("coupon_discount", o.getCouponDiscount());
        m.put("created_at", o.getCreatedAt());
        m.put("paid_at", o.getPaidAt());
        m.put("delivered_at", o.getDeliveredAt());
        List<OrderItem> items = orderItemRepository.findByOrderId(o.getId());
        m.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("product_title", i.getProductTitle());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            return im;
        }).toList());
        return m;
    }
}
