<template>
  <router-view />
  <LoadingModal />
  <ToastContainer />
</template>

<script setup lang="ts">
// Root application component
// All routing and layout is handled by the router and child components
import { onMounted } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { useLocaleStore } from '@/stores/locale'
import LoadingModal from '@/components/common/LoadingModal.vue'
import ToastContainer from '@/components/common/ToastContainer.vue'

const themeStore = useThemeStore()
const localeStore = useLocaleStore()

onMounted(() => {
  themeStore.init()
  localeStore.init()
})
</script>