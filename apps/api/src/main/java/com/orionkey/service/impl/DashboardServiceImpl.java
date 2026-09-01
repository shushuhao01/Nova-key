package com.orionkey.service.impl;

import com.orionkey.constant.CardKeyStatus;
import com.orionkey.constant.OrderStatus;
import com.orionkey.entity.Order;
import com.orionkey.entity.Product;
import com.orionkey.entity.VisitStats;
import com.orionkey.repository.*;
import com.orionkey.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CardKeyRepository cardKeyRepository;
    private final VisitStatsRepository visitStatsRepository;
    private final ProductSpecRepository productSpecRepository;

    @Override
    public Map<String, Object> getStats() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime yesterdayStart = yesterday.atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();

        // Use aggregate queries instead of loading all orders
        BigDecimal todaySales = orderRepository.sumSalesSince(todayStart);
        BigDecimal monthSales = orderRepository.sumSalesSince(monthStart);
        BigDecimal yesterdaySales = orderRepository.sumSalesBetween(yesterdayStart, todayStart);
        long todayOrders = orderRepository.countPaidOrdersSince(todayStart);
        long monthOrders = orderRepository.countPaidOrdersSince(monthStart);
        long yesterdayOrders = orderRepository.countPaidOrdersBetween(yesterdayStart, todayStart);

        // PV / UV：今日、昨日、本月累计
        VisitStats todayVisit = visitStatsRepository.findByVisitDate(today).orElse(null);
        VisitStats yesterdayVisit = visitStatsRepository.findByVisitDate(yesterday).orElse(null);
        long todayPv = todayVisit != null ? todayVisit.getPv() : 0;
        long todayUv = todayVisit != null ? todayVisit.getUv() : 0;
        long yesterdayPv = yesterdayVisit != null ? yesterdayVisit.getPv() : 0;
        long yesterdayUv = yesterdayVisit != null ? yesterdayVisit.getUv() : 0;
        List<Object[]> monthStats = visitStatsRepository.sumPvUvBetween(today.withDayOfMonth(1), today.plusDays(1));
        long monthPv = 0, monthUv = 0;
        if (!monthStats.isEmpty() && monthStats.get(0) != null) {
            Object[] row = monthStats.get(0);
            monthPv = ((Number) row[0]).longValue();
            monthUv = ((Number) row[1]).longValue();
        }

        // 电商标准转化率 = 成交订单数 / UV × 100%
        double conversionRate = pct(todayOrders, todayUv);
        double monthConversionRate = pct(monthOrders, monthUv);
        double yesterdayConversionRate = pct(yesterdayOrders, yesterdayUv);

        // Low stock products — count ALL available keys per product (across all specs)
        List<Product> products = productRepository.findAll().stream()
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .toList();
        List<Map<String, Object>> lowStock = new ArrayList<>();
        for (Product p : products) {
            long available = cardKeyRepository.countByProductIdAndStatus(p.getId(), CardKeyStatus.AVAILABLE);
            if (available <= p.getLowStockThreshold()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("product_id", p.getId());
                m.put("title", p.getTitle());
                m.put("available_stock", available);
                m.put("threshold", p.getLowStockThreshold());
                lowStock.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today_sales", todaySales);
        result.put("month_sales", monthSales);
        result.put("yesterday_sales", yesterdaySales);
        result.put("today_orders", todayOrders);
        result.put("month_orders", monthOrders);
        result.put("yesterday_orders", yesterdayOrders);
        result.put("conversion_rate", round2(conversionRate));
        result.put("month_conversion_rate", round2(monthConversionRate));
        result.put("yesterday_conversion_rate", round2(yesterdayConversionRate));
        result.put("today_pv", todayPv);
        result.put("today_uv", todayUv);
        result.put("month_pv", monthPv);
        result.put("month_uv", monthUv);
        result.put("yesterday_pv", yesterdayPv);
        result.put("yesterday_uv", yesterdayUv);
        result.put("low_stock_products", lowStock);
        return result;
    }

    private static double pct(long orders, long uv) {
        return uv > 0 ? (double) orders / uv * 100 : 0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    public List<?> getSalesTrend(String period, String startDate, String endDate) {
        LocalDate today = LocalDate.now();
        LocalDate start;
        if (startDate != null) {
            start = LocalDate.parse(startDate);
        } else if ("week".equalsIgnoreCase(period)) {
            start = today.minusDays(6);
        } else if ("month".equalsIgnoreCase(period)) {
            start = today.withDayOfMonth(1);
        } else {
            start = today.minusDays(30);
        }
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : today;

        boolean monthlyGroup = "monthly".equalsIgnoreCase(period);

        // Still load filtered orders for trend grouping, but with date range filter
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay();

        List<Order> orders = orderRepository.findAll().stream()
                .filter(o -> (o.getStatus() == OrderStatus.PAID || o.getStatus() == OrderStatus.DELIVERED || o.getStatus() == OrderStatus.COMPLETED) && o.getPaidAt() != null)
                .filter(o -> !o.getPaidAt().isBefore(startDt) && o.getPaidAt().isBefore(endDt))
                .toList();

        Map<String, BigDecimal> salesMap = new TreeMap<>();
        Map<String, Integer> countMap = new TreeMap<>();

        for (Order o : orders) {
            String key;
            if (monthlyGroup) {
                key = o.getPaidAt().toLocalDate().withDayOfMonth(1).toString().substring(0, 7);
            } else {
                key = o.getPaidAt().toLocalDate().toString();
            }
            salesMap.merge(key, o.getActualAmount() != null ? o.getActualAmount() : BigDecimal.ZERO, BigDecimal::add);
            countMap.merge(key, 1, Integer::sum);
        }

        return salesMap.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("sales_amount", e.getValue());
            m.put("order_count", countMap.getOrDefault(e.getKey(), 0));
            return m;
        }).collect(Collectors.toList());
    }
}
