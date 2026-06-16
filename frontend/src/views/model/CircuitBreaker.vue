<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">熔断配置 - {{ model?.modelName }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary">返回列表</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label>熔断开关</label>
        <select v-model.number="config.enabled" class="form-control">
          <option :value="1">开启</option>
          <option :value="0">关闭</option>
        </select>
      </div>
      <div class="form-group">
        <label>熔断范围</label>
        <select v-model="config.circuitBreakScope" class="form-control">
          <option value="channel">渠道级（按 API Key 熔断）</option>
          <option value="model">模型级</option>
        </select>
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>重试次数</label>
          <input v-model.number="config.retryCount" type="number" class="form-control" min="0" />
        </div>
        <div class="form-group">
          <label>熔断持续时间 (秒)</label>
          <input v-model.number="config.circuitBreakDuration" type="number" class="form-control" min="1" />
        </div>
      </div>
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
        <router-link :to="'/admin/model/list'" class="btn btn-secondary">取消</router-link>
      </div>
    </form>
  </div>

  <!-- 通用弹框 -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { modelApi, type CustomModel, type CircuitBreakerConfig } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const saving = ref(false)
const config = ref<CircuitBreakerConfig>({
  modelId: 0, enabled: 0, retryCount: 3,
  circuitBreakDuration: 60, circuitBreakScope: 'model'
})

/* ---------- 弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? '提示'
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.getCircuitBreaker(id)
    model.value = res.data.model
    if (res.data.config) {
      config.value = { ...config.value, ...res.data.config, modelId: id }
    } else {
      config.value.modelId = id
    }
  } catch (e: any) {
    openDialog({ title: '加载失败', message: e.message })
    router.push('/admin/model/list')
  }
})

async function handleSave() {
  saving.value = true
  try {
    await modelApi.saveCircuitBreaker(Number(route.params.id), config.value)
    openDialog({ title: '成功', message: '熔断配置保存成功', onConfirm: () => router.push('/admin/model/list') })
  } catch (e: any) {
    openDialog({ title: '保存失败', message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
