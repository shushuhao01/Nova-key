package com.orionkey.scheduler;

import com.orionkey.constant.CommissionStatus;
import com.orionkey.constant.WithdrawalStatus;
import com.orionkey.entity.CommissionRecord;
import com.orionkey.entity.Distributor;
import com.orionkey.entity.WithdrawalRecord;
import com.orionkey.repository.CommissionRecordRepository;
import com.orionkey.repository.DistributorRepository;
import com.orionkey.repository.WithdrawalRecordRepository;
import com.orionkey.service.DistributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 分销推广定时任务
 *
 * 1. 佣金结算（每小时）— PENDING 佣金超过 settle_delay_days 的转为 SETTLED
 * 2. 提现超时处理（每30分钟）— PROCESSING 超过24小时退回余额
 * 3. 余额对账（每天）— 核对余额一致性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributionScheduler {

    private final DistributionService distributionService;
    private final CommissionRecordRepository commissionRecordRepository;
    private final WithdrawalRecordRepository withdrawalRecordRepository;
    private final DistributorRepository distributorRepository;

    /**
     * 佣金结算 — 每小时执行
     * 扫描 PENDING 状态佣金记录，超过结算延迟期的转为 SETTLED，入可提现余额。
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void settlePendingCommissions() {
        try {
            distributionService.settlePendingCommissions();
        } catch (Exception e) {
            log.error("佣金结算定时任务失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 提现超时处理 — 每30分钟执行
     * PROCESSING 状态超过24小时未确认收款的提现，退回冻结余额到可提现余额。
     */
    @Scheduled(cron = "0 */30 * * * *")
    @Transactional
    public void handleWithdrawalTimeout() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<WithdrawalRecord> timeoutList = withdrawalRecordRepository.findProcessingTimeout(cutoff);
        if (timeoutList.isEmpty()) return;

        log.info("发现 {} 笔超时提现待处理", timeoutList.size());
        for (WithdrawalRecord wr : timeoutList) {
            try {
                Distributor d = distributorRepository.findById(wr.getDistributorId()).orElse(null);
                if (d == null) continue;

                // 退回冻结余额到可提现余额
                BigDecimal amount = wr.getAmount();
                d.setFrozenBalance(d.getFrozenBalance().subtract(amount));
                d.setAvailableBalance(d.getAvailableBalance().add(amount));
                distributorRepository.save(d);

                // 更新提现状态为失败
                wr.setStatus(WithdrawalStatus.FAILED);
                wr.setFailReason("超时未确认收款，自动撤销并退回余额");
                withdrawalRecordRepository.save(wr);

                log.info("提现 {} 超时处理完成，金额 {} 已退回分销员 {}", wr.getId(), amount, d.getId());
            } catch (Exception e) {
                log.error("提现 {} 超时处理失败: {}", wr.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 余额对账 — 每天凌晨2点执行
     * 核对每个分销员的余额一致性：理论可提现 = SUM(SETTLED佣金) - SUM(SUCCESS提现)
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional(readOnly = true)
    public void balanceReconciliation() {
        List<Distributor> distributors = distributorRepository.findAll();
        int anomalyCount = 0;

        for (Distributor d : distributors) {
            try {
                // 理论已结算佣金
                BigDecimal settledCommission = commissionRecordRepository
                        .sumByDistributorAndStatus(d.getId(), CommissionStatus.SETTLED.name());
                if (settledCommission == null) settledCommission = BigDecimal.ZERO;

                // 理论可提现 = 已结算佣金 - 已提现金额
                BigDecimal expectedAvailable = settledCommission.subtract(d.getWithdrawnAmount());
                BigDecimal actualAvailable = d.getAvailableBalance() != null ? d.getAvailableBalance() : BigDecimal.ZERO;

                if (expectedAvailable.compareTo(actualAvailable) != 0) {
                    anomalyCount++;
                    log.warn("余额对账异常：分销员 {} 理论可提现={} 实际可提现={} 差额={}",
                            d.getId(), expectedAvailable, actualAvailable,
                            expectedAvailable.subtract(actualAvailable));
                }
            } catch (Exception e) {
                log.error("分销员 {} 余额对账失败: {}", d.getId(), e.getMessage());
            }
        }

        if (anomalyCount > 0) {
            log.warn("余额对账完成，发现 {} 个异常分销员", anomalyCount);
        } else {
            log.info("余额对账完成，所有分销员余额一致（共 {} 人）", distributors.size());
        }
    }
}
