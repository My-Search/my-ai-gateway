<template>
  <div class="login-page">
    <div class="stars-bg"></div>
    <div class="stars-bright"></div>

    <div class="login-card">
      <div class="corner corner-tl"></div>
      <div class="corner corner-tr"></div>
      <div class="corner corner-bl"></div>
      <div class="corner corner-br"></div>

      <h1>AI Gateway</h1>
      <div class="subtitle">个人统一AI网关 · 管理后台</div>

      <div v-if="loading" class="loading-text">正在检查登录状态...</div>

      <template v-if="!loading">
        <div v-if="errorMsg" class="alert alert-error">{{ errorMsg }}</div>

        <!-- 设置账号 -->
        <form v-if="!hasAdmin" @submit.prevent="handleSetup">
          <div class="alert alert-info">欢迎首次使用，请设置管理员账号</div>
          <div class="form-group">
            <label for="username">用户名</label>
            <input id="username" v-model="username" type="text" class="form-control" placeholder="请输入用户名" required minlength="3" />
          </div>
          <div class="form-group">
            <label for="password">密码</label>
            <input id="password" v-model="password" type="password" class="form-control" placeholder="请输入密码" required minlength="6" />
          </div>
          <div class="form-group">
            <label for="confirmPassword">确认密码</label>
            <input id="confirmPassword" v-model="confirmPassword" type="password" class="form-control" placeholder="请再次输入密码" required />
          </div>
          <button type="submit" class="btn btn-primary" style="width:100%;padding:14px;font-size:14px;letter-spacing:2px;">
            创 建 账 号
          </button>
        </form>

        <!-- 登录 -->
        <form v-else @submit.prevent="handleLogin">
          <div class="form-group">
            <label for="username">用户名</label>
            <input id="username" v-model="username" type="text" class="form-control" placeholder="请输入用户名" required />
          </div>
          <div class="form-group">
            <label for="password">密码</label>
            <input id="password" v-model="password" type="password" class="form-control" placeholder="请输入密码" required />
          </div>
          <button type="submit" class="btn btn-primary" style="width:100%;padding:14px;font-size:14px;letter-spacing:2px;">
            登 录
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
    errorMsg.value = res.error || '登录失败'
  }
}

async function handleSetup() {
  errorMsg.value = ''
  if (password.value !== confirmPassword.value) {
    errorMsg.value = '两次输入的密码不一致'
    return
  }
  if (password.value.length < 6) {
    errorMsg.value = '密码长度至少6位'
    return
  }
  const res = await setupAdmin(username.value, password.value, confirmPassword.value)
  if (res.success) {
    router.replace('/admin/dashboard')
  } else {
    errorMsg.value = res.error || '设置失败'
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background:
    radial-gradient(ellipse at 20% 80%, rgba(120, 119, 198, 0.15), transparent 50%),
    radial-gradient(ellipse at 80% 20%, rgba(255, 119, 198, 0.1), transparent 50%),
    radial-gradient(ellipse at 40% 40%, rgba(88, 166, 255, 0.08), transparent 50%),
    radial-gradient(ellipse at 60% 60%, rgba(142, 103, 255, 0.1), transparent 50%),
    #050508;
  position: relative;
  overflow: hidden;
}

.loading-text {
  text-align: center;
  color: var(--text-muted);
  padding: 40px 0;
  font-size: 14px;
}

/* Stars */
.stars-bg {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
  opacity: 0.8;
}
.stars-bg::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(2px 2px at 20px 30px, rgba(255,255,255,0.8), transparent),
    radial-gradient(2px 2px at 40px 70px, rgba(255,255,255,0.6), transparent),
    radial-gradient(1px 1px at 90px 40px, rgba(255,255,255,0.9), transparent),
    radial-gradient(1px 1px at 130px 80px, rgba(188,140,255,0.7), transparent),
    radial-gradient(2px 2px at 160px 120px, rgba(255,255,255,0.5), transparent),
    radial-gradient(1px 1px at 200px 50px, rgba(88,166,255,0.8), transparent),
    radial-gradient(2px 2px at 250px 90px, rgba(255,255,255,0.6), transparent),
    radial-gradient(1px 1px at 300px 150px, rgba(255,255,255,0.7), transparent),
    radial-gradient(2px 2px at 340px 60px, rgba(188,140,255,0.6), transparent),
    radial-gradient(1px 1px at 380px 100px, rgba(255,255,255,0.9), transparent);
  background-size: 430px 200px;
  animation: twinkle 4s ease-in-out infinite alternate;
}
.stars-bg::after {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  background-image:
    radial-gradient(1px 1px at 50px 320px, rgba(255,255,255,0.6), transparent),
    radial-gradient(2px 2px at 100px 350px, rgba(88,166,255,0.7), transparent),
    radial-gradient(1px 1px at 150px 380px, rgba(188,140,255,0.5), transparent);
  background-size: 200px 450px;
  animation: twinkle 5s ease-in-out infinite alternate;
  animation-delay: 1.5s;
}
.stars-bright {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  pointer-events: none;
}

@keyframes twinkle {
  0% { opacity: 0.5; }
  50% { opacity: 0.8; }
  100% { opacity: 1; }
}

/* Login card */
.login-card {
  width: 400px;
  background: rgba(22, 27, 34, 0.7);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(88, 166, 255, 0.2);
  border-radius: 16px;
  padding: 40px 36px;
  position: relative;
  z-index: 10;
  box-shadow: 0 0 40px rgba(88, 166, 255, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.login-card::before {
  content: '';
  position: absolute;
  top: -1px; left: -1px; right: -1px; bottom: -1px;
  background: linear-gradient(135deg, #58a6ff, #bc8cff, #58a6ff);
  background-size: 200% 200%;
  border-radius: 17px;
  z-index: -1;
  animation: borderGlow 3s ease infinite;
  opacity: 0.5;
}

@keyframes borderGlow {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
}

.login-card h1 {
  font-size: 28px;
  font-weight: 700;
  text-align: center;
  margin-bottom: 8px;
  background: linear-gradient(135deg, #58a6ff 0%, #bc8cff 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: 2px;
  text-transform: uppercase;
}

.login-card .subtitle {
  text-align: center;
  color: #6e7681;
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
  color: #8b949e;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.login-card .form-control {
  width: 100%;
  padding: 14px 16px;
  background: rgba(13, 17, 23, 0.8);
  border: 1px solid rgba(88, 166, 255, 0.3);
  border-radius: 8px;
  color: #e6edf3;
  font-size: 15px;
  transition: all 0.3s ease;
}

.login-card .form-control:focus {
  outline: none;
  border-color: #58a6ff;
  box-shadow: 0 0 20px rgba(88, 166, 255, 0.3), 0 0 40px rgba(88, 166, 255, 0.1), inset 0 0 10px rgba(88, 166, 255, 0.05);
}

.login-card .form-control::placeholder {
  color: #484f58;
}

.corner {
  position: absolute;
  width: 20px;
  height: 20px;
  border: 2px solid #58a6ff;
  opacity: 0.5;
}
.corner-tl { top: 10px; left: 10px; border-right: none; border-bottom: none; }
.corner-tr { top: 10px; right: 10px; border-left: none; border-bottom: none; }
.corner-bl { bottom: 10px; left: 10px; border-right: none; border-top: none; }
.corner-br { bottom: 10px; right: 10px; border-left: none; border-top: none; }

.alert-error {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 20px;
  background: rgba(248, 81, 73, 0.1);
  border: 1px solid rgba(248, 81, 73, 0.3);
  color: #f85149;
}

.alert-info {
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 20px;
  background: rgba(88, 166, 255, 0.1);
  border: 1px solid rgba(88, 166, 255, 0.3);
  color: #58a6ff;
}

@media (max-width: 480px) {
  .login-card { width: 90%; padding: 30px 24px; margin: 20px; }
  .login-card h1 { font-size: 22px; }
}
</style>
