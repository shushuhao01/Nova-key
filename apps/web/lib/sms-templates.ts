/**
 * 阿里云短信模板申请指引（后台「申请模板」弹窗唯一数据源）
 * 数据来源：docs/手机号登录与账号绑定改造方案.md §5.6.5
 * 纯前端常量，不新增接口，未启用短信时也能查看。
 */

export type SmsTemplateKind = "验证码" | "通知"

export interface SmsTemplateVariable {
  /** 变量名，必须与阿里云模板正文中的 ${变量名} 逐字一致 */
  name: string
  desc: string
  example: string
}

export interface SmsTemplateGuideItem {
  /** 唯一标识：验证码类为配置键，通知类为 notification:模板编码 */
  key: string
  /** 对应的 site_config 配置键（通知类模板没有，走 notification_templates.sms_template_code） */
  configKey?: string
  title: string
  scene: string
  trigger: string
  kind: SmsTemplateKind
  content: string
  variables: SmsTemplateVariable[]
  notes: string[]
}

/** 通用规则（每个模板都适用） */
export const SMS_TEMPLATE_COMMON_NOTES: string[] = [
  "变量写法为 ${变量名}，变量名统一「小写字母 + 下划线」，不要使用中文或大写。",
  "验证码类模板的验证码变量名固定为 code，值为 6 位数字。",
  "短信签名必须与阿里云已审核通过的签名完全一致，否则报 isv.SMS_SIGNATURE_ILLEGAL。",
  "TemplateParam 的 key 必须与模板正文变量名逐字一致，错一个字符就会报 isv.TEMPLATE_MISSING_PARAMETERS。",
  "验证码有效期文案需与「客户配置 → 验证码有效期」保持一致（默认 10 分钟）。",
]

export const SMS_TEMPLATE_GUIDE: SmsTemplateGuideItem[] = [
  {
    key: "sms_tpl_verify_code",
    configKey: "sms_tpl_verify_code",
    title: "通用验证码",
    scene: "通用验证码（sms_tpl_verify_code）",
    trigger: "手机号注册等通用校验",
    kind: "验证码",
    content: "您的验证码为${code}，10分钟内有效，请勿泄露给他人。",
    variables: [{ name: "code", desc: "6 位数字验证码", example: "123456" }],
    notes: [
      "阿里云对验证码模板的变量与长度有额外校验，最终以控制台提示为准。",
      "该模板同时用于「测试连通」的退化发送与「测试发送」的默认模板。",
    ],
  },
  {
    key: "sms_tpl_login_code",
    configKey: "sms_tpl_login_code",
    title: "登录验证码",
    scene: "登录验证码（sms_tpl_login_code）",
    trigger: "登录页「手机验证码登录」",
    kind: "验证码",
    content: "您正在登录${site_name}，验证码${code}，请勿泄露。",
    variables: [
      { name: "code", desc: "6 位数字验证码", example: "123456" },
      { name: "site_name", desc: "站点名称，自动取后台「基础信息」中的站点名称", example: "Nova key" },
    ],
    notes: ["未配置该模板时，手机验证码登录会提示「短信模板未配置」。"],
  },
  {
    key: "sms_tpl_password_reset",
    configKey: "sms_tpl_password_reset",
    title: "找回密码",
    scene: "找回密码（sms_tpl_password_reset）",
    trigger: "忘记密码 → 短信重置（需开启 phone_reset_by_sms_enabled）",
    kind: "验证码",
    content: "您正在重置${site_name}登录密码，验证码${code}，如非本人操作请忽略。",
    variables: [
      { name: "code", desc: "6 位数字验证码", example: "123456" },
      { name: "site_name", desc: "站点名称，自动取后台「基础信息」中的站点名称", example: "Nova key" },
    ],
    notes: ["该场景默认关闭，优先引导用户绑定邮箱找回密码。"],
  },
  {
    key: "sms_tpl_bind_phone",
    configKey: "sms_tpl_bind_phone",
    title: "绑定手机号",
    scene: "绑定手机号（sms_tpl_bind_phone）",
    trigger: "个人中心 → 绑定手机号",
    kind: "验证码",
    content: "您正在绑定手机号，验证码${code}，请勿泄露。",
    variables: [{ name: "code", desc: "6 位数字验证码", example: "123456" }],
    notes: ["绑定手机号成功后可能触发账号合并，操作不可逆，请提示用户确认。"],
  },
  {
    key: "notification:ADMIN_RESET_PASSWORD",
    title: "管理员重置密码通知",
    scene: "通知模板 ADMIN_RESET_PASSWORD 的短信模板 CODE",
    trigger: "客服在后台「客户管理」重置用户密码",
    kind: "通知",
    content: "您的${site_name}账号${username}密码已重置，请使用新密码登录并及时修改。",
    variables: [
      { name: "site_name", desc: "站点名称", example: "Nova key" },
      { name: "username", desc: "被重置账号的用户名", example: "nova123" },
    ],
    notes: [
      "推荐方案 A：短信只做「已重置」提示，新密码由客服从后台结果弹窗「一键复制」后告知用户。",
      "若把明文新密码写入短信正文（如 ${password}），阿里云审核通常不通过（敏感信息类模板）。",
      "本模板在「消息通知 → 模板列表」中勾选短信渠道后，将 CODE 填入该模板的「短信模板 CODE」。",
    ],
  },
  {
    key: "notification:CUSTOM",
    title: "业务通知（可选）",
    scene: "消息通知勾选「短信」渠道的任意模板",
    trigger: "订单、发货等业务事件通知管理员",
    kind: "通知",
    content: "示例：您有新的订单通知，订单号${order_no}，金额${amount}元，请及时处理。",
    variables: [
      { name: "order_no", desc: "订单号（示例变量，按实际业务变量填写）", example: "NO202610280001" },
      { name: "amount", desc: "订单金额（示例变量）", example: "99.00" },
    ],
    notes: [
      "变量名自行定义，但必须与该通知模板正文中的变量完全对应。",
      "单个变量值建议不超过 20 字符，超长阿里云会报 isv.TEMPLATE_PARAMS_ILLEGAL。",
      "通知发送时会把模板渲染用的变量整体作为 TemplateParam 传入，额外变量可能被阿里云拒绝，请保持模板变量与实际事件变量一致。",
    ],
  },
]

/** 按 key 取模板指引 */
export function getSmsTemplateGuide(key: string): SmsTemplateGuideItem | undefined {
  return SMS_TEMPLATE_GUIDE.find((item) => item.key === key)
}

/** 生成可直接粘贴到阿里云控制台/工单的申请文本 */
export function buildSmsTemplateApplyText(item: SmsTemplateGuideItem): string {
  const lines: string[] = [
    "【阿里云短信模板申请信息】",
    `场景：${item.scene}`,
    `触发时机：${item.trigger}`,
    `建议模板类型：${item.kind}`,
    `模板正文：${item.content}`,
    "变量说明：",
    ...item.variables.map((v) => `- ${v.name}：${v.desc}（示例：${v.example}）`),
    "注意事项：",
    ...SMS_TEMPLATE_COMMON_NOTES.map((n) => `- ${n}`),
    ...item.notes.map((n) => `- ${n}`),
  ]
  return lines.join("\n")
}
