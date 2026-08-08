package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.UserRole;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.OrderItemRepository;
import com.orionkey.repository.OrderRepository;
import com.orionkey.repository.UserRepository;
import com.orionkey.service.AdminCustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCustomerServiceImpl implements AdminCustomerService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    private static final UserRole CUSTOMER_ROLE = UserRole.USER;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long totalRegistered = userRepository.countByRoleNot(CUSTOMER_ROLE);
        long totalAnonymous = orderRepository.countAnonymousEmails();
        long totalCustomers = totalRegistered + totalAnonymous;

        long newRegistered = userRepository.countByRoleNotAndCreatedAtGreaterThanEqual(CUSTOMER_ROLE, monthStart);
        long newAnonymous = orderRepository.countNewAnonymousEmails(monthStart);

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
    @Transactional(readOnly = true)
    public PageResult<?> listRegistered(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<User> userPage;
        if (keyword != null && !keyword.isBlank()) {
            userPage = userRepository.findByRoleNotAndUsernameContainingOrRoleNotAndEmailContaining(
                    CUSTOMER_ROLE, keyword.trim(), CUSTOMER_ROLE, keyword.trim(), pageable);
        } else {
            userPage = userRepository.findByRoleNotOrderByCreatedAtDesc(CUSTOMER_ROLE, pageable);
        }
        List<Map<String, Object>> list = userPage.getContent().stream().map(u -> {
            UUID uid = u.getId();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uid);
            m.put("username", u.getUsername());
            m.put("email", u.getEmail());
            m.put("points", u.getPoints());
            m.put("is_banned", u.getIsDeleted() == 1);
            m.put("created_at", u.getCreatedAt());
            m.put("order_count", orderRepository.countByUserId(uid));
            m.put("paid_count", orderRepository.countPaidByUserId(uid));
            m.put("total_spent", orderRepository.sumPaidByUserId(uid));
            return m;
        }).toList();
        return PageResult.of(userPage, list);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<?> listAnonymous(String keyword, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
        Page<String> emailPage;
        if (keyword != null && !keyword.isBlank()) {
            emailPage = orderRepository.findAnonymousEmailsByKeyword("%" + keyword.trim().toLowerCase() + "%", pageable);
        } else {
            emailPage = orderRepository.findAnonymousEmails(pageable);
        }
        List<String> emails = emailPage.getContent();
        // 批量取出本页邮箱的全部订单，内存聚合（每页最多 100 个邮箱，可控）
        Map<String, List<Order>> ordersByEmail = emails.isEmpty()
                ? Map.of()
                : ordersByEmailMap(orderRepository.findByEmailInAndUserIdIsNullOrderByCreatedAtDesc(emails));

        List<Map<String, Object>> list = emails.stream().map(email -> {
            List<Order> orders = ordersByEmail.getOrDefault(email, List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", email);
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
        return PageResult.of(emailPage, list);
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
    public Map<String, Object> anonymousDetail(String email, int page, int pageSize) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        String e = email.trim().toLowerCase();
        List<Order> all = orderRepository.findByEmailAndUserIdIsNullOrderByCreatedAtDesc(e);
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
        m.put("email", e);
        m.put("order_count", all.size());
        m.put("paid_count", paidCount);
        m.put("total_spent", totalSpent);
        m.put("first_order_at", all.get(all.size() - 1).getCreatedAt());
        m.put("last_order_at", all.get(0).getCreatedAt());
        m.put("orders", PageResult.of(slice, page, pageable.getPageSize(), all.size()));
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

    private boolean isPaid(Order o) {
        return o.getStatus() != null && switch (o.getStatus()) {
            case PAID, DELIVERED -> true;
            default -> false;
        };
    }

    private Map<String, List<Order>> ordersByEmailMap(List<Order> orders) {
        Map<String, List<Order>> map = new LinkedHashMap<>();
        for (Order o : orders) {
            if (o.getEmail() == null) continue;
            map.computeIfAbsent(o.getEmail().trim().toLowerCase(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    /** 订单摘要（含商品明细） */
    private Map<String, Object> toOrderMap(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("email", o.getEmail());
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
