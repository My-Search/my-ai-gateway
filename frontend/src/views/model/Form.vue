<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? '编辑模型' : '添加模型' }}</div>
      <router-link to="/admin/model/list" class="btn btn-secondary">返回列表</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label for="modelName">模型名称 *</label>
        <input id="modelName" v-model="form.modelName" class="form-control" placeholder="如：my-gpt4" required />
        <div class="form-hint">该名称将用于 API 调用时指定模型</div>
      </div>
      <div class="form-group">
        <label for="description">描述</label>
        <textarea id="description" v-model="form.description" class="form-control" placeholder="模型描述（可选）"></textarea>
      </div>
      <div class="form-group">
        <label for="strategy">选择策略</label>
        <select id="strategy" v-model="form.strategy" class="form-control">
          <option value="">故障转移（Failover）</option>
          <option value="random">随机（Random）</option>
          <option value="round_robin">轮询（Round Robin）</option>
        </select>
        <div class="form-hint">选择关联渠道时的模型选择策略</div>
      </div>
      <div class="form-group">
        <label for="enabled">状态</label>
        <select id="enabled" v-model.number="form.enabled" class="form-control">
          <option :value="1">启用</option>
          <option :value="0">禁用</option>
        </select>
      </div>
      <div style="display:flex;gap:8px;margin-top:24px;">
        <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
        <router-link to="/admin/model/list" class="btn btn-secondary">取消</router-link>
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
import { modelApi, type CustomModel } from '@/api/model'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const form = ref<Partial<CustomModel>>({ modelName: '', description: '', strategy: '', enabled: 1 })

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
      const res = await modelApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      openDialog({ title: '加载失败', message: e.message })
      router.push('/admin/model/list')
    }
  }
})

async function handleSave() {
  saving.value = true
  try {
    if (isEdit.value) {
      await modelApi.update(Number(route.params.id), form.value)
      openDialog({ title: '成功', message: '模型更新成功', onConfirm: () => router.push('/admin/model/list') })
    } else {
      await modelApi.create(form.value)
      openDialog({ title: '成功', message: '模型创建成功', onConfirm: () => router.push('/admin/model/list') })
    }
  } catch (e: any) {
    openDialog({ title: '保存失败', message: e.message })
  } finally {
    saving.value = false
  }
}
</script>
