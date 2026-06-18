<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">模型关联 - {{ model?.modelName }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> 返回列表</router-link>
    </div>

    <div class="action-bar">
      <div class="left">
        <SearchableSelect
          v-model="selectedModelIds"
          :options="selectOptions"
          placeholder="-- 选择渠道模型 --"
          :multiple="true"
          :width="300"
          :dropdown-width="500"
        />
        <button class="btn btn-primary btn-sm" :disabled="selectedModelIds.length === 0" @click="addRel"><SvgIcon name="link" :size="14" /> 添加关联</button>
        <button v-if="isDirty" class="btn btn-primary btn-sm" :disabled="isSaving" @click="saveOrder">
          <SvgIcon name="check" :size="14" /> {{ isSaving ? '保存中...' : '保存顺序' }}
        </button>
      </div>
    </div>

    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>排序</th>
            <th>渠道</th>
            <th>模型</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(rel, index) in rels"
            :key="rel.id"
            draggable="true"
            :class="{ dragging: draggingIndex === index, 'drag-over': dragOverIndex === index && draggingIndex !== index }"
            @dragstart="onDragStart(index)"
            @dragover="onDragOver(index, $event)"
            @drop="onDrop(index)"
            @dragend="onDragEnd"
          >
            <td><span class="drag-handle" title="拖拽排序">≡</span></td>
            <td>{{ rel.channelName }}</td>
            <td><code class="model-tag">{{ rel.channelModelName }}</code></td>
            <td>
              <button class="btn btn-sm btn-danger" @click="removeRel(rel)"><SvgIcon name="trash" :size="14" /> 删除</button>
            </td>
          </tr>
          <tr v-if="!rels.length">
            <td colspan="4" style="text-align:center;color:var(--text-muted);padding:40px;">暂无关联</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- 通用弹框 -->
  <Dialog
    v-model="dialogVisible"
    :title="dialogTitle"
    :type="dialogType"
    :confirm-class="dialogConfirmClass"
    @confirm="onDialogConfirm"
  >
    {{ dialogMessage }}
  </Dialog>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { modelApi, type CustomModel, type ModelChannelRel } from '@/api/model'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'

const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const rels = ref<ModelChannelRel[]>([])
const availableModels = ref<any[]>([])
const selectedModelIds = ref<number[]>([])

// 拖拽排序状态
const draggingIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)
const isDirty = ref(false)
const originalRelIds = ref<number[]>([])
const isSaving = ref(false)

/* ---------- 弹框状态 ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref('提示')
const dialogMessage = ref('')
const dialogType = ref<'alert' | 'confirm'>('alert')
const dialogConfirmClass = ref('btn-primary')
let dialogOnConfirm: (() => void) | null = null

function openDialog(opts: {
  title?: string
  message: string
  type?: 'alert' | 'confirm'
  confirmClass?: string
  onConfirm?: () => void
}) {
  dialogTitle.value = opts.title ?? '提示'
  dialogMessage.value = opts.message
  dialogType.value = opts.type ?? 'alert'
  dialogConfirmClass.value = opts.confirmClass ?? 'btn-primary'
  dialogOnConfirm = opts.onConfirm ?? null
  dialogVisible.value = true
}

function onDialogConfirm() {
  dialogOnConfirm?.()
  dialogOnConfirm = null
}
/* ------------------------------ */

const selectOptions = computed(() => {
  // 过滤已关联的模型
  const existingIds = new Set(rels.value.map(r => r.channelModelId))
  return availableModels.value
    .filter(am => !existingIds.has(am.id))
    .map(am => ({
      value: am.id,
      label: `${am.modelName} (${am.channelName || ''})`
    }))
})

async function loadData() {
  const id = Number(route.params.id)
  try {
    const res = await modelApi.getRels(id)
    model.value = res.data.model
    rels.value = res.data.rels.sort((a, b) => a.sortOrder - b.sortOrder)
    originalRelIds.value = rels.value.map(r => r.id)
    isDirty.value = false
    availableModels.value = res.data.availableModels
  } catch (e: any) {
    openDialog({ title: '加载失败', message: e.message })
    router.push('/admin/model/list')
  }
}

async function addRel() {
  if (selectedModelIds.value.length === 0) return
  const id = Number(route.params.id)
  try {
    const res = await modelApi.batchAddRels(id, selectedModelIds.value)
    if (res.data.success) {
      selectedModelIds.value = []
      await loadData()
    }
  } catch (e: any) {
    openDialog({ title: '添加失败', message: e.message })
  }
}

function removeRel(rel: ModelChannelRel) {
  openDialog({
    title: '确认删除',
    message: '确认删除此关联？',
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        await modelApi.removeRel(rel.id)
        await loadData()
      } catch (e: any) {
        openDialog({ title: '删除失败', message: e.message })
      }
    }
  })
}

function onDragStart(index: number) {
  draggingIndex.value = index
}

function onDragOver(index: number, e: DragEvent) {
  e.preventDefault()
  dragOverIndex.value = index
}

function onDrop(index: number) {
  if (draggingIndex.value === null || draggingIndex.value === index) return

  const newRels = [...rels.value]
  const [moved] = newRels.splice(draggingIndex.value, 1)
  newRels.splice(index, 0, moved)
  rels.value = newRels

  const currentIds = rels.value.map(r => r.id)
  isDirty.value = JSON.stringify(currentIds) !== JSON.stringify(originalRelIds.value)

  draggingIndex.value = null
  dragOverIndex.value = null
}

function onDragEnd() {
  draggingIndex.value = null
  dragOverIndex.value = null
}

async function saveOrder() {
  isSaving.value = true
  try {
    const updates = rels.value.map((rel, index) =>
      modelApi.updateRelSort(rel.id, index + 1)
    )
    await Promise.all(updates)
    isDirty.value = false // 保存成功后隐藏保存按钮
    await loadData()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e)
    openDialog({ title: '保存失败', message })
  } finally {
    isSaving.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
tr[draggable="true"] {
  cursor: grab;
}

tr[draggable="true"]:active {
  cursor: grabbing;
}

tr.dragging {
  opacity: 0.5;
  background-color: var(--bg-hover, #f5f5f5);
}

tr.drag-over {
  background-color: var(--primary-light, #e3f2fd);
  box-shadow: inset 0 -2px 0 var(--primary, #2196f3);
}

.drag-handle {
  cursor: grab;
  color: var(--text-muted);
  user-select: none;
  font-size: 18px;
  line-height: 1;
}

.drag-handle:active {
  cursor: grabbing;
}
</style>
