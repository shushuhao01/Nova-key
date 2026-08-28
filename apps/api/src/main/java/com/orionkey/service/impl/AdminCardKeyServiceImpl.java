package com.orionkey.service.impl;

import com.orionkey.common.PageResult;
import com.orionkey.constant.CardKeyStatus;
import com.orionkey.constant.ErrorCode;
import com.orionkey.entity.CardImportBatch;
import com.orionkey.entity.CardKey;
import com.orionkey.entity.Distributor;
import com.orionkey.entity.Order;
import com.orionkey.entity.OrderItem;
import com.orionkey.entity.Product;
import com.orionkey.entity.User;
import com.orionkey.exception.BusinessException;
import com.orionkey.repository.*;
import com.orionkey.service.AdminCardKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCardKeyServiceImpl implements AdminCardKeyService {

    private final CardKeyRepository cardKeyRepository;
    private final CardImportBatchRepository cardImportBatchRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final DistributorRepository distributorRepository;

    @Override
    public List<?> getStockSummary(UUID productId, UUID specId) {
        // Get all products or specific product
        List<Product> products;
        if (productId != null) {
            products = productRepository.findById(productId).map(List::of).orElse(List.of());
        } else {
            products = productRepository.findAll();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : products) {
            if (p.getIsDeleted() != 0) continue;
            var specs = productSpecRepository.findByProductIdAndIsDeletedOrderBySortOrderAsc(p.getId(), 0);

            if (specId == null) {
                // 无规格筛选：展示默认库存池（spec_id=null）+ 所有规格库存
                Map<String, Object> defaultEntry = buildStockEntry(p.getId(), p.getTitle(), null, null);
                long defaultTotal = ((Number) defaultEntry.get("total")).longValue();
                // 如果默认池有卡密，或者商品没有任何规格，则显示默认池条目
                if (defaultTotal > 0 || specs.isEmpty()) {
                    defaultEntry.put("spec_enabled", p.isSpecEnabled());
                    result.add(defaultEntry);
                }
                for (var spec : specs) {
                    Map<String, Object> entry = buildStockEntry(p.getId(), p.getTitle(), spec.getId(), spec.getName());
                    entry.put("spec_enabled", p.isSpecEnabled());
                    result.add(entry);
                }
            } else {
                // 按指定规格筛选
                for (var spec : specs) {
                    if (spec.getId().equals(specId)) {
                        Map<String, Object> entry = buildStockEntry(p.getId(), p.getTitle(), spec.getId(), spec.getName());
                        entry.put("spec_enabled", p.isSpecEnabled());
                        result.add(entry);
                    }
                }
            }
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> importCardKeys(Map<String, Object> req, UUID importedBy) {
        UUID productId = UUID.fromString((String) req.get("product_id"));
        UUID specId = req.get("spec_id") != null ? UUID.fromString((String) req.get("spec_id")) : null;
        String content = (String) req.get("content");

        productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在"));

        // 校验 specId 归属：防止传入不属于该商品的规格 ID
        if (specId != null) {
            productSpecRepository.findById(specId)
                    .filter(s -> s.getProductId().equals(productId) && s.getIsDeleted() == 0)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "规格不存在或不属于该商品"));
        }

        String[] lines = content.split("\\r?\\n");
        int total = 0, success = 0, fail = 0;
        StringBuilder failDetail = new StringBuilder();
        List<CardKey> importedCardKeys = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            total++;

            if (cardKeyRepository.existsByContentAndProductIdAndSpecId(trimmed, productId, specId)) {
                fail++;
                failDetail.append("重复: ").append(trimmed).append("\n");
                continue;
            }

            CardKey key = new CardKey();
            key.setProductId(productId);
            key.setSpecId(specId);
            key.setContent(trimmed);
            key.setStatus(CardKeyStatus.AVAILABLE);
            cardKeyRepository.save(key);
            importedCardKeys.add(key);
            success++;
        }

        if (total == 0) {
            throw new BusinessException(ErrorCode.CARD_KEY_FORMAT_ERROR, "卡密导入格式错误");
        }

        CardImportBatch batch = new CardImportBatch();
        batch.setProductId(productId);
        batch.setSpecId(specId);
        batch.setImportedBy(importedBy);
        batch.setTotalCount(total);
        batch.setSuccessCount(success);
        batch.setFailCount(fail);
        batch.setFailDetail(fail > 0 ? failDetail.toString() : null);
        cardImportBatchRepository.save(batch);

        // Update import batch id on successfully imported card keys
        for (CardKey key : importedCardKeys) {
            key.setImportBatchId(batch.getId());
            cardKeyRepository.save(key);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", batch.getId());
        result.put("product_id", batch.getProductId());
        result.put("spec_id", batch.getSpecId());
        result.put("imported_by", batch.getImportedBy());
        result.put("total_count", batch.getTotalCount());
        result.put("success_count", batch.getSuccessCount());
        result.put("fail_count", batch.getFailCount());
        result.put("fail_detail", batch.getFailDetail());
        result.put("created_at", batch.getCreatedAt());
        return result;
    }

    @Override
    public PageResult<?> getImportBatches(UUID productId, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        Page<CardImportBatch> batchPage;
        if (productId != null) {
            batchPage = cardImportBatchRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
        } else {
            batchPage = cardImportBatchRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return PageResult.of(batchPage, batchPage.getContent());
    }

    @Override
    @Transactional
    public void invalidateCardKey(UUID id) {
        CardKey key = cardKeyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "卡密不存在"));
        if (key.getStatus() == CardKeyStatus.SOLD) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已售出的卡密不可作废");
        }
        key.setStatus(CardKeyStatus.INVALID);
        cardKeyRepository.save(key);
    }

    @Override
    @Transactional
    public int batchInvalidateCardKeys(UUID productId, UUID specId) {
        return cardKeyRepository.updateStatusByProductIdAndSpecId(
                productId, specId, CardKeyStatus.AVAILABLE, CardKeyStatus.INVALID);
    }

    @Override
    public List<?> getCardKeysByOrder(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = new HashMap<>();
        for (OrderItem item : items) {
            itemMap.put(item.getId(), item);
        }

        return keys.stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("card_key_id", k.getId());
            map.put("content", k.getContent());
            OrderItem item = k.getOrderItemId() != null ? itemMap.get(k.getOrderItemId()) : null;
            map.put("product_title", item != null ? item.getProductTitle() : null);
            map.put("spec_name", item != null ? item.getSpecName() : null);
            map.put("status", k.getStatus().name());
            return map;
        }).toList();
    }

    @Override
    public PageResult<?> listCardKeys(UUID productId, UUID specId, String status, String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<CardKeyStatus> statuses = parseStatuses(status);
        Page<CardKey> keyPage;
        if (statuses != null && statuses.size() == 1 && statuses.contains(CardKeyStatus.SOLD)) {
            keyPage = cardKeyRepository.findAdminSoldList(productId, specId, kw, pageable);
        } else {
            keyPage = cardKeyRepository.findAdminList(productId, specId, statuses, kw, pageable);
        }
        var list = keyPage.getContent().stream().map(k -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", k.getId());
            map.put("content", k.getContent());
            map.put("status", k.getStatus().name());
            map.put("order_id", k.getOrderId());
            map.put("created_at", k.getCreatedAt());
            map.put("sold_at", k.getSoldAt());
            map.put("buyer", resolveBuyer(k.getOrderId()));
            return map;
        }).toList();
        return PageResult.of(keyPage, list);
    }

    @Override
    public PageResult<?> listSoldRecords(String keyword, int page, int pageSize) {
        var pageable = PageRequest.of(page - 1, pageSize);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        Page<CardKey> keyPage = cardKeyRepository.findSoldRecords(kw, pageable);

        // 批量取订单 + 规格/商品标题 + 推广员
        List<CardKey> keys = keyPage.getContent();
        Set<UUID> orderIds = keys.stream().map(CardKey::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Order> orderMap = orderIds.isEmpty() ? Map.of()
                : orderRepository.findByIdIn(orderIds.stream().toList()).stream().collect(Collectors.toMap(Order::getId, o -> o));

        Set<UUID> productIds = keys.stream().map(CardKey::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Product> productMap = productIds.isEmpty() ? Map.of()
                : productRepository.findAllById(productIds).stream().collect(Collectors.toMap(Product::getId, p -> p));

        Set<UUID> distIds = orderMap.values().stream().map(Order::getReferralDistributorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Distributor> distMap = distIds.isEmpty() ? Map.of()
                : distributorRepository.findAllById(distIds).stream().collect(Collectors.toMap(Distributor::getId, d -> d));
        Set<UUID> distUserIds = distMap.values().stream().map(Distributor::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> distUserMap = distUserIds.isEmpty() ? Map.of()
                : userRepository.findAllById(distUserIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        var list = keys.stream().map(k -> {
            Order o = k.getOrderId() != null ? orderMap.get(k.getOrderId()) : null;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", k.getId());
            map.put("content", k.getContent());
            Product p = productMap.get(k.getProductId());
            map.put("product_title", p != null ? p.getTitle() : resolveOrderProductTitle(k, o));
            map.put("amount", o != null && o.getActualAmount() != null ? o.getActualAmount() : BigDecimal.ZERO);
            map.put("sold_at", k.getSoldAt());
            map.put("buyer", resolveBuyerFromOrder(o));
            map.put("promoter", resolvePromoter(o, distMap, distUserMap));
            return map;
        }).toList();
        return PageResult.of(keyPage, list);
    }

    /** 解析状态参数：null/空=全部；unsold=未售出(AVAILABLE+LOCKED)；sold=已售出(SOLD) */
    private List<CardKeyStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.trim().toLowerCase()) {
            case "unsold" -> List.of(CardKeyStatus.AVAILABLE, CardKeyStatus.LOCKED);
            case "sold" -> List.of(CardKeyStatus.SOLD);
            default -> null;
        };
    }

    /** 依据订单解析购买用户（注册用户显示用户名，匿名显示邮箱） */
    private String resolveBuyer(UUID orderId) {
        if (orderId == null) return null;
        return orderRepository.findById(orderId).map(this::resolveBuyerFromOrder).orElse(null);
    }

    private String resolveBuyerFromOrder(Order o) {
        if (o == null) return null;
        if (o.getUserId() != null) {
            return userRepository.findById(o.getUserId()).map(User::getUsername).orElse(o.getEmail());
        }
        return o.getEmail();
    }

    private String resolveOrderProductTitle(CardKey k, Order o) {
        if (k.getOrderItemId() != null) {
            return orderItemRepository.findById(k.getOrderItemId()).map(OrderItem::getProductTitle).orElse(null);
        }
        if (o != null) {
            return orderItemRepository.findByOrderId(o.getId()).stream().findFirst()
                    .map(OrderItem::getProductTitle).orElse(null);
        }
        return null;
    }

    private String resolvePromoter(Order o, Map<UUID, Distributor> distMap, Map<UUID, User> distUserMap) {
        if (o == null || o.getReferralDistributorId() == null) return null;
        Distributor d = distMap.get(o.getReferralDistributorId());
        if (d == null) return null;
        User u = d.getUserId() != null ? distUserMap.get(d.getUserId()) : null;
        if (u != null) return (u.getUsername() != null ? u.getUsername() : u.getEmail());
        return d.getDistributorCode();
    }

    private Map<String, Object> buildStockEntry(UUID productId, String productTitle, UUID specId, String specName) {
        List<Object[]> counts = cardKeyRepository.countByProductIdAndSpecIdGroupByStatus(productId, specId);
        long total = 0, available = 0, sold = 0, locked = 0, invalid = 0;
        for (Object[] row : counts) {
            CardKeyStatus status = (CardKeyStatus) row[0];
            long cnt = (Long) row[1];
            total += cnt;
            switch (status) {
                case AVAILABLE -> available = cnt;
                case SOLD -> sold = cnt;
                case LOCKED -> locked = cnt;
                case INVALID -> invalid = cnt;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("product_id", productId);
        map.put("product_title", productTitle);
        map.put("spec_id", specId);
        map.put("spec_name", specName);
        map.put("total", total);
        map.put("available", available);
        map.put("sold", sold);
        map.put("locked", locked);
        map.put("invalid", invalid);
        return map;
    }
}
