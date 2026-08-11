#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  支付宝网关签名自检脚本（绕过系统代码，直接手工构造请求）
#
#  用法（在服务器 /www/wwwroot/nova-key 下执行）:
#    bash test-alipay-sign.sh /path/to/app_private_key.pem [app_id]
#
#  说明:
#    1. 私钥文件 = 支付宝密钥工具生成的应用私钥（含 -----BEGIN ...----- 头）
#    2. 脚本用 openssl SHA256withRSA 签名（RSA2），构造 alipay.trade.query
#    3. 若返回 alipay_trade_query_response（code=40004 订单不存在 或 10000）
#       → 签名通过，说明系统代码/配置有问题，继续查系统侧
#    4. 若返回 error_response 或 isv.invalid-signature
#       → 支付宝侧应用公钥没生效/不匹配，问题在开放平台配置
# ═══════════════════════════════════════════════════════════════

PRIV="$1"
APP_ID="${2:-2021004145638840}"
GATEWAY="https://openapi.alipay.com/gateway.do"

if [ -z "$PRIV" ] || [ ! -f "$PRIV" ]; then
  echo "用法: bash test-alipay-sign.sh 私钥文件路径 [AppID]"
  echo "  私钥 = 支付宝密钥工具生成的应用私钥（完整 PEM 文件）"
  exit 1
fi

# 北京时间时间戳（与系统签名一致）
TS=$(TZ='Asia/Shanghai' date '+%Y-%m-%d %H:%M:%S')
TS_ENC=${TS// /%20}
OUT="NOVA_SIGN_TEST_$(date +%s)"
BIZ="{\"out_trade_no\":\"$OUT\"}"

# 支付宝签名规范：key 字典序、剔除空值、含 sign_type（与系统 buildSignContent 一致）
CONTENT="app_id=${APP_ID}&biz_content=${BIZ}&charset=utf-8&format=JSON&method=alipay.trade.query&sign_type=RSA2&timestamp=${TS}&version=1.0"

echo "=== AppID: $APP_ID ==="
echo "=== 待签名字符串 ==="
echo "  $CONTENT"
echo ""

echo "=== 用私钥签名 (SHA256withRSA=RSA2) ==="
SIGN=$(printf '%s' "$CONTENT" | openssl dgst -sha256 -sign "$PRIV" 2>/dev/null | openssl base64 -A)
if [ -z "$SIGN" ]; then
  echo "  私钥签名失败：请确认私钥文件格式正确（含 -----BEGIN ...----- 头）"
  exit 1
fi
echo "  sign=$SIGN"
echo ""

# sign 是 Base64，含 + / = 必须 URL 编码后放入 query
SIGN_ENC=$(printf '%s' "$SIGN" | sed -e 's/\+/%2B/g' -e 's|/|%2F|g' -e 's/=/%3D/g')

echo "=== 请求网关 openapi.alipay.com ==="
RESP=$(curl -s -X POST "$GATEWAY?app_id=${APP_ID}&method=alipay.trade.query&format=JSON&charset=utf-8&sign_type=RSA2&timestamp=${TS_ENC}&version=1.0&sign=${SIGN_ENC}" \
  --data-urlencode "biz_content=${BIZ}" \
  -H "Content-Type: application/x-www-form-urlencoded")
echo "$RESP"
echo ""

echo "=== 判定 ==="
if echo "$RESP" | grep -q 'alipay_trade_query_response'; then
  echo "✅ 签名验证通过！网关已接受本私钥签名（返回业务响应）。"
  echo "   → 问题在【系统代码/配置侧】，继续排查系统发出的请求。"
else
  echo "❌ 网关未接受签名（error_response / isv.invalid-signature）。"
  echo "   → 问题在【支付宝开放平台侧】：应用公钥未真正生效/不匹配。"
  echo "     请到开放平台「开发设置→接口加签方式」重新粘贴应用公钥并【点保存】，"
  echo "     再复制保存后的「应用公钥」与本地比对；确认加签方式=密钥+RSA2。"
fi
