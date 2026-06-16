<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ isEdit ? '编辑密钥' : '添加密钥' }}</div>
      <router-link to="/admin/apikey/list" class="btn btn-secondary">返回列表</router-link>
    </div>
    <form @submit.prevent="handleSave" style="max-width:600px;">
      <div class="form-group">
        <label for="keyName">密钥名称 *</label>
        <input id="keyName" v-model="form.keyName" class="form-control" placeholder="如：生产密钥" required />
      </div>
      <div class="form-group">
        <label for="keyValue">密钥值 *</label>
        <input id="keyValue" v-model="form.keyValue" class="form-control" :placeholder="isEdit ? '留空则不修改' : '请输入密钥值'" :required="!isEdit" />
        <div class="form-hint">调用 API 时在 Authorization 头部使用 Bearer 此值</div>
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
        <router-link to="/admin/apikey/list" class="btn btn-secondary">取消</router-link>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apikeyApi, type ApiKey } from '@/api/apikey'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const form = ref<Partial<ApiKey>>({ keyName: '', keyValue: '', enabled: 1 })

onMounted(async () => {
  if (isEdit.value) {
    try {
      const res = await apikeyApi.get(Number(route.params.id))
      form.value = { ...res.data }
    } catch (e: any) {
      alert('加载失败: ' + e.message)
      router.push('/admin/apikey/list')
    }
  }
})

async function handleSave() {
  saving.value = true
  try {
    const payload = { ...form.value }
    // 编辑模式可以空着 keyValue 不修改
    if (isEdit.value && !payload.keyValue) {
      delete payload.keyValue
    }
    if (isEdit.value) {
      await apikeyApi.update(Number(route.params.id), payload)
      alert('密钥更新成功')
    } else {
      await apikeyApi.create(payload)
      alert('密钥创建成功')
    }
    router.push('/admin/apikey/list')
  } catch (e: any) {
    alert('保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}
</script>
