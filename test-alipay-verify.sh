#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  支付宝响应签名自检脚本（openssl 复验连接测试报错里的 验签串+签名）
#
#  用法（在服务器 /www/wwwroot/nova-key 下执行）:
#    bash test-alipay-verify.sh 支付宝公钥文件
#
#  说明:
#    1. 公钥文件 = 开放平台密钥管理复制的『支付宝公钥』（含 -----BEGIN PUBLIC KEY----- 头，
#       或整行裸 base64 均可，脚本自动兼容）
#    2. 验签串与响应签名取自最近一次"支付渠道连接测试"的报错输出，已硬编码在下方
#    3. openssl 复验通过 → 该公钥能验证支付宝签名，问题在系统代码（反馈给开发）
#       复验失败 → 该公钥不是支付宝响应签名使用的公钥（填错/证书模式/复制错应用）
# ═══════════════════════════════════════════════════════════════

PUB="$1"
if [ -z "$PUB" ] || [ ! -f "$PUB" ]; then
  echo "用法: bash test-alipay-verify.sh 支付宝公钥文件路径"
  echo "  公钥 = 开放平台密钥管理复制的『支付宝公钥』，先保存为文件，如:"
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

# 最近一次连接测试报错里的验签数据（若复验结果与预期不符，可更新为最新一次报错的值）
CONTENT='buyer_pay_amount=0.00&code=40004&invoice_amount=0.00&msg=Business Failed&out_trade_no=NOVA_TEST_1786437705903&point_amount=0.00&receipt_amount=0.00&sub_code=ACQ.TRADE_NOT_EXIST&sub_msg=交易不存在'
SIGN='CnuT0YC297E76A0+pLfos41l1OlZP1o0F7GACNTc/hxzaaaVKR5bz99VTABAGwghSOHRBTIh3TwjnPR5zmPMKfojX2C7sDyRpj/MOhgdfHUmtWazSPzCOccOfxXQW8LMjRH1NNbH44cf7GWxhxX+x6jA0BoFesLhkgkytixXRqmmRp9beaDoBySGDh1OI1Tdl7pab0Ni3HQmsPyA35cLA0tEphAT0eO3v4nsGuSuLCW8fxhIJIT2Pq4D3RCNr+q7Z2bm0Oj3+eVDHgThwRpIYBviEs/4PcCAuBRMOy+g3qTvEYC4InReVv3sSTS7sm2owEdZww6a/C7sVjKFJcKPBQ=='

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
  echo "   → 后台填写的『支付宝公钥』并非支付宝响应签名使用的公钥。"
  echo "     请到开放平台该应用(2021004145638840)「开发设置→密钥管理」："
  echo "     1) 核对『加签方式』是「公钥」还是「公钥证书」；"
  echo "        若是「公钥证书」，响应用平台证书签名，需用『平台公钥证书』验签（当前系统不支持证书模式）；"
  echo "     2) 若是「公钥」，重新复制该应用的『支付宝公钥』（注意不是『应用公钥』）再测。"
fi
