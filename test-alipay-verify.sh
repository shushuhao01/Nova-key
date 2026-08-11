#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  支付宝响应签名自检脚本（openssl 复验连接测试报错里的 验签串+签名）
#
#  用法（在服务器 /www/wwwroot/nova-key 下执行）:
#    bash test-alipay-verify.sh 支付宝公钥文件 [验签串] [签名]
#    验签串/签名省略时使用内置的最近一次报错数据
#
#  说明:
#    1. 公钥文件 = 待验证的公钥（含 -----BEGIN PUBLIC KEY----- 头，或整行裸 base64 均可）
#    2. 验签串与响应签名取自"支付渠道连接测试"报错输出；若测试数据已更新，
#       可从最新报错复制"系统实际验签串"和"响应签名"作为第 2、3 个参数传入
#    3. openssl 复验通过 → 该公钥能验证支付宝签名，问题在系统代码（反馈给开发）
#       复验失败 → 该公钥不是支付宝响应签名使用的公钥（填错/证书模式/复制错应用）
# ═══════════════════════════════════════════════════════════════

PUB="$1"
if [ -z "$PUB" ] || [ ! -f "$PUB" ]; then
  echo "用法: bash test-alipay-verify.sh 公钥文件路径 [验签串] [签名]"
  echo "  公钥 = 待验证的公钥，先保存为文件，如:"
  echo "    cat > alipay_pub.pem"
  echo "    （粘贴公钥内容后按 Ctrl+D）"
  exit 1
fi

# 兼容无 PEM 头的裸 base64 公钥：自动补齐 SPKI 头尾
if ! grep -q "BEGIN" "$PUB"; then
  B64=$(tr -d '\r\n ' < "$PUB")
  { echo "-----BEGIN PUBLIC KEY-----"; echo "$B64"; echo "-----END PUBLIC KEY-----"; } > alipay_pub_tmp.pem
  echo "  (检测到无头公钥，已自动补齐 PEM 头尾为 alipay_pub_tmp.pem)"
  PUB="alipay_pub_tmp.pem"
fi

# 内置的最近一次连接测试报错里的验签数据（可用第 2、3 个参数覆盖）
CONTENT=${2:-'buyer_pay_amount=0.00&code=40004&invoice_amount=0.00&msg=Business Failed&out_trade_no=NOVA_TEST_1786440019003&point_amount=0.00&receipt_amount=0.00&sub_code=ACQ.TRADE_NOT_EXIST&sub_msg=交易不存在'}
SIGN=${3:-'CHPyuuFhOsme58Sfxra2NJ14K4SB5DLOLt1ReDEAHxq1rpCUeTjI80aM6ar47AWlJg30Dvo9NbXVNNoPIPbUrriSI3YAxXhQm6MkF/+CN2YbT2RMm1QN5hssKRkfYoeOaRq49I/aRbZhWnJ4Wrb54M3HGVRC/gKkLGsXLghBVnH2hoEx3AVAYgKItr28ggB8W3BhQS90fHGo3+k7Qn5QExV7xLH3JygAtXQQZJr6VM5FA6X5WglgybUmT6SxZ4jv83rDYZWJACzD8rVhh7BdIn6gEN1tg38EuSJ6RbGbPYqyPP5Tz/hPv7AfZMqvx6ykyZF0brkLisWYNr8vFP1cZQ=='}

echo "=== 用公钥验签 (SHA256withRSA=RSA2) ==="
echo "  验签串: $CONTENT"
echo ""
printf '%s' "$CONTENT" > /tmp/alipay_verify.content
printf '%s' "$SIGN" | base64 -d > /tmp/alipay_verify.sig
openssl dgst -sha256 -verify "$PUB" -signature /tmp/alipay_verify.sig /tmp/alipay_verify.content
echo ""

echo "=== 判定 ==="
if openssl dgst -sha256 -verify "$PUB" -signature /tmp/alipay_verify.sig /tmp/alipay_verify.content >/dev/null 2>&1; then
  echo "✅ 复验通过：该公钥能验证支付宝响应签名 → 问题在系统代码/拼接，请把本输出反馈给开发"
else
  echo "❌ 复验失败：该公钥无法验证支付宝响应签名"
  echo "   → 该公钥不是支付宝响应签名使用的公钥。"
  echo "     排查方向："
  echo "     1) 核对『加签方式』是「公钥」还是「公钥证书」；"
  echo "        若是「公钥证书」，响应用平台证书签名，需用『平台公钥证书』验签（当前系统不支持证书模式）；"
  echo "     2) 若是「公钥」，确认复制的是该应用的『支付宝公钥』（开放平台密钥/沙箱/mapi网关 三套公钥互不相同，勿混用）；"
  echo "     3) 尝试账号级『平台公钥』（若密钥管理页面有该栏）。"
fi
