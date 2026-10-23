<template>
  <div v-if="loading" class="page-loading">
    <LoadingSpinner :size="18" :text="t('common.loading')" />
  </div>

  <ChatPlayground v-else :models="models" />

  <Dialog
    v-model="visible"
    :title="title"
    :type="type"
    :confirm-class="confirmClass"
    @confirm="onConfirm"
  >
    {{ message }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { modelApi, type CustomModel } from '@/api/model'
import ChatPlayground from '@/components/chat/ChatPlayground.vue'
import Dialog from '@/components/common/Dialog.vue'
import { useDialog } from '@/composables/useDialog'
import { useI18n } from '@/composables/useI18n'

const { t } = useI18n()
const { visible, title, message, type, confirmClass, onConfirm, open } = useDialog()

const models = ref<CustomModel[]>([])
const loading = ref(true)

onMounted(async () => {
  loading.value = true
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch (e: any) {
    // 不再静默吞掉：模型列表加载失败会让整个测试台不可用，必须让用户看到原因
    open({ title: t('error.loadFailed'), message: e?.message || t('error.network') })
  } finally {
    loading.value = false
  }
})
</script>
