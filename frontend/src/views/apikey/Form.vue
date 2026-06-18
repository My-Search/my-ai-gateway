<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? '编辑密钥' : '添加密钥' }}</div>
      <router-link to="/admin/apikey/list" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> 返回列表</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label for="keyName">密钥名称 *</label>
        <input id="keyName" v-model="form.keyName" class="form-control" placeholder="如：生产密钥" required />
      </div>
      <div class="form-group">
        <label for="keyValue">密钥值</label>
        <input id="keyValue" v-model="form.keyValue" class="form-control" :disabled="isEdit" :placeholder="isEdit ? '编辑时不可修改' : '留空则自动生成'" />
        <div class="form-hint">调用 API 时在 Authorization 头部使用 Bearer 此值，{{ isEdit ? '密钥值创建后不可修改' : '留空将自动生成' }}</div>
      </div>
      <div class="form-group">
        <label for="enabled">状态</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
        </select>
      </div>
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving"><SvgIcon name="check" :size="14" /> {{ saving ? '保存中...' : '保存' }}</button>
        <router-link to="/admin/apikey/list" class="btn btn-secondary"><SvgIcon name="x" :size="14" /> 取消</router-link>
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
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apikeyApi, type ApiKey } from '@/api/apikey'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

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
  if (isEdit.value) {
    try {
      const res = await apikeyApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      openDialog({ title: '加载失败', message: e.message })
      router.push('/admin/apikey/list')
    }
  }
})

async function handleSave() {
  saving.value = true
  try {
    const payload = { ...form.value }
    // 编辑模式下密钥值不可修改
    if (isEdit.value) {
      delete payload.keyValue
    }
    if (isEdit.value) {
      await apikeyApi.update(Number(route.params.id), payload)
      openDialog({ title: '成功', message: '密钥更新成功', onConfirm: () => router.push('/admin/apikey/list') })
    } else {
      await apikeyApi.create(payload)
      openDialog({ title: '成功', message: '密钥创建成功', onConfirm: () => router.push('/admin/apikey/list') })
    }
  } catch (e: any) {
    openDialog({ title: '保存失败', message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
