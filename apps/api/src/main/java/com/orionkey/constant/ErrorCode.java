package com.orionkey.constant;

public final class ErrorCode {

    private ErrorCode() {}

    // ── 通用 10001~10099 ──
    public static final int BAD_REQUEST = 10001;
    public static final int UNAUTHORIZED = 10002;
    public static final int FORBIDDEN = 10003;
    public static final int NOT_FOUND = 10004;
    public static final int TOO_MANY_REQUESTS = 10005;
    public static final int SERVER_ERROR = 10006;
    public static final int MAINTENANCE = 10007;

    // ── Auth 20001~20099 ──
    public static final int USERNAME_EXISTS = 20001;
    public static final int EMAIL_EXISTS = 20002;
    public static final int CAPTCHA_INVALID = 20003;
    public static final int INVALID_CREDENTIALS = 20004;
    public static final int OLD_PASSWORD_WRONG = 20005;
    public static final int ACCOUNT_DISABLED = 20006;
    public static final int ACCOUNT_LOCKED = 20007;
    public static final int EMAIL_CODE_INVALID = 20008;
    public static final int PHONE_EXISTS = 20009;
    public static final int PHONE_NOT_REGISTERED = 20010;
    public static final int PHONE_LOGIN_DISABLED = 20011;
    public static final int ADMIN_PHONE_LOGIN_FORBIDDEN = 20012;
    public static final int MUST_CHANGE_PASSWORD = 20013;
    public static final int PASSWORD_MISMATCH = 20014;
    public static final int WEAK_PASSWORD = 20015;
    public static final int PHONE_FEATURE_DISABLED = 20016;
    public static final int EMAIL_NOT_BOUND = 20017;
    /** 当前账号已绑定手机号/邮箱，不可重复绑定（更换需联系客服） */
    public static final int BIND_ALREADY_BOUND = 20018;
    /** 目标账号已同时绑定手机号与邮箱（完整账号），不允许绑定合并 */
    public static final int TARGET_ACCOUNT_COMPLETE = 20019;
    /** 后台关闭了账号合并，且目标标识已被其他账号占用 */
    public static final int MERGE_DISABLED = 20020;
    /** 双方账号均为推广员，无法绑定合并 */
    public static final int MERGE_DUAL_DISTRIBUTOR = 20021;
    /** 短信服务未启用（后台未开启 sms_enabled） */
    public static final int SMS_NOT_ENABLED = 20022;
    /** 短信服务未配置（AK/SK/签名/模板缺失） */
    public static final int SMS_NOT_CONFIGURED = 20023;
    /** 短信发送失败（阿里云返回错误） */
    public static final int SMS_SEND_FAILED = 20024;

    // ── Product 30001~30099 ──
    public static final int PRODUCT_NOT_FOUND = 30001;
    public static final int INSUFFICIENT_STOCK = 30002;
    public static final int SPEC_NOT_FOUND = 30003;
    public static final int PURCHASE_LIMIT_EXCEEDED = 30004;
    public static final int SPEC_HAS_CARD_KEYS = 30005;
    public static final int SPEC_NAME_DUPLICATE = 30006;

    // ── Order 40001~40099 ──
    public static final int ORDER_NOT_FOUND = 40001;
    public static final int ORDER_EXPIRED = 40002;
    public static final int ORDER_NOT_PAID = 40003;
    public static final int ORDER_OUT_OF_STOCK = 40004;
    public static final int ORDER_ALREADY_DELIVERED = 40005;
    public static final int PURCHASE_LIMIT = 40006;
    public static final int UNPAID_ORDER_EXISTS = 40007;
    public static final int CART_EMPTY = 40008;

    // ── Payment 50001~50099 ──
    public static final int CHANNEL_UNAVAILABLE = 50001;
    public static final int WEBHOOK_VERIFY_FAIL = 50002;
    public static final int TXID_INVALID_FORMAT = 50003;
    public static final int TXID_ALREADY_USED = 50004;
    public static final int TXID_VERIFY_FAILED = 50005;
    public static final int ORDER_NOT_USDT = 50006;

    // ── Security 60001~60099 ──
    public static final int TURNSTILE_FAILED = 60001;
    public static final int DEVICE_RATE_LIMITED = 60002;

    // ── Admin 70001~70099 ──
    public static final int CATEGORY_NAME_EXISTS = 70001;
    public static final int CATEGORY_HAS_PRODUCTS = 70002;
    public static final int CARD_KEY_FORMAT_ERROR = 70003;
    public static final int CARD_KEY_DUPLICATE = 70004;
    public static final int PAYMENT_CONFIG_INCOMPLETE = 70005;

    // ── Distribution 80001~80099 ──
    public static final int DISTRIBUTOR_NOT_FOUND = 80001;
    public static final int DISTRIBUTOR_ALREADY_EXISTS = 80002;
    public static final int DISTRIBUTOR_NOT_APPROVED = 80003;
    public static final int DISTRIBUTOR_DISABLED = 80004;
    public static final int INSUFFICIENT_BALANCE = 80005;
    public static final int WITHDRAWAL_AMOUNT_TOO_SMALL = 80006;
    public static final int WECHAT_NOT_BOUND = 80007;
    public static final int DISTRIBUTION_DISABLED = 80008;
    public static final int PRODUCT_EXCLUDED = 80009;
}
