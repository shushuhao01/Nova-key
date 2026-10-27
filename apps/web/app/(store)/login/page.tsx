"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, LogIn } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth, useCart, useSiteConfig } from "@/lib/context"
import { setToken, authApi, withMockFallback, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import type { AuthResult } from "@/types"
import { mockLogin } from "@/lib/mock-data"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog"

const INPUT_CLASS =
  "h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PHONE_PATTERN = /^1[3-9]\d{9}$/

export default function LoginPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const { refreshCart } = useCart()
  const { config } = useSiteConfig()
  const router = useRouter()
  const searchParams = useSearchParams()
  const redirectTo = searchParams.get("redirect")

  const phoneEnabled =
    config?.customer_phone_enabled === true && config?.phone_login_enabled === true
  // 手机验证码登录：需短信服务与「手机号验证码登录」同时开启，未启用则不展示入口
  const smsCodeEnabled =
    config?.sms_enabled === true && config?.phone_code_login_enabled === true

  const [tab, setTab] = useState("password")

  // 密码登录
  const [account, setAccount] = useState("")
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [rememberMe, setRememberMe] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  // 手机号登录
  const [phone, setPhone] = useState("")
  const [phonePassword, setPhonePassword] = useState("")
  const [showPhonePassword, setShowPhonePassword] = useState(false)
  const [phoneLoading, setPhoneLoading] = useState(false)
  const [hintPassword, setHintPassword] = useState("")
  const [hintLoading, setHintLoading] = useState(false)

  // 验证码登录
  const [codeEmail, setCodeEmail] = useState("")
  const [loginCode, setLoginCode] = useState("")
  const [codeLoading, setCodeLoading] = useState(false)
  const [codeSending, setCodeSending] = useState(false)
  const [loginCountdown, setLoginCountdown] = useState(0)

  // 手机验证码登录
  const [smsPhone, setSmsPhone] = useState("")
  const [smsLoginCode, setSmsLoginCode] = useState("")
  const [smsCodeLoading, setSmsCodeLoading] = useState(false)
  const [smsCodeSending, setSmsCodeSending] = useState(false)
  const [smsCountdown, setSmsCountdown] = useState(0)

  // 忘记密码弹窗
  const [forgotOpen, setForgotOpen] = useState(false)
  const [fgEmail, setFgEmail] = useState("")
  const [fgCode, setFgCode] = useState("")
  const [fgPassword, setFgPassword] = useState("")
  const [fgConfirm, setFgConfirm] = useState("")
  const [fgSending, setFgSending] = useState(false)
  const [fgSubmitting, setFgSubmitting] = useState(false)
  const [fgCountdown, setFgCountdown] = useState(0)
  const [fgExpireMinutes, setFgExpireMinutes] = useState(10)

  // 强制修改初始密码弹窗（不可关闭）
  const [forceOpen, setForceOpen] = useState(false)
  const [forceOld, setForceOld] = useState("")
  const [forceNew, setForceNew] = useState("")
  const [forceConfirm, setForceConfirm] = useState("")
  const [forceSubmitting, setForceSubmitting] = useState(false)

  // 重发倒计时
  useEffect(() => {
    if (loginCountdown <= 0) return
    const timer = setTimeout(() => setLoginCountdown((v) => v - 1), 1000)
    return () => clearTimeout(timer)
  }, [loginCountdown])
  useEffect(() => {
    if (fgCountdown <= 0) return
    const timer = setTimeout(() => setFgCountdown((v) => v - 1), 1000)
    return () => clearTimeout(timer)
  }, [fgCountdown])
  useEffect(() => {
    if (smsCountdown <= 0) return
    const timer = setTimeout(() => setSmsCountdown((v) => v - 1), 1000)
    return () => clearTimeout(timer)
  }, [smsCountdown])

  const finishLogin = async (result: AuthResult, remember: boolean) => {
    setToken(result.token)
    setUser(result.user)

    if (remember) {
      localStorage.setItem("rememberMe", "true")
    } else {
      localStorage.removeItem("rememberMe")
    }

    // 使用默认密码登录：必须先修改初始密码才能继续
    if (result.must_change_password === true) {
      setForceOld(phonePassword || password)
      setForceNew("")
      setForceConfirm("")
      setForceOpen(true)
      return
    }

    await refreshCart()
    toast.success(t("auth.loginSuccess"))
    router.push(redirectTo || "/")
  }

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!account.trim() || !password.trim()) return

    setIsLoading(true)
    try {
      setTurnstileHeaders(turnstileToken)
      const result = await withMockFallback(
        () => authApi.login({ account: account.trim(), password }),
        () => mockLogin()
      )

      await finishLogin(result, rememberMe)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
    }
  }

  const handlePhoneLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!PHONE_PATTERN.test(phone.trim())) {
      toast.error(t("auth.phoneInvalid"))
      return
    }
    if (!phonePassword.trim()) return

    setPhoneLoading(true)
    try {
      const result = await authApi.login({ account: phone.trim(), password: phonePassword })
      await finishLogin(result, false)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setPhoneLoading(false)
    }
  }

  const handleViewDefaultPassword = async () => {
    setHintLoading(true)
    try {
      const res = await authApi.loginHint()
      setHintPassword(res.default_password)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setHintLoading(false)
    }
  }

  const handleSendLoginCode = async () => {
    if (!codeEmail.trim()) {
      toast.error(t("auth.emailRequired"))
      return
    }
    if (!EMAIL_PATTERN.test(codeEmail.trim())) {
      toast.error(t("auth.emailInvalid"))
      return
    }
    setCodeSending(true)
    try {
      const res = await authApi.sendEmailCode({ email: codeEmail.trim(), scene: "LOGIN" })
      setLoginCountdown(res.resend_after_seconds || 60)
      toast.success(t("auth.codeSent"))
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setCodeSending(false)
    }
  }

  const handleLoginByCode = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!codeEmail.trim()) {
      toast.error(t("auth.emailRequired"))
      return
    }
    if (!EMAIL_PATTERN.test(codeEmail.trim())) {
      toast.error(t("auth.emailInvalid"))
      return
    }
    if (!loginCode.trim()) {
      toast.error(t("auth.codeRequired"))
      return
    }

    setCodeLoading(true)
    try {
      const result = await authApi.loginByCode({ email: codeEmail.trim(), code: loginCode.trim() })
      await finishLogin(result, false)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setCodeLoading(false)
    }
  }

  const handleSendSmsLoginCode = async () => {
    if (!PHONE_PATTERN.test(smsPhone.trim())) {
      toast.error(t("auth.phoneInvalid"))
      return
    }
    setSmsCodeSending(true)
    try {
      const res = await authApi.sendSmsCode({ phone: smsPhone.trim(), scene: "LOGIN" })
      setSmsCountdown(res.resend_after_seconds || 60)
      toast.success(t("auth.codeSent"))
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSmsCodeSending(false)
    }
  }

  const handleLoginBySms = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!PHONE_PATTERN.test(smsPhone.trim())) {
      toast.error(t("auth.phoneInvalid"))
      return
    }
    if (!smsLoginCode.trim()) {
      toast.error(t("auth.codeRequired"))
      return
    }

    setSmsCodeLoading(true)
    try {
      const result = await authApi.loginBySms({ phone: smsPhone.trim(), code: smsLoginCode.trim() })
      await finishLogin(result, false)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSmsCodeLoading(false)
    }
  }

  const isEmailOrPhone = (value: string) =>
    EMAIL_PATTERN.test(value) || PHONE_PATTERN.test(value)
  const handleSendForgotCode = async () => {
    if (!fgEmail.trim()) {
      toast.error(t("auth.forgotInputRequired"))
      return
    }
    if (!isEmailOrPhone(fgEmail.trim())) {
      toast.error(t("auth.forgotInputRequired"))
      return
    }
    setFgSending(true)
    try {
      const res = await authApi.sendEmailCode({ email: fgEmail.trim(), scene: "PASSWORD_RESET" })
      setFgCountdown(res.resend_after_seconds || 60)
      setFgExpireMinutes(res.expire_minutes || 10)
      toast.success(t("auth.codeSent"))
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setFgSending(false)
    }
  }

  const handleResetPassword = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!fgEmail.trim()) {
      toast.error(t("auth.forgotInputRequired"))
      return
    }
    if (!isEmailOrPhone(fgEmail.trim())) {
      toast.error(t("auth.forgotInputRequired"))
      return
    }
    if (!fgCode.trim()) {
      toast.error(t("auth.codeRequired"))
      return
    }
    if (fgPassword.length < 6) {
      toast.error(t("auth.passwordHint"))
      return
    }
    if (fgPassword !== fgConfirm) {
      toast.error(t("auth.passwordMismatch"))
      return
    }

    setFgSubmitting(true)
    try {
      await authApi.resetPassword({ email: fgEmail.trim(), code: fgCode.trim(), new_password: fgPassword })
      toast.success(t("auth.resetSuccess"))
      setForgotOpen(false)
      setFgCode("")
      setFgPassword("")
      setFgConfirm("")
      setTab("password")
      setAccount(fgEmail.trim())
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setFgSubmitting(false)
    }
  }

  const openForgot = () => {
    // 若已在密码登录框填入邮箱或手机号，则带入弹窗，减少重复输入
    if (isEmailOrPhone(account.trim())) {
      setFgEmail(account.trim())
    }
    setForgotOpen(true)
  }

  const handleForceChangePassword = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!forceOld.trim()) {
      toast.error(t("auth.oldPasswordRequired"))
      return
    }
    if (forceNew.length < 8) {
      toast.error(t("auth.passwordHint"))
      return
    }
    if (forceNew !== forceConfirm) {
      toast.error(t("auth.passwordMismatch"))
      return
    }

    setForceSubmitting(true)
    try {
      const result = await authApi.forceChangePassword({
        old_password: forceOld,
        new_password: forceNew,
        confirm_password: forceConfirm,
      })
      setToken(result.token)
      setUser(result.user)
      toast.success(t("auth.changePasswordSuccess"))
      setForceOpen(false)
      await refreshCart()
      router.push(redirectTo || "/")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setForceSubmitting(false)
    }
  }

  const tabCount = 2 + (phoneEnabled ? 1 : 0) + (smsCodeEnabled ? 1 : 0)
  const tabGridClass =
    tabCount === 4 ? "grid-cols-4" : tabCount === 3 ? "grid-cols-3" : "grid-cols-2"

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <div className="w-full max-w-sm">
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h1 className="mb-6 text-center text-xl font-bold text-card-foreground">
            {t("auth.login")}
          </h1>

          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className={`grid w-full ${tabGridClass}`}>
              <TabsTrigger value="password">{t("auth.passwordLogin")}</TabsTrigger>
              {phoneEnabled && <TabsTrigger value="phone">{t("auth.phoneLogin")}</TabsTrigger>}
              {smsCodeEnabled && <TabsTrigger value="sms">{t("auth.smsCodeLogin")}</TabsTrigger>}
              <TabsTrigger value="code">{t("auth.codeLogin")}</TabsTrigger>
            </TabsList>

            {/* 密码登录 */}
            <TabsContent value="password">
              <form onSubmit={handleLogin} className="flex flex-col gap-4 pt-2">
                <div>
                  <label htmlFor="account" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.usernameOrEmail")}
                  </label>
                  <input
                    id="account"
                    type="text"
                    value={account}
                    onChange={(e) => setAccount(e.target.value)}
                    autoComplete="username email"
                    className={INPUT_CLASS}
                    required
                  />
                </div>

                <div>
                  <label htmlFor="password" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.password")}
                  </label>
                  <div className="relative">
                    <input
                      id="password"
                      type={showPassword ? "text" : "password"}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      autoComplete="current-password"
                      className={`${INPUT_CLASS} pr-10`}
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
                </div>

                <div className="flex items-center justify-between">
                  <div className="flex items-center">
                    <input
                      id="rememberMe"
                      type="checkbox"
                      checked={rememberMe}
                      onChange={(e) => setRememberMe(e.target.checked)}
                      className="h-4 w-4 rounded border-input text-primary focus:ring-2 focus:ring-ring focus:ring-offset-0"
                    />
                    <label
                      htmlFor="rememberMe"
                      className="ml-2 cursor-pointer select-none text-sm text-muted-foreground"
                    >
                      {t("auth.rememberMe")}
                    </label>
                  </div>
                  <button
                    type="button"
                    onClick={openForgot}
                    className="text-sm font-medium text-primary hover:underline"
                  >
                    {t("auth.forgotPassword")}
                  </button>
                </div>

                <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-1" />

                <button
                  type="submit"
                  disabled={isLoading}
                  className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                >
                  {isLoading ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                  ) : (
                    <LogIn className="h-4 w-4" />
                  )}
                  {t("auth.login")}
                </button>
              </form>
            </TabsContent>

            {/* 手机号登录 */}
            {phoneEnabled && (
              <TabsContent value="phone">
                <form onSubmit={handlePhoneLogin} className="flex flex-col gap-4 pt-2">
                  <div>
                    <label htmlFor="phone" className="mb-1.5 block text-sm font-medium text-foreground">
                      {t("auth.phone")}
                    </label>
                    <input
                      id="phone"
                      type="tel"
                      inputMode="numeric"
                      maxLength={11}
                      value={phone}
                      onChange={(e) => setPhone(e.target.value.replace(/\D/g, ""))}
                      autoComplete="tel"
                      placeholder={t("auth.phonePlaceholder")}
                      className={INPUT_CLASS}
                      required
                    />
                  </div>

                  <div>
                    <label htmlFor="phonePassword" className="mb-1.5 block text-sm font-medium text-foreground">
                      {t("auth.password")}
                    </label>
                    <div className="relative">
                      <input
                        id="phonePassword"
                        type={showPhonePassword ? "text" : "password"}
                        value={phonePassword}
                        onChange={(e) => setPhonePassword(e.target.value)}
                        autoComplete="current-password"
                        className={`${INPUT_CLASS} pr-10`}
                        required
                      />
                      <button
                        type="button"
                        onClick={() => setShowPhonePassword(!showPhonePassword)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                      >
                        {showPhonePassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                  </div>

                  <p className="text-xs text-muted-foreground">{t("auth.phoneLoginHint")}</p>

                  <div className="rounded-md bg-muted/60 px-3 py-2 text-xs text-muted-foreground">
                    {hintPassword ? (
                      <span>
                        {t("auth.defaultPasswordShown")}：
                        <span className="mx-1 font-mono font-medium text-foreground">{hintPassword}</span>
                      </span>
                    ) : (
                      <button
                        type="button"
                        onClick={handleViewDefaultPassword}
                        disabled={hintLoading}
                        className="font-medium text-primary hover:underline disabled:opacity-50"
                      >
                        {hintLoading ? t("common.loading") : t("auth.viewDefaultPassword")}
                      </button>
                    )}
                  </div>

                  <button
                    type="submit"
                    disabled={phoneLoading}
                    className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                  >
                    {phoneLoading ? (
                      <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                    ) : (
                      <LogIn className="h-4 w-4" />
                    )}
                    {t("auth.login")}
                  </button>

                  <button
                    type="button"
                    onClick={openForgot}
                    className="text-center text-sm font-medium text-primary hover:underline"
                  >
                    {t("auth.forgotPassword")}
                  </button>
                </form>
              </TabsContent>
            )}

            {/* 手机验证码登录 */}
            {smsCodeEnabled && (
              <TabsContent value="sms">
                <form onSubmit={handleLoginBySms} className="flex flex-col gap-4 pt-2">
                  <div>
                    <label htmlFor="smsPhone" className="mb-1.5 block text-sm font-medium text-foreground">
                      {t("auth.phone")}
                    </label>
                    <input
                      id="smsPhone"
                      type="tel"
                      inputMode="numeric"
                      maxLength={11}
                      value={smsPhone}
                      onChange={(e) => setSmsPhone(e.target.value.replace(/\D/g, ""))}
                      autoComplete="tel"
                      placeholder={t("auth.phonePlaceholder")}
                      className={INPUT_CLASS}
                      required
                    />
                  </div>

                  <div>
                    <label htmlFor="smsLoginCode" className="mb-1.5 block text-sm font-medium text-foreground">
                      {t("auth.verifyCode")}
                    </label>
                    <div className="flex gap-2">
                      <input
                        id="smsLoginCode"
                        type="text"
                        inputMode="numeric"
                        maxLength={6}
                        value={smsLoginCode}
                        onChange={(e) => setSmsLoginCode(e.target.value.replace(/\D/g, ""))}
                        autoComplete="one-time-code"
                        placeholder={t("auth.codePlaceholder")}
                        className={INPUT_CLASS}
                        required
                      />
                      <button
                        type="button"
                        onClick={handleSendSmsLoginCode}
                        disabled={smsCodeSending || smsCountdown > 0}
                        className="h-10 shrink-0 whitespace-nowrap rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50"
                      >
                        {smsCountdown > 0 ? `${smsCountdown}s` : t("auth.sendCode")}
                      </button>
                    </div>
                    <p className="mt-1.5 text-xs text-muted-foreground">{t("auth.smsCodeLoginHint")}</p>
                  </div>

                  <button
                    type="submit"
                    disabled={smsCodeLoading}
                    className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                  >
                    {smsCodeLoading ? (
                      <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                    ) : (
                      <LogIn className="h-4 w-4" />
                    )}
                    {t("auth.login")}
                  </button>

                  <button
                    type="button"
                    onClick={openForgot}
                    className="text-center text-sm font-medium text-primary hover:underline"
                  >
                    {t("auth.forgotPassword")}
                  </button>
                </form>
              </TabsContent>
            )}

            {/* 验证码登录 */}
            <TabsContent value="code">
              <form onSubmit={handleLoginByCode} className="flex flex-col gap-4 pt-2">
                <div>
                  <label htmlFor="codeEmail" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.email")}
                  </label>
                  <input
                    id="codeEmail"
                    type="email"
                    value={codeEmail}
                    onChange={(e) => setCodeEmail(e.target.value)}
                    autoComplete="email"
                    placeholder={t("auth.emailPlaceholder")}
                    className={INPUT_CLASS}
                    required
                  />
                </div>

                <div>
                  <label htmlFor="loginCode" className="mb-1.5 block text-sm font-medium text-foreground">
                    {t("auth.verifyCode")}
                  </label>
                  <div className="flex gap-2">
                    <input
                      id="loginCode"
                      type="text"
                      inputMode="numeric"
                      maxLength={6}
                      value={loginCode}
                      onChange={(e) => setLoginCode(e.target.value.replace(/\D/g, ""))}
                      autoComplete="one-time-code"
                      placeholder={t("auth.codePlaceholder")}
                      className={INPUT_CLASS}
                      required
                    />
                    <button
                      type="button"
                      onClick={handleSendLoginCode}
                      disabled={codeSending || loginCountdown > 0}
                      className="h-10 shrink-0 whitespace-nowrap rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50"
                    >
                      {loginCountdown > 0 ? `${loginCountdown}s` : t("auth.sendCode")}
                    </button>
                  </div>
                  <p className="mt-1.5 text-xs text-muted-foreground">{t("auth.codeLoginHint")}</p>
                </div>

                <button
                  type="submit"
                  disabled={codeLoading}
                  className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                >
                  {codeLoading ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                  ) : (
                    <LogIn className="h-4 w-4" />
                  )}
                  {t("auth.login")}
                </button>
              </form>
            </TabsContent>
          </Tabs>

          <p className="mt-4 text-center text-sm text-muted-foreground">
            {t("auth.noAccount")}{" "}
            <Link href="/register" className="font-medium text-primary hover:underline">
              {t("auth.goRegister")}
            </Link>
          </p>
        </div>
      </div>

      {/* 忘记密码 */}
      <Dialog open={forgotOpen} onOpenChange={setForgotOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("auth.forgotPasswordTitle")}</DialogTitle>
            <DialogDescription>{t("auth.forgotPasswordDesc")}</DialogDescription>
          </DialogHeader>

          <form onSubmit={handleResetPassword} className="flex flex-col gap-4">
            <div>
              <label htmlFor="fgEmail" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.forgotInputLabel")}
              </label>
              <input
                id="fgEmail"
                type="text"
                value={fgEmail}
                onChange={(e) => setFgEmail(e.target.value)}
                autoComplete="email"
                placeholder={t("auth.forgotInputPlaceholder")}
                className={INPUT_CLASS}
                required
              />
            </div>

            <div>
              <label htmlFor="fgCode" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.verifyCode")}
              </label>
              <div className="flex gap-2">
                <input
                  id="fgCode"
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={fgCode}
                  onChange={(e) => setFgCode(e.target.value.replace(/\D/g, ""))}
                  autoComplete="one-time-code"
                  placeholder={t("auth.codePlaceholder")}
                  className={INPUT_CLASS}
                  required
                />
                <button
                  type="button"
                  onClick={handleSendForgotCode}
                  disabled={fgSending || fgCountdown > 0}
                  className="h-10 shrink-0 whitespace-nowrap rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50"
                >
                  {fgCountdown > 0 ? `${fgCountdown}s` : fgSending ? t("auth.resend") : t("auth.sendCode")}
                </button>
              </div>
              {fgCountdown > 0 && (
                <p className="mt-1.5 text-xs text-muted-foreground">
                  {t("auth.codeSentTip")} {fgEmail}（{fgExpireMinutes} {t("auth.codeValidTip")}）
                </p>
              )}
            </div>

            <div>
              <label htmlFor="fgPassword" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.newPassword")}
              </label>
              <input
                id="fgPassword"
                type="password"
                value={fgPassword}
                onChange={(e) => setFgPassword(e.target.value)}
                autoComplete="new-password"
                placeholder={t("auth.passwordPlaceholder")}
                className={INPUT_CLASS}
                required
              />
            </div>

            <div>
              <label htmlFor="fgConfirm" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.confirmNewPassword")}
              </label>
              <input
                id="fgConfirm"
                type="password"
                value={fgConfirm}
                onChange={(e) => setFgConfirm(e.target.value)}
                autoComplete="new-password"
                className={INPUT_CLASS}
                required
              />
            </div>

            <button
              type="submit"
              disabled={fgSubmitting}
              className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
            >
              {fgSubmitting && (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              )}
              {t("auth.resetPassword")}
            </button>
          </form>
        </DialogContent>
      </Dialog>

      {/* 强制修改初始密码（不可关闭） */}
      <Dialog open={forceOpen} onOpenChange={() => {}}>
        <DialogContent
          className="max-w-md [&>button]:hidden"
          onEscapeKeyDown={(e) => e.preventDefault()}
          onInteractOutside={(e) => e.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>{t("auth.forceChangeTitle")}</DialogTitle>
            <DialogDescription>{t("auth.forceChangeDesc")}</DialogDescription>
          </DialogHeader>

          <form onSubmit={handleForceChangePassword} className="flex flex-col gap-4">
            <div>
              <label htmlFor="forceOld" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.oldPassword")}
              </label>
              <input
                id="forceOld"
                type="password"
                value={forceOld}
                onChange={(e) => setForceOld(e.target.value)}
                autoComplete="current-password"
                className={INPUT_CLASS}
                required
              />
            </div>

            <div>
              <label htmlFor="forceNew" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.newPassword")}
              </label>
              <input
                id="forceNew"
                type="password"
                value={forceNew}
                onChange={(e) => setForceNew(e.target.value)}
                autoComplete="new-password"
                placeholder={t("auth.passwordPlaceholder")}
                className={INPUT_CLASS}
                required
              />
            </div>

            <div>
              <label htmlFor="forceConfirm" className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.confirmNewPassword")}
              </label>
              <input
                id="forceConfirm"
                type="password"
                value={forceConfirm}
                onChange={(e) => setForceConfirm(e.target.value)}
                autoComplete="new-password"
                placeholder={t("auth.confirmPasswordPlaceholder")}
                className={INPUT_CLASS}
                required
              />
            </div>

            <button
              type="submit"
              disabled={forceSubmitting}
              className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
            >
              {forceSubmitting && (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              )}
              {t("auth.changePassword")}
            </button>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
