<template>
  <div class="card">
    <div class="card-header">
      <div class="card-title">{{ t('model.rels.title').replace('{name}', model?.modelName || '') }}</div>
      <router-link :to="'/admin/model/list'" class="btn btn-secondary"><SvgIcon name="arrow-left" :size="14" /> {{ t('common.back') }}</router-link>
    </div>

    <div class="action-bar">
      <div class="left">
        <SearchableSelect
          v-model="selectedModelIds"
          :options="selectOptions"
          :placeholder="t('model.rels.selectModel')"
          :multiple="true"
          :width="300"
          :dropdown-width="500"
        />
        <button class="btn btn-primary btn-sm" :disabled="selectedModelIds.length === 0" @click="addRel"><SvgIcon name="link" :size="14" /> {{ t('model.rels.addRel') }}</button>
        <button v-if="isDirty" class="btn btn-primary btn-sm" :disabled="isSaving" @click="saveOrder">
          <SvgIcon name="check" :size="14" /> {{ isSaving ? t('common.saving') : t('model.rels.saveOrder') }}
        </button>
      </div>
    </div>

    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>{{ t('model.rels.sort') }}</th>
            <th>{{ t('model.rels.channel') }}</th>
            <th>{{ t('model.rels.model') }}</th>
            <th>{{ t('model.rels.actions') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(rel, index) in rels"
            :key="rel.id"
            draggable="true"
            :class="{
              dragging: draggingIndex === index,
              'drag-over-before': dragOverIndex === index && insertPosition === 'before' && draggingIndex !== index,
              'drag-over-after': dragOverIndex === index && insertPosition === 'after' && draggingIndex !== index
            }"
            @dragstart="onDragStart(index)"
            @dragover="onDragOver(index, $event)"
            @drop="onDrop(index)"
            @dragend="onDragEnd"
          >
            <td><span class="drag-handle" :title="t('model.rels.dragSort')">≡</span></td>
            <td>{{ rel.channelName }}</td>
            <td><code class="model-tag">{{ rel.channelModelName }}</code></td>
            <td>
              <button class="btn btn-sm btn-danger" @click="removeRel(rel)"><SvgIcon name="trash" :size="14" /> {{ t('model.rels.delete') }}</button>
            </td>
          </tr>
          <tr v-if="!rels.length">
            <td colspan="4" style="text-align:center;color:var(--text-muted);padding:40px;">{{ t('model.rels.noRels') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <!-- Common Dialog -->
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
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel, type ModelChannelRel } from '@/api/model'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const rels = ref<ModelChannelRel[]>([])
const availableModels = ref<any[]>([])
const selectedModelIds = ref<number[]>([])

  // Drag and drop sort state
const draggingIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)
const insertPosition = ref<'before' | 'after'>('before')
const isDirty = ref(false)
const originalRelIds = ref<number[]>([])
const isSaving = ref(false)

/* ---------- Dialog state ---------- */
const dialogVisible = ref(false)
const dialogTitle = ref(t('common.prompt'))
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
  dialogTitle.value = opts.title ?? t('common.prompt')
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
  // Filter out already-linked models
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
    openDialog({ title: t('error.loadFailed'), message: e.message })
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
    openDialog({ title: t('model.rels.addFailed'), message: e.message })
  }
}

function removeRel(rel: ModelChannelRel) {
  openDialog({
    title: t('common.confirmDelete'),
    message: t('model.rels.deleteConfirm'),
    type: 'confirm',
    confirmClass: 'btn-danger',
    onConfirm: async () => {
      try {
        await modelApi.removeRel(rel.id)
        await loadData()
      } catch (e: any) {
        openDialog({ title: t('model.rels.deleteFailed'), message: e.message })
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
  const tr = e.currentTarget as HTMLElement
  const rect = tr.getBoundingClientRect()
  insertPosition.value = e.clientY < rect.top + rect.height / 2 ? 'before' : 'after'
}

function onDrop(index: number) {
  if (draggingIndex.value === null || draggingIndex.value === index) return

  const newRels = [...rels.value]
  const [moved] = newRels.splice(draggingIndex.value, 1)

  // Calculate insert position: before → insert before target, after → insert after target
  // Note: array has changed after splice, need to adjust index
  let insertAt = insertPosition.value === 'after' ? index + 1 : index
  if (draggingIndex.value < index && insertAt > 0) {
    insertAt-- // One item removed above, target position shifted up
  }
  newRels.splice(insertAt, 0, moved)
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
    const sortedRelIds = rels.value.map(r => r.id)
    await modelApi.batchUpdateSortOrders(sortedRelIds)
    isDirty.value = false // Hide save button after successful save
    await loadData()
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e)
    openDialog({ title: t('model.rels.saveFailed'), message })
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
  background-color: var(--bg-hover);
}

tr.drag-over-before {
  box-shadow: inset 0 2px 0 var(--accent-blue);
  background-color: color-mix(in srgb, var(--accent-blue) 6%, transparent);
}

tr.drag-over-after {
  box-shadow: inset 0 -2px 0 var(--accent-blue);
  background-color: color-mix(in srgb, var(--accent-blue) 6%, transparent);
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
