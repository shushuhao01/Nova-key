"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, LogIn } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth, useCart } from "@/lib/context"
import { setToken, authApi, withMockFallback, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import { mockLogin } from "@/lib/mock-data"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog"

const INPUT_CLASS =
  "h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function LoginPage() {
  const { t } = useLocale()
  const { setUser } = useAuth()
  const { refreshCart } = useCart()
  const router = useRouter()
  const searchParams = useSearchParams()
  const redirectTo = searchParams.get("redirect")

  const [tab, setTab] = useState("password")

  // 密码登录
  const [account, setAccount] = useState("")
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [rememberMe, setRememberMe] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  // 验证码登录
  const [codeEmail, setCodeEmail] = useState("")
  const [loginCode, setLoginCode] = useState("")
  const [codeLoading, setCodeLoading] = useState(false)
  const [codeSending, setCodeSending] = useState(false)
  const [loginCountdown, setLoginCountdown] = useState(0)

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

      setToken(result.token)
      setUser(result.user)

      if (rememberMe) {
        localStorage.setItem("rememberMe", "true")
      } else {
        localStorage.removeItem("rememberMe")
      }

      // Refresh cart after login (merges session cart)
      await refreshCart()

      toast.success(t("auth.loginSuccess"))
      router.push(redirectTo || "/")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setIsLoading(false)
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
      setToken(result.token)
      setUser(result.user)
      await refreshCart()
      toast.success(t("auth.loginSuccess"))
      router.push(redirectTo || "/")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setCodeLoading(false)
    }
  }

  const handleSendForgotCode = async () => {
    if (!fgEmail.trim()) {
      toast.error(t("auth.emailRequired"))
      return
    }
    if (!EMAIL_PATTERN.test(fgEmail.trim())) {
      toast.error(t("auth.emailInvalid"))
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
      toast.error(t("auth.emailRequired"))
      return
    }
    if (!EMAIL_PATTERN.test(fgEmail.trim())) {
      toast.error(t("auth.emailInvalid"))
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
    // 若已在密码登录框填入邮箱，则带入弹窗，减少重复输入
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(account.trim())) {
      setFgEmail(account.trim())
    }
    setForgotOpen(true)
  }

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <div className="w-full max-w-sm">
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h1 className="mb-6 text-center text-xl font-bold text-card-foreground">
            {t("auth.login")}
          </h1>

          <Tabs value={tab} onValueChange={setTab}>
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="password">{t("auth.passwordLogin")}</TabsTrigger>
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
                {t("auth.email")}
              </label>
              <input
                id="fgEmail"
                type="email"
                value={fgEmail}
                onChange={(e) => setFgEmail(e.target.value)}
                autoComplete="email"
                placeholder={t("auth.emailPlaceholder")}
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
    </div>
  )
}
