package com.orionkey.constant;

public enum CommissionStatus {
    /** 待结算（订单未完成或未过结算期） */
    PENDING,
    /** 已结算（可提现） */
    SETTLED,
    /** 申请中（已提交提现，金额冻结） */
    WITHDRAWING,
    /** 已提现（提现成功到账） */
    WITHDRAWN,
    /** 结算拒绝（提现申请被拒，金额退回可提现，可重新勾选） */
    REJECTED,
    /** 已取消（订单退款时取消） */
    CANCELLED
}
