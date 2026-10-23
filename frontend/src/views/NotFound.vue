<template>
  <div class="not-found">
    <div class="nf-card">
      <div class="nf-icon">
        <SvgIcon name="question" :size="28" />
      </div>

      <div class="nf-code">404</div>

      <h1 class="nf-title">{{ t('notFound.title') }}</h1>
      <p class="nf-desc">{{ t('notFound.desc') }}</p>

      <div class="nf-actions">
        <button class="btn-primary" @click="goHome">
          <SvgIcon name="home" :size="15" />
          <span>{{ t('notFound.back') }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()
const router = useRouter()

function goHome() {
  router.replace('/admin/dashboard')
}
</script>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--bg-primary);
}

.nf-card {
  text-align: center;
  max-width: 380px;
}

.nf-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-md, 12px);
  color: var(--text-muted);
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  margin-bottom: 20px;
}

.nf-code {
  font-size: 44px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.1;
  color: var(--accent-blue);
}

.nf-title {
  margin: 8px 0 6px;
  font-size: 17px;
  font-weight: 650;
  color: var(--text-primary);
}

.nf-desc {
  margin: 0 0 22px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
}

.nf-actions {
  display: flex;
  justify-content: center;
}
</style>
