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
        <tbody ref="tbodyRef">
          <tr v-for="(rel, index) in rels" :key="rel.id" :data-index="index" :class="{ 'row-disabled': rel.channelEnabled !== 1 }">
            <td><span class="drag-handle" :title="t('model.rels.dragSort')">≡</span></td>
            <td>
              <span :class="{ 'text-disabled': rel.channelEnabled !== 1 }">{{ rel.channelName }}</span>
              <span v-if="rel.channelEnabled !== 1" class="badge badge-disabled">{{ t('common.disabled') }}</span>
            </td>
            <td>
              <code class="model-tag" :class="{ 'text-disabled': rel.channelEnabled !== 1 }">{{ rel.channelModelName }}</code>
            </td>
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
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from '@/composables/useI18n'
import { modelApi, type CustomModel, type ModelChannelRel } from '@/api/model'
import SearchableSelect from '@/components/common/SearchableSelect.vue'
import Dialog from '@/components/common/Dialog.vue'
import Sortable from 'sortablejs'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const model = ref<CustomModel | null>(null)
const rels = ref<ModelChannelRel[]>([])
const availableModels = ref<any[]>([])
const selectedModelIds = ref<number[]>([])

const tbodyRef = ref<HTMLElement | null>(null)
let sortableInstance: Sortable | null = null

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

function initSortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
  const tbody = tbodyRef.value
  if (!tbody) return

  sortableInstance = new Sortable(tbody, {
    handle: '.drag-handle',
    animation: 150,
    easing: 'cubic-bezier(0.25, 0.46, 0.45, 0.94)',
    ghostClass: 'sortable-ghost',
    dragClass: 'sortable-drag',
    onEnd: (evt) => {
      if (evt.oldIndex === undefined || evt.newIndex === undefined || evt.oldIndex === evt.newIndex) return

      const newRels = [...rels.value]
      const [moved] = newRels.splice(evt.oldIndex, 1)
      newRels.splice(evt.newIndex, 0, moved)
      rels.value = newRels

      const currentIds = rels.value.map(r => r.id)
      isDirty.value = JSON.stringify(currentIds) !== JSON.stringify(originalRelIds.value)
    }
  })
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

// Initialize Sortable after data is loaded and DOM is updated
watch(rels, () => {
  nextTick(() => initSortable())
})

onMounted(async () => {
  await loadData()
  await nextTick()
  initSortable()
})
</script>

<style scoped>
/* Row disabled state */
.row-disabled {
  opacity: 0.5;
  background-color: var(--bg-muted);
}

.text-disabled {
  color: var(--text-muted);
  text-decoration: line-through;
}

.badge-disabled {
  margin-left: 8px;
  font-size: 10px;
  padding: 2px 6px;
  background: var(--text-muted);
  color: var(--bg-primary);
  border-radius: 4px;
}

/* SortableJS drag states */
.sortable-ghost {
  opacity: 0.35;
  background-color: color-mix(in srgb, var(--accent-blue) 10%, transparent);
}

.sortable-ghost td {
  box-shadow: inset 0 2px 0 var(--accent-blue);
}

.sortable-drag {
  opacity: 0.8;
  background-color: var(--bg-card);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.sortable-drag td {
  cursor: grabbing;
}

.drag-handle {
  cursor: grab;
  color: var(--text-muted);
  user-select: none;
  font-size: 18px;
  line-height: 1;
  touch-action: none; /* Prevent touch scroll on handle */
}

.drag-handle:active {
  cursor: grabbing;
}
</style>
