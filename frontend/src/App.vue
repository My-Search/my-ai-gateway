<template>
  <router-view />
  <LoadingModal />
</template>

<script setup lang="ts">
// Root application component
// All routing and layout is handled by the router and child components
import LoadingModal from '@/components/common/LoadingModal.vue'
</script>