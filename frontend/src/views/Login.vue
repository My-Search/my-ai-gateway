<template>
  <div class="login-page">
    <div class="stars-bg"></div>
    <div class="stars-bright"></div>
    <div class="stars-layer-1"></div>
    <div class="stars-layer-2"></div>
    <div class="stars-layer-3"></div>
    <div class="nebula nebula-1"></div>
    <div class="nebula nebula-2"></div>
    <div class="nebula nebula-3"></div>
    <div class="shooting-star shooting-star-1"></div>
    <div class="shooting-star shooting-star-2"></div>
    <div class="shooting-star shooting-star-3"></div>

    <div class="login-card">
      <div class="corner corner-tl"></div>
      <div class="corner corner-tr"></div>
      <div class="corner corner-bl"></div>
      <div class="corner corner-br"></div>

      <h1>My AI Gateway</h1>
      <div class="subtitle">{{ t('login.title') }}</div>

      <div v-if="loading" class="loading-text">{{ t('login.checking') }}</div>

      <template v-if="!loading">
        <div v-if="errorMsg" class="alert alert-error">{{ errorMsg }}</div>

        <form v-if="!hasAdmin" @submit.prevent="handleSetup">
          <div class="alert alert-info">{{ t('login.welcome') }}</div>
          <div class="form-group">
            <label for="username">{{ t('login.username') }}</label>
            <input id="username" v-model="username" type="text" class="form-control" :placeholder="t('login.usernamePlaceholder')" required minlength="3" />
          </div>
          <div class="form-group">
            <label for="password">{{ t('login.password') }}</label>
            <input id="password" v-model="password" type="password" class="form-control" :placeholder="t('login.passwordPlaceholder')" required minlength="6" />
          </div>
          <div class="form-group">
            <label for="confirmPassword">{{ t('login.confirmPassword') }}</label>
            <input id="confirmPassword" v-model="confirmPassword" type="password" class="form-control" :placeholder="t('login.confirmPasswordPlaceholder')" required />
          </div>
          <button type="submit" class="btn btn-primary" style="width:100%;padding:14px;font-size:14px;letter-spacing:2px;">
            <SvgIcon name="rocket" :size="16" /> {{ t('login.setup') }}
          </button>
        </form>

        <form v-else @submit.prevent="handleLogin">
          <div class="form-group">
            <label for="username">{{ t('login.username') }}</label>
            <input id="username" v-model="username" type="text" class="form-control" :placeholder="t('login.usernamePlaceholder')" required />
          </div>
          <div class="form-group">
            <label for="password">{{ t('login.password') }}</label>
            <input id="password" v-model="password" type="password" class="form-control" :placeholder="t('login.passwordPlaceholder')" required />
          </div>
          <button type="submit" class="btn btn-primary" style="width:100%;padding:14px;font-size:14px;letter-spacing:2px;">
            <SvgIcon name="key" :size="16" /> {{ t('login.login') }}
          </button>
        </form>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { checkAuth, loginUser, setupAdmin } from '@/composables/useAuth'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const errorMsg = ref('')
const hasAdmin = ref(true)
const loading = ref(true)

onMounted(async () => {
  try {
    const auth = await checkAuth()
    if (auth.authenticated) {
      // 已登录 → 用 router.replace 跳转（由 beforeEach guard 拦截的话会重定向回来，
      // 但 checkAuth 返回 authenticated=true 说明 session 有效，guard 会放行）
      router.replace('/admin/dashboard')
      return
    }
    hasAdmin.value = auth.hasAdminAccount
  } catch {
    hasAdmin.value = true
  } finally {
    loading.value = false
  }
})

async function handleLogin() {
  errorMsg.value = ''
  const res = await loginUser(username.value, password.value)
  if (res.success) {
    // 登录成功后客户端导航到仪表盘
    // 此时 session cookie 已在浏览器中（由 login 响应的 Set-Cookie 设置）
    // beforeEach guard 中的 checkAuth 会携带 cookie 验证通过
    router.replace('/admin/dashboard')
  } else {
    errorMsg.value = res.error || t('login.loginFailed')
  }
}

async function handleSetup() {
  errorMsg.value = ''
  if (password.value !== confirmPassword.value) {
    errorMsg.value = t('login.passwordsNotMatch')
    return
  }
  if (password.value.length < 6) {
    errorMsg.value = t('login.passwordTooShort')
    return
  }
  const res = await setupAdmin(username.value, password.value, confirmPassword.value)
  if (res.success) {
    router.replace('/admin/dashboard')
  } else {
    errorMsg.value = res.error || t('login.setupFailed')
  }
}
</script>

<style scoped>
/* ═══════════════════════════════════════════════════════
   Login Page — Immersive Starry Sky
   ═══════════════════════════════════════════════════════ */

.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background:
    radial-gradient(ellipse at 15% 85%, color-mix(in srgb, var(--accent-blue) 12%, transparent), transparent 50%),
    radial-gradient(ellipse at 85% 15%, color-mix(in srgb, var(--accent-purple) 10%, transparent), transparent 45%),
    radial-gradient(ellipse at 50% 50%, color-mix(in srgb, var(--accent-cyan) 6%, transparent), transparent 60%),
    radial-gradient(ellipse at 70% 70%, rgba(255, 107, 157, 0.05), transparent 50%),
    linear-gradient(180deg, color-mix(in srgb, var(--bg-primary) 85%, black) 0%, var(--bg-primary) 40%, color-mix(in srgb, var(--bg-primary) 110%, white) 70%, color-mix(in srgb, var(--bg-primary) 85%, black) 100%);
  position: relative;
  overflow: hidden;
}

.loading-text {
  text-align: center;
  color: color-mix(in srgb, var(--text-secondary) 90%, transparent);
  padding: 40px 0;
  font-size: 14px;
}

/* ── Base star field (preserved class) ── */
.stars-bg {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  opacity: 0.7;
  will-change: opacity;
  animation: twinkle-slow 6s ease-in-out infinite alternate;
}
.stars-bg::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(1.5px 1.5px at 20px 30px, rgba(255,255,255,0.9), transparent),
    radial-gradient(1px 1px at 60px 90px, rgba(198,120,221,0.6), transparent),
    radial-gradient(2px 2px at 110px 50px, rgba(255,255,255,0.7), transparent),
    radial-gradient(1px 1px at 160px 120px, rgba(97,175,239,0.8), transparent),
    radial-gradient(1.5px 1.5px at 210px 70px, rgba(255,255,255,0.6), transparent),
    radial-gradient(1px 1px at 260px 140px, rgba(86,182,194,0.5), transparent),
    radial-gradient(2px 2px at 310px 40px, rgba(255,255,255,0.8), transparent),
    radial-gradient(1px 1px at 360px 100px, rgba(198,120,221,0.5), transparent),
    radial-gradient(1.5px 1.5px at 410px 160px, rgba(255,255,255,0.7), transparent),
    radial-gradient(1px 1px at 460px 80px, rgba(97,175,239,0.6), transparent);
  background-size: 500px 200px;
}
.stars-bg::after {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(1px 1px at 30px 250px, rgba(255,255,255,0.6), transparent),
    radial-gradient(1.5px 1.5px at 100px 300px, rgba(97,175,239,0.5), transparent),
    radial-gradient(1px 1px at 180px 350px, rgba(198,120,221,0.4), transparent),
    radial-gradient(2px 2px at 250px 280px, rgba(255,255,255,0.7), transparent),
    radial-gradient(1px 1px at 330px 320px, rgba(86,182,194,0.5), transparent),
    radial-gradient(1.5px 1.5px at 400px 370px, rgba(255,255,255,0.5), transparent);
  background-size: 450px 400px;
}

.stars-bright {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  will-change: opacity;
  animation: twinkle-slow 4s ease-in-out infinite alternate-reverse;
}
.stars-bright::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(2.5px 2.5px at 80px 60px, rgba(255,255,255,0.95), transparent),
    radial-gradient(2px 2px at 200px 150px, rgba(97,175,239,0.9), transparent),
    radial-gradient(2.5px 2.5px at 350px 90px, rgba(198,120,221,0.85), transparent),
    radial-gradient(2px 2px at 500px 180px, rgba(255,255,255,0.8), transparent),
    radial-gradient(3px 3px at 650px 50px, rgba(86,182,194,0.7), transparent),
    radial-gradient(2px 2px at 800px 130px, rgba(255,255,255,0.9), transparent);
  background-size: 900px 220px;
}

/* ── Multi-layer star depth ── */
.stars-layer-1 {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  will-change: opacity;
  animation: twinkle-r1 8s ease-in-out infinite;
  transform: translateZ(0);
}
.stars-layer-1::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(0.8px 0.8px at 15px 25px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 55px 80px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 95px 45px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 135px 110px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 175px 65px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 215px 130px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 255px 35px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 295px 95px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 335px 150px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 375px 55px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 415px 120px, #fff, transparent),
    radial-gradient(0.8px 0.8px at 455px 75px, #fff, transparent);
  background-size: 480px 170px;
  opacity: 0.4;
}

.stars-layer-2 {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  will-change: opacity;
  animation: twinkle-r2 5s ease-in-out infinite;
  transform: translateZ(0);
}
.stars-layer-2::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(1.2px 1.2px at 30px 50px, rgba(255,255,255,0.9), transparent),
    radial-gradient(1.5px 1.5px at 120px 130px, rgba(97,175,239,0.8), transparent),
    radial-gradient(1.2px 1.2px at 220px 80px, rgba(255,255,255,0.85), transparent),
    radial-gradient(1.5px 1.5px at 320px 160px, rgba(198,120,221,0.7), transparent),
    radial-gradient(1.2px 1.2px at 420px 40px, rgba(255,255,255,0.9), transparent),
    radial-gradient(1.5px 1.5px at 520px 110px, rgba(86,182,194,0.75), transparent),
    radial-gradient(1.2px 1.2px at 620px 70px, rgba(255,255,255,0.8), transparent),
    radial-gradient(1.5px 1.5px at 720px 150px, rgba(255,107,157,0.6), transparent);
  background-size: 780px 200px;
  opacity: 0.6;
}

.stars-layer-3 {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  will-change: opacity, transform;
  animation: twinkle-r3 3s ease-in-out infinite;
  transform: translateZ(0);
}
.stars-layer-3::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(2px 2px at 70px 90px, #fff, transparent),
    radial-gradient(2.5px 2.5px at 250px 40px, rgba(97,175,239,0.95), transparent),
    radial-gradient(2px 2px at 430px 130px, #fff, transparent),
    radial-gradient(3px 3px at 610px 60px, rgba(198,120,221,0.9), transparent),
    radial-gradient(2px 2px at 790px 100px, #fff, transparent),
    radial-gradient(2.5px 2.5px at 970px 150px, rgba(86,182,194,0.85), transparent);
  background-size: 1050px 200px;
  opacity: 0.8;
}

/* ── Nebula clouds ── */
.nebula {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  will-change: opacity, transform;
  transform: translateZ(0);
}
.nebula-1 {
  width: 500px;
  height: 400px;
  top: -10%;
  left: -5%;
  background: radial-gradient(ellipse, color-mix(in srgb, var(--accent-blue) 15%, transparent), color-mix(in srgb, var(--accent-purple) 8%, transparent), transparent 70%);
  animation: nebula-breathe 12s ease-in-out infinite;
}
.nebula-2 {
  width: 450px;
  height: 350px;
  bottom: -8%;
  right: -5%;
  background: radial-gradient(ellipse, color-mix(in srgb, var(--accent-purple) 12%, transparent), rgba(255, 107, 157, 0.06), transparent 70%);
  animation: nebula-breathe 15s ease-in-out infinite 4s;
}
.nebula-3 {
  width: 350px;
  height: 300px;
  top: 40%;
  left: 55%;
  background: radial-gradient(ellipse, color-mix(in srgb, var(--accent-cyan) 10%, transparent), color-mix(in srgb, var(--accent-blue) 5%, transparent), transparent 70%);
  animation: nebula-breathe 18s ease-in-out infinite 8s;
}

/* ── Shooting stars ── */
.shooting-star {
  position: absolute;
  width: 120px;
  height: 2px;
  background: linear-gradient(90deg, rgba(255,255,255,0.9), color-mix(in srgb, var(--accent-blue) 60%, transparent), transparent);
  border-radius: 50%;
  pointer-events: none;
  will-change: transform, opacity;
  transform: translateZ(0) rotate(-35deg);
  opacity: 0;
  filter: drop-shadow(0 0 4px rgba(255,255,255,0.6));
}
.shooting-star::before {
  content: '';
  position: absolute;
  right: 0;
  top: -1px;
  width: 6px;
  height: 4px;
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 0 8px 2px rgba(255,255,255,0.8), 0 0 20px 4px color-mix(in srgb, var(--accent-blue) 40%, transparent);
}
.shooting-star-1 {
  top: 12%;
  left: 55%;
  animation: shoot-1 8s ease-in infinite 2s;
}
.shooting-star-2 {
  top: 28%;
  left: 75%;
  width: 90px;
  animation: shoot-2 12s ease-in infinite 6s;
}
.shooting-star-3 {
  top: 8%;
  left: 35%;
  width: 100px;
  animation: shoot-3 15s ease-in infinite 10s;
}

/* ── Keyframes ── */
@keyframes twinkle-slow {
  0% { opacity: 0.5; }
  100% { opacity: 0.8; }
}
@keyframes twinkle-r1 {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 0.6; }
}
@keyframes twinkle-r2 {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.75; }
}
@keyframes twinkle-r3 {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}
@keyframes nebula-breathe {
  0%, 100% { opacity: 0.5; transform: translateZ(0) scale(1); }
  50% { opacity: 1; transform: translateZ(0) scale(1.12); }
}
@keyframes shoot-1 {
  0% { transform: translateZ(0) rotate(-35deg) translateX(0); opacity: 0; }
  2% { opacity: 1; }
  8% { transform: translateZ(0) rotate(-35deg) translateX(-500px); opacity: 0; }
  100% { opacity: 0; }
}
@keyframes shoot-2 {
  0% { transform: translateZ(0) rotate(-28deg) translateX(0); opacity: 0; }
  1.5% { opacity: 0.8; }
  6% { transform: translateZ(0) rotate(-28deg) translateX(-450px); opacity: 0; }
  100% { opacity: 0; }
}
@keyframes shoot-3 {
  0% { transform: translateZ(0) rotate(-40deg) translateX(0); opacity: 0; }
  1% { opacity: 0.9; }
  5% { transform: translateZ(0) rotate(-40deg) translateX(-550px); opacity: 0; }
  100% { opacity: 0; }
}
@keyframes borderGlow {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
}

/* ── Login card ── */
.login-card {
  width: 400px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 40px 36px;
  position: relative;
  z-index: 10;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.3);
}

.login-card::before {
  content: '';
  position: absolute;
  top: -1px; left: -1px; right: -1px; bottom: -1px;
  background: linear-gradient(135deg, var(--accent-blue), var(--accent-purple), var(--accent-cyan), var(--accent-blue));
  background-size: 300% 300%;
  border-radius: 13px;
  z-index: -1;
  animation: borderGlow 6s ease infinite;
  opacity: 0.35;
}

.login-card h1 {
  font-size: 28px;
  font-weight: 700;
  text-align: center;
  margin-bottom: 8px;
  background: linear-gradient(135deg, var(--accent-blue) 0%, var(--accent-purple) 50%, var(--accent-cyan) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 2px;
  text-transform: uppercase;
}

.login-card .subtitle {
  text-align: center;
  color: var(--text-muted);
  font-size: 13px;
  margin-bottom: 32px;
  letter-spacing: 1px;
}

.login-card .form-group {
  margin-bottom: 24px;
  position: relative;
}

.login-card .form-group label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.login-card .form-control {
  width: 100%;
  padding: 14px 16px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-primary);
  font-size: 15px;
  transition: border-color 0.15s;
}

.login-card .form-control:focus {
  outline: none;
  border-color: var(--accent-blue);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent-blue) 15%, transparent);
}

.login-card .form-control::placeholder {
  color: var(--border-color);
}

.corner {
  position: absolute;
  width: 20px;
  height: 20px;
  border: 2px solid color-mix(in srgb, var(--accent-blue) 50%, transparent);
  opacity: 0.6;
  transition: opacity 0.3s ease;
}
.corner-tl { top: 10px; left: 10px; border-right: none; border-bottom: none; }
.corner-tr { top: 10px; right: 10px; border-left: none; border-bottom: none; }
.corner-bl { bottom: 10px; left: 10px; border-right: none; border-top: none; }
.corner-br { bottom: 10px; right: 10px; border-left: none; border-top: none; }

.login-card:hover .corner {
  opacity: 1;
}

.alert-error {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 20px;
  background: color-mix(in srgb, var(--accent-red) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent-red) 30%, transparent);
  color: var(--accent-red);
}

.alert-info {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 20px;
  background: color-mix(in srgb, var(--accent-blue) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent-blue) 30%, transparent);
  color: var(--accent-blue);
}

.btn {
  position: relative;
  overflow: hidden;
  transition: all 0.3s ease;
}

@media (max-width: 480px) {
  .login-card { width: 90%; padding: 30px 24px; margin: 20px; }
  .login-card h1 { font-size: 22px; }
  .nebula { filter: blur(60px); }
  .shooting-star { display: none; }
}
</style>
