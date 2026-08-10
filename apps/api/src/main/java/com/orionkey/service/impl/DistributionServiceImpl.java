package com.orionkey.service.impl;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.constant.DistributorStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.*;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.DistributionService;
import com.orionkey.service.NotificationService;
import com.orionkey.service.UserMessageService;
import com.orionkey.service.WxpayService;
import com.orionkey.service.WxpayService.WxpayConfig;
import com.orionkey.service.WxpayService.WxpayTransferResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${app.base-url:https://noepay.cn}")
    private String baseUrl;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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

        // 上级分销员
        if (inviteCode != null && !inviteCode.isBlank()) {
            distributorRepository.findByInviteCode(inviteCode.trim()).ifPresent(parent -> {
                d.setParentId(parent.getId());
            });
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
    public Map<String, Object> getDistributorStats(UUID userId) {
        Distributor d = requireDistributorByUserId(userId);
        UUID distId = d.getId();

        long promotionProductCount = promotionLinkRepository.findByDistributorId(distId, PageRequest.of(0, 1))
                .getTotalElements();
        long totalClicks = clickRepository.countByDistributorId(distId);

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal pendingCommission = nullSafe(commissionRecordRepository.sumByDistributorAndStatus(distId, CommissionStatus.PENDING));
        long subCount = distributorRepository.findByParentId(distId).size();
        long customerCount = customerBindingRepository.countByDistributorId(distId);

        // 汇总推广链接的销售额
        Page<PromotionLink> links = promotionLinkRepository.findByDistributorId(distId, PageRequest.of(0, Integer.MAX_VALUE));
        for (PromotionLink pl : links.getContent()) {
            if (pl.getTotalSales() != null) {
                totalSales = totalSales.add(pl.getTotalSales());
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promotion_product_count", promotionProductCount);
        m.put("total_clicks", totalClicks);
        m.put("total_sales", totalSales.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_commission", pendingCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("available_balance", d.getAvailableBalance());
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
        Page<Distributor> dp = distributorRepository.findAdminList(statusEnum, keyword,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
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
        m.put("pending_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)));
        m.put("settled_commission", nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED)));
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
            return m;
        }).toList();
        return pageResult(items, pp.getTotalElements(), page, pageSize);
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
        Page<CommissionRecord> cp = commissionRecordRepository.findAdminList(distributorId, statusEnum,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                pageable);

        Set<UUID> distIds = cp.getContent().stream().map(CommissionRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));

        List<Map<String, Object>> items = cp.getContent().stream().map(cr -> {
            Map<String, Object> m = commissionToMap(cr);
            Distributor d = distMap.get(cr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            return m;
        }).toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> adminCommissionStats() {
        List<Distributor> all = distributorRepository.findAll();
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal pendingTotal = BigDecimal.ZERO;
        BigDecimal settledTotal = BigDecimal.ZERO;
        BigDecimal cancelledTotal = BigDecimal.ZERO;
        BigDecimal todayTotal = BigDecimal.ZERO;
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        for (Distributor d : all) {
            totalCommission = totalCommission.add(nullSafe(d.getTotalCommission()));
            pendingTotal = pendingTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)));
            settledTotal = settledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED)));
            cancelledTotal = cancelledTotal.add(nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.CANCELLED)));
        }

        // 今日佣金
        for (CommissionRecord cr : commissionRecordRepository.findAll()) {
            if (cr.getCreatedAt() != null && !cr.getCreatedAt().isBefore(todayStart) && cr.getStatus() != CommissionStatus.CANCELLED) {
                todayTotal = todayTotal.add(nullSafe(cr.getCommissionAmount()));
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_commission", totalCommission.setScale(2, RoundingMode.HALF_UP));
        m.put("pending_commission", pendingTotal.setScale(2, RoundingMode.HALF_UP));
        m.put("settled_commission", settledTotal.setScale(2, RoundingMode.HALF_UP));
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
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findAdminList(statusEnum,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null,
                pageable);

        Set<UUID> distIds = wp.getContent().stream().map(WithdrawalRecord::getDistributorId).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));

        List<Map<String, Object>> items = wp.getContent().stream().map(wr -> {
            Map<String, Object> m = withdrawalToMap(wr);
            Distributor d = distMap.get(wr.getDistributorId());
            m.put("distributor_code", d != null ? d.getDistributorCode() : null);
            return m;
        }).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
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
            String outBillNo = "WD" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            String transferNotifyUrl = baseUrl.replaceAll("/+$", "") + "/api/payments/webhook/wxpay-transfer";

            WxpayTransferResult result = wxpayService.createTransfer(
                    config, outBillNo, d.getWechatOpenid(), amount, "佣金提现", transferNotifyUrl);

            wr.setOutBillNo(outBillNo);
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
        log.info("Withdrawal {} rejected, distributor {} available += {}", id, d.getId(), amount);

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
                ? all.stream().map(d -> nullSafe(commissionRecordRepository.sumByDistributorAndStatus(d.getId(), CommissionStatus.PENDING)))
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
            // 分销人
            PromotionLink pl = o.getPromotionLinkId() != null ? linkMap.get(o.getPromotionLinkId()) : null;
            Distributor dist = pl != null ? distMap.get(pl.getDistributorId()) : null;
            it.put("distributor_name", dist != null ? dist.getDistributorCode() : "—");
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
        List<Product> all = productRepository.findPublicProducts(null, Pageable.unpaged()).getContent();
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
        Page<PromotionLink> lp = promotionLinkRepository.findByDistributorId(d.getId(), Pageable.unpaged());

        List<Map<String, Object>> all = lp.getContent().stream()
                // 最新推广的商品排在前面
                .sorted(Comparator.comparing(PromotionLink::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(pl -> pl.getProductId() != null)
                // 仅展示仍在分销的商品（已取消分销的推广商品不再可见）
                .filter(pl -> isProductDistributable(pl.getProductId()))
                .map(pl -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("link_id", pl.getId());
                    m.put("link_code", pl.getLinkCode());
                    m.put("product_id", pl.getProductId());
                    m.put("click_count", pl.getClickCount());
                    m.put("paid_count", pl.getPaidCount());
                    m.put("total_sales", pl.getTotalSales());
                    m.put("total_commission", pl.getTotalCommission());
                    productRepository.findById(pl.getProductId()).ifPresent(p -> {
                        m.put("product_title", p.getTitle());
                        m.put("cover_url", p.getCoverUrl());
                        m.put("base_price", p.getBasePrice());
                        DistributionRule r = getOrCreateRule();
                        ProductCommission pc = productCommissionRepository.findByProductId(p.getId()).orElse(null);
                        BigDecimal rate = pc != null && pc.getCustomRate() != null ? pc.getCustomRate() : r.getDefaultRate();
                        m.put("default_rate", rateToPercent(r.getDefaultRate()));
                        m.put("custom_rate", rateToPercent(pc != null ? pc.getCustomRate() : null));
                        m.put("commission_rate", rateToPercent(rate));
                        m.put("commission_amount", p.getBasePrice().multiply(rate).setScale(2, RoundingMode.HALF_UP));
                    });
                    return m;
                }).toList();
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

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("link_url", linkUrl);
        m.put("qr_url", buildQrUrl(linkUrl));
        m.put("distributor_code", d.getDistributorCode());
        m.put("store_name", siteName());
        m.put("store_logo", siteLogo());
        m.put("default_rate", rateToPercent(rule.getDefaultRate()));
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
            cp = commissionRecordRepository.findAdminList(me, statusEnum, null, null, pageable);
        } else {
            cp = commissionRecordRepository.findByDistributorIdOrderByCreatedAtDesc(me, pageable);
        }
        // 我作为上级抽成的订单项 key 集合（用于区分"自己推广 / 下级抽成"）
        Set<String> parentItemKeys = commissionRecordRepository.findParentCommissionItemKeys(me).stream()
                .map(row -> String.valueOf(row[0]) + ":" + String.valueOf(row[1]))
                .collect(Collectors.toSet());
        List<Map<String, Object>> items = cp.getContent().stream()
                .map(cr -> commissionDetailToMap(cr, me, parentItemKeys))
                .toList();
        return pageResult(items, cp.getTotalElements(), page, pageSize);
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
    public Map<String, Object> applyWithdrawal(UUID userId, BigDecimal amount) {
        Distributor d = requireDistributorByUserId(userId);
        if (d.getStatus() != DistributorStatus.APPROVED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分销员未审核通过");
        }
        // 提现必须已绑定微信（提现通过微信支付商家转账到零钱，需收款 openid）
        if (d.getWechatOpenid() == null || d.getWechatOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先在分销中心绑定微信后再申请提现");
        }
        // S7: 悲观行锁 — 防止并发提现超扣余额（余额检查→扣减必须原子化）
        Distributor locked = distributorRepository.findByIdWithLock(d.getId()).orElse(d);
        DistributionRule rule = getOrCreateRule();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提现金额必须大于 0");
        }
        if (amount.compareTo(rule.getMinWithdrawAmount()) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提现金额不能低于最低提现金额 " + rule.getMinWithdrawAmount());
        }
        if (locked.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "可提现余额不足");
        }

        // 计算手续费
        BigDecimal fee = amount.multiply(nullSafe(rule.getWithdrawFeeRate())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualAmount = amount.subtract(fee).setScale(2, RoundingMode.HALF_UP);

        // 冻结金额
        locked.setAvailableBalance(locked.getAvailableBalance().subtract(amount));
        locked.setFrozenBalance(locked.getFrozenBalance().add(amount));
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
        log.info("Withdrawal applied: distributor={}, amount={}, actual={}", locked.getId(), amount, actualAmount);

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
    public Map<String, Object> listMyWithdrawals(UUID userId, int page, int pageSize) {
        Distributor d = requireDistributorByUserId(userId);
        Pageable pageable = toPageable(page, pageSize);
        Page<WithdrawalRecord> wp = withdrawalRecordRepository.findByDistributorIdOrderByCreatedAtDesc(d.getId(), pageable);
        List<Map<String, Object>> items = wp.getContent().stream().map(this::withdrawalToMap).toList();
        return pageResult(items, wp.getTotalElements(), page, pageSize);
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

                // 已结算的佣金需要从余额扣减
                if (oldStatus == CommissionStatus.SETTLED) {
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

        if ("FINISHED".equalsIgnoreCase(state) || "SUCCESS".equalsIgnoreCase(state)) {
            // 转账成功：冻结 → 已提现
            BigDecimal settleAmount = wr.getActualAmount() != null ? wr.getActualAmount() : wr.getAmount();
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setWithdrawnAmount(d.getWithdrawnAmount().add(settleAmount));
            distributorRepository.save(d);

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
        } else {
            // 转账失败/关闭：冻结退回可用余额
            d.setFrozenBalance(d.getFrozenBalance().subtract(wr.getAmount()));
            d.setAvailableBalance(d.getAvailableBalance().add(wr.getAmount()));
            distributorRepository.save(d);

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
        }
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
        return m;
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
