package com.orionkey.service;

import com.orionkey.common.PageResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminCardKeyService {

    List<?> getStockSummary(UUID productId, UUID specId);

    Map<String, Object> importCardKeys(Map<String, Object> request, UUID importedBy);

    PageResult<?> getImportBatches(UUID productId, int page, int pageSize);

    void invalidateCardKey(UUID id);

    int batchInvalidateCardKeys(UUID productId, UUID specId);

    List<?> getCardKeysByOrder(UUID orderId);

    PageResult<?> listCardKeys(UUID productId, UUID specId, String status, String keyword, int page, int pageSize);

    /** 全局已售出卡密记录（商品/金额/卡密/售出时间/用户/推广员，默认 10 条/页） */
    PageResult<?> listSoldRecords(String keyword, int page, int pageSize);
}
