<template>
  <ChatPlayground :models="models" />
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { modelApi, type CustomModel } from '@/api/model'
import ChatPlayground from '@/components/chat/ChatPlayground.vue'

const models = ref<CustomModel[]>([])

onMounted(async () => {
  try {
    const res = await modelApi.list()
    models.value = res.data
  } catch {
    // ignore
  }
})
</script>
