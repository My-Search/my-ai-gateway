<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">渠道模型 - {{ channel?.name }}</div>
      <router-link to="/admin/channel/list" class="btn btn-secondary">返回列表</router-link>
    </div>
    <div v-if="!models.length" class="empty-state">暂无模型数据</div>
    <div class="table-container" v-else>
      <table>
        <thead>
          <tr>
            <th>模型名称</th>
            <th>显示名称</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in models" :key="m.id">
            <td><code class="model-tag">{{ m.modelName }}</code></td>
            <td>{{ m.displayName || m.modelName }}</td>
            <td><span class="badge badge-success">已关联</span></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { channelApi, type Channel, type ChannelModel } from '@/api/channel'

const route = useRoute()
const router = useRouter()
const channel = ref<Channel | null>(null)
const models = ref<ChannelModel[]>([])

onMounted(async () => {
  const id = Number(route.params.id)
  try {
    const res = await channelApi.getModels(id)
    channel.value = res.data.channel
    models.value = res.data.models
  } catch (e: any) {
    alert('加载失败: ' + e.message)
    router.push('/admin/channel/list')
  }
})
</script>
