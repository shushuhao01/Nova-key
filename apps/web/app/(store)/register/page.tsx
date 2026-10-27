"use client"

import { useState, useEffect, useCallback } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { Eye, EyeOff, UserPlus, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth, useSiteConfig } from "@/lib/context"
import { authApi, withMockFallback, getApiErrorMessage, setTurnstileHeaders, setToken } from "@/services/api"
import { mockCaptcha } from "@/lib/mock-data"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"

const PHONE_PATTERN = /^1[3-9]\d{9}$/

export default function RegisterPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const { config } = useSiteConfig()
  const router = useRouter()

  // 手机号注册仅在「客户手机号 + 手机号登录 + 手机号注册」三个开关同时开启时展示
  const phoneEnabled =
    config?.customer_phone_enabled === true &&
    config?.phone_login_enabled === true &&
    config?.phone_register_enabled === true

  const [mode, setMode] = useState<"email" | "phone">("email")

  const [form, setForm] = useState({
    username: "",
    email: "",
    password: "",
    captcha: "",
  })
  const [phoneForm, setPhoneForm] = useState({
    phone: "",
    password: "",
    confirmPassword: "",
    captcha: "",
  })
  // 通过推广/邀请链接进入注册页时自动预填邀请码（?invite=xxx / ?invite_code=xxx）
  const [inviteCode, setInviteCode] = useState<string>(() => {
    if (typeof window === "undefined") return ""
    const params = new URLSearchParams(window.location.search)
    return params.get("invite") || params.get("invite_code") || ""
  })
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [captchaId, setCaptchaId] = useState("")
  const [captchaImage, setCaptchaImage] = useState("")
  const [captchaLoading, setCaptchaLoading] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  const fetchCaptcha = useCallback(async () => {
    setCaptchaLoading(true)
    try {
      const result = await withMockFallback(
        () => authApi.getCaptcha(),
        () => mockCaptcha()
      )
      setCaptchaId(result.captcha_id)
      setCaptchaImage(result.captcha_image)
    } catch {
      // silent fail — use mock
      const mock = mockCaptcha()
      setCaptchaId(mock.captcha_id)
      setCaptchaImage(mock.captcha_image)
    } finally {
      setCaptchaLoading(false)
    }
  }, [])

  // Fetch captcha on mount
  useEffect(() => {
    fetchCaptcha()
  }, [fetchCaptcha])

  const update = (key: string, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  const updatePhone = (key: string, value: string) => {
    setPhoneForm((prev) => ({ ...prev, [key]: value }))
  }

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.username.trim() || !form.email.trim() || !form.password.trim() || !form.captcha.trim()) {
      toast.error(t("auth.fillAllRequired"))
      return
    }

    setIsLoading(true)
    try {
      setTurnstileHeaders(turnstileToken)
      await withMockFallback(
        () => authApi.register({
          username: form.username.trim(),
          password: form.password,
          email: form.email.trim(),
          captcha_id: captchaId,
          captcha: form.captcha.trim(),
          invite_code: inviteCode.trim() || undefined,
        }),
        () => {
          const { mockRegister } = require("@/lib/mock-data")
          return mockRegister()
        }
      )
      toast.success(t("auth.registerSuccess"))
      router.push("/login")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      // Refresh captcha after failed attempt
      fetchCaptcha()
      setForm((prev) => ({ ...prev, captcha: "" }))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  const handleRegisterPhone = async (e: React.FormEvent) => {
    e.preventDefault()
    const phone = phoneForm.phone.trim()
    if (!phone || !phoneForm.password.trim() || !phoneForm.confirmPassword.trim() || !phoneForm.captcha.trim()) {
      toast.error(t("auth.fillAllRequired"))
      return
    }
    if (!PHONE_PATTERN.test(phone)) {
      toast.error(t("auth.phoneInvalid"))
      return
    }
    if (phoneForm.password !== phoneForm.confirmPassword) {
      toast.error(t("auth.passwordMismatch"))
      return
    }

    setIsLoading(true)
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await withMockFallback(
        () => authApi.registerPhone({
          phone,
          password: phoneForm.password,
          confirm_password: phoneForm.confirmPassword,
          captcha_id: captchaId,
          captcha: phoneForm.captcha.trim(),
          invite_code: inviteCode.trim() || undefined,
        }),
        () => {
          const { mockRegister } = require("@/lib/mock-data")
          return mockRegister()
        }
      )
      // 手机号注册成功后后端直接返回登录态，无需再登录
      setToken(result.token)
      setUser(result.user)
      toast.success(t("auth.registerSuccess"))
      router.push("/profile")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      fetchCaptcha()
      setPhoneForm((prev) => ({ ...prev, captcha: "" }))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  const renderPasswordField = (
    id: string,
    value: string,
    onChange: (v: string) => void,
    placeholder: string,
    autoComplete: string
  ) => (
    <div className="relative">
      <input
        id={id}
        type={showPassword ? "text" : "password"}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
        required
      />
      <button
        type="button"
        onClick={() => setShowPassword(!showPassword)}
        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
      >
        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </button>
    </div>
  )

  const renderCaptchaField = (id: string, value: string, onChange: (v: string) => void) => (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-foreground">
        {t("auth.imageCode")}
        <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
      </label>
      <div className="flex gap-2">
        <input
          id={id}
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value.toUpperCase())}
          maxLength={6}
          placeholder={t("auth.captchaPlaceholder")}
          className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm uppercase text-foreground placeholder:normal-case placeholder:text-muted-foreground/60 tracking-wider focus:outline-none focus:ring-2 focus:ring-ring"
          required
        />
        <div
          className="relative flex h-10 w-24 shrink-0 cursor-pointer items-center justify-center rounded-lg border border-border bg-muted overflow-hidden"
          onClick={fetchCaptcha}
          title={t("auth.clickToRefresh")}
        >
          {captchaLoading ? (
            <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
          ) : captchaImage ? (
            <img src={captchaImage} alt="captcha" className="h-full w-full object-contain" />
          ) : (
            <span className="select-none text-lg font-bold tracking-wider text-foreground/60">
              ----
            </span>
          )}
          <RefreshCw className="absolute right-1 top-1 h-3 w-3 text-muted-foreground" />
        </div>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">
        {t("auth.captchaHint")} · {t("auth.clickToRefresh")}
      </p>
    </div>
  )

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <div className="w-full max-w-sm">
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h1 className="mb-6 text-center text-xl font-bold text-card-foreground">
            {t("auth.register")}
          </h1>

          <Tabs value={mode} onValueChange={(v) => setMode(v as "email" | "phone")}>
            {phoneEnabled && (
              <TabsList className="mb-4 grid w-full grid-cols-2">
                <TabsTrigger value="email">{t("auth.emailRegister")}</TabsTrigger>
                <TabsTrigger value="phone">{t("auth.phoneRegister")}</TabsTrigger>
              </TabsList>
            )}

            {/* 邮箱注册 */}
            <TabsContent value="email">
              <form onSubmit={handleRegister} className="flex flex-col gap-4">
                {/* Username */}
                <div>
                  <label htmlFor="reg-username" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.username")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  <input
                    id="reg-username"
                    type="text"
                    value={form.username}
                    onChange={(e) => update("username", e.target.value)}
                    placeholder={t("auth.usernamePlaceholder")}
                    autoComplete="username"
                    className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
                    required
                  />
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.usernameHint")}</p>
                </div>

                {/* Email */}
                <div>
                  <label htmlFor="reg-email" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.email")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  <input
                    id="reg-email"
                    type="email"
                    value={form.email}
                    onChange={(e) => update("email", e.target.value)}
                    placeholder={t("auth.emailPlaceholder")}
                    autoComplete="email"
                    className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
                    required
                  />
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.emailHint")}</p>
                </div>

                {/* Password */}
                <div>
                  <label htmlFor="reg-password" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.password")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  {renderPasswordField(
                    "reg-password",
                    form.password,
                    (v) => update("password", v),
                    t("auth.passwordPlaceholder"),
                    "new-password"
                  )}
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.passwordHint")}</p>
                </div>

                {/* Invite Code (optional) */}
                <div>
                  <label htmlFor="reg-invite" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.inviteCode")}
                    <span className="ml-1 text-xs font-normal text-muted-foreground">（选填）</span>
                  </label>
                  <input
                    id="reg-invite"
                    type="text"
                    value={inviteCode}
                    onChange={(e) => setInviteCode(e.target.value)}
                    placeholder={t("auth.inviteCodePlaceholder")}
                    className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
                  />
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.inviteCodeHint")}</p>
                </div>

                {/* Image Captcha */}
                {renderCaptchaField("reg-captcha", form.captcha, (v) => update("captcha", v))}

                <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-1" />

                {/* Submit */}
                <button
                  type="submit"
                  disabled={isLoading}
                  className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                >
                  {isLoading ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                  ) : (
                    <UserPlus className="h-4 w-4" />
                  )}
                  {t("auth.register")}
                </button>
              </form>
            </TabsContent>

            {/* 手机号注册 */}
            <TabsContent value="phone">
              <form onSubmit={handleRegisterPhone} className="flex flex-col gap-4">
                <div>
                  <label htmlFor="reg-phone" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.phone")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  <input
                    id="reg-phone"
                    type="tel"
                    inputMode="numeric"
                    maxLength={11}
                    value={phoneForm.phone}
                    onChange={(e) => updatePhone("phone", e.target.value.replace(/\D/g, ""))}
                    placeholder={t("auth.phonePlaceholder")}
                    autoComplete="tel"
                    className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
                    required
                  />
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.phoneHint")}</p>
                </div>

                <div>
                  <label htmlFor="reg-phone-password" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.password")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  {renderPasswordField(
                    "reg-phone-password",
                    phoneForm.password,
                    (v) => updatePhone("password", v),
                    t("auth.passwordPlaceholder"),
                    "new-password"
                  )}
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.passwordHint")}</p>
                </div>

                <div>
                  <label htmlFor="reg-phone-confirm" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.confirmPassword")}
                    <span className="ml-0.5 text-red-500" title={t("auth.required")}>*</span>
                  </label>
                  {renderPasswordField(
                    "reg-phone-confirm",
                    phoneForm.confirmPassword,
                    (v) => updatePhone("confirmPassword", v),
                    t("auth.confirmPasswordPlaceholder"),
                    "new-password"
                  )}
                </div>

                <div>
                  <label htmlFor="reg-phone-invite" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.inviteCode")}
                    <span className="ml-1 text-xs font-normal text-muted-foreground">（选填）</span>
                  </label>
                  <input
                    id="reg-phone-invite"
                    type="text"
                    value={inviteCode}
                    onChange={(e) => setInviteCode(e.target.value)}
                    placeholder={t("auth.inviteCodePlaceholder")}
                    className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
                  />
                  <p className="mt-1 text-xs text-muted-foreground">{t("auth.inviteCodeHint")}</p>
                </div>

                {renderCaptchaField("reg-phone-captcha", phoneForm.captcha, (v) => updatePhone("captcha", v))}

                <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-1" />

                <button
                  type="submit"
                  disabled={isLoading}
                  className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                >
                  {isLoading ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                  ) : (
                    <UserPlus className="h-4 w-4" />
                  )}
                  {t("auth.register")}
                </button>
              </form>
            </TabsContent>
          </Tabs>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            {t("auth.hasAccount")}{" "}
            <Link href="/login" className="font-medium text-primary hover:underline">
              {t("auth.goLogin")}
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
