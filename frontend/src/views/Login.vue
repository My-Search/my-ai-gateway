<template>
  <div class="login-page">
    <!-- 左侧品牌展示区 -->
    <div class="brand-panel">
      <div class="brand-content">
        <div class="brand-logo">
          <img src="/favicon.svg" alt="logo" />
          <span class="brand-name">My AI Gateway</span>
        </div>
        <div class="brand-tagline">
          <h2>{{ t('login.brandTitle') }}</h2>
          <p>{{ t('login.brandDesc') }}</p>
        </div>
        <ul class="brand-features">
          <li v-for="feature in brandFeatures" :key="feature.icon">
            <SvgIcon :name="feature.icon" :size="18" class="feature-icon" />
            <span>{{ t(feature.key) }}</span>
          </li>
        </ul>
      </div>
      <div class="brand-footer">
        <span class="status-dot active"></span>
        <span>{{ t('login.serviceOnline') }}</span>
      </div>
    </div>

    <!-- 右侧表单区 -->
    <div class="form-panel">
      <div class="form-wrapper">
        <div class="form-header">
          <div class="mobile-logo">
            <img src="/favicon.svg" alt="logo" />
          </div>
          <h1>{{ hasAdmin ? t('login.signIn') : t('login.setupTitle') }}</h1>
          <p class="form-subtitle">{{ t('login.title') }}</p>
        </div>

        <div v-if="loading" class="loading-text">
          <SvgIcon name="loading" :size="20" class="spin-icon" />
          <span>{{ t('login.checking') }}</span>
        </div>

        <template v-if="!loading">
          <div v-if="errorMsg" class="alert alert-error">{{ errorMsg }}</div>

          <!-- 初始化设置表单 -->
          <form v-if="!hasAdmin" @submit.prevent="handleSetup" class="login-form">
            <div class="alert alert-info">{{ t('login.welcome') }}</div>
            <div class="form-group">
              <label for="username">{{ t('login.username') }}</label>
              <div class="input-wrapper">
                <SvgIcon name="user" :size="16" class="input-icon" />
                <input id="username" v-model="username" type="text" class="form-control has-icon"
                  :placeholder="t('login.usernamePlaceholder')" required minlength="3" />
              </div>
            </div>
            <div class="form-group">
              <label for="password">{{ t('login.password') }}</label>
              <div class="input-wrapper">
                <SvgIcon name="key" :size="16" class="input-icon" />
                <input id="password" v-model="password" :type="showPassword ? 'text' : 'password'" class="form-control has-icon"
                  :placeholder="t('login.passwordPlaceholder')" required minlength="6" />
                <button type="button" class="toggle-pwd" @click="showPassword = !showPassword" tabindex="-1">
                  <SvgIcon :name="showPassword ? 'eye-off' : 'eye'" :size="16" />
                </button>
              </div>
            </div>
            <div class="form-group">
              <label for="confirmPassword">{{ t('login.confirmPassword') }}</label>
              <div class="input-wrapper">
                <SvgIcon name="key" :size="16" class="input-icon" />
                <input id="confirmPassword" v-model="confirmPassword" :type="showConfirm ? 'text' : 'password'" class="form-control has-icon"
                  :placeholder="t('login.confirmPasswordPlaceholder')" required />
                <button type="button" class="toggle-pwd" @click="showConfirm = !showConfirm" tabindex="-1">
                  <SvgIcon :name="showConfirm ? 'eye-off' : 'eye'" :size="16" />
                </button>
              </div>
            </div>
            <button type="submit" class="btn btn-primary btn-submit">
              <SvgIcon name="rocket" :size="16" />
              {{ t('login.setup') }}
            </button>
          </form>

          <!-- 登录表单 -->
          <form v-else @submit.prevent="handleLogin" class="login-form">
            <div class="form-group">
              <label for="login-username">{{ t('login.username') }}</label>
              <div class="input-wrapper">
                <SvgIcon name="user" :size="16" class="input-icon" />
                <input id="login-username" v-model="username" type="text" class="form-control has-icon"
                  :placeholder="t('login.usernamePlaceholder')" required />
              </div>
            </div>
            <div class="form-group">
              <label for="login-password">{{ t('login.password') }}</label>
              <div class="input-wrapper">
                <SvgIcon name="key" :size="16" class="input-icon" />
                <input id="login-password" v-model="password" :type="showPassword ? 'text' : 'password'" class="form-control has-icon"
                  :placeholder="t('login.passwordPlaceholder')" required />
                <button type="button" class="toggle-pwd" @click="showPassword = !showPassword" tabindex="-1">
                  <SvgIcon :name="showPassword ? 'eye-off' : 'eye'" :size="16" />
                </button>
              </div>
            </div>
            <button type="submit" class="btn btn-primary btn-submit">
              <SvgIcon name="key" :size="16" />
              {{ t('login.login') }}
            </button>
          </form>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { checkAuth, loginUser, setupAdmin } from '@/composables/useAuth'
import { useI18n } from '@/composables/useI18n'
import SvgIcon from '@/components/common/SvgIcon.vue'

const { t } = useI18n()
const router = useRouter()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const errorMsg = ref('')
const hasAdmin = ref(true)
const loading = ref(true)
const showPassword = ref(false)
const showConfirm = ref(false)

const brandFeatures = [
  { icon: 'channel', key: 'login.featureChannels' },
  { icon: 'zap', key: 'login.featureRouting' },
  { icon: 'monitor', key: 'login.featureCircuit' },
  { icon: 'log', key: 'login.featureLogging' },
]

onMounted(async () => {
  try {
    const auth = await checkAuth()
    if (auth.authenticated) {
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
   Login Page - Enterprise Split-Screen
   ═══════════════════════════════════════════════════════ */

.login-page {
  display: flex;
  min-height: 100vh;
  background: var(--bg-primary);
}

/* ── 左侧品牌面板 ── */
.brand-panel {
  flex: 0 0 440px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 48px 32px;
  background: linear-gradient(135deg,
      color-mix(in srgb, var(--accent-blue) 15%, var(--bg-tertiary)),
      color-mix(in srgb, var(--accent-purple) 12%, var(--bg-tertiary)));
  border-right: 1px solid var(--border-color);
  position: relative;
  overflow: hidden;
}

.brand-panel::before {
  content: '';
  position: absolute;
  top: -20%;
  right: -10%;
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, color-mix(in srgb, var(--accent-blue) 10%, transparent), transparent 70%);
  pointer-events: none;
}

.brand-panel::after {
  content: '';
  position: absolute;
  bottom: -15%;
  left: -5%;
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, color-mix(in srgb, var(--accent-purple) 8%, transparent), transparent 70%);
  pointer-events: none;
}

.brand-content {
  position: relative;
  z-index: 1;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 64px;
}

.brand-logo img {
  width: 36px;
  height: 36px;
}

.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.02em;
}

.brand-tagline h2 {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.4;
  margin-bottom: 12px;
  letter-spacing: -0.02em;
}

.brand-tagline p {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.7;
  max-width: 320px;
}

.brand-features {
  list-style: none;
  margin-top: 40px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.brand-features li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
}

.feature-icon {
  color: var(--accent-blue);
  flex-shrink: 0;
}

.brand-footer {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-muted);
  font-weight: 500;
}

/* ── 右侧表单面板 ── */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-wrapper {
  width: 100%;
  max-width: 380px;
}

.form-header {
  text-align: center;
  margin-bottom: 32px;
}

.mobile-logo {
  display: none;
  margin-bottom: 16px;
}

.mobile-logo img {
  width: 48px;
  height: 48px;
}

.form-header h1 {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.02em;
  margin-bottom: 6px;
}

.form-subtitle {
  font-size: 13px;
  color: var(--text-muted);
}

.loading-text {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 0;
  font-size: 14px;
  color: var(--text-secondary);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ── 表单 ── */
.login-form .form-group {
  margin-bottom: 20px;
}

.login-form .form-group label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 6px;
  letter-spacing: 0.01em;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon {
  position: absolute;
  left: 12px;
  color: var(--text-muted);
  pointer-events: none;
  z-index: 1;
}

.form-control.has-icon {
  padding-left: 38px;
  padding-right: 38px;
}

.toggle-pwd {
  position: absolute;
  right: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: color 0.15s ease, background 0.15s ease;
}

.toggle-pwd:hover {
  color: var(--text-secondary);
  background: var(--bg-hover);
}

.btn-submit {
  width: 100%;
  justify-content: center;
  padding: 12px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.02em;
  margin-top: 4px;
}

/* ── 响应式 ── */
@media (max-width: 900px) {
  .brand-panel {
    display: none;
  }

  .mobile-logo {
    display: inline-flex;
  }

  .form-panel {
    align-items: center;
    padding: 40px 20px 30vh;
  }
}

@media (max-width: 480px) {
  .form-wrapper {
    max-width: 100%;
  }

  .form-header h1 {
    font-size: 20px;
  }
}
</style>
