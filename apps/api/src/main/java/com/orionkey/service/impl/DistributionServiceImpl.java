package com.orionkey.service.impl;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.constant.DistributorStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.OrderStatus;
import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.*;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.DistributionService;
import com.orionkey.service.NotificationService;
import com.orionkey.service.UserMessageService;
import com.orionkey.service.WechatMpConfigService;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayTransferQueryResult;
import com.orionkey.service.WxpayService.WxpayTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributionServiceImpl implements DistributionService {

    private final DistributorRepository distributorRepository;
    private final DistributionRuleRepository ruleRepository;
    private final CommissionTierRepository tierRepository;
    private final ProductCommissionRepository productCommissionRepository;
    private final DistributorProductRateRepository distributorProductRateRepository;
    private final CustomerBindingRepository customerBindingRepository;
    private final PromotionLinkRepository promotionLinkRepository;
    private final CommissionRecordRepository commissionRecordRepository;
    private final WithdrawalRecordRepository withdrawalRecordRepository;
    private final DistributionClickRepository clickRepository;
    private final UserMessageService userMessageService;
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final WxpayService wxpayService;
    private final PaymentServiceImpl paymentServiceImpl;
    private final SiteConfigRepository siteConfigRepository;
    private final WechatMpConfigService wechatMpConfigService;
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 时间区间查询哨兵值：from/to 为 null 时表示不限，PG 对 null 时间参数在 "IS NULL" 谓词下无法推断类型，必须传非空 */
    private static final LocalDateTime RANGE_FROM_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime RANGE_TO_MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    // ════════════════════════════════════════════════════════════════
    //  ── 分销员 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> applyDistributor(UUID userId, String inviteCode) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户未登录");
        }
        if (distributorRepository.findByUserId(userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "您已是分销员");
        }
        DistributionRule rule = getOrCreateRule();
        if (!rule.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销功能未开启");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));

        Distributor d = new Distributor();
        d.setUserId(userId);
        d.setDistributorCode(generateDistributorCode());
        d.setInviteCode(generateUniqueInviteCode());
        d.setSubRate(rule.getDefaultSubRate());

        // 上级分销员（仅绑定已审核通过的分销员，防止绑定无效/被禁用上级）
        if (inviteCode != null && !inviteCode.isBlank()) {
            distributorRepository.findByInviteCode(inviteCode.trim())
                    .filter(parent -> parent.getStatus() == DistributorStatus.APPROVED)
                    .ifPresent(parent -> d.setParentId(parent.getId()));
        }

        boolean autoApprove = rule.isAutoApprove();
        d.setStatus(autoApprove ? DistributorStatus.APPROVED : DistributorStatus.PENDING);
        if (autoApprove) {
            d.setApprovedAt(LocalDateTime.now());
        }
        distributorRepository.save(d);
        log.info("Distributor applied: userId={}, code={}, status={}", userId, d.getDistributorCode(), d.getStatus());

        // 发送用户消息
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("distributor_code", d.getDistributorCode());
            vars.put("status", d.getStatus().name());
            userMessageService.sendUserMessage(userId, user.getEmail(),
                    autoApprove ? "DIST_APPROVED" : "DIST_APPLIED", vars);
        } catch (Exception e) {
            log.warn("Failed to send distributor apply message: {}", e.getMessage());
        }

        // 自动审核通过时通知管理员
        if (autoApprove) {
            try {
                Map<String, Object> adminVars = new LinkedHashMap<>();
                adminVars.put("distributor_code", d.getDistributorCode());
                adminVars.put("user_email", user.getEmail());
                notificationService.sendTemplate("DIST_APPLIED", adminVars);
            } catch (Exception e) {
                log.warn("Failed to notify admin for distributor approval: {}", e.getMessage());
            }
        }

        return distributorToMap(d, user);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDistributorProfile(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        User u = userRepository.findById(userId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", d.getStatus().name());
        m.put("distributor_code", d.getDistributorCode());
        m.put("username", u != null ? u.getUsername() : null);
        m.put("email", u != null ? u.getEmail() : null);
        m.put("available_balance", d.getAvailableBalance());
        m.put("total_commission", d.getTotalCommission());
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("frozen_balance", d.getFrozenBalance());
        m.put("invite_code", d.getInviteCode());
        m.put("wechat_bound", d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
        m.put("parent_id", d.getParentId());
        m.put("sub_rate", rateToPercent(d.getSubRate()));
        m.put("custom_rate", rateToPercent(d.getCustomRate()));
        m.put("default_rate", rateToPercent(getOrCreateRule().getDefaultRate()));
        m.put("applied_at", d.getCreatedAt());
        m.put("approved_at", d.getApprovedAt());
        m.put("reject_reason", d.getRejectReason());
        m.put("rejected_at", d.getRejectedAt());
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDistributorStats(UUID userId, String range) {
        Distributor d = requireDistributorByUserId(userId);
        UUID distId = d.getId();

        // 时间范围：month=本月 1 号 00:00（北京时间）起；all=不限
        LocalDateTime from = "month".equalsIgnoreCase(range)
                ? LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).withHour(0)
                        .withMinute(0).withSecond(0).withNano(0)
                : null;

        long promotionProductCount = promotionLinkRepository.findByDistributorId(distId, PageRequest.of(0, 1))
                .getTotalElements();
        long totalClicks = clickRepository.countByDistributorId(distId);

        // 按推广员 ID 汇总：全店推广与商品推广链接进来的已付款订单都计入成交额。
        // from==null（累计）与 from!=null（本月）走不同查询，避免 JPQL 可空时间参数导致 PG 类型推断失败
        BigDecimal sales = from != null
                ? nullSafe(orderRepository.sumSalesByDistributorSince(distId, from))
                : nullSafe(orderRepository.sumSalesByDistributorAll(distId));
        BigDecimal pendingCommission = from != null
                ? nullSafe(commissionRecordRepository.sumByDistributorAndStatusSince(distId, CommissionStatus.PENDING, from))
                : nullSafe(commissionRecordRepository.sumByDistributorAndStatusAll(distId, CommissionStatus.PENDING));
        // 可结算部分：待结算中订单已完成且超过结算延迟期的（可直接申请提现）。区间统计按佣金创建时间同口径过滤，避免"待结算"出现负数
        BigDecimal settlableCommission = from != null
                ? nullSafe(commissionRecordRepository.sumSettlablePendingByDistributorSince(distId, settleCutoff(), from))
                : nullSafe(commissionRecordRepository.sumSettlablePendingByDistributor(distId, settleCutoff()));
        BigDecimal totalCommission = from != null
                ? nullSafe(commissionRecordRepository.sumTotalByDistributorSince(distId, from))
                : nullSafe(commissionRecordRepository.sumTotalByDistributorAll(distId));
        // 已结算佣金固定取全部时段（含已结算、申请中、已提现；历史口径字段保留）
        BigDecimal settledCommission = nullSafe(commissionRecordRepository
                .sumByDistributorAndStatusAll(distId, CommissionStatus.SETTLED))
                .add(nullSafe(commissionRecordRepository
                        .sumByDistributorAndStatusAll(distId, CommissionStatus.WITHDRAWING)))
                .add(nullSafe(commissionRecordRepository
                        .sumByDistributorAndStatusAll(distId, CommissionStatus.WITHDRAWN)));
        // 申请中（在途）佣金：已申请提现、正在审核/转账
        BigDecimal withdrawingCommission = nullSafe(commissionRecordRepository
                .sumByDistributorAndStatusAll(distId, CommissionStatus.WITHDRAWING));
        long subCount = distributorRepository.findByParentId(distId).size();
        long customerCount = customerBindingRepository.countByDistributorId(distId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promotion_product_count", promotionProductCount);
        m.put("total_clicks", totalClicks);
        m.put("total_sales", sales.setScale(2, RoundingMode.HALF_UP));
        // 兼容旧字段 pending_commission（历史前端/脚本），新字段 pending_settlement 与前端对齐
        m.put("pending_commission", pendingCommission.setScale(2, RoundingMode.HALF_UP));
        // 待结算 = 不满足结算期（订单完成未满 N 天）的待结算佣金；扣除可结算部分，最小为 0
        m.put("pending_settlement", pendingCommission.subtract(settlableCommission).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP));
        // 可结算：待结算中订单已完成且超过结算延迟期（可直接勾选提现），与可提现余额相加即总可提现
        m.put("settlable_settlement", settlableCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("settled_commission", settledCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("withdrawing_settlement", withdrawingCommission.setScale(2, RoundingMode.HALF_UP));
        // 可提现余额（展示口径）= 已入账余额（不含可结算，可结算单独字段展示；两者相加即总可提现）
        m.put("available_balance", d.getAvailableBalance().setScale(2, RoundingMode.HALF_UP));
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("subordinate_count", subCount);
        m.put("customer_count", customerCount);
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：分销员管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListDistributors(String status, String keyword, LocalDate from, LocalDate to, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        DistributorStatus statusEnum = parseStatus(status);
        // JPQL：枚举参数由 Hibernate 绑定类型，null 也携带类型，PG 不会报类型推断错误
        Page<Distributor> dp = distributorRepository.findAdminList(statusEnum,
                keyword != null ? keyword : "",
                from != null ? from.atStartOfDay() : RANGE_FROM_MIN,
                to != null ? to.plusDays(1).atStartOfDay() : RANGE_TO_MAX,
                pageable);

        // 批量查用户信息
        Set<UUID> userIds = dp.getContent().stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = dp.getContent().stream().map(d -> {
            User u = userMap.get(d.getUserId());
            Map<String, Object> m = distributorToMap(d, u);
            m.put("applied_at", d.getCreatedAt());
            m.put("customer_count", customerBindingRepository.countByDistributorId(d.getId()));
            m.put("subordinate_count", distributorRepository.findByParentId(d.getId()).size());
            // 成交数据（佣金记录/订单口径，含全店推广与商品推广链接成交）：
            // 总佣金不依赖 Distributor 冗余字段（仅结算时累加，未结算显示 0），改从佣金记录实时聚合
            m.put("total_sales", nullSafe(orderRepository.sumSalesByDistributorAll(d.getId())).setScale(2, RoundingMode.HALF_UP));
            m.put("paid_order_count", orderRepository.countPaidOrdersByDistributorAll(d.getId()));
            m.put("total_commission", nullSafe(commissionRecordRepository.sumTotalByDistributorAll(d.getId())).setScale(2, RoundingMode.HALF_UP));
            m.put("pending_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatusAll(d.getId(), CommissionStatus.PENDING)).setScale(2, RoundingMode.HALF_UP));
            m.put("settled_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatusAll(d.getId(), CommissionStatus.SETTLED)).setScale(2, RoundingMode.HALF_UP));
            return m;
        }).toList();

        return pageResult(items, dp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminGetDistributor(UUID id) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        User user = userRepository.findById(d.getUserId()).orElse(null);
        Map<String, Object> m = distributorToMap(d, user);
        m.put("applied_at", d.getCreatedAt());
        m.put("customer_count", customerBindingRepository.countByDistributorId(d.getId()));
        m.put("subordinate_count", distributorRepository.findByParentId(d.getId()).size());
        // 成交数据（佣金记录/订单口径，含全店推广与商品推广链接成交），与列表口径一致
        m.put("total_sales", nullSafe(orderRepository.sumSalesByDistributorAll(d.getId())).setScale(2, RoundingMode.HALF_UP));
        m.put("paid_order_count", orderRepository.countPaidOrdersByDistributorAll(d.getId()));
        m.put("total_commission", nullSafe(commissionRecordRepository.sumTotalByDistributorAll(d.getId())).setScale(2, RoundingMode.HALF_UP));
        // 待结算 = 待结算总额 − 可结算（订单完成超结算期部分）；可结算单独输出
        BigDecimal pending = nullSafe(commissionRecordRepository.sumByDistributorAndStatusAll(d.getId(), CommissionStatus.PENDING));
        BigDecimal settlable = nullSafe(commissionRecordRepository.sumSettlablePendingByDistributor(d.getId(), settleCutoff()));
        m.put("pending_commission", pending.subtract(settlable).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        m.put("settlable_commission", settlable.setScale(2, RoundingMode.HALF_UP));
        m.put("settled_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatusAll(d.getId(), CommissionStatus.SETTLED)).setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    @Override
    @Transactional
    public void adminUpdateDistributorStatus(UUID id, String status, String reason) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        DistributorStatus newStatus = parseStatus(status);
        if (newStatus == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的状态");
        }
        d.setStatus(newStatus);
        switch (newStatus) {
            case APPROVED -> {
                d.setApprovedAt(LocalDateTime.now());
                d.setRejectReason(null);
                d.setRejectedAt(null);
            }
            case REJECTED -> {
                d.setRejectReason(reason != null && !reason.isBlank() ? reason : null);
                d.setRejectedAt(LocalDateTime.now());
            }
            case DISABLED -> d.setDisabledAt(LocalDateTime.now());
            default -> {}
        }
        distributorRepository.save(d);
        log.info("Distributor {} status updated to {}", id, newStatus);

        User user = userRepository.findById(d.getUserId()).orElse(null);
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("distributor_code", d.getDistributorCode());
            vars.put("status", newStatus.name());
            if (reason != null) vars.put("reason", reason);
            userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                    "DIST_STATUS_" + newStatus.name(), vars);
        } catch (Exception e) {
            log.warn("Failed to send distributor status message: {}", e.getMessage());
        }

        try {
            Map<String, Object> adminVars = new LinkedHashMap<>();
            adminVars.put("distributor_code", d.getDistributorCode());
            adminVars.put("status", newStatus.name());
            notificationService.sendTemplate("DIST_STATUS_CHANGED", adminVars);
        } catch (Exception e) {
            log.warn("Failed to notify admin for distributor status change: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void adminUpdateDistributorRate(UUID id, BigDecimal customRate, BigDecimal subRate) {
        Distributor d = distributorRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        if (customRate != null) {
            d.setCustomRate(percentToRate(customRate));
        }
        if (subRate != null) {
            // 抽成比例不能 >= 100%，否则下级实得 0
            if (subRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "下级抽成比例必须小于 100%");
            }
            if (subRate.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "下级抽成比例不能为负数");
            }
            d.setSubRate(percentToRate(subRate));
        }
        distributorRepository.save(d);
        log.info("Distributor {} rate updated: customRate={}%, subRate={}%", id, customRate, subRate);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：规则配置 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getRules() {
        return ruleToMap(getOrCreateRule());
    }

    @Override
    @Transactional
    public void updateRules(Map<String, Object> request) {
        DistributionRule rule = getOrCreateRule();
        if (request.containsKey("default_rate")) {
            rule.setDefaultRate(toBigDecimal(request.get("default_rate"), rule.getDefaultRate()).setScale(4, RoundingMode.HALF_UP));
        }
        if (request.containsKey("enabled")) {
            rule.setEnabled(toBool(request.get("enabled"), rule.isEnabled()));
        }
        if (request.containsKey("auto_approve")) {
            rule.setAutoApprove(toBool(request.get("auto_approve"), rule.isAutoApprove()));
        }
        if (request.containsKey("min_withdraw_amount")) {
            rule.setMinWithdrawAmount(toBigDecimal(request.get("min_withdraw_amount"), rule.getMinWithdrawAmount()).setScale(2, RoundingMode.HALF_UP));
        }
        if (request.containsKey("settle_delay_days")) {
            rule.setSettleDelayDays(toInt(request.get("settle_delay_days"), rule.getSettleDelayDays()));
        }
        if (request.containsKey("withdraw_fee_rate")) {
            rule.setWithdrawFeeRate(toBigDecimal(request.get("withdraw_fee_rate"), rule.getWithdrawFeeRate()).setScale(4, RoundingMode.HALF_UP));
        }
        if (request.containsKey("binding_protection_days")) {
            rule.setBindingProtectionDays(toInt(request.get("binding_protection_days"), rule.getBindingProtectionDays()));
        }
        if (request.containsKey("tier_enabled")) {
            rule.setTierEnabled(toBool(request.get("tier_enabled"), rule.isTierEnabled()));
        }
        if (request.containsKey("sub_distribution_enabled")) {
            rule.setSubDistributionEnabled(toBool(request.get("sub_distribution_enabled"), rule.isSubDistributionEnabled()));
        }
        if (request.containsKey("default_sub_rate")) {
            rule.setDefaultSubRate(toBigDecimal(request.get("default_sub_rate"), rule.getDefaultSubRate()).setScale(4, RoundingMode.HALF_UP));
        }
        ruleRepository.save(rule);
        log.info("Distribution rules updated");
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：商品佣金 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListProductCommissions(int page, int pageSize, String keyword) {
        // 最新添加的商品排在前面
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String kw = keyword != null && !keyword.isBlank() ? "%" + keyword.trim().toLowerCase() + "%" : null;
        Page<Product> pp = kw == null
                ? productRepository.findAdminProducts(null, null, pageable)
                : productRepository.findAdminProductsByKeyword(null, kw, null, pageable);
        BigDecimal defaultRate = getOrCreateRule().getDefaultRate();
        List<Map<String, Object>> items = pp.getContent().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("cover_url", p.getCoverUrl());
            m.put("base_price", p.getBasePrice());
            m.put("enabled", p.isEnabled());
            ProductCommission pc = productCommissionRepository.findByProductId(p.getId()).orElse(null);
            m.put("commission_set", pc != null);
            m.put("custom_rate", rateToPercent(pc != null ? pc.getCustomRate() : null));
            m.put("excluded", pc != null && pc.isExcluded());
            m.put("default_rate", rateToPercent(defaultRate));
            // 推广成交数据（佣金记录口径，含全店推广与商品推广链接成交）：销售额/佣金/付款订单/推广人数
            List<Object[]> agg = commissionRecordRepository.aggregateByProductAdmin(p.getId());
            BigDecimal sales = toBigDecimal(agg.get(0)[0], BigDecimal.ZERO);
            BigDecimal commission = toBigDecimal(agg.get(0)[1], BigDecimal.ZERO);
            long paid = ((Number) agg.get(0)[2]).longValue();
            long promoters = ((Number) agg.get(0)[3]).longValue();
            // 点击（双源，避免重复）：商品推广链接 clickCount 累计（含历史） + 全店推广链接进店后点击该商品的埋点
            long clicks = ((Number) promotionLinkRepository.aggregateByProduct(p.getId()).get(0)[2]).longValue();
            for (Object[] row : clickRepository.countStoreLinkProductClicksGroupedByDistributor(p.getId())) {
                clicks += ((Number) row[1]).longValue();
            }
            m.put("promotion_sales", sales.setScale(2, RoundingMode.HALF_UP));
            m.put("promotion_commission", commission.setScale(2, RoundingMode.HALF_UP));
            m.put("click_count", clicks);
            m.put("paid_count", paid);
            m.put("promoter_count", promoters);
            m.put("conversion_rate", clicks > 0 ? new BigDecimal(paid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(clicks), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2));
            return m;
        }).toList();
        return pageResult(items, pp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminProductPromoters(UUID productId, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        // 佣金记录口径：含全店推广与商品推广链接成交的推广员排行（按推广销售额倒序）
        Page<Object[]> cp = commissionRecordRepository.aggregatePromotersByProduct(productId, pageable);
        Set<UUID> distIds = cp.getContent().stream().map(row -> (UUID) row[0]).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));
        Map<UUID, User> userMap = distIds.isEmpty() ? Map.of()
                : userRepository.findAllById(distMap.values().stream().map(Distributor::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        // 点击（双源，避免重复）：商品推广链接 clickCount 按推广员聚合（含历史） + 全店推广链接进店后点击该商品的埋点
        Map<UUID, Long> clickMap = new HashMap<>();
        for (Object[] row : promotionLinkRepository.sumClickCountGroupedByDistributor(productId)) {
            clickMap.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        for (Object[] row : clickRepository.countStoreLinkProductClicksGroupedByDistributor(productId)) {
            clickMap.merge((UUID) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        // 初始首次推广时间：该商品最早创建的推广链接时间 与 最早点击埋点时间，取更早者
        Map<UUID, LocalDateTime> promotedAtMap = new HashMap<>();
        for (Object[] row : promotionLinkRepository.minCreatedAtGroupedByDistributor(productId)) {
            promotedAtMap.put((UUID) row[0], (LocalDateTime) row[1]);
        }
        for (Object[] row : clickRepository.minCreatedAtGroupedByDistributor(productId)) {
            promotedAtMap.merge((UUID) row[0], (LocalDateTime) row[1], (a, b) -> a.isBefore(b) ? a : b);
        }

        List<Map<String, Object>> items = cp.getContent().stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            UUID distId = (UUID) row[0];
            BigDecimal sales = toBigDecimal(row[1], BigDecimal.ZERO);
            BigDecimal commission = toBigDecimal(row[2], BigDecimal.ZERO);
            long paid = ((Number) row[3]).longValue();
            long clicks = clickMap.getOrDefault(distId, 0L);
            Distributor d = distMap.get(distId);
            User u = d != null ? userMap.get(d.getUserId()) : null;
            m.put("distributor_id", distId);
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            m.put("username", u != null ? u.getUsername() : null);
            m.put("email", u != null ? u.getEmail() : null);
            m.put("total_sales", sales.setScale(2, RoundingMode.HALF_UP));
            m.put("total_commission", commission.setScale(2, RoundingMode.HALF_UP));
            m.put("click_count", clicks);
            m.put("paid_count", paid);
            m.put("conversion_rate", clicks > 0
                    ? new BigDecimal(paid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(clicks), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2));
            m.put("created_at", promotedAtMap.get(distId));
            return m;
        }).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminProductStats(String range, LocalDate from, LocalDate to) {
        // 区间 [from, to)；为空则不限（全部）
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt = to != null ? to.plusDays(1).atStartOfDay() : null;
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        // 上一周期（环比）：与所选区间等长
        LocalDateTime prevFrom = null;
        LocalDateTime prevTo = null;
        if (fromDt != null && toDt != null) {
            long span = java.time.Duration.between(fromDt, toDt).toDays();
            prevTo = fromDt;
            prevFrom = fromDt.minusDays(span);
        }

        // 分销推广统计（商品推广 + 全店推广，凡是通过分销推广链接成交的数据均计入）
        long clicks = clickRepository.countByRange(fromDt != null ? fromDt : RANGE_FROM_MIN,
                toDt != null ? toDt : RANGE_TO_MAX);
        long paid = orderRepository.countDistributionOrdersRange(fromDt != null ? fromDt : RANGE_FROM_MIN,
                toDt != null ? toDt : RANGE_TO_MAX);
        BigDecimal conversion = clicks > 0
                ? new BigDecimal(paid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(clicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal commission = nullSafe(commissionRecordRepository.sumCommissionAmountBetween(
                fromDt != null ? fromDt : LocalDateTime.of(1970, 1, 1, 0, 0),
                toDt != null ? toDt : now.plusYears(100)));

        long todayClicks = clickRepository.countBetween(todayStart, now);
        long todayPaid = orderRepository.countDistributionOrdersRange(todayStart, now);
        BigDecimal todayConversion = todayClicks > 0
                ? new BigDecimal(todayPaid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(todayClicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal todayCommission = nullSafe(commissionRecordRepository.sumCommissionAmountBetween(todayStart, now));

        long prevClicks = prevFrom != null ? clickRepository.countBetween(prevFrom, prevTo) : 0;
        long prevPaid = prevFrom != null ? orderRepository.countDistributionOrdersRange(prevFrom, prevTo) : 0;
        BigDecimal prevConversion = prevClicks > 0
                ? new BigDecimal(prevPaid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(prevClicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal prevCommission = prevFrom != null
                ? nullSafe(commissionRecordRepository.sumCommissionAmountBetween(prevFrom, prevTo)) : BigDecimal.ZERO;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", range);
        m.put("clicks", statValue(clicks, todayClicks, prevClicks));
        m.put("paid_count", statValue(paid, todayPaid, prevPaid));
        m.put("conversion_rate", statValue(conversion, todayConversion, prevConversion));
        m.put("commission_amount", statValue(commission, todayCommission, prevCommission));
        return m;
    }

    private Map<String, Object> statValue(Object total, Object today, Object prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("today", today);
        m.put("prev", prev);
        return m;
    }

    @Override
    @Transactional
    public void adminUpdateProductCommission(UUID productId, BigDecimal customRate, boolean excluded) {
        productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        ProductCommission pc = productCommissionRepository.findByProductId(productId).orElse(null);
        if (pc == null) {
            pc = new ProductCommission();
            pc.setProductId(productId);
        }
        pc.setCustomRate(percentToRate(customRate));
        pc.setExcluded(excluded);
        productCommissionRepository.save(pc);
        log.info("Product commission updated: productId={}, rate={}%, excluded={}", productId, customRate, excluded);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：佣金记录 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListCommissions(UUID distributorId, String status, LocalDate from, LocalDate to, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        CommissionStatus statusEnum = parseCommissionStatus(status);
        // JPQL：枚举参数由 Hibernate 绑定类型，null 也携带类型，PG 不会报类型推断错误
        Page<CommissionRecord> cp = commissionRecordRepository.findAdminList(distributorId,
                statusEnum,
                from != null ? from.atStartOfDay() : RANGE_FROM_MIN,
                to != null ? to.plusDays(1).atStartOfDay() : RANGE_TO_MAX,
                pageable);

        Set<UUID> distIds = cp.getContent().stream().map(CommissionRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));
        Set<UUID> userIds = distMap.values().stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        // 计算"可结算"状态（PENDING + 订单已完成且超过结算延迟期），并带出结算延迟天数供前端悬浮提示
        enrichSettlable(cp.getContent());
        int delayDays = settleDelayDays();

        List<Map<String, Object>> items = cp.getContent().stream().map(cr -> {
            Map<String, Object> m = commissionToMap(cr);
            m.put("settle_delay_days", delayDays);
            Distributor d = distMap.get(cr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            m.put("distributor_name", d != null && userMap.get(d.getUserId()) != null ? userMap.get(d.getUserId()).getUsername() : null);
            return m;
        }).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminCommissionStats(LocalDate from, LocalDate to) {
        // 区间 [from, to)；为空则统计全量（按佣金创建时间）
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime toDt = to != null ? to.plusDays(1).atStartOfDay() : LocalDateTime.now().plusYears(100);
        List<Distributor> all = distributorRepository.findAll();
        // 总佣金口径：佣金记录实时汇总（不含已取消），按区间佣金创建时间
        BigDecimal totalCommission = nullSafe(commissionRecordRepository
                .sumCommissionAmountBetween(fromDt, toDt));
        BigDecimal pendingTotal = BigDecimal.ZERO;
        BigDecimal settlableTotal = BigDecimal.ZERO;
        BigDecimal settledTotal = BigDecimal.ZERO;
        BigDecimal withdrawingTotal = BigDecimal.ZERO;
        BigDecimal withdrawnTotal = BigDecimal.ZERO;
        BigDecimal cancelledTotal = BigDecimal.ZERO;
        LocalDateTime cutoff = settleCutoff();

        for (Distributor d : all) {
            BigDecimal pending = nullSafe(commissionRecordRepository.sumByDistributorAndStatusBetween(d.getId(), CommissionStatus.PENDING, fromDt, toDt));
            pendingTotal = pendingTotal.add(pending);
            // 可结算部分：待结算中订单已完成且超过结算延迟期的（可提前申请提现）
            BigDecimal settlable = nullSafe(commissionRecordRepository
                    .sumSettlablePendingByDistributorBetween(d.getId(), cutoff, fromDt, toDt));
            settlableTotal = settlableTotal.add(settlable);
            // 已结算（纯已入账未提现）/ 申请中（已提交提现待审核打款）/ 已提现（打款成功）分开统计
            settledTotal = settledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatusBetween(d.getId(), CommissionStatus.SETTLED, fromDt, toDt)));
            withdrawingTotal = withdrawingTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatusBetween(d.getId(), CommissionStatus.WITHDRAWING, fromDt, toDt)));
            withdrawnTotal = withdrawnTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatusBetween(d.getId(), CommissionStatus.WITHDRAWN, fromDt, toDt)));
            cancelledTotal = cancelledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatusBetween(d.getId(), CommissionStatus.CANCELLED, fromDt, toDt)));
        }

        // 今日佣金（不含已取消，按佣金创建时间）
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        BigDecimal todayTotal = nullSafe(commissionRecordRepository
                .sumCommissionAmountBetween(todayStart, todayStart.plusDays(1)));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        // 待结算 = 不满足结算期（扣除可结算部分）
        m.put("pending_commission", pendingTotal.subtract(settlableTotal).setScale(2, RoundingMode.HALF_UP));
        m.put("settlable_commission", settlableTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("settled_commission", settledTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("withdrawing_commission", withdrawingTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("withdrawn_commission", withdrawnTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("cancelled_commission", cancelledTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("today_commission", todayTotal.setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：提现管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminListWithdrawals(String status, LocalDate from, LocalDate to, int page, int pageSize) {
        Pageable pageable = toPageable(page, pageSize);
        WithdrawalStatus statusEnum = parseWithdrawalStatus(status);
        // JPQL：枚举参数由 Hibernate 绑定类型，null 也携带类型，PG 不会报类型推断错误
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findAdminList(statusEnum,
                from != null ? from.atStartOfDay() : RANGE_FROM_MIN,
                to != null ? to.plusDays(1).atStartOfDay() : RANGE_TO_MAX,
                pageable);

        Set<UUID> distIds = wp.getContent().stream().map(WithdrawalRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));
        Set<UUID> userIds = distMap.values().stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = wp.getContent().stream().map(wr -> {
            Map<String, Object> m = withdrawalToMap(wr);
            Distributor d = distMap.get(wr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            m.put("distributor_name", d != null && userMap.get(d.getUserId()) != null ? userMap.get(d.getUserId()).getUsername() : null);
            // 收款账户：微信昵称 + 脱敏 openid（转账到零钱收款账号）
            m.put("account_info", buildAccountInfo(d));
            // 关联的佣金明细条数（订单级提现：每条明细对应一笔佣金记录）
            m.put("item_count", commissionRecordRepository.findByWithdrawalId(wr.getId()).size());
            return m;
        }).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> adminGetWithdrawalItems(UUID withdrawalId) {
        return commissionRecordRepository.findByWithdrawalId(withdrawalId).stream().map(cr -> {
            Map<String, Object> m = commissionToMap(cr);
            m.put("withdrawal_status", cr.getStatus().name());
            return m;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminWithdrawalStats(LocalDate from, LocalDate to) {
        // 区间 [from, to)；为空则统计全量（1970 → 现在+100年）
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime toDt = to != null ? to.plusDays(1).atStartOfDay() : LocalDateTime.now().plusYears(100);
        BigDecimal totalSales = nullSafe(orderRepository.sumDistributionSales(fromDt, toDt));
        BigDecimal totalCommission = nullSafe(commissionRecordRepository.sumCommissionAmountBetween(fromDt, toDt));
        // 提现单状态口径：按提现单申请金额分状态统计（区间按提现申请创建时间）
        BigDecimal pendingWithdrawal = nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.PENDING, fromDt, toDt));
        BigDecimal approvedWithdrawal = nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.APPROVED, fromDt, toDt))
                .add(nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.PROCESSING, fromDt, toDt)));
        BigDecimal successWithdrawal = nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.SUCCESS, fromDt, toDt));
        BigDecimal rejectedWithdrawal = nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.REJECTED, fromDt, toDt))
                .add(nullSafe(withdrawalRecordRepository.sumAmountByStatusBetween(WithdrawalStatus.FAILED, fromDt, toDt)));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_sales", totalSales.setScale(2, RoundingMode.HALF_UP));
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_withdrawal", pendingWithdrawal.setScale(2, RoundingMode.HALF_UP));
        m.put("approved_withdrawal", approvedWithdrawal.setScale(2, RoundingMode.HALF_UP));
        m.put("success_withdrawal", successWithdrawal.setScale(2, RoundingMode.HALF_UP));
        m.put("rejected_withdrawal", rejectedWithdrawal.setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    @Override
    @Transactional
    public void adminApproveWithdrawal(UUID id) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        if (wr.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅待审核的提现可审批");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        wr.setStatus(WithdrawalStatus.APPROVED);
        wr.setApprovedAt(LocalDateTime.now());

        BigDecimal amount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();

        // 尝试发起微信商家转账到零钱（需分销员已绑定 openid + 已配置 native_wxpay 渠道）
        boolean transferred = tryWxpayTransfer(wr, d, amount);

        if (!transferred) {
            // 手动打款模式：审批通过后保持冻结，等管理员线下支付后点击"手动结算"才扣余额
            log.info("Withdrawal {} approved (manual), distributor {} frozen = {}",
                    id, d.getId(), d.getFrozenBalance());
        } else {
            // 微信转账已发起：冻结金额保持不变，等微信回调确认转账成功后再转为已提现
            log.info("Withdrawal {} approved (wxpay transfer initiated), distributor {} frozen = {}",
                    id, d.getId(), d.getFrozenBalance());
        }

        withdrawalRecordRepository.save(wr);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", wr.getAmount());
            vars.put("actual_amount", wr.getActualAmount());
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_APPROVED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal approved message: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void adminManualSettle(UUID id, BigDecimal actualAmount) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        // 仅 APPROVED（审批通过待打款）或 PROCESSING（微信转账中但需手动兜底）可手动结算
        if (wr.getStatus() != WithdrawalStatus.APPROVED && wr.getStatus() != WithdrawalStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅已通过或转账中的提现可手动结算");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        // 实际结算金额：优先用传入的，其次用记录的实到金额，最后用提现金额
        BigDecimal settleAmount = (actualAmount != null && actualAmount.compareTo(BigDecimal.ZERO) > 0)
                ? actualAmount
                : (wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount());

        // 从冻结余额扣减，转入已提现
        d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
        d.setWithdrawnAmount(d.getWithdrawnAmount().add(settleAmount));
        distributorRepository.save(d);

        // 关联佣金记录 → 已提现
        for (CommissionRecord cr : commissionRecordRepository.findByWithdrawalId(wr.getId())) {
            if (cr.getStatus() == CommissionStatus.WITHDRAWING) {
                cr.setStatus(CommissionStatus.WITHDRAWN);
                commissionRecordRepository.save(cr);
            }
        }

        // 更新提现记录
        wr.setActualAmount(settleAmount);
        wr.setStatus(WithdrawalStatus.SUCCESS);
        wr.setTransferredAt(LocalDateTime.now());
        wr.setCompletedAt(LocalDateTime.now());
        withdrawalRecordRepository.save(wr);

        log.info("Withdrawal {} manually settled, distributor {} frozen -= {}, withdrawn += {}",
                id, d.getId(), wr.getAmount(), settleAmount);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", settleAmount);
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_SUCCESS", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal success message: {}", e.getMessage());
        }
    }

    /**
     * 尝试发起微信商家转账到零钱。
     * 条件：分销员已绑定 openid + 存在已启用的 native_wxpay 渠道。
     * 成功：状态改为 PROCESSING，保存 outBillNo/transferBillNo/packageInfo，返回 true。
     * 失败：保持 APPROVED 状态，记录失败原因，返回 false（走手动打款模式）。
     */
    private boolean tryWxpayTransfer(WithdrawalRecord wr, Distributor d, BigDecimal amount) {
        if (d.getWechatOpenid() == null || d.getWechatOpenid().isBlank()) {
            log.info("Distributor {} has no wechat openid, skip wxpay transfer", d.getId());
            return false;
        }

        // 查找已启用的 native_wxpay 渠道
        List<PaymentChannel> wxpayChannels = paymentChannelRepository
                .findByProviderTypeAndIsDeleted("native_wxpay", 0);
        if (wxpayChannels.isEmpty()) {
            log.info("No native_wxpay channel configured, skip wxpay transfer");
            return false;
        }
        PaymentChannel channel = wxpayChannels.stream()
                .filter(PaymentChannel::isEnabled)
                .findFirst()
                .orElse(null);
        if (channel == null) {
            log.info("No enabled native_wxpay channel, skip wxpay transfer");
            return false;
        }

        try {
            WxpayConfig config = paymentServiceImpl.buildWxpayConfig(channel);
            // 复用原商户单号（微信支持失败后原单号重试，如运营账户资金不足场景；换新单号有重复打款风险）
            String outBillNo = wr.getOutBillNo();
            if (outBillNo == null || outBillNo.isBlank()) {
                outBillNo = "WD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            }
            // 先落单号：即便本次调用失败（如资金不足），也保留单号供充值后原单号重试
            wr.setOutBillNo(outBillNo);
            String transferNotifyUrl = baseUrl.replaceAll("/+$", "") + "/api/payments/webhook/wxpay-transfer";

            WxpayTransferResult result = wxpayService.createTransfer(
                    config, outBillNo, d.getWechatOpenid(), amount, "佣金提现", transferNotifyUrl);

            wr.setTransferBillNo(result.transferBillNo());
            wr.setPackageInfo(result.packageInfo());
            wr.setStatus(WithdrawalStatus.PROCESSING);
            wr.setTransferredAt(LocalDateTime.now());
            log.info("Wxpay transfer initiated: withdrawal={}, outBillNo={}, state={}",
                    wr.getId(), outBillNo, result.state());
            return true;
        } catch (Exception e) {
            log.warn("Wxpay transfer failed for withdrawal {}, fallback to manual: {}", wr.getId(), e.getMessage());
            wr.setFailReason("微信转账失败：" + e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public void adminRejectWithdrawal(UUID id, String reason) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        if (wr.getStatus() != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅待审核的提现可拒绝");
        }
        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));

        wr.setStatus(WithdrawalStatus.REJECTED);
        wr.setFailReason(reason);
        withdrawalRecordRepository.save(wr);

        // 退回冻结金额到可用余额
        BigDecimal amount = wr.getAmount();
        d.setFrozenBalance(d.getFrozenBalance().subtract(amount));
        d.setAvailableBalance(d.getAvailableBalance().add(amount));
        distributorRepository.save(d);

        // 关联佣金记录 → 结算拒绝（可重新勾选提现）
        for (CommissionRecord cr : commissionRecordRepository.findByWithdrawalId(wr.getId())) {
            if (cr.getStatus() == CommissionStatus.WITHDRAWING) {
                cr.setStatus(CommissionStatus.REJECTED);
                commissionRecordRepository.save(cr);
            }
        }
        log.info("Withdrawal {} rejected, distributor {} available += {}, commission records -> REJECTED",
                id, d.getId(), amount);

        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", amount);
            vars.put("reason", reason);
            userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_REJECTED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal rejected message: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 管理后台：统计 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminGetOverviewStats(String range, LocalDate from, LocalDate to) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime[] cur = resolveRange(range, from, to);
        LocalDateTime[] prev = prevRange(range, from, to);

        BigDecimal curSales = cur == null ? BigDecimal.ZERO : orderRepository.sumDistributionSales(cur[0], cur[1]);
        BigDecimal todaySales = orderRepository.sumDistributionSales(todayStart, todayEnd);
        BigDecimal prevSales = prev == null ? BigDecimal.ZERO : orderRepository.sumDistributionSales(prev[0], prev[1]);

        BigDecimal curCommission = cur == null ? BigDecimal.ZERO : commissionRecordRepository.sumCommissionAmountBetween(cur[0], cur[1]);
        BigDecimal todayCommission = commissionRecordRepository.sumCommissionAmountBetween(todayStart, todayEnd);
        BigDecimal prevCommission = prev == null ? BigDecimal.ZERO : commissionRecordRepository.sumCommissionAmountBetween(prev[0], prev[1]);

        BigDecimal curWithdrawn = cur == null ? BigDecimal.ZERO : withdrawalRecordRepository.sumAmountBetween(cur[0], cur[1]);
        BigDecimal todayWithdrawn = withdrawalRecordRepository.sumAmountBetween(todayStart, todayEnd);
        BigDecimal prevWithdrawn = prev == null ? BigDecimal.ZERO : withdrawalRecordRepository.sumAmountBetween(prev[0], prev[1]);

        List<Distributor> all = distributorRepository.findAll();
        long curDistributors = cur == null ? all.size() : countCreatedBetween(all, cur[0], cur[1]);
        long todayNewDistributors = countCreatedBetween(all, todayStart, todayEnd);
        long prevDistributors = prev == null ? 0 : countCreatedBetween(all, prev[0], prev[1]);

        long curPending = cur == null
                ? distributorRepository.countByStatus(DistributorStatus.PENDING)
                : all.stream().filter(d -> d.getStatus() == DistributorStatus.PENDING
                        && d.getCreatedAt() != null && isBetween(d.getCreatedAt(), cur[0], cur[1])).count();
        long todayPending = all.stream().filter(d -> d.getStatus() == DistributorStatus.PENDING
                && d.getCreatedAt() != null && isBetween(d.getCreatedAt(), todayStart, todayEnd)).count();
        long prevPending = prev == null ? 0 : all.stream().filter(d -> d.getStatus() == DistributorStatus.PENDING
                && d.getCreatedAt() != null && isBetween(d.getCreatedAt(), prev[0], prev[1])).count();

        // 存量（当前值）
        BigDecimal availableBalance = BigDecimal.ZERO;
        BigDecimal frozenBalance = BigDecimal.ZERO;
        for (Distributor d : all) {
            availableBalance = availableBalance.add(nullSafe(d.getAvailableBalance()));
            frozenBalance = frozenBalance.add(nullSafe(d.getFrozenBalance()));
        }
        BigDecimal curPendingCommission = cur == null
                ? all.stream().map(d -> nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING.name())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : commissionRecordRepository.sumPendingBetween(cur[0], cur[1]);
        BigDecimal todayPendingCommission = commissionRecordRepository.sumPendingBetween(todayStart, todayEnd);
        BigDecimal prevPendingCommission = prev == null ? BigDecimal.ZERO : commissionRecordRepository.sumPendingBetween(prev[0], prev[1]);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", range != null ? range : "all");
        m.put("from", cur != null ? cur[0].toLocalDate().toString() : null);
        m.put("to", cur != null ? cur[1].minusNanos(1).toLocalDate().toString() : null);

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("total_sales", card(curSales, todaySales, prevSales, true));
        cards.put("total_distributors", card(BigDecimal.valueOf(curDistributors), BigDecimal.valueOf(todayNewDistributors), BigDecimal.valueOf(prevDistributors), false));
        cards.put("pending_count", card(BigDecimal.valueOf(curPending), BigDecimal.valueOf(todayPending), BigDecimal.valueOf(prevPending), false));
        cards.put("total_commission", card(curCommission, todayCommission, prevCommission, true));
        cards.put("pending_settlement", card(curPendingCommission, todayPendingCommission, prevPendingCommission, true));
        cards.put("available_balance", card(availableBalance, BigDecimal.ZERO, BigDecimal.ZERO, true));
        cards.put("frozen_balance", card(frozenBalance, BigDecimal.ZERO, BigDecimal.ZERO, true));
        cards.put("withdrawn_amount", card(curWithdrawn, todayWithdrawn, prevWithdrawn, true));
        m.put("cards", cards);
        m.put("today_sales", todaySales.setScale(2, RoundingMode.HALF_UP));
        m.put("today_commission", todayCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("today_new_distributors", todayNewDistributors);
        return m;
    }

    /** 汇总卡片：主值 + 今日值 + 环比基值（money=true 金额格式化，false 计数） */
    private Map<String, Object> card(BigDecimal value, BigDecimal today, BigDecimal prev, boolean money) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("value", value.setScale(2, RoundingMode.HALF_UP));
        c.put("today", today.setScale(2, RoundingMode.HALF_UP));
        c.put("prev", prev.setScale(2, RoundingMode.HALF_UP));
        c.put("money", money);
        return c;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminRecentDistributionOrders(LocalDate from, LocalDate to, int limit) {
        LocalDate today = LocalDate.now();
        LocalDate f = from != null ? from : today.minusDays(30);
        LocalDate t = to != null ? to : today;
        List<Order> orders = orderRepository.findDistributionOrders(
                f.atStartOfDay(), t.plusDays(1).atStartOfDay(), PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                .getContent();

        // 批量加载关联数据
        Set<UUID> linkIds = orders.stream().map(Order::getPromotionLinkId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, PromotionLink> linkMap = linkIds.isEmpty() ? Map.of()
                : promotionLinkRepository.findAllById(linkIds).stream().collect(Collectors.toMap(PromotionLink::getId, l -> l));
        Set<UUID> distIds = linkMap.values().stream().map(PromotionLink::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));
        Set<UUID> distUserIds = distMap.values().stream().map(Distributor::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> distUserMap = distUserIds.isEmpty() ? Map.of()
                : userRepository.findAllById(distUserIds).stream().collect(Collectors.toMap(User::getId, u -> u));
        Set<UUID> userIds = orders.stream().map(Order::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Order o : orders) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("order_id", o.getId());
            it.put("paid_at", o.getPaidAt());
            it.put("status", o.getStatus().name());
            it.put("amount", o.getActualAmount());
            // 商品（首个订单项）
            List<OrderItem> ois = orderItemRepository.findByOrderId(o.getId());
            it.put("product_title", ois.isEmpty() ? "—" : ois.get(0).getProductTitle());
            it.put("quantity", ois.isEmpty() ? 0 : ois.stream().mapToInt(OrderItem::getQuantity).sum());
            // 分销人：显示用户名（无则邮箱，再兜底推广员编号）
            PromotionLink pl = o.getPromotionLinkId() != null ? linkMap.get(o.getPromotionLinkId()) : null;
            Distributor dist = pl != null ? distMap.get(pl.getDistributorId()) : null;
            User du = dist != null && dist.getUserId() != null ? distUserMap.get(dist.getUserId()) : null;
            it.put("distributor_name", du != null
                    ? (du.getUsername() != null && !du.getUsername().isBlank() ? du.getUsername() : du.getEmail())
                    : (dist != null ? dist.getDistributorCode() : "—"));
            it.put("distributor_code", dist != null ? dist.getDistributorCode() : null);
            // 客户
            User buyer = o.getUserId() != null ? userMap.get(o.getUserId()) : null;
            it.put("customer", buyer != null ? buyer.getUsername() : (o.getEmail() != null ? o.getEmail() : "—"));
            // 佣金合计
            BigDecimal commission = commissionRecordRepository.findByOrderId(o.getId()).stream()
                    .filter(cr -> cr.getStatus() != CommissionStatus.CANCELLED)
                    .map(cr -> nullSafe(cr.getCommissionAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            it.put("commission", commission.setScale(2, RoundingMode.HALF_UP));
            items.add(it);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", items);
        m.put("total", items.size());
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：推广商品 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listPromotionProducts(int page, int pageSize) {
        DistributionRule rule = getOrCreateRule();
        // 分销员可推广商品：仅展示已开启分销（存在 product_commission 且未排除）的商品，与首页显示相互独立
        List<Product> all = productRepository.findEnabledProducts(Pageable.unpaged()).getContent();
        // 仅展示已开启分销（存在 product_commission 且未排除）的商品，最新添加的排在前面
        List<Product> distributable = all.stream()
                .filter(p -> isProductDistributable(p.getId()))
                .sorted(Comparator.comparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> items = paginate(distributable, page, pageSize).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("cover_url", p.getCoverUrl());
            m.put("base_price", p.getBasePrice());
            m.put("default_rate", rateToPercent(rule.getDefaultRate()));
            ProductCommission pc = productCommissionRepository.findByProductId(p.getId()).orElse(null);
            m.put("excluded", false);
            m.put("custom_rate", rateToPercent(pc != null ? pc.getCustomRate() : null));
            BigDecimal rate = pc != null && pc.getCustomRate() != null ? pc.getCustomRate() : rule.getDefaultRate();
            // 预计佣金金额 = 商品基础价 × 最高佣金比例
            m.put("commission_rate", rateToPercent(rate));
            m.put("commission_amount", p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
            return m;
        }).toList();
        return pageResult(items, distributable.size(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyPromotionProducts(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        UUID distId = d.getId();

        // 商品点击数（双源，避免重复）：商品推广链接 clickCount 累计（含历史） + 全店推广链接进店后点击该商品的埋点
        Map<UUID, Long> clicksByProduct = new HashMap<>();
        for (PromotionLink pl : promotionLinkRepository.findByDistributorId(distId, Pageable.unpaged()).getContent()) {
            if (pl.getProductId() != null) {
                clicksByProduct.merge(pl.getProductId(), (long) pl.getClickCount(), Long::sum);
            }
        }
        for (Object[] row : clickRepository.countStoreLinkProductClicksGroupedByProductForDistributor(distId)) {
            clicksByProduct.merge((UUID) row[0], ((Number) row[1]).longValue(), Long::sum);
        }

        // 佣金记录按商品聚合（权威来源：商品链接与全店推广链接进来的成交/佣金都计入对应商品）
        Map<UUID, long[]> aggByProduct = new HashMap<>(); // productId -> [成交订单数(去重), 佣金(分)]
        for (Object[] row : commissionRecordRepository.aggregateCommissionByProduct(distId)) {
            UUID pid = (UUID) row[0];
            long orders = ((Number) row[2]).longValue();
            BigDecimal comm = (BigDecimal) row[1];
            aggByProduct.put(pid, new long[]{orders, comm != null ? comm.movePointRight(2).longValue() : 0L});
        }

        Set<UUID> productIds = new LinkedHashSet<>();
        productIds.addAll(aggByProduct.keySet());
        productIds.addAll(clicksByProduct.keySet());

        List<Map<String, Object>> all = productIds.stream()
                .map(pid -> {
                    Product p = productRepository.findById(pid).orElse(null);
                    if (p == null) return null;
                    long[] agg = aggByProduct.get(pid);
                    long paid = agg != null ? agg[0] : 0L;
                    BigDecimal commission = agg != null
                            ? BigDecimal.valueOf(agg[1], 2)
                            : BigDecimal.ZERO;
                    PromotionLink pl = promotionLinkRepository
                            .findByDistributorIdAndProductId(distId, pid).orElse(null);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("link_id", pl != null ? pl.getId() : null);
                    m.put("link_code", pl != null ? pl.getLinkCode() : null);
                    m.put("product_id", pid);
                    m.put("click_count", clicksByProduct.getOrDefault(pid, 0L));
                    m.put("paid_count", paid);
                    m.put("total_commission", commission);
                    m.put("product_title", p.getTitle());
                    m.put("cover_url", p.getCoverUrl());
                    m.put("base_price", p.getBasePrice());
                    DistributionRule r = getOrCreateRule();
                    ProductCommission pc = productCommissionRepository.findByProductId(pid).orElse(null);
                    BigDecimal rate = pc != null && pc.getCustomRate() != null ? pc.getCustomRate() : r.getDefaultRate();
                    m.put("default_rate", rateToPercent(r.getDefaultRate()));
                    m.put("custom_rate", rateToPercent(pc != null ? pc.getCustomRate() : null));
                    m.put("commission_rate", rateToPercent(rate));
                    m.put("commission_amount", p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
                    return m;
                })
                .filter(Objects::nonNull)
                // 按佣金总额降序，其次点击降序
                .sorted(Comparator
                        .comparing((Map<String, Object> mm) -> (BigDecimal) mm.get("total_commission"),
                                Comparator.reverseOrder())
                        .thenComparing((Map<String, Object> mm) -> (Long) mm.get("click_count"),
                                Comparator.reverseOrder()))
                .toList();
        List<Map<String, Object>> items = paginate(all, page, pageSize);
        return pageResult(items, all.size(), page, pageSize);
    }

    @Override
    @Transactional
    public Map<String, Object> generatePromotionLink(UUID userId, UUID productId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        if (!isProductDistributable(productId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品未开启分销，无法生成推广链接");
        }
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), productId).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(productId);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Promotion link created: distributor={}, product={}, code={}", d.getId(), productId, link.getLinkCode());
        }
        return linkToMap(link);
    }

    @Override
    @Transactional
    public Map<String, Object> generateStoreLink(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), null).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(null);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Store promotion link created: distributor={}, code={}", d.getId(), link.getLinkCode());
        }
        return linkToMap(link);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyLinks(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<PromotionLink> lp = promotionLinkRepository.findByDistributorId(d.getId(), pageable);
        List<Map<String, Object>> items = lp.getContent().stream().map(this::linkToMap).toList();
        return pageResult(items, lp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getStoreStats(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        // 店铺汇总 = 全店推广链接 + 商品推广链接合计（只要通过我的任意推广链接进来的都计入）
        List<PromotionLink> links = promotionLinkRepository
                .findByDistributorId(d.getId(), Pageable.unpaged()).getContent();
        PromotionLink storeLink = links.stream()
                .filter(l -> l.getProductId() == null).findFirst().orElse(null);
        long clicks = 0L, uniqueClicks = 0L, paid = 0L;
        BigDecimal sales = BigDecimal.ZERO, commission = BigDecimal.ZERO;
        for (PromotionLink pl : links) {
            clicks += pl.getClickCount();
            uniqueClicks += pl.getUniqueClickCount();
            paid += pl.getPaidCount();
            sales = sales.add(nullSafe(pl.getTotalSales()));
            commission = commission.add(nullSafe(pl.getTotalCommission()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exists", storeLink != null);
        m.put("link_code", storeLink != null ? storeLink.getLinkCode() : null);
        m.put("click_count", clicks);
        m.put("unique_click_count", uniqueClicks);
        m.put("paid_count", paid);
        m.put("conversion_rate", clicks > 0
                ? new BigDecimal(paid).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(clicks), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2));
        m.put("total_sales", sales.setScale(2, RoundingMode.HALF_UP));
        m.put("total_commission", commission.setScale(2, RoundingMode.HALF_UP));
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：客户邀请码绑定（注册 / 个人中心补填） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> bindCustomerByInviteCode(UUID userId, String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入邀请码");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        String email = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase();
        if (email.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "您的账号未绑定邮箱，无法绑定推广员");
        }
        Distributor distributor = distributorRepository.findByInviteCode(inviteCode.trim()).orElse(null);
        if (distributor == null || distributor.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请码无效或推广员未开通");
        }
        // 已绑定该推广员 → 幂等返回
        Optional<CustomerBinding> existing =
                customerBindingRepository.findByCustomerEmailAndDistributorId(email, distributor.getId());
        if (existing.isPresent()) {
            return customerBindingToMap(existing.get(), distributor);
        }
        // 保护期内已绑定其他推广员 → 不允许更换
        CustomerBinding active = customerBindingRepository.findActiveBindingByEmail(email).orElse(null);
        if (active != null && !active.getDistributorId().equals(distributor.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "您已绑定其他推广员（保护期内），暂无法更换推广员");
        }
        DistributionRule rule = getOrCreateRule();
        CustomerBinding binding = new CustomerBinding();
        binding.setDistributorId(distributor.getId());
        binding.setCustomerUserId(userId);
        binding.setCustomerEmail(email);
        binding.setProductId(null);
        binding.setPromotionLinkId(null);
        binding.setProtectionExpiresAt(LocalDateTime.now().plusDays(rule.getBindingProtectionDays()));
        binding.setPurchaseCount(0);
        customerBindingRepository.save(binding);
        log.info("Customer {} bound to distributor {} by invite code {}", email, distributor.getId(), inviteCode.trim());
        return customerBindingToMap(binding, distributor);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCustomerBinding(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return null;
        CustomerBinding cb = customerBindingRepository
                .findActiveBindingByEmail(user.getEmail().trim().toLowerCase()).orElse(null);
        if (cb == null) return null;
        Distributor d = distributorRepository.findById(cb.getDistributorId()).orElse(null);
        if (d == null) return null;
        return customerBindingToMap(cb, d);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getCustomerBindingHistory(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return Map.of("bindings", List.of());
        }
        String email = user.getEmail().trim().toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        // 该客户全部绑定记录，最新在前（客户可换绑，旧记录保留作为历史）
        List<CustomerBinding> all = customerBindingRepository.findAll().stream()
                .filter(cb -> cb.getCustomerEmail() != null
                        && email.equals(cb.getCustomerEmail().trim().toLowerCase()))
                .sorted(Comparator.comparing(CustomerBinding::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            CustomerBinding cb = all.get(i);
            Distributor d = distributorRepository.findById(cb.getDistributorId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("distributor_id", cb.getDistributorId());
            User du = d != null && d.getUserId() != null
                    ? userRepository.findById(d.getUserId()).orElse(null) : null;
            m.put("distributor_name", du != null ? du.getUsername() : null);
            m.put("invite_code", d != null ? d.getInviteCode() : null);
            m.put("bound_at", cb.getCreatedAt());
            m.put("protection_expires_at", cb.getProtectionExpiresAt());
            // 解绑时间：最新一条保护期未过 → 生效中；保护期已过 → 保护期截止；
            // 历史绑定被更新的绑定取代 → 解绑时间为下一条（更新的）绑定创建时间
            LocalDateTime end;
            boolean active = false;
            if (i == 0) {
                if (cb.getProtectionExpiresAt() != null && cb.getProtectionExpiresAt().isAfter(now)) {
                    end = null;
                    active = true;
                } else {
                    end = cb.getProtectionExpiresAt();
                }
            } else {
                end = all.get(i - 1).getCreatedAt();
            }
            m.put("unbound_at", end);
            m.put("active", active);
            list.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bindings", list);
        return result;
    }

    private Map<String, Object> customerBindingToMap(CustomerBinding cb, Distributor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bound", true);
        m.put("distributor_id", cb.getDistributorId());
        m.put("bound_at", cb.getCreatedAt());
        m.put("protection_expires_at", cb.getProtectionExpiresAt());
        m.put("invite_code", d.getInviteCode());
        User du = userRepository.findById(d.getUserId()).orElse(null);
        m.put("distributor_name", du != null ? du.getUsername() : null);
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：推广海报（返回海报绘制所需数据，前端 canvas 合成） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> generateProductPoster(UUID userId, UUID productId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        if (!isProductDistributable(productId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品未开启分销，无法生成海报");
        }
        Product p = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        // 复用推广链接：海报二维码必须可识别
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), productId).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(productId);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Promotion link auto-created for poster: distributor={}, product={}", d.getId(), productId);
        }
        DistributionRule rule = getOrCreateRule();
        ProductCommission pc = productCommissionRepository.findByProductId(productId).orElse(null);
        BigDecimal rate = pc != null && pc.getCustomRate() != null ? pc.getCustomRate() : rule.getDefaultRate();
        String linkUrl = buildPromotionUrl(link.getLinkCode());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_id", p.getId());
        m.put("product_title", p.getTitle());
        m.put("cover_url", p.getCoverUrl());
        m.put("base_price", p.getBasePrice());
        m.put("commission_rate", rateToPercent(rate));
        m.put("commission_amount", p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
        m.put("link_url", linkUrl);
        m.put("qr_url", buildQrUrl(linkUrl));
        m.put("distributor_code", d.getDistributorCode());
        m.put("store_name", siteName());
        m.put("store_logo", siteLogo());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> generateStorePoster(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        PromotionLink link = promotionLinkRepository.findByDistributorIdAndProductId(d.getId(), null).orElse(null);
        if (link == null) {
            link = new PromotionLink();
            link.setDistributorId(d.getId());
            link.setProductId(null);
            link.setLinkCode(generateUniqueLinkCode());
            link.setClickCount(0);
            link.setUniqueClickCount(0);
            link.setPaidCount(0);
            link.setTotalSales(BigDecimal.ZERO);
            link.setTotalCommission(BigDecimal.ZERO);
            promotionLinkRepository.save(link);
            log.info("Store promotion link auto-created for poster: distributor={}", d.getId());
        }
        String linkUrl = buildPromotionUrl(link.getLinkCode());
        DistributionRule rule = getOrCreateRule();

        // 全店海报热销商品：已开启分销的商品取前 3 个（最新添加优先；分销侧不受首页显示限制）
        List<Map<String, Object>> hotProducts = productRepository.findEnabledProducts(Pageable.unpaged()).getContent().stream()
                .filter(p -> isProductDistributable(p.getId()))
                .sorted(Comparator.comparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(3)
                .map(p -> {
                    Map<String, Object> hp = new LinkedHashMap<>();
                    hp.put("product_id", p.getId());
                    hp.put("product_title", p.getTitle());
                    hp.put("cover_url", p.getCoverUrl());
                    hp.put("base_price", p.getBasePrice());
                    return hp;
                })
                .toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("link_url", linkUrl);
        m.put("qr_url", buildQrUrl(linkUrl));
        m.put("store_name", siteName());
        m.put("store_logo", siteLogo());
        m.put("hot_products", hotProducts);
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：微信绑定（提现到微信零钱收款） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getWechatBindUrl(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        WechatOauthConfig cfg = findWechatOauthConfig();
        if (cfg == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "微信支付渠道未配置公众号 AppSecret，无法绑定微信（请在支付渠道配置中补充）");
        }
        String callbackUrl = trimTrailingSlash(baseUrl) + "/my/distribution/bind-wechat/callback";
        String redirect = URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);
        String bindUrl = "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + cfg.appid()
                + "&redirect_uri=" + redirect
                + "&response_type=code&scope=snsapi_base"
                + "&state=" + d.getId() + "#wechat_redirect";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bind_url", bindUrl);
        m.put("appid", cfg.appid());
        m.put("callback_url", callbackUrl);
        m.put("wechat_bound", d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> wechatCallback(String code, String state) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信授权失败：缺少 code");
        }
        Distributor d = distributorRepository.findById(UUID.fromString(state))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        WechatOauthConfig cfg = findWechatOauthConfig();
        if (cfg == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "微信支付渠道未配置公众号 AppSecret，无法绑定微信");
        }
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + cfg.appid()
                + "&secret=" + cfg.secret() + "&code=" + code + "&grant_type=authorization_code";
        try {
            String body = restTemplate.getForObject(url, String.class);
            String openid = extractJsonField(body, "openid");
            String errMsg = extractJsonField(body, "errmsg");
            if (openid == null || openid.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "微信授权失败：" + (errMsg != null ? errMsg : "无效的授权码"));
            }
            d.setWechatOpenid(openid);
            String nickname = extractJsonField(body, "nickname");
            if (nickname != null && !nickname.isBlank()) {
                d.setWechatNickname(nickname);
            }
            d.setWechatBoundAt(LocalDateTime.now());
            distributorRepository.save(d);
            log.info("Distributor {} bound wechat openid={}", d.getId(), openid);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Wechat oauth failed for state={}", state, e);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信授权失败，请重试");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wechat_bound", true);
        m.put("openid", d.getWechatOpenid());
        return m;
    }

    @Override
    @Transactional
    public void unbindWechat(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        d.setWechatOpenid(null);
        d.setWechatUnionid(null);
        d.setWechatNickname(null);
        d.setWechatBoundAt(null);
        distributorRepository.save(d);
        log.info("Distributor {} unbound wechat", d.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> wechatStatus(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        boolean bound = d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wechat_bound", bound);
        m.put("openid", bound ? maskOpenid(d.getWechatOpenid()) : null);
        m.put("nickname", d.getWechatNickname());
        m.put("bound_at", d.getWechatBoundAt());
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：佣金明细 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyCommissions(UUID userId, String status, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        UUID me = d.getId();
        Pageable pageable = toPageable(page, pageSize);
        CommissionStatus statusEnum = parseCommissionStatus(status);
        Page<CommissionRecord> cp;
        if (statusEnum != null) {
            // 复用 admin 查询（distributorId + status，无时间范围限制）
            cp = commissionRecordRepository.findAdminList(me, statusEnum, RANGE_FROM_MIN, RANGE_TO_MAX, pageable);
        } else {
            cp = commissionRecordRepository.findByDistributorIdOrderByCreatedAtDesc(me, pageable);
        }
        // 我作为上级抽成的订单项 key 集合（用于区分"自己推广 / 下级抽成"）
        Set<String> parentItemKeys = commissionRecordRepository.findParentCommissionItemKeys(me).stream()
                .map(row -> String.valueOf(row[0]) + ":" + String.valueOf(row[1]))
                .collect(Collectors.toSet());
        // 计算"可结算"状态（PENDING + 订单已完成且超过结算延迟期），并带出延迟天数供前端悬浮提示
        enrichSettlable(cp.getContent());
        int delayDays = settleDelayDays();
        List<Map<String, Object>> items = cp.getContent().stream()
                .map(cr -> {
                    Map<String, Object> m = commissionDetailToMap(cr, me, parentItemKeys);
                    m.put("settle_delay_days", delayDays);
                    return m;
                })
                .toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportMyCommissions(UUID userId, String status) {
        Distributor d = requireDistributorByUserId(userId);
        UUID me = d.getId();
        CommissionStatus statusEnum = parseCommissionStatus(status);
        Page<CommissionRecord> cp;
        if (statusEnum != null) {
            cp = commissionRecordRepository.findAdminList(me, statusEnum, RANGE_FROM_MIN, RANGE_TO_MAX, Pageable.unpaged());
        } else {
            cp = commissionRecordRepository.findByDistributorIdOrderByCreatedAtDesc(me, Pageable.unpaged());
        }
        Set<String> parentItemKeys = commissionRecordRepository.findParentCommissionItemKeys(me).stream()
                .map(row -> String.valueOf(row[0]) + ":" + String.valueOf(row[1]))
                .collect(Collectors.toSet());
        // 计算"可结算"状态（PENDING + 订单已完成且超过结算延迟期）
        enrichSettlable(cp.getContent());

        String[] headers = {"订单号", "商品名称", "订单金额", "佣金比例", "佣金金额", "来源", "结算状态", "创建时间", "结算时间"};
        int[] colWidths = {14, 42, 12, 10, 12, 12, 12, 20, 20}; // 单位：字符宽度 1/256
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("佣金明细");
            // 列宽
            for (int i = 0; i < colWidths.length; i++) {
                sheet.setColumnWidth(i, colWidths[i] * 256);
            }
            // 表头样式：白字加粗 + 深蓝底纹 + 居中 + 边框
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            // 数据样式：边框 + 垂直居中
            CellStyle dataStyle = wb.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);
            dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle centerStyle = wb.createCellStyle();
            centerStyle.cloneStyleFrom(dataStyle);
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1); // 冻结表头

            int rowIdx = 1;
            for (CommissionRecord cr : cp.getContent()) {
                Row row = sheet.createRow(rowIdx++);
                row.setHeight((short) -1); // 自动行高（长文本换行时用）
                String statusLabel = exportStatusLabel(cr);
                boolean fromSub = parentItemKeys.contains(String.valueOf(cr.getOrderId()) + ":" + String.valueOf(cr.getOrderItemId()));
                String[] values = {
                        String.valueOf(cr.getOrderId()).substring(0, 8),
                        cr.getProductTitle() != null ? cr.getProductTitle() : "",
                        nullSafe(cr.getOrderAmount()).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        rateToPercent(cr.getCommissionRate()).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%",
                        cr.getCommissionAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        fromSub ? "下级抽成" : "自己推广",
                        statusLabel,
                        fmtDateTime(cr.getCreatedAt()),
                        fmtDateTime(cr.getSettledAt()),
                };
                for (int i = 0; i < values.length; i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(values[i]);
                    c.setCellStyle(i == 0 || i == 4 ? centerStyle : dataStyle);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            log.error("Export commissions failed", e);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "佣金明细导出失败");
        }
    }

    /** 佣金记录导出用状态文案（PENDING 且可结算时显示"可结算"，与前端徽标一致） */
    private String exportStatusLabel(CommissionRecord cr) {
        if (cr.getStatus() == CommissionStatus.PENDING) {
            return Boolean.TRUE.equals(cr.getSettlable()) ? "可结算" : "待结算";
        }
        return switch (cr.getStatus()) {
            case SETTLED -> "已结算";
            case WITHDRAWING -> "申请中";
            case WITHDRAWN -> "已提现";
            case REJECTED -> "结算拒绝";
            case CANCELLED -> "已取消";
            default -> cr.getStatus().name();
        };
    }

    private String fmtDateTime(LocalDateTime dt) {
        return dt != null ? dt.format(DATETIME_FMT) : "";
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：最近推广成交订单（含下级推广订单） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyPromotionOrders(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        UUID me = d.getId();
        Pageable pageable = toPageable(page, pageSize);
        Page<CommissionRecord> cp = commissionRecordRepository.findByDistributorIdOrderByCreatedAtDesc(me, pageable);
        Set<String> parentItemKeys = commissionRecordRepository.findParentCommissionItemKeys(me).stream()
                .map(row -> String.valueOf(row[0]) + ":" + String.valueOf(row[1]))
                .collect(Collectors.toSet());
        // 计算"可结算"状态，供"最近推广成交订单"展示结算状态列
        enrichSettlable(cp.getContent());
        int delayDays = settleDelayDays();

        List<Map<String, Object>> items = cp.getContent().stream().map(cr -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", cr.getId());
            m.put("order_id", cr.getOrderId());
            m.put("product_id", cr.getProductId());
            m.put("product_title", cr.getProductTitle());
            m.put("product_price", cr.getOrderAmount());
            m.put("commission_rate", rateToPercent(cr.getCommissionRate()));
            boolean fromSub = parentItemKeys.contains(String.valueOf(cr.getOrderId()) + ":" + String.valueOf(cr.getOrderItemId()));
            m.put("source_type", fromSub ? "SUB" : "SELF");
            m.put("commission_amount", cr.getCommissionAmount());
            m.put("status", cr.getStatus().name());
            m.put("settlable", Boolean.TRUE.equals(cr.getSettlable()));
            m.put("settle_delay_days", delayDays);
            m.put("created_at", cr.getCreatedAt());
            orderRepository.findById(cr.getOrderId()).ifPresent(o -> {
                m.put("order_status", o.getStatus().name());
                m.put("paid_at", o.getPaidAt());
            });
            if (fromSub) {
                // 展示实际成交的推广员（下级）
                commissionRecordRepository
                        .findByOrderIdAndOrderItemIdAndParentDistributorId(cr.getOrderId(), cr.getOrderItemId(), me)
                        .stream().findFirst()
                        .flatMap(seller -> distributorRepository.findById(seller.getDistributorId()))
                        .flatMap(sd -> userRepository.findById(sd.getUserId()))
                        .ifPresent(u -> m.put("seller_name", u.getUsername()));
            }
            return m;
        }).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：提现 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> applyWithdrawal(UUID userId, List<UUID> commissionRecordIds) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        // 提现必须已绑定微信（提现通过微信支付商家转账到零钱，需收款 openid）
        if (d.getWechatOpenid() == null || d.getWechatOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先在分销中心绑定微信后再申请提现");
        }
        if (commissionRecordIds == null || commissionRecordIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要提现的订单");
        }
        // 去重后校验归属（订单级提现：以勾选的已结算/可结算佣金记录为准）
        List<UUID> ids = commissionRecordIds.stream().distinct().toList();
        List<CommissionRecord> records = commissionRecordRepository.findByIdIn(ids);
        if (records.size() != ids.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "部分佣金记录不存在，请刷新后重试");
        }
        for (CommissionRecord cr : records) {
            if (!cr.getDistributorId().equals(d.getId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存在非本人的佣金记录");
            }
        }

        // S7: 悲观行锁 — 防止并发提现超扣余额（余额检查→扣减必须原子化）
        Distributor locked = distributorRepository.findByIdWithLock(d.getId()).orElse(d);

        // 加锁后重读佣金记录：与结算定时任务串行化（双方都先锁分销员行），避免并发导致同一笔佣金重复入账
        records = commissionRecordRepository.findByIdIn(ids);
        enrichSettlable(records);

        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal needSettle = BigDecimal.ZERO; // 可结算但尚未自动结算的部分（PENDING，暂不在可提现余额中）
        for (CommissionRecord cr : records) {
            // 已结算/结算拒绝可提现；待结算中订单已完成且超过结算延迟期的"可结算"也可直接提现
            if (cr.getStatus() == CommissionStatus.PENDING) {
                if (!Boolean.TRUE.equals(cr.getSettlable())) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "存在不可提现的订单，请刷新后重试");
                }
                needSettle = needSettle.add(cr.getCommissionAmount());
            } else if (cr.getStatus() != CommissionStatus.SETTLED && cr.getStatus() != CommissionStatus.REJECTED) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存在不可提现的订单，请刷新后重试");
            }
            amount = amount.add(cr.getCommissionAmount());
        }

        DistributionRule rule = getOrCreateRule();
        if (amount.compareTo(rule.getMinWithdrawAmount()) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提现金额不能低于最低提现金额 " + rule.getMinWithdrawAmount());
        }
        // 余额校验只需覆盖已入账部分；可结算部分在申请时先按结算口径入账（available 不变，仅计入累计佣金与冻结）
        BigDecimal inBalance = amount.subtract(needSettle);
        if (locked.getAvailableBalance().compareTo(inBalance) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可提现余额不足");
        }

        // 计算手续费
        BigDecimal fee = amount.multiply(nullSafe(rule.getWithdrawFeeRate())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualAmount = amount.subtract(fee).setScale(2, RoundingMode.HALF_UP);

        // 冻结金额：已入账部分从可提现余额扣减，可结算部分直接进入冻结；可结算部分按结算口径计入累计佣金
        locked.setAvailableBalance(locked.getAvailableBalance().subtract(inBalance));
        locked.setFrozenBalance(locked.getFrozenBalance().add(amount));
        if (needSettle.signum() > 0) {
            locked.setTotalCommission(locked.getTotalCommission().add(needSettle));
        }
        distributorRepository.save(locked);

        // 创建提现记录
        WithdrawalRecord wr = new WithdrawalRecord();
        wr.setDistributorId(locked.getId());
        wr.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        wr.setFee(fee);
        wr.setActualAmount(actualAmount);
        wr.setStatus(WithdrawalStatus.PENDING);
        wr.setAppliedAt(LocalDateTime.now());
        withdrawalRecordRepository.save(wr);

        // 佣金记录 → 申请中，关联提现单；可结算（PENDING）部分记录实际结算时间
        for (CommissionRecord cr : records) {
            if (cr.getStatus() == CommissionStatus.PENDING) {
                cr.setSettledAt(LocalDateTime.now());
            }
            cr.setStatus(CommissionStatus.WITHDRAWING);
            cr.setWithdrawalId(wr.getId());
            commissionRecordRepository.save(cr);
        }
        log.info("Withdrawal applied: distributor={}, records={}, amount={}, actual={}",
                locked.getId(), records.size(), amount, actualAmount);

        // 发送通知
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("amount", amount);
            vars.put("actual_amount", actualAmount);
            vars.put("fee", fee);
            userMessageService.sendUserMessage(locked.getUserId(), null, "WITHDRAWAL_APPLIED", vars);
        } catch (Exception e) {
            log.warn("Failed to send withdrawal applied message: {}", e.getMessage());
        }
        try {
            Map<String, Object> adminVars = new LinkedHashMap<>();
            adminVars.put("distributor_code", locked.getDistributorCode());
            adminVars.put("amount", amount);
            notificationService.sendTemplate("WITHDRAWAL_PENDING", adminVars);
        } catch (Exception e) {
            log.warn("Failed to notify admin for withdrawal: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", wr.getId());
        result.put("amount", wr.getAmount());
        result.put("fee", wr.getFee());
        result.put("actual_amount", wr.getActualAmount());
        result.put("status", wr.getStatus().name());
        result.put("available_balance", locked.getAvailableBalance());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getWithdrawableOrders(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        // 可提现范围：已结算 + 结算拒绝（被拒后金额退回可提现，可重新勾选）+ 可结算（PENDING 但订单已完成且超过结算延迟期）
        List<CommissionRecord> records = commissionRecordRepository
                .findByDistributorIdAndStatusInOrderByCreatedAtDesc(d.getId(),
                        List.of(CommissionStatus.SETTLED, CommissionStatus.REJECTED, CommissionStatus.PENDING));
        if (records.isEmpty()) {
            return List.of();
        }
        // 计算可结算状态后，过滤掉未满足结算期的待结算佣金
        enrichSettlable(records);
        List<CommissionRecord> eligible = records.stream()
                .filter(cr -> cr.getStatus() != CommissionStatus.PENDING || Boolean.TRUE.equals(cr.getSettlable()))
                .toList();
        if (eligible.isEmpty()) {
            return List.of();
        }
        // 按订单分组：一个订单可能含多笔佣金（多商品/多阶梯/上下级抽成）
        Map<UUID, List<CommissionRecord>> byOrder = eligible.stream()
                .collect(Collectors.groupingBy(CommissionRecord::getOrderId, LinkedHashMap::new, Collectors.toList()));

        int delayDays = settleDelayDays();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<UUID, List<CommissionRecord>> e : byOrder.entrySet()) {
            List<CommissionRecord> crs = e.getValue();
            BigDecimal orderAmount = BigDecimal.ZERO;
            BigDecimal commission = BigDecimal.ZERO;
            List<UUID> recordIds = new ArrayList<>();
            String productTitles = crs.stream().map(CommissionRecord::getProductTitle)
                    .filter(t -> t != null && !t.isBlank()).distinct().limit(3).collect(Collectors.joining("、"));
            boolean anySettlable = false;
            for (CommissionRecord cr : crs) {
                orderAmount = orderAmount.add(nullSafe(cr.getOrderAmount()));
                commission = commission.add(cr.getCommissionAmount());
                recordIds.add(cr.getId());
                if (cr.getStatus() == CommissionStatus.PENDING && Boolean.TRUE.equals(cr.getSettlable())) {
                    anySettlable = true;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_id", e.getKey());
            m.put("product_title", productTitles.isBlank() ? null : productTitles);
            m.put("order_amount", orderAmount.setScale(2, RoundingMode.HALF_UP));
            m.put("commission_amount", commission.setScale(2, RoundingMode.HALF_UP));
            m.put("commission_record_ids", recordIds);
            // true=该订单含"可结算"（未自动结算但满足结算期），前端展示"可结算"标签
            m.put("settlable", anySettlable);
            m.put("settle_delay_days", delayDays);
            m.put("created_at", crs.get(0).getCreatedAt());
            items.add(m);
        }
        return items;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyWithdrawals(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findByDistributorIdOrderByCreatedAtDesc(d.getId(), pageable);
        List<Map<String, Object>> items = wp.getContent().stream().map(this::withdrawalToMap).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getWithdrawalConfirmInfo(UUID userId, UUID withdrawalId) {
        Distributor d = requireDistributorByUserId(userId);
        WithdrawalRecord wr = withdrawalRecordRepository.findById(withdrawalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));
        if (!wr.getDistributorId().equals(d.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该提现单");
        }
        if (wr.getStatus() != WithdrawalStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不可确认收款");
        }
        if (wr.getPackageInfo() == null || wr.getPackageInfo().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少收款确认信息，请稍后重试");
        }
        PaymentChannel channel = paymentChannelRepository
                .findByProviderTypeAndIsDeleted("native_wxpay", 0).stream()
                .filter(PaymentChannel::isEnabled).findFirst().orElse(null);
        if (channel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未配置可用的微信支付渠道");
        }
        WxpayConfig config = paymentServiceImpl.buildWxpayConfig(channel);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mchId", config.mchid());
        result.put("appId", config.appid());
        result.put("packageInfo", wr.getPackageInfo());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：下级分销员 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listSubordinates(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        // 最新加入的下级排在前面
        List<Distributor> subs = distributorRepository.findByParentId(d.getId()).stream()
                .sorted(Comparator.comparing(Distributor::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        // 手动分页
        int from = Math.min((page - 1) * pageSize, subs.size());
        int to = Math.min(from + pageSize, subs.size());
        List<Distributor> slice = subs.subList(from, to);

        Set<UUID> userIds = slice.stream().map(Distributor::getUserId).collect(Collectors.toSet());
        Map<UUID, User> userMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> items = slice.stream().map(sub -> {
            User u = userMap.get(sub.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sub.getId());
            m.put("distributor_code", sub.getDistributorCode());
            m.put("invite_code", sub.getInviteCode());
            m.put("username", u != null ? u.getUsername() : null);
            m.put("email", u != null ? u.getEmail() : null);
            m.put("status", sub.getStatus().name());
            m.put("total_commission", sub.getTotalCommission());
            m.put("withdrawn_amount", sub.getWithdrawnAmount());
            m.put("created_at", sub.getCreatedAt());
            // 下级推广数据
            m.put("customer_count", customerBindingRepository.countByDistributorId(sub.getId()));
            m.put("subordinate_count", distributorRepository.findByParentId(sub.getId()).size());
            Page<PromotionLink> links = promotionLinkRepository.findByDistributorId(sub.getId(), Pageable.unpaged());
            long paid = 0;
            BigDecimal sales = BigDecimal.ZERO;
            for (PromotionLink pl : links.getContent()) {
                paid += pl.getPaidCount();
                sales = sales.add(nullSafe(pl.getTotalSales()));
            }
            m.put("paid_count", paid);
            m.put("total_sales", sales.setScale(2, RoundingMode.HALF_UP));
            // 我从该下级获得的抽成金额
            m.put("sub_commission", nullSafe(commissionRecordRepository.sumParentCommissionBySub(d.getId(), sub.getId()))
                    .setScale(2, RoundingMode.HALF_UP));
            return m;
        }).toList();
        return pageResult(items, subs.size(), page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 前台：客户管理 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listMyCustomers(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        long total = customerBindingRepository.countByDistributorId(d.getId());

        // 手动分页查询客户绑定（无分页 repository 方法，用 findAll + filter）
        List<CustomerBinding> all = customerBindingRepository.findAll().stream()
                .filter(cb -> d.getId().equals(cb.getDistributorId()))
                .sorted(Comparator.comparing(CustomerBinding::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        List<CustomerBinding> slice = all.subList(from, to);

        List<Map<String, Object>> items = slice.stream().map(cb -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", cb.getId());
            m.put("customer_email", cb.getCustomerEmail());
            m.put("customer_user_id", cb.getCustomerUserId());
            m.put("product_id", cb.getProductId());
            m.put("purchase_count", cb.getPurchaseCount());
            m.put("last_purchase_at", cb.getLastPurchaseAt());
            m.put("protection_expires_at", cb.getProtectionExpiresAt());
            m.put("created_at", cb.getCreatedAt());
            return m;
        }).toList();
        return pageResult(items, total, page, pageSize);
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 公开：推广链接解析 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public Map<String, Object> resolvePromotionLink(String linkCode, String ip, String userAgent) {
        if (linkCode == null || linkCode.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "推广链接编码不能为空");
        }
        PromotionLink link = promotionLinkRepository.findByLinkCode(linkCode.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "推广链接不存在"));

        // 记录点击
        try {
            DistributionClick click = new DistributionClick();
            click.setDistributorId(link.getDistributorId());
            click.setPromotionLinkId(link.getId());
            click.setProductId(link.getProductId());
            click.setIp(ip);
            click.setUserAgent(userAgent);
            clickRepository.save(click);

            // 更新点击数
            link.setClickCount(link.getClickCount() + 1);
            // 判断是否独立访客
            if (ip != null && !ip.isBlank()) {
                long existing = clickRepository.countByPromotionLinkIdAndIp(link.getId(), ip);
                if (existing <= 1) {
                    link.setUniqueClickCount(link.getUniqueClickCount() + 1);
                }
            }
            promotionLinkRepository.save(link);
        } catch (Exception e) {
            log.warn("Failed to record promotion click: {}", e.getMessage());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("distributor_id", link.getDistributorId());
        m.put("product_id", link.getProductId());
        m.put("promotion_link_id", link.getId());
        return m;
    }

    @Override
    @Transactional
    public void recordProductClick(UUID promotionLinkId, UUID productId, String ip, String userAgent) {
        if (promotionLinkId == null || productId == null) {
            return;
        }
        PromotionLink link = promotionLinkRepository.findById(promotionLinkId).orElse(null);
        if (link == null) {
            return;
        }
        try {
            DistributionClick click = new DistributionClick();
            click.setDistributorId(link.getDistributorId());
            click.setPromotionLinkId(link.getId());
            click.setProductId(productId);
            click.setIp(ip);
            click.setUserAgent(userAgent);
            clickRepository.save(click);
        } catch (Exception e) {
            // 埋点失败不影响用户操作，静默记录
            log.warn("Failed to record product click: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金预估 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> commissionPreview(UUID userId, List<UUID> productIds) {
        DistributionRule rule = getOrCreateRule();
        boolean distEnabled = rule.isEnabled();
        Map<String, Object> result = new LinkedHashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            result.put("items", List.of());
            result.put("total_commission", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            result.put("is_distribution_enabled", distEnabled);
            return result;
        }

        // 查找当前用户分销员身份
        Distributor d = (userId != null) ? distributorRepository.findByUserId(userId).orElse(null) : null;

        // 全局最高分销员自定义比例
        BigDecimal maxDistributorRate = distributorRepository.findMaxCustomRate();

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalCommission = BigDecimal.ZERO;

        for (UUID pid : productIds) {
            Product p = productRepository.findById(pid).orElse(null);
            if (p == null) continue;

            ProductCommission pc = productCommissionRepository.findByProductId(pid).orElse(null);
            // 新语义：未开启分销（无记录）或已排除 → 不参与分销
            boolean excluded = !isProductDistributable(pid);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", p.getId());
            m.put("product_title", p.getTitle());
            m.put("base_price", p.getBasePrice());
            m.put("is_excluded", excluded);

            if (excluded || !distEnabled) {
                m.put("commission_rate", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                m.put("commission_amount", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                m.put("commission_preview", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                m.put("max_commission_rate", BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                m.put("max_commission", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                items.add(m);
                continue;
            }

            // 计算最高佣金比例 = MAX(全局默认, 商品自定义, 所有分销员自定义最大值)
            BigDecimal maxRate = rule.getDefaultRate();
            if (pc != null && pc.getCustomRate() != null) {
                maxRate = maxRate.max(pc.getCustomRate());
            }
            if (maxDistributorRate != null) {
                maxRate = maxRate.max(maxDistributorRate);
            }

            BigDecimal rate;
            if (d != null && d.getStatus() == DistributorStatus.APPROVED) {
                // 已登录的分销员，用实际比例
                rate = resolveCommissionRate(d, pid, rule);
            } else {
                // 未登录或非分销员，用最高比例
                rate = maxRate;
            }

            BigDecimal commissionAmount = p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal maxAmount = p.getBasePrice().multiply(maxRate).setScale(2, RoundingMode.HALF_UP);
            totalCommission = totalCommission.add(commissionAmount);

            m.put("commission_rate", rateToPercent(rate));
            m.put("commission_amount", commissionAmount);
            m.put("commission_preview", commissionAmount);
            m.put("max_commission_rate", rateToPercent(maxRate));
            m.put("max_commission", maxAmount);
            items.add(m);
        }

        result.put("items", items);
        result.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        result.put("is_distribution_enabled", distEnabled);
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金计算（订单支付成功时调用） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void onOrderPaid(UUID orderId) {
        try {
            // M7: 幂等 — 该订单已生成过佣金记录则跳过（回调/主动查单多路径防重复入账）
            if (!commissionRecordRepository.findByOrderId(orderId).isEmpty()) {
                log.info("Order {} already has commission records, skip commission", orderId);
                return;
            }

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));

            // 只有 actualAmount > 0 的已付款订单才产生佣金
            if (order.getActualAmount() == null || order.getActualAmount().compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Order {} actualAmount <= 0, skip commission", orderId);
                return;
            }

            UUID distId = order.getReferralDistributorId();
            if (distId == null) {
                log.info("Order {} has no referralDistributorId, skip commission", orderId);
                return;
            }

            // S6: 客户保护期绑定 — 客户在保护期内归属原推广员，防止被其他推广员"抢单"
            if (order.getEmail() != null && !order.getEmail().isBlank()) {
                String email = order.getEmail().trim().toLowerCase();
                CustomerBinding activeBinding = customerBindingRepository.findActiveBindingByEmail(email).orElse(null);
                if (activeBinding != null && !activeBinding.getDistributorId().equals(distId)) {
                    log.info("Customer {} already bound to distributor {} (protection period), reassign from {}",
                            email, activeBinding.getDistributorId(), distId);
                    order.setReferralDistributorId(activeBinding.getDistributorId());
                    // 佣金归属已改为原推广员，该成交不再计入新推广员的推广链接统计（避免链接统计与佣金不一致）
                    if (order.getPromotionLinkId() != null) {
                        log.info("Order {} promotionLink {} discarded due to protection reassignment", orderId, order.getPromotionLinkId());
                        order.setPromotionLinkId(null);
                    }
                    distId = activeBinding.getDistributorId();
                }
            }

            Distributor d = distributorRepository.findById(distId).orElse(null);
            if (d == null || d.getStatus() != DistributorStatus.APPROVED) {
                log.warn("Distributor {} not found or not approved for order {}", distId, orderId);
                return;
            }

            // 自购不返佣
            if (d.getUserId() != null && d.getUserId().equals(order.getUserId())) {
                log.info("Self-purchase order {}, skip commission", orderId);
                return;
            }

            DistributionRule rule = getOrCreateRule();

            // 查找或创建客户绑定
            CustomerBinding binding = null;
            if (order.getEmail() != null && !order.getEmail().isBlank()) {
                String email = order.getEmail().trim().toLowerCase();
                binding = customerBindingRepository.findByCustomerEmailAndDistributorId(email, distId).orElse(null);
                if (binding == null) {
                    binding = new CustomerBinding();
                    binding.setDistributorId(distId);
                    binding.setCustomerEmail(email);
                    binding.setCustomerUserId(order.getUserId());
                    binding.setProductId(null);
                    binding.setPromotionLinkId(order.getPromotionLinkId());
                    binding.setProtectionExpiresAt(LocalDateTime.now().plusDays(rule.getBindingProtectionDays()));
                    binding.setPurchaseCount(0);
                    customerBindingRepository.save(binding);
                }
            }

            // 阶梯佣金
            int tierOrder = (binding != null ? binding.getPurchaseCount() : 0) + 1;
            BigDecimal tierRate = BigDecimal.ONE;
            if (rule.isTierEnabled()) {
                tierRate = resolveTierRate(tierOrder);
            }

            // 遍历订单项计算佣金
            List<OrderItem> orderItems = orderItemRepository.findByOrderId(orderId);
            // M1: 佣金基数 = 实际付款金额 × 订单项小计占比（优惠券/积分抵扣后分摊到各商品）
            BigDecimal totalSubtotal = orderItems.stream()
                    .map(OrderItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal orderCommissionTotal = BigDecimal.ZERO;
            BigDecimal fullCommissionTotal = BigDecimal.ZERO;

            for (OrderItem oi : orderItems) {
                // 检查商品是否被排除
                if (isProductExcluded(oi.getProductId())) {
                    log.info("Product {} is excluded, skip commission for order item", oi.getProductId());
                    continue;
                }

                BigDecimal rate = resolveCommissionRate(d, oi.getProductId(), rule);
                BigDecimal base = totalSubtotal.compareTo(BigDecimal.ZERO) > 0
                        ? order.getActualAmount().multiply(oi.getSubtotal()).divide(totalSubtotal, 2, RoundingMode.HALF_UP)
                        : oi.getSubtotal();
                BigDecimal commissionAmount = base.multiply(rate).multiply(tierRate).setScale(2, RoundingMode.HALF_UP);
                if (commissionAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                fullCommissionTotal = fullCommissionTotal.add(commissionAmount);

                // 二级分销上级抽成
                BigDecimal parentAmount = BigDecimal.ZERO;
                UUID parentId = null;
                BigDecimal parentSubRateVal = null;
                if (rule.isSubDistributionEnabled() && d.getParentId() != null) {
                    Distributor parent = distributorRepository.findById(d.getParentId()).orElse(null);
                    if (parent != null && parent.getStatus() == DistributorStatus.APPROVED) {
                        BigDecimal subRate = parent.getSubRate() != null ? parent.getSubRate() : rule.getDefaultSubRate();
                        parentAmount = commissionAmount.multiply(subRate).setScale(2, RoundingMode.HALF_UP);
                        parentId = parent.getId();
                        parentSubRateVal = subRate;
                    }
                }

                // S3: 下级实得 = 佣金 − 上级抽成；上级抽成单独记佣金记录（总支出 = 佣金，平台不多付）
                BigDecimal selfAmount = commissionAmount.subtract(parentAmount).max(BigDecimal.ZERO);

                // 创建佣金记录（下级）
                CommissionRecord cr = new CommissionRecord();
                cr.setDistributorId(distId);
                cr.setOrderId(orderId);
                cr.setOrderItemId(oi.getId());
                cr.setProductId(oi.getProductId());
                cr.setProductTitle(oi.getProductTitle());
                cr.setOrderAmount(base);
                cr.setCommissionRate(rate);
                cr.setCommissionAmount(selfAmount);
                cr.setTierOrder(tierOrder);
                cr.setStatus(CommissionStatus.PENDING);
                if (parentId != null && parentAmount.compareTo(BigDecimal.ZERO) > 0) {
                    cr.setParentDistributorId(parentId);
                    cr.setParentCommissionAmount(parentAmount);

                    // 为上级创建独立佣金记录（抽成金额）
                    CommissionRecord parentCr = new CommissionRecord();
                    parentCr.setDistributorId(parentId);
                    parentCr.setOrderId(orderId);
                    parentCr.setOrderItemId(oi.getId());
                    parentCr.setProductId(oi.getProductId());
                    parentCr.setProductTitle(oi.getProductTitle());
                    parentCr.setOrderAmount(base);
                    parentCr.setCommissionRate(parentSubRateVal);
                    parentCr.setCommissionAmount(parentAmount);
                    parentCr.setTierOrder(tierOrder);
                    parentCr.setStatus(CommissionStatus.PENDING);
                    commissionRecordRepository.save(parentCr);
                }

                commissionRecordRepository.save(cr);
                orderCommissionTotal = orderCommissionTotal.add(selfAmount);
            }

            // 更新客户绑定购买次数
            if (binding != null) {
                binding.setPurchaseCount(binding.getPurchaseCount() + 1);
                binding.setLastPurchaseAt(LocalDateTime.now());
                customerBindingRepository.save(binding);
            }

            // 更新推广链接统计
            if (order.getPromotionLinkId() != null) {
                final BigDecimal finalCommissionTotal = fullCommissionTotal;
                promotionLinkRepository.findById(order.getPromotionLinkId()).ifPresent(link -> {
                    link.setPaidCount(link.getPaidCount() + 1);
                    link.setTotalSales(link.getTotalSales().add(order.getActualAmount()));
                    link.setTotalCommission(link.getTotalCommission().add(finalCommissionTotal));
                    promotionLinkRepository.save(link);
                });
            }

            // 发送佣金获得消息（下级实得金额）
            if (orderCommissionTotal.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    User user = userRepository.findById(d.getUserId()).orElse(null);
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("order_id", orderId);
                    vars.put("commission_amount", orderCommissionTotal);
                    userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                            "COMMISSION_EARNED", vars);
                } catch (Exception e) {
                    log.warn("Failed to send commission earned message: {}", e.getMessage());
                }
            }

            log.info("Commission calculated for order {}: self={}, full={}", orderId, orderCommissionTotal, fullCommissionTotal);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to calculate commission for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金结算（定时任务） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void settlePendingCommissions() {
        DistributionRule rule = getOrCreateRule();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(rule.getSettleDelayDays());
        List<CommissionRecord> pending = commissionRecordRepository.findPendingSettlement(cutoff);
        if (pending.isEmpty()) {
            return;
        }
        log.info("Settling {} pending commission records", pending.size());

        Map<UUID, Distributor> distCache = new HashMap<>();
        for (CommissionRecord cr : pending) {
            try {
                Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                        distributorRepository.findById(id).orElse(null));
                if (d == null) continue;

                // 与"申请提现"串行化：申请提现同样先锁分销员行，此处加锁后重读佣金状态，
                // 若已被并发提现（转 WITHDRAWING）或取消（CANCELLED）则跳过，避免重复入账
                Distributor locked = distributorRepository.findByIdWithLock(d.getId()).orElse(d);
                CommissionRecord fresh = commissionRecordRepository.findById(cr.getId()).orElse(null);
                if (fresh == null || fresh.getStatus() != CommissionStatus.PENDING) {
                    continue;
                }
                cr = fresh;
                d = locked;

                cr.setStatus(CommissionStatus.SETTLED);
                cr.setSettledAt(LocalDateTime.now());
                commissionRecordRepository.save(cr);

                d.setAvailableBalance(d.getAvailableBalance().add(cr.getCommissionAmount()));
                d.setTotalCommission(d.getTotalCommission().add(cr.getCommissionAmount()));
                distributorRepository.save(d);

                try {
                    User user = userRepository.findById(d.getUserId()).orElse(null);
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("commission_amount", cr.getCommissionAmount());
                    vars.put("product_title", cr.getProductTitle());
                    userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                            "COMMISSION_SETTLED", vars);
                } catch (Exception e) {
                    log.warn("Failed to send commission settled message: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("Failed to settle commission {}: {}", cr.getId(), e.getMessage());
            }
        }
        log.info("Commission settlement completed: {} records settled", pending.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 佣金取消（订单退款时调用） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void cancelCommissions(UUID orderId) {
        List<CommissionRecord> records = commissionRecordRepository.findByOrderId(orderId);
        if (records.isEmpty()) {
            log.info("No commission records found for order {}", orderId);
            return;
        }
        log.info("Cancelling {} commission records for order {}", records.size(), orderId);

        Map<UUID, Distributor> distCache = new HashMap<>();
        for (CommissionRecord cr : records) {
            try {
                if (cr.getStatus() == CommissionStatus.CANCELLED) {
                    continue;
                }

                CommissionStatus oldStatus = cr.getStatus();
                cr.setStatus(CommissionStatus.CANCELLED);
                commissionRecordRepository.save(cr);

                // 不同状态的钱所在位置不同，退款取消时需从对应余额/冻结中扣回：
                // SETTLED/REJECTED → 已退回可提现余额（available_balance）
                // WITHDRAWING → 金额在提现冻结中（frozen_balance）
                // WITHDRAWN → 已打款到账，标记取消并仅记日志（后续人工追回）
                if (oldStatus == CommissionStatus.SETTLED || oldStatus == CommissionStatus.REJECTED) {
                    Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                            distributorRepository.findById(id).orElse(null));
                    if (d != null) {
                        BigDecimal balance = d.getAvailableBalance().subtract(cr.getCommissionAmount());
                        d.setAvailableBalance(balance);
                        d.setTotalCommission(d.getTotalCommission().subtract(cr.getCommissionAmount()));
                        distributorRepository.save(d);

                        if (balance.compareTo(BigDecimal.ZERO) < 0) {
                            log.warn("Distributor {} available balance went negative after cancellation: {}", d.getId(), balance);
                        }
                    }
                } else if (oldStatus == CommissionStatus.WITHDRAWING) {
                    Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                            distributorRepository.findById(id).orElse(null));
                    if (d != null) {
                        d.setFrozenBalance(d.getFrozenBalance().subtract(cr.getCommissionAmount()));
                        d.setTotalCommission(d.getTotalCommission().subtract(cr.getCommissionAmount()));
                        distributorRepository.save(d);
                    }
                } else if (oldStatus == CommissionStatus.WITHDRAWN) {
                    log.warn("Commission {} already withdrawn, refund needs manual recovery (orderId={})",
                            cr.getId(), orderId);
                }

                // 发送取消通知
                Distributor d = distCache.computeIfAbsent(cr.getDistributorId(), id ->
                        distributorRepository.findById(id).orElse(null));
                if (d != null) {
                    try {
                        User user = userRepository.findById(d.getUserId()).orElse(null);
                        Map<String, Object> vars = new LinkedHashMap<>();
                        vars.put("order_id", orderId);
                        vars.put("commission_amount", cr.getCommissionAmount());
                        vars.put("product_title", cr.getProductTitle());
                        userMessageService.sendUserMessage(d.getUserId(), user != null ? user.getEmail() : null,
                                "COMMISSION_CANCELLED", vars);
                    } catch (Exception e) {
                        log.warn("Failed to send commission cancelled message: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to cancel commission {}: {}", cr.getId(), e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 微信转账结果回调（提现到账确认） ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void handleTransferCallback(String outBillNo, String state, String failReason) {
        if (outBillNo == null || outBillNo.isBlank()) {
            log.warn("Transfer callback missing out_bill_no");
            return;
        }
        WithdrawalRecord wr = withdrawalRecordRepository.findByOutBillNo(outBillNo).orElse(null);
        if (wr == null) {
            log.warn("Transfer callback: withdrawal not found for outBillNo={}", outBillNo);
            return;
        }
        // 幂等：已终态（SUCCESS/FAILED）的记录不再处理
        if (wr.getStatus() == WithdrawalStatus.SUCCESS || wr.getStatus() == WithdrawalStatus.FAILED) {
            log.info("Transfer callback: withdrawal {} already {}, skip", wr.getId(), wr.getStatus());
            return;
        }

        Distributor d = distributorRepository.findById(wr.getDistributorId()).orElse(null);
        if (d == null) {
            log.warn("Transfer callback: distributor not found for withdrawal {}", wr.getId());
            return;
        }

        if (isTransferSuccessState(state)) {
            // 转账成功：冻结 → 已提现
            BigDecimal settleAmount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setWithdrawnAmount(d.getWithdrawnAmount().add(settleAmount));
            distributorRepository.save(d);

            // 关联佣金记录 → 已提现
            for (CommissionRecord cr : commissionRecordRepository.findByWithdrawalId(wr.getId())) {
                if (cr.getStatus() == CommissionStatus.WITHDRAWING) {
                    cr.setStatus(CommissionStatus.WITHDRAWN);
                    commissionRecordRepository.save(cr);
                }
            }

            wr.setStatus(WithdrawalStatus.SUCCESS);
            wr.setCompletedAt(LocalDateTime.now());
            wr.setFailReason(null);
            withdrawalRecordRepository.save(wr);
            log.info("Transfer callback: withdrawal {} FINISHED, distributor {} frozen -= {}, withdrawn += {}",
                    wr.getId(), d.getId(), wr.getAmount(), settleAmount);

            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("amount", settleAmount);
                userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_SUCCESS", vars);
            } catch (Exception e) {
                log.warn("Failed to send withdrawal success message: {}", e.getMessage());
            }
        } else if (isTransferFailedState(state)) {
            // 转账失败/关闭：冻结退回可用余额
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setAvailableBalance(d.getAvailableBalance().add(wr.getAmount()));
            distributorRepository.save(d);

            // 关联佣金记录 → 结算拒绝（退回可提现，可重新勾选）
            for (CommissionRecord cr : commissionRecordRepository.findByWithdrawalId(wr.getId())) {
                if (cr.getStatus() == CommissionStatus.WITHDRAWING) {
                    cr.setStatus(CommissionStatus.REJECTED);
                    commissionRecordRepository.save(cr);
                }
            }

            wr.setStatus(WithdrawalStatus.FAILED);
            wr.setFailReason(failReason != null && !failReason.isBlank() ? "微信转账失败：" + failReason : "微信转账失败");
            withdrawalRecordRepository.save(wr);
            log.info("Transfer callback: withdrawal {} {} -> FAILED, refund frozen={} to distributor {}",
                    wr.getId(), state, wr.getAmount(), d.getId());

            try {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("amount", wr.getAmount());
                vars.put("reason", wr.getFailReason());
                userMessageService.sendUserMessage(d.getUserId(), null, "WITHDRAWAL_REJECTED", vars);
            } catch (Exception e) {
                log.warn("Failed to send withdrawal failed message: {}", e.getMessage());
            }
        } else {
            // 中间态（ACCEPTED/PROCESSING/WAIT_USER_CONFIRM/TRANSFERING 等）：仅记录，保持 PROCESSING，不动余额
            wr.setStatus(WithdrawalStatus.PROCESSING);
            if (wr.getTransferredAt() == null) {
                wr.setTransferredAt(LocalDateTime.now());
            }
            withdrawalRecordRepository.save(wr);
            log.info("Transfer callback: withdrawal {} state={} is intermediate, keep processing", wr.getId(), state);
        }
    }

    /** 微信商家转账批次状态：成功终态 */
    private boolean isTransferSuccessState(String state) {
        return "FINISHED".equalsIgnoreCase(state) || "SUCCESS".equalsIgnoreCase(state);
    }

    /** 微信商家转账批次状态：失败/关闭终态 */
    private boolean isTransferFailedState(String state) {
        return "CLOSED".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state)
                || "FAIL".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)
                || "CANCEL".equalsIgnoreCase(state);
    }

    @Override
    @Transactional
    public Map<String, Object> adminGetWithdrawalTransferStatus(UUID id) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", wr.getId());
        result.put("status", wr.getStatus().name());
        result.put("amount", wr.getAmount());
        result.put("actual_amount", wr.getActualAmount());
        result.put("out_bill_no", wr.getOutBillNo());
        result.put("transfer_bill_no", wr.getTransferBillNo());
        result.put("fail_reason", wr.getFailReason());
        result.put("approved_at", wr.getApprovedAt());
        result.put("transferred_at", wr.getTransferredAt());
        result.put("completed_at", wr.getCompletedAt());

        // 主动查询微信转账状态（仅对已发起转账、且尚未到终态的提现单；回调丢失时兜底补账）
        String wxState = null;
        String wxFailReason = null;
        String wxTransferBillNo = wr.getTransferBillNo();
        if (wr.getOutBillNo() != null && !wr.getOutBillNo().isBlank()
                && wr.getStatus() != WithdrawalStatus.SUCCESS && wr.getStatus() != WithdrawalStatus.FAILED) {
            PaymentChannel channel = paymentChannelRepository
                    .findByProviderTypeAndIsDeleted("native_wxpay", 0).stream()
                    .filter(PaymentChannel::isEnabled)
                    .findFirst().orElse(null);
            if (channel != null) {
                try {
                    WxpayConfig config = paymentServiceImpl.buildWxpayConfig(channel);
                    WxpayTransferQueryResult qr = wxpayService.queryTransfer(config, wr.getOutBillNo());
                    if (qr != null && qr.state() != null) {
                        wxState = qr.state();
                        wxFailReason = qr.failReason();
                        if (qr.transferBillNo() != null) {
                            wxTransferBillNo = qr.transferBillNo();
                        }
                        if (isTransferSuccessState(wxState) || isTransferFailedState(wxState)) {
                            handleTransferCallback(wr.getOutBillNo(), wxState, wxFailReason);
                        }
                    } else if (qr != null && qr.error() != null) {
                        result.put("wx_error", qr.error());
                    }
                } catch (Exception e) {
                    log.warn("Query transfer status failed for withdrawal {}: {}", id, e.getMessage());
                }
            }
        }

        result.put("wx_state", wxState);
        result.put("wx_fail_reason", wxFailReason);
        result.put("wx_transfer_bill_no", wxTransferBillNo);
        result.put("can_retry", wr.getStatus() == WithdrawalStatus.APPROVED || wr.getStatus() == WithdrawalStatus.FAILED);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> adminRetryWithdrawalTransfer(UUID id) {
        WithdrawalRecord wr = withdrawalRecordRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提现记录不存在"));

        if (wr.getStatus() != WithdrawalStatus.APPROVED && wr.getStatus() != WithdrawalStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅「已通过」或「转账失败」的提现可重新发起转账");
        }

        Distributor d = distributorRepository.findById(wr.getDistributorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
        if (d.getWechatOpenid() == null || d.getWechatOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该分销员未绑定微信，无法发起转账");
        }

        BigDecimal amount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();

        // 转账失败终态：失败回调已把冻结退回可用余额、佣金记录退回 REJECTED，需重新冻结 + 重新置为提现中
        if (wr.getStatus() == WithdrawalStatus.FAILED) {
            Distributor locked = distributorRepository.findByIdWithLock(d.getId()).orElse(d);
            if (locked.getAvailableBalance().compareTo(wr.getAmount()) < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员可提现余额不足，无法重新发起转账");
            }
            locked.setAvailableBalance(locked.getAvailableBalance().subtract(wr.getAmount()));
            locked.setFrozenBalance(locked.getFrozenBalance().add(wr.getAmount()));
            distributorRepository.save(locked);
            d = locked;

            for (CommissionRecord cr : commissionRecordRepository.findByWithdrawalId(wr.getId())) {
                if (cr.getStatus() == CommissionStatus.REJECTED) {
                    cr.setStatus(CommissionStatus.WITHDRAWING);
                    commissionRecordRepository.save(cr);
                }
            }
        }

        // 清除上次转账痕迹后重新发起（保留原 out_bill_no：微信支持失败后原单号重试，换新单号有重复打款风险）
        wr.setStatus(WithdrawalStatus.APPROVED);
        wr.setFailReason(null);
        wr.setTransferBillNo(null);
        wr.setPackageInfo(null);

        boolean transferred = tryWxpayTransfer(wr, d, amount);
        withdrawalRecordRepository.save(wr);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", wr.getId());
        result.put("transferred", transferred);
        result.put("status", wr.getStatus().name());
        result.put("out_bill_no", wr.getOutBillNo());
        result.put("transfer_bill_no", wr.getTransferBillNo());
        result.put("fail_reason", wr.getFailReason());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 阶梯佣金配置 ──
    // ════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCommissionTiers() {
        return tierRepository.findAllByOrderByTierOrderAsc().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("tier_order", t.getTierOrder());
            m.put("rate", t.getRate());
            m.put("enabled", t.isEnabled());
            return m;
        }).toList();
    }

    @Override
    @Transactional
    public void updateCommissionTiers(List<Map<String, Object>> tiers) {
        if (tiers == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "阶梯配置不能为空");
        }
        // 删除旧配置
        tierRepository.deleteAll();

        // 创建新配置
        for (Map<String, Object> tier : tiers) {
            CommissionTier t = new CommissionTier();
            t.setTierOrder(toInt(tier.get("tier_order"), 1));
            BigDecimal rate = toBigDecimal(tier.get("rate"), BigDecimal.ONE);
            t.setRate(rate.setScale(4, RoundingMode.HALF_UP));
            t.setEnabled(toBool(tier.get("enabled"), true));
            tierRepository.save(t);
        }
        log.info("Commission tiers updated: {} tiers", tiers.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  ── 辅助方法 ──
    // ════════════════════════════════════════════════════════════════

    private DistributionRule getOrCreateRule() {
        return ruleRepository.getRule().orElseGet(() -> {
            DistributionRule r = new DistributionRule();
            ruleRepository.save(r);
            log.info("Default distribution rule created");
            return r;
        });
    }

    /** 结算延迟天数（只读场景安全读取，无规则时用默认 7 天） */
    private int settleDelayDays() {
        return ruleRepository.getRule().map(DistributionRule::getSettleDelayDays).orElse(7);
    }

    /** 结算截止时间：订单完成时间早于此时间才可结算（即"完成后 N 天"） */
    private LocalDateTime settleCutoff() {
        return LocalDateTime.now().minusDays(settleDelayDays());
    }

    /**
     * 批量计算佣金记录是否"可结算"（展示用）：
     * 仅对 PENDING 判定——订单已完成（COMPLETED）且完成时间早于结算截止时间；
     * 其余状态（已结算/申请中/已提现/结算拒绝/已取消）不可结算，置 false。
     */
    private void enrichSettlable(List<CommissionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = settleCutoff();
        Set<UUID> orderIds = records.stream().map(CommissionRecord::getOrderId).collect(Collectors.toSet());
        Map<UUID, Order> orderMap = orderIds.isEmpty() ? Map.of()
                : orderRepository.findAllById(orderIds).stream().collect(Collectors.toMap(Order::getId, o -> o));
        for (CommissionRecord cr : records) {
            if (cr.getStatus() != CommissionStatus.PENDING) {
                cr.setSettlable(false);
                continue;
            }
            Order o = orderMap.get(cr.getOrderId());
            cr.setSettlable(o != null && o.getStatus() == OrderStatus.COMPLETED
                    && o.getCompletedAt() != null && o.getCompletedAt().isBefore(cutoff));
        }
    }

    private Distributor requireDistributorByUserId(UUID userId) {
        return distributorRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分销员不存在"));
    }

    /**
     * 佣金比例优先级：
     * 1. 分销员 × 商品 自定义比例
     * 2. 商品 自定义比例
     * 3. 分销员 默认比例
     * 4. 全局默认比例
     */
    private BigDecimal resolveCommissionRate(Distributor d, UUID productId, DistributionRule rule) {
        // 1. 分销员 × 商品 自定义比例
        Optional<DistributorProductRate> dpr = distributorProductRateRepository
                .findByDistributorIdAndProductId(d.getId(), productId);
        if (dpr.isPresent()) {
            return dpr.get().getCustomRate();
        }
        // 2. 商品自定义比例
        Optional<ProductCommission> pc = productCommissionRepository.findByProductId(productId);
        if (pc.isPresent() && pc.get().getCustomRate() != null) {
            return pc.get().getCustomRate();
        }
        // 3. 分销员默认比例
        if (d.getCustomRate() != null) {
            return d.getCustomRate();
        }
        // 4. 全局默认比例
        return rule.getDefaultRate();
    }

    /**
     * 商品是否参与分销（新语义：默认不分销）。
     * 只有存在 product_commission 记录且未被排除（is_excluded=false）的商品才参与分销。
     */
    private boolean isProductExcluded(UUID productId) {
        return productCommissionRepository.findByProductId(productId)
                .map(pc -> pc.isExcluded())
                .orElse(true);
    }

    /** 商品是否已开启分销（存在 product_commission 记录且未被排除） */
    private boolean isProductDistributable(UUID productId) {
        return productCommissionRepository.findByProductId(productId)
                .map(pc -> !pc.isExcluded())
                .orElse(false);
    }

    private BigDecimal resolveTierRate(int tierOrder) {
        List<CommissionTier> tiers = tierRepository.findByEnabledTrueOrderByTierOrderAsc();
        if (tiers.isEmpty()) {
            return BigDecimal.ONE;
        }
        // 查找匹配的阶梯
        for (CommissionTier t : tiers) {
            if (t.getTierOrder() == tierOrder) {
                return t.getRate();
            }
        }
        // 超过最大阶梯数：第 N+1 次起不再返佣（设计文档：超档 0%）
        log.info("Tier order {} exceeds max tier {}, no commission", tierOrder,
                tiers.get(tiers.size() - 1).getTierOrder());
        return BigDecimal.ZERO;
    }

    private String generateDistributorCode() {
        String datePart = LocalDate.now().format(DATE_FMT);
        for (int i = 0; i < 20; i++) {
            int seq = ThreadLocalRandom.current().nextInt(10000);
            String code = String.format("D%s%04d", datePart, seq);
            if (distributorRepository.findByDistributorCode(code).isEmpty()) {
                return code;
            }
        }
        return "D" + datePart + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String generateUniqueInviteCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode(8);
            if (distributorRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        return randomCode(8);
    }

    private String generateUniqueLinkCode() {
        for (int i = 0; i < 20; i++) {
            String code = randomCode(6);
            if (promotionLinkRepository.findByLinkCode(code).isEmpty()) {
                return code;
            }
        }
        return randomCode(6);
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(rnd.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private String buildPromotionUrl(String linkCode) {
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://noepay.cn";
        return base + (base.endsWith("/") ? "" : "/") + "p/" + linkCode;
    }

    private Pageable toPageable(int page, int pageSize) {
        return PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(pageSize, 1), 100));
    }

    /** 内存分页辅助（用于已过滤的集合列表） */
    private <T> List<T> paginate(List<T> all, int page, int pageSize) {
        if (all == null || all.isEmpty()) return List.of();
        int size = Math.min(Math.max(pageSize, 1), 100);
        int start = Math.max(page - 1, 0) * size;
        if (start >= all.size()) return List.of();
        return all.subList(start, Math.min(start + size, all.size()));
    }

    /** 快捷日期区间解析：返回 [from, toExclusive)，null 表示"全部" */
    private LocalDateTime[] resolveRange(String range, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        String r = range == null || range.isBlank() ? "all" : range;
        switch (r) {
            case "today" -> { from = today; to = today; }
            case "yesterday" -> { from = today.minusDays(1); to = today.minusDays(1); }
            case "thisMonth" -> { from = today.withDayOfMonth(1); to = today; }
            case "lastMonth" -> {
                LocalDate lastMonth = today.minusMonths(1);
                from = lastMonth.withDayOfMonth(1);
                to = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
            }
            case "thisYear" -> { from = today.withDayOfYear(1); to = today; }
            case "custom" -> {
                if (from == null) return null;
                if (to == null) to = from;
            }
            default -> { return null; } // all
        }
        return new LocalDateTime[]{ from.atStartOfDay(), to.plusDays(1).atStartOfDay() };
    }

    /** 上一周期区间解析（用于环比），null 表示无环比 */
    private LocalDateTime[] prevRange(String range, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        String r = range == null || range.isBlank() ? "all" : range;
        switch (r) {
            case "today" -> { from = today.minusDays(1); to = today.minusDays(1); }
            case "yesterday" -> { from = today.minusDays(2); to = today.minusDays(2); }
            case "thisMonth" -> {
                LocalDate prevMonth = today.minusMonths(1);
                from = prevMonth.withDayOfMonth(1);
                to = prevMonth.withDayOfMonth(prevMonth.lengthOfMonth());
            }
            case "lastMonth" -> {
                LocalDate prevPrev = today.minusMonths(2);
                from = prevPrev.withDayOfMonth(1);
                to = prevPrev.withDayOfMonth(prevPrev.lengthOfMonth());
            }
            case "thisYear" -> { from = today.minusYears(1).withDayOfYear(1); to = today.minusYears(1).withDayOfYear(today.minusYears(1).lengthOfYear()); }
            case "custom" -> {
                if (from == null) return null;
                if (to == null) to = from;
                long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
                to = from.minusDays(1);
                from = from.minusDays(days);
            }
            default -> { return null; } // all
        }
        return new LocalDateTime[]{ from.atStartOfDay(), to.plusDays(1).atStartOfDay() };
    }

    private long countCreatedBetween(List<Distributor> all, LocalDateTime from, LocalDateTime to) {
        return all.stream().filter(d -> d.getCreatedAt() != null && isBetween(d.getCreatedAt(), from, to)).count();
    }

    private boolean isBetween(LocalDateTime t, LocalDateTime from, LocalDateTime toExclusive) {
        return !t.isBefore(from) && t.isBefore(toExclusive);
    }

    private Map<String, Object> pageResult(List<?> items, long total, int page, int pageSize) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", items);
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("page_size", pageSize);
        pagination.put("total", total);
        m.put("pagination", pagination);
        return m;
    }

    private DistributorStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return DistributorStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的分销员状态: " + status);
        }
    }

    private CommissionStatus parseCommissionStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return CommissionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的佣金状态: " + status);
        }
    }

    private WithdrawalStatus parseWithdrawalStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return WithdrawalStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的提现状态: " + status);
        }
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object o, BigDecimal def) {
        if (o == null) return def;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean toBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }

    /** 系数(0.05) → 百分比(5.00)，API 统一对外返回百分比 */
    private BigDecimal rateToPercent(BigDecimal rate) {
        return rate == null ? null : rate.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    /** 百分比(5.00) → 系数(0.05)，API 统一接收百分比并转为系数存储 */
    private BigDecimal percentToRate(BigDecimal percent) {
        return percent == null ? null : percent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return def;
        }
    }

    // ── 微信 OAuth 配置 ──

    /** 微信网页授权（公众号）配置：appid + app_secret */
    private record WechatOauthConfig(String appid, String secret) {
    }

    /**
     * 从原生微信支付渠道（native_wxpay）读取公众号 AppID / AppSecret，
     * 用于分销员绑定微信（OAuth snsapi_base 换取 openid）。
     * 渠道未配置或缺少 app_secret 时返回 null。
     */
    private WechatOauthConfig findWechatOauthConfig() {
        // 优先使用「公众号配置」（网站设置 → 公众号配置），未配置则回退到支付渠道
        try {
            if (wechatMpConfigService.isConfigured()) {
                Map<String, Object> cfg = wechatMpConfigService.getConfig();
                String appid = String.valueOf(cfg.get("appid"));
                String secret = String.valueOf(cfg.get("appsecret"));
                if (appid != null && !appid.isBlank() && !"null".equals(appid)
                        && secret != null && !secret.isBlank() && !"null".equals(secret)) {
                    return new WechatOauthConfig(appid, secret);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load wechat mp config: {}", e.getMessage());
        }
        try {
            PaymentChannel channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted("native_wxpay", 0).orElse(null);
            if (channel == null) return null;
            Map<String, String> cfg = parseConfigData(channel.getConfigData());
            String appid = cfg.get("appid");
            String secret = cfg.get("app_secret");
            if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
                return null;
            }
            return new WechatOauthConfig(appid, secret);
        } catch (Exception e) {
            log.warn("Failed to load wechat oauth config: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseConfigData(String configData) {
        if (configData == null || configData.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(configData,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse channel config_data: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 站点名称（用于海报展示） */
    private String siteName() {
        return siteConfigRepository.findByConfigKey("site_name")
                .map(c -> c.getConfigValue())
                .filter(v -> v != null && !v.isBlank())
                .orElse("Nova Key");
    }

    /** 站点 Logo 完整 URL（用于海报展示） */
    private String siteLogo() {
        String logo = siteConfigRepository.findByConfigKey("site_logo")
                .map(c -> c.getConfigValue())
                .filter(v -> v != null && !v.isBlank())
                .orElse("");
        if (logo.isBlank()) return "";
        if (logo.startsWith("http://") || logo.startsWith("https://")) return logo;
        return trimTrailingSlash(baseUrl) + (logo.startsWith("/") ? "" : "/") + logo;
    }

    /** 海报二维码图片 URL（复用前端 /qr-image 路由生成 PNG，支持微信长按识别） */
    private String buildQrUrl(String linkUrl) {
        return trimTrailingSlash(baseUrl) + "/qr-image?url=" + URLEncoder.encode(linkUrl, StandardCharsets.UTF_8)
                + "&size=400";
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 从微信 API 返回的 JSON 中提取指定字段值 */
    private String extractJsonField(String body, String field) {
        if (body == null || body.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            log.warn("Failed to parse wechat json field {}: {}", field, e.getMessage());
            return null;
        }
    }

    /** 脱敏 openid：保留前 6 位与后 4 位，中间省略 */
    private static String maskOpenid(String openid) {
        if (openid == null || openid.isBlank()) return null;
        if (openid.length() <= 12) return openid.substring(0, 3) + "****";
        return openid.substring(0, 6) + "****" + openid.substring(openid.length() - 4);
    }

    // ── Map 转换 ──

    private Map<String, Object> distributorToMap(Distributor d, User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("user_id", d.getUserId());
        m.put("username", user != null ? user.getUsername() : null);
        m.put("email", user != null ? user.getEmail() : null);
        m.put("distributor_code", d.getDistributorCode());
        m.put("status", d.getStatus().name());
        m.put("custom_rate", rateToPercent(d.getCustomRate()));
        m.put("default_rate", rateToPercent(getOrCreateRule().getDefaultRate()));
        m.put("parent_id", d.getParentId());
        m.put("sub_rate", rateToPercent(d.getSubRate()));
        m.put("wechat_bound", d.getWechatOpenid() != null && !d.getWechatOpenid().isBlank());
        m.put("total_commission", d.getTotalCommission());
        m.put("available_balance", d.getAvailableBalance());
        // 可结算余额（待结算中订单已完成且超过结算延迟期，可直接申请提现，未计入可提现余额账本）
        m.put("settlable_balance", nullSafe(commissionRecordRepository
                .sumSettlablePendingByDistributor(d.getId(), settleCutoff())).setScale(2, RoundingMode.HALF_UP));
        m.put("frozen_balance", d.getFrozenBalance());
        m.put("withdrawn_amount", d.getWithdrawnAmount());
        m.put("invite_code", d.getInviteCode());
        m.put("approved_at", d.getApprovedAt());
        m.put("disabled_at", d.getDisabledAt());
        m.put("reject_reason", d.getRejectReason());
        m.put("rejected_at", d.getRejectedAt());
        m.put("created_at", d.getCreatedAt());
        return m;
    }

    private Map<String, Object> ruleToMap(DistributionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("default_rate", r.getDefaultRate());
        m.put("enabled", r.isEnabled());
        m.put("auto_approve", r.isAutoApprove());
        m.put("min_withdraw_amount", r.getMinWithdrawAmount());
        m.put("settle_delay_days", r.getSettleDelayDays());
        m.put("withdraw_fee_rate", r.getWithdrawFeeRate());
        m.put("binding_protection_days", r.getBindingProtectionDays());
        m.put("tier_enabled", r.isTierEnabled());
        m.put("sub_distribution_enabled", r.isSubDistributionEnabled());
        m.put("default_sub_rate", r.getDefaultSubRate());
        return m;
    }

    private Map<String, Object> linkToMap(PromotionLink pl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pl.getId());
        m.put("distributor_id", pl.getDistributorId());
        m.put("product_id", pl.getProductId());
        m.put("link_code", pl.getLinkCode());
        m.put("url", buildPromotionUrl(pl.getLinkCode()));
        m.put("click_count", pl.getClickCount());
        m.put("unique_click_count", pl.getUniqueClickCount());
        m.put("paid_count", pl.getPaidCount());
        m.put("total_sales", pl.getTotalSales());
        m.put("total_commission", pl.getTotalCommission());
        m.put("created_at", pl.getCreatedAt());
        return m;
    }

    /** 前台佣金明细：在基础字段上补充来源（自己推广/下级抽成）与订单信息 */
    private Map<String, Object> commissionDetailToMap(CommissionRecord cr, UUID me, Set<String> parentItemKeys) {
        Map<String, Object> m = commissionToMap(cr);
        boolean fromSub = parentItemKeys.contains(String.valueOf(cr.getOrderId()) + ":" + String.valueOf(cr.getOrderItemId()));
        m.put("source_type", fromSub ? "SUB" : "SELF");
        m.put("source_label", fromSub ? "下级抽成" : "自己推广");
        m.put("commission_rate_percent", rateToPercent(cr.getCommissionRate()));
        // 兼容前端旧字段名
        m.put("rate", rateToPercent(cr.getCommissionRate()));
        orderRepository.findById(cr.getOrderId()).ifPresent(o -> {
            m.put("order_status", o.getStatus().name());
            m.put("paid_at", o.getPaidAt());
        });
        if (fromSub) {
            commissionRecordRepository
                    .findByOrderIdAndOrderItemIdAndParentDistributorId(cr.getOrderId(), cr.getOrderItemId(), me)
                    .stream().findFirst()
                    .flatMap(seller -> distributorRepository.findById(seller.getDistributorId()))
                    .flatMap(sd -> userRepository.findById(sd.getUserId()))
                    .ifPresent(u -> m.put("seller_name", u.getUsername()));
        }
        return m;
    }

    private Map<String, Object> commissionToMap(CommissionRecord cr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cr.getId());
        m.put("distributor_id", cr.getDistributorId());
        m.put("order_id", cr.getOrderId());
        m.put("order_item_id", cr.getOrderItemId());
        m.put("product_id", cr.getProductId());
        m.put("product_title", cr.getProductTitle());
        m.put("order_amount", cr.getOrderAmount());
        m.put("commission_rate", cr.getCommissionRate());
        m.put("commission_amount", cr.getCommissionAmount());
        m.put("tier_order", cr.getTierOrder());
        m.put("status", cr.getStatus().name());
        m.put("parent_distributor_id", cr.getParentDistributorId());
        m.put("parent_commission_amount", cr.getParentCommissionAmount());
        m.put("settled_at", cr.getSettledAt());
        m.put("created_at", cr.getCreatedAt());
        m.put("withdrawal_id", cr.getWithdrawalId());
        m.put("settlable", Boolean.TRUE.equals(cr.getSettlable()));
        return m;
    }

    /**
     * 提现收款账户展示：微信昵称 + 脱敏 openid（转账到零钱收款账号）；未绑定微信返回 null（前端显示 —）。
     */
    private String buildAccountInfo(Distributor d) {
        if (d == null) return null;
        String nickname = d.getWechatNickname();
        String openid = d.getWechatOpenid();
        boolean hasNickname = nickname != null && !nickname.isBlank();
        boolean hasOpenid = openid != null && !openid.isBlank();
        if (!hasNickname && !hasOpenid) return null;
        String masked = hasOpenid ? maskWechatOpenid(openid) : null;
        if (hasNickname && masked != null) return nickname + " (" + masked + ")";
        if (hasNickname) return nickname;
        return masked;
    }

    /** 脱敏 openid：保留首尾，如 oxxxxxxxx****xxxx */
    private String maskWechatOpenid(String openid) {
        if (openid.length() <= 8) {
            return openid.substring(0, 1) + "***" + openid.substring(openid.length() - 1);
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }

    private Map<String, Object> withdrawalToMap(WithdrawalRecord wr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", wr.getId());
        m.put("distributor_id", wr.getDistributorId());
        m.put("amount", wr.getAmount());
        m.put("fee", wr.getFee());
        m.put("actual_amount", wr.getActualAmount());
        m.put("status", wr.getStatus().name());
        m.put("fail_reason", wr.getFailReason());
        m.put("out_bill_no", wr.getOutBillNo());
        m.put("transfer_bill_no", wr.getTransferBillNo());
        // 转账中时返回 package_info，供分销员在微信内拉起确认收款（设计文档 8.3）
        m.put("package_info", wr.getPackageInfo());
        m.put("applied_at", wr.getAppliedAt());
        m.put("approved_at", wr.getApprovedAt());
        m.put("transferred_at", wr.getTransferredAt());
        m.put("completed_at", wr.getCompletedAt());
        m.put("created_at", wr.getCreatedAt());
        return m;
    }
}
