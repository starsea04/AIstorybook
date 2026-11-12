<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import { bookApi, presetApi } from '../api'
import type { BookDraft, StoryPage, TextLang } from '../types'
import BookFlipReader from '../components/BookFlipReader.vue'
import { activeImageTask, editablePage, mergePolledImages, mergeSavedImages, type EditorImageTask } from '../lib/editorPages'
import pageLayout from '../../../shared/book-page.json'

type Character = {
  id?: number
  name: string
  genderAge: string
  appearance: string
  clothing: string
  features: string
  description?: string
  source: string
  presetId?: number
  refImageUrl?: string
  locked?: boolean
  avatarUrl?: string
  emoji?: string
  appearancePrompt?: string
  category?: string
}
type ImageTask = EditorImageTask
type AutoBookTask = { taskId: number; status: string; stage: string; stageLabel: string; progress: number; errorMsg?: string; bookId?: number; book?: any }

const step = ref(1)
const route = useRoute()
const busy = ref(false)
const autoSaving = ref(false)
const errorMessage = ref('')
const bookId = ref<number | null>(null)
const bookTitle = ref('我的新绘本')
const bookDescription = ref('')
const mode = ref<'preset' | 'custom' | 'auto'>('preset')
const selectedChars = ref<number[]>([])
const presets = ref<Character[]>([])
const presetCategory = ref('ALL')
const presetCategoryLabels: Record<string, string> = { ALL: '全部', BOY: '男孩', GIRL: '女孩', ANIMAL: '动物', FANTASY: '幻想', OTHER: '其他' }
const presetCategories = ['ALL', 'BOY', 'GIRL', 'ANIMAL', 'FANTASY', 'OTHER']
const characters = ref<Character[]>([])
const characterLoading = ref(false)
const polishing = ref(false)
const referenceLoading = ref(false)
const referenceUploadLoading = ref(false)
const referenceFileInput = ref<HTMLInputElement | null>(null)
const pageImageFileInput = ref<HTMLInputElement | null>(null)
const pageImageUploadId = ref<number | null>(null)
const pageImageTargetId = ref<number | null>(null)
const retryingPageId = ref<number | null>(null)
const tasksKnown = ref(true)
const previewPage = ref<StoryPage | null>(null)
const previewVisible = ref(false)
const saveState = ref('')
const form = ref({ theme: '', ageGroup: '3-6岁', storyStyle: '温馨治愈', pageCount: 8, imageStyle: '卡通', textLang: 'ZH' as TextLang, protagonist: '' })
const customCharacter = ref<Character>({ name: '', genderAge: '', appearance: '', clothing: '', features: '', source: 'CUSTOM' })
const styles = [{ name: '水彩', emoji: '🎨' }, { name: '卡通', emoji: '🧸' }, { name: '油画', emoji: '🖌️' }, { name: '剪纸', emoji: '✂️' }, { name: '3D 渲染', emoji: '🧊' }, { name: '像素', emoji: '👾' }]
const ages = ['3-6岁', '7-10岁', '11-14岁']
const storyStyles = ['温馨治愈', '冒险奇幻', '幽默搞笑', '科普认知']
const pages = ref<StoryPage[]>([])
const tasks = ref<ImageTask[]>([])
const autoTask = ref<AutoBookTask | null>(null)
const progress = computed(() => step.value === 4 ? 100 : (step.value - 1) * 33)
const activeTaskCount = computed(() => tasks.value.filter(task => ['PENDING', 'PROCESSING'].includes(task.status)).length)
const overallTaskProgress = computed(() => tasks.value.length ? Math.round(tasks.value.reduce((sum, task) => sum + (task.status === 'SUCCESS' ? 100 : task.progress || 0), 0) / tasks.value.length) : 0)
const autoBookActive = computed(() => Boolean(autoTask.value && ['PENDING', 'PROCESSING'].includes(autoTask.value.status)))
const pageStructureBusy = computed(() => busy.value || autoSaving.value || retryingPageId.value !== null || activeTaskCount.value > 0 || !tasksKnown.value)
const filteredPresets = computed(() => presetCategory.value === 'ALL' ? presets.value : presets.value.filter(character => character.category === presetCategory.value))

let pollTimer: ReturnType<typeof setTimeout> | undefined
let autoPollTimer: ReturnType<typeof setTimeout> | undefined
let saveTimer: ReturnType<typeof setTimeout> | undefined
let pollRun = 0
let autoPollRun = 0
let syncingPages = false
let saveQueue: Promise<void> = Promise.resolve()

function withPageSync(update: () => void) { syncingPages = true; try { update() } finally { syncingPages = false } }
function imageTask(page: StoryPage) { return activeImageTask(page, tasks.value) }
function previewImage(page: StoryPage) { previewPage.value = page; previewVisible.value = true }

function fail(error: any, fallback = '操作失败，请稍后重试') {
  errorMessage.value = error?.message || fallback
  ElMessage.error(errorMessage.value)
}

function dataOf(response: any, message = '服务返回了无效结果') {
  if (!response || response.code !== 0) throw new Error(response?.message || message)
  return response.data
}

function stopPolling() {
  pollRun++
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = undefined
}

function stopAutoPolling() {
  autoPollRun++
  if (autoPollTimer) clearTimeout(autoPollTimer)
  autoPollTimer = undefined
}

function normalizeCharacter(character: Partial<Character>): Character {
  return {
    id: character.id,
    name: character.name || '',
    genderAge: character.genderAge || '',
    appearance: character.appearance || character.appearancePrompt || '',
    clothing: character.clothing || '',
    features: character.features || '',
    description: character.description || '',
    source: character.source || 'CUSTOM',
    presetId: character.presetId,
    refImageUrl: character.refImageUrl || '',
    locked: Boolean(character.locked),
    avatarUrl: character.avatarUrl,
    emoji: character.emoji,
    category: character.category
  }
}

async function generateStory() {
  if (!form.value.theme.trim()) {
    errorMessage.value = '请先写下一个故事主题'
    return ElMessage.warning(errorMessage.value)
  }
  errorMessage.value = ''
  busy.value = true
  try {
    const data: any = dataOf(await bookApi.generateStory(form.value), '故事生成失败')
    if (!data?.id) throw new Error('故事已生成，但服务没有返回绘本编号')
    bookId.value = Number(data.id)
    bookTitle.value = String(data.title ?? '我的新绘本')
    bookDescription.value = String(data.description ?? data.coverText ?? '')
    pages.value = Array.isArray(data.pages) ? data.pages : []
    if (!pages.value.length) throw new Error('故事已生成，但没有返回绘本页面')
    characters.value = Array.isArray(data.characters) ? data.characters.map(normalizeCharacter) : []
    step.value = 2
    await loadPresets()
  } catch (error: any) {
    fail(error, '故事生成失败，请确认 AI 服务可用')
  } finally {
    busy.value = false
  }
}

async function loadPresets() {
  try {
    const data: any = dataOf(await presetApi.list(), '预设角色加载失败')
    presets.value = Array.isArray(data) ? data : []
    if (!selectedChars.value.length) selectedChars.value = presets.value.slice(0, 1).map(character => Number(character.id))
  } catch (error: any) {
    fail(error, '预设角色加载失败')
  }
}

function selectedPresetCharacters() {
  return presets.value.filter(character => selectedChars.value.includes(Number(character.id))).map(character => ({
    name: character.name,
    genderAge: '',
    appearance: character.appearancePrompt || character.description || '',
    clothing: '',
    features: '',
    description: character.description || '',
    source: 'PRESET',
    presetId: Number(character.id),
    refImageUrl: character.avatarUrl || ''
  } satisfies Character))
}

async function polishCustomCharacter() {
  if (!bookId.value) return fail(new Error('请先生成绘本'))
  if (!customCharacter.value.name.trim() || !customCharacter.value.appearance.trim()) return ElMessage.warning('请先填写角色名字和外貌特征')
  polishing.value = true
  try {
    const data: any = dataOf(await bookApi.polishCharacter(bookId.value, normalizeCharacter(customCharacter.value) as unknown as Record<string, unknown>), '角色润色失败')
    if (data?.appearance) customCharacter.value.appearance = data.appearance
    ElMessage.success('角色外貌描述已润色')
  } catch (error: any) {
    fail(error, '角色润色失败')
  } finally {
    polishing.value = false
  }
}

async function generateReference() {
  if (!bookId.value) return fail(new Error('请先生成绘本'))
  if (!customCharacter.value.name.trim() || !customCharacter.value.appearance.trim()) return ElMessage.warning('请先填写角色名字和外貌特征')
  referenceLoading.value = true
  try {
    const data: any = dataOf(await bookApi.generateCharacterReference(bookId.value, normalizeCharacter(customCharacter.value) as unknown as Record<string, unknown>), '角色参考图生成失败')
    customCharacter.value.refImageUrl = data?.refImageUrl || ''
    ElMessage.success('角色参考图已生成')
  } catch (error: any) {
    fail(error, '角色参考图生成失败')
  } finally {
    referenceLoading.value = false
  }
}

function chooseReferenceImage() {
  referenceFileInput.value?.click()
}

async function uploadReferenceImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!bookId.value) return fail(new Error('请先生成绘本'))
  const supportedTypes = ['image/png', 'image/jpeg', 'image/webp']
  if (!supportedTypes.includes(file.type.toLowerCase())) {
    return ElMessage.warning('角色参考图仅支持 PNG、JPG 或 WebP 图片')
  }
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('角色参考图不能超过 5 MB')

  referenceUploadLoading.value = true
  try {
    const data: any = dataOf(await bookApi.uploadCharacterReference(bookId.value, file), '角色参考图上传失败')
    if (!data?.refImageUrl) throw new Error('服务未返回角色参考图地址')
    customCharacter.value.refImageUrl = data.refImageUrl
    ElMessage.success('角色参考图已上传')
  } catch (error: any) {
    fail(error, '角色参考图上传失败')
  } finally {
    referenceUploadLoading.value = false
  }
}

async function refreshBook(run?: number) {
  if (!bookId.value) return
  const requestedBook = bookId.value
  const data: any = dataOf(await bookApi.get(requestedBook), '绘本状态读取失败')
  if (requestedBook !== bookId.value || run !== undefined && run !== pollRun) return
  if (Array.isArray(data?.pages)) {
    withPageSync(() => {
      if (step.value === 4) mergePolledImages(pages.value, data.pages)
      else pages.value = data.pages
    })
  }
  if (step.value !== 4 && Array.isArray(data?.characters)) characters.value = data.characters.map(normalizeCharacter)
}

async function pollImageTasks(run: number) {
  if (run !== pollRun || !bookId.value) return
  try {
    const data: any = dataOf(await bookApi.imageTasks(bookId.value), '插画任务状态读取失败')
    if (run !== pollRun) return
    tasks.value = Array.isArray(data) ? data : []
    tasksKnown.value = true
    await refreshBook(run)
    if (run !== pollRun) return
    if (activeTaskCount.value > 0) {
      pollTimer = setTimeout(() => void pollImageTasks(run), 2000)
    } else if (step.value === 3) {
      step.value = 4
      const failures = tasks.value.filter(task => task.status === 'FAILED').length
      saveState.value = failures ? `${failures} 页插画生成失败，可单独重试` : '插画已全部完成'
    } else if (step.value === 4) saveState.value = '插画状态已更新'
  } catch (error: any) {
    if (run !== pollRun) return
    errorMessage.value = error?.message || '插画任务状态读取失败'
    pollTimer = setTimeout(() => void pollImageTasks(run), 4000)
  }
}

function startPolling() {
  stopPolling()
  const run = pollRun
  void pollImageTasks(run)
}

function applyBookData(data: any) {
  if (!data || typeof data !== 'object') throw new Error('服务没有返回绘本内容')
  if (data.id != null) bookId.value = Number(data.id)
  bookTitle.value = String(data.title ?? '我的新绘本')
  bookDescription.value = String(data.description ?? data.coverText ?? '')
  if (data.ageGroup) form.value.ageGroup = String(data.ageGroup)
  if (data.storyStyle) form.value.storyStyle = String(data.storyStyle)
  if (data.imageStyle) form.value.imageStyle = String(data.imageStyle)
  if (data.textLang === 'ZH' || data.textLang === 'EN') form.value.textLang = data.textLang
  if (Array.isArray(data.pages)) {
    syncingPages = true
    pages.value = data.pages
    form.value.pageCount = data.pages.length || form.value.pageCount
    syncingPages = false
  }
  if (Array.isArray(data.characters)) characters.value = data.characters.map(normalizeCharacter)
}

async function loadExistingBook(queryId: unknown) {
  const rawId = Array.isArray(queryId) ? queryId[0] : queryId
  const id = Number(rawId)
  if (!Number.isInteger(id) || id <= 0) return
  busy.value = true
  errorMessage.value = ''
  try {
    const data: any = dataOf(await bookApi.get(id), '绘本内容加载失败')
    applyBookData(data)
    await loadPresets()
    step.value = 4
    tasksKnown.value = false
    startPolling()
    saveState.value = '已加载已有绘本'
  } catch (error: any) {
    fail(error, '绘本内容加载失败，请返回我的绘本重试')
  } finally {
    busy.value = false
  }
}

async function pollAutoTask(taskId: number, run: number) {
  if (run !== autoPollRun) return
  try {
    const data: any = dataOf(await bookApi.autoTask(taskId), '一键创作任务状态读取失败')
    if (run !== autoPollRun) return
    autoTask.value = data
    if (data.status === 'SUCCESS') {
      const completedBookId = Number(data.bookId ?? data.book?.id)
      if (!completedBookId) throw new Error('一键创作已完成，但没有返回绘本编号')
      bookId.value = completedBookId
      const [bookResponse, taskResponse] = await Promise.all([
        bookApi.get(completedBookId),
        bookApi.imageTasks(completedBookId)
      ])
      if (run !== autoPollRun) return
      const book = dataOf(bookResponse, '绘本内容读取失败') || data.book
      applyBookData(book)
      const imageTasks: any = dataOf(taskResponse, '插画任务状态读取失败')
      tasks.value = Array.isArray(imageTasks) ? imageTasks : []
      if (!pages.value.length) throw new Error('一键创作完成，但绘本没有页面')
      step.value = 4
      saveState.value = '一键创作已完成'
      errorMessage.value = ''
      stopAutoPolling()
      return
    }
    if (data.status === 'FAILED') {
      errorMessage.value = data.errorMsg || '一键创作失败，请重试'
      stopAutoPolling()
      return
    }
    autoPollTimer = setTimeout(() => void pollAutoTask(taskId, run), 2000)
  } catch (error: any) {
    if (run !== autoPollRun) return
    errorMessage.value = error?.message || '一键创作任务状态读取失败，正在重试'
    autoPollTimer = setTimeout(() => void pollAutoTask(taskId, run), 4000)
  }
}

function startAutoPolling(taskId: number) {
  stopAutoPolling()
  const run = autoPollRun
  void pollAutoTask(taskId, run)
}

async function beginImageGeneration() {
  if (!bookId.value) throw new Error('缺少绘本编号，请重新生成故事')
  const data: any = dataOf(await bookApi.generateImages(bookId.value), '插画任务提交失败')
  if (Array.isArray(data?.pages)) pages.value = data.pages
  tasks.value = Array.isArray(data?.tasks) ? data.tasks : []
  step.value = 3
  if (!tasks.value.length) {
    await refreshBook()
    step.value = 4
    saveState.value = '插画已全部完成'
  } else startPolling()
}

async function prepareStoryboard() {
  if (!bookId.value) return fail(new Error('缺少绘本编号，请重新生成故事'))
  errorMessage.value = ''
  characterLoading.value = true
  busy.value = true
  try {
    let chosen: Character[]
    if (mode.value === 'preset') {
      chosen = selectedPresetCharacters()
      if (!chosen.length) throw new Error('请至少选择一个预设角色')
    } else if (mode.value === 'custom') {
      if (!customCharacter.value.name.trim()) throw new Error('请填写自定义角色名字')
      chosen = [normalizeCharacter(customCharacter.value)]
    } else {
      const extracted: any = dataOf(await bookApi.extractCharacters(bookId.value), '角色提取失败')
      chosen = Array.isArray(extracted) ? extracted.map(normalizeCharacter) : []
      if (!chosen.length) throw new Error('没有从故事中提取到角色，请改用预设或自定义角色')
    }

    const savedCharacters: any = dataOf(await bookApi.saveCharacters(bookId.value, chosen as unknown as Record<string, unknown>[]), '角色保存失败')
    characters.value = Array.isArray(savedCharacters) ? savedCharacters.map(normalizeCharacter) : chosen
    const storyboard: any = dataOf(await bookApi.generateStoryboard(bookId.value), '分镜生成失败')
    if (Array.isArray(storyboard?.pages)) pages.value = storyboard.pages
    if (Array.isArray(storyboard?.characters)) characters.value = storyboard.characters.map(normalizeCharacter)
    await beginImageGeneration()
  } catch (error: any) {
    fail(error, '角色、分镜或插画生成失败，可重试当前步骤')
  } finally {
    characterLoading.value = false
    busy.value = false
  }
}

async function autoCreate() {
  if (!form.value.theme.trim()) {
    errorMessage.value = '请先写下一个故事主题'
    return ElMessage.warning(errorMessage.value)
  }
  busy.value = true
  errorMessage.value = ''
  stopAutoPolling()
  autoTask.value = null
  try {
    const data: any = dataOf(await bookApi.autoCreate(form.value), '一键创作失败')
    const taskId = Number(data?.taskId)
    if (!taskId) throw new Error('一键创作任务已提交，但服务没有返回任务编号')
    autoTask.value = data
    bookId.value = Number(data.bookId ?? data.book?.id) || null
    if (data.book) applyBookData(data.book)
    step.value = 3
    startAutoPolling(taskId)
  } catch (error: any) {
    fail(error, '一键创作失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

async function retryPage(page: StoryPage) {
  if (!bookId.value || retryingPageId.value !== null || !tasksKnown.value || imageTask(page)) return
  retryingPageId.value = page.id ?? -page.pageNo
  errorMessage.value = ''
  try {
    await persistDraft()
    if (!page.id) throw new Error('页面保存后未返回编号，请刷新后重试')
    retryingPageId.value = page.id
    const data: any = dataOf(await bookApi.retryImage(bookId.value, page.id, true), '插画重试失败')
    const created = Array.isArray(data?.tasks) ? data.tasks as ImageTask[] : []
    tasks.value = [...tasks.value.filter(task => !created.some(nextTask => nextTask.id === task.id)), ...created]
    if (Array.isArray(data?.pages)) withPageSync(() => mergePolledImages(pages.value, data.pages))
    if (created.length) {
      saveState.value = `第 ${page.pageNo} 页插画正在重试`
      startPolling()
    } else ElMessage.info('该页面已有插画任务正在处理')
  } catch (error: any) {
    fail(error, '插画重试失败')
    startPolling()
  } finally {
    retryingPageId.value = null
  }
}

function choosePageImage(page: StoryPage) {
  if (!bookId.value || !page.id) return ElMessage.warning('请先保存页面，再替换插画')
  pageImageTargetId.value = Number(page.id)
  pageImageFileInput.value?.click()
}

async function uploadPageImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const pageId = pageImageTargetId.value
  pageImageTargetId.value = null
  if (!file || !pageId || !bookId.value) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type.toLowerCase())) return ElMessage.warning('插画仅支持 PNG、JPG 或 WebP 图片')
  if (file.size > 6 * 1024 * 1024) return ElMessage.warning('插画不能超过 6 MB')
  pageImageUploadId.value = pageId
  try {
    await persistDraft()
    const data: any = dataOf(await bookApi.uploadPageImage(bookId.value, pageId, file), '插画上传失败')
    if (Array.isArray(data?.pages)) withPageSync(() => mergePolledImages(pages.value, data.pages))
    saveState.value = '插画已替换'
    ElMessage.success('页面插画已替换')
  } catch (error: any) {
    fail(error, '插画上传失败')
  } finally {
    pageImageUploadId.value = null
  }
}

function renumberPages() {
  pages.value = pages.value.map((page, index) => ({ ...page, pageNo: index + 1 }))
}

function movePage(index: number, offset: number) {
  if (pageStructureBusy.value) return
  const target = index + offset
  if (target < 0 || target >= pages.value.length) return
  const updated = [...pages.value]
  ;[updated[index], updated[target]] = [updated[target], updated[index]]
  pages.value = updated
  renumberPages()
}

function deletePage(index: number) {
  if (pageStructureBusy.value) return
  if (pages.value.length <= 1) return ElMessage.warning('绘本至少需要保留一页')
  pages.value = pages.value.filter((_, i) => i !== index)
  renumberPages()
}

function addPage() {
  if (pageStructureBusy.value) return
  pages.value = [...pages.value, { pageNo: pages.value.length + 1, storyText: '', summary: '', scenePrompt: '', imageStatus: 'READY' }]
}

async function persistDraft(showSuccess = false) {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = undefined
  const operation = saveQueue.catch(() => {}).then(async () => {
    if (!bookId.value) return
    const submitted = [...pages.value]
    const draft = JSON.parse(JSON.stringify({ id: bookId.value, title: bookTitle.value,
      description: bookDescription.value, ...form.value, pages: submitted.map(editablePage) })) as BookDraft
    const data: any = dataOf(await bookApi.save(draft), '草稿保存失败')
    if (Array.isArray(data?.pages)) withPageSync(() => mergeSavedImages(pages.value, submitted, data.pages))
    saveState.value = '已自动保存'
    if (showSuccess) ElMessage.success('绘本草稿已保存到我的绘本')
  })
  saveQueue = operation
  await operation
}

async function saveFinal() {
  if (!bookId.value) return fail(new Error('缺少绘本编号，请重新生成故事'))
  busy.value = true
  errorMessage.value = ''
  if (saveTimer) clearTimeout(saveTimer)
  try {
    await persistDraft(true)
    step.value = 4
  } catch (error: any) {
    fail(error, '草稿保存失败')
  } finally {
    busy.value = false
  }
}

function back() {
  if (busy.value) return
  if (step.value === 3 && activeTaskCount.value > 0) return ElMessage.info('插画任务完成后即可编辑页面')
  if (step.value > 1) step.value--
}

watch(() => JSON.stringify([bookTitle.value, bookDescription.value, pages.value.map(editablePage)]), () => {
  if (step.value !== 4 || syncingPages || !bookId.value) return
  if (saveTimer) clearTimeout(saveTimer)
  saveState.value = '修改未保存'
  saveTimer = setTimeout(async () => {
    if (busy.value) return
    autoSaving.value = true
    try {
      await persistDraft()
    } catch (error: any) {
      saveState.value = '自动保存失败'
      errorMessage.value = error?.message || '自动保存失败'
    } finally {
      autoSaving.value = false
    }
  }, 900)
}, { deep: true, flush: 'sync' })

onBeforeUnmount(() => {
  stopPolling()
  stopAutoPolling()
  if (saveTimer) clearTimeout(saveTimer)
})

onMounted(() => {
  void loadExistingBook(route.query.id)
})
</script>

<template>
  <div class="wizard-shell">
    <div class="stepper">
      <div v-for="(label, i) in ['故事设定', '角色与分镜', '生成插画', '编辑完成']" :key="label" class="step" :class="{ active: step === i + 1, done: step > i + 1 }">
        <i>{{ step > i + 1 ? '✓' : i + 1 }}</i><span>{{ label }}</span><div v-if="i < 3" class="step-line"></div>
      </div>
    </div>
    <div class="wizard-panel">
      <p v-if="errorMessage" role="alert" class="form-error">{{ errorMessage }}</p>
      <template v-if="step === 1">
        <h1>先说说你的故事吧 <span>✏️</span></h1>
        <p class="intro">不用担心写得不够好，灵感的第一句话最珍贵。</p>
        <div class="field"><label>故事主题 / 想法 <b class="required">*</b></label><textarea v-model="form.theme" rows="4" maxlength="500" placeholder="比如：一只害怕黑暗的小熊，发现月亮其实是一盏温柔的夜灯……"></textarea><small class="count">{{ form.theme.length }} / 500</small></div>
        <div class="field"><label>主角设定（可选）</label><input v-model="form.protagonist" maxlength="120" placeholder="比如：小熊糯米，戴红色围巾" /></div>
        <div class="field"><label>目标年龄段</label><div class="choice-grid"><button v-for="age in ages" :key="age" class="choice" :class="{ active: form.ageGroup === age }" @click="form.ageGroup = age">{{ age }}</button></div></div>
        <div class="field"><label>故事风格</label><div class="choice-grid"><button v-for="style in storyStyles" :key="style" class="choice" :class="{ active: form.storyStyle === style }" @click="form.storyStyle = style">{{ style }}</button></div></div>
        <div class="field"><label>绘本页数</label><div class="choice-grid"><button v-for="count in [8, 10, 12, 16]" :key="count" class="choice" :class="{ active: form.pageCount === count }" @click="form.pageCount = count">{{ count }} 页</button></div></div>
        <div class="field"><label>插画风格</label><div class="style-grid"><button v-for="style in styles" :key="style.name" class="style-choice" :class="{ active: form.imageStyle === style.name }" @click="form.imageStyle = style.name"><span class="style-emoji">{{ style.emoji }}</span>{{ style.name }}</button></div></div>
        <div class="field"><label>配文语言</label><div class="lang-toggle"><button :class="{ active: form.textLang === 'ZH' }" @click="form.textLang = 'ZH'">中文</button><button :class="{ active: form.textLang === 'EN' }" @click="form.textLang = 'EN'">EN · English</button></div></div>
      </template>

      <template v-else-if="step === 2">
        <h1>让角色成为故事的朋友 <span>🧸</span></h1>
        <p class="intro">选择预设角色，或为故事主角自定义形象。</p>
        <div class="character-tabs"><button class="character-tab" :class="{ active: mode === 'preset' }" @click="mode = 'preset'">预设角色库</button><button class="character-tab" :class="{ active: mode === 'custom' }" @click="mode = 'custom'">自定义角色</button><button class="character-tab" :class="{ active: mode === 'auto' }" @click="mode = 'auto'">AI 自动提取</button></div>
        <div v-if="characterLoading" class="loading-state"><div class="loader"></div><b>正在保存角色并生成分镜…</b></div>
        <div v-else-if="mode === 'preset'" class="character-grid">
          <div class="preset-filters" role="group" aria-label="预设角色类别"><button v-for="category in presetCategories" :key="category" class="preset-filter" :class="{ active: presetCategory === category }" @click="presetCategory = category">{{ presetCategoryLabels[category] }}</button></div>
          <button v-for="character in filteredPresets" :key="character.id" class="character-card" :class="{ selected: selectedChars.includes(Number(character.id)) }" :aria-pressed="selectedChars.includes(Number(character.id))" @click="selectedChars = selectedChars.includes(Number(character.id)) ? selectedChars.filter(id => id !== Number(character.id)) : [...selectedChars, Number(character.id)]">
            <img v-if="character.avatarUrl" class="preset-avatar" :src="character.avatarUrl" :alt="character.name" /><div v-else class="character-art">{{ character.emoji || '🧸' }}</div><b>{{ character.name }}</b><p>{{ character.description || character.appearancePrompt || '平台预设角色' }}</p>
          </button>
          <div v-if="!filteredPresets.length" class="empty">当前类别没有可用的预设角色。</div>
        </div>
        <div v-else-if="mode === 'custom'" class="custom-character">
          <div class="field"><label>角色名字</label><input v-model="customCharacter.name" maxlength="60" placeholder="比如：小熊糯米" /></div>
          <div class="field"><label>年龄与性别</label><input v-model="customCharacter.genderAge" maxlength="80" placeholder="比如：一只年幼的小熊" /></div>
          <div class="field"><label>外貌特征</label><textarea v-model="customCharacter.appearance" rows="3" maxlength="1200" placeholder="棕色毛发、圆圆的耳朵"></textarea><div class="field-actions"><el-button size="small" :loading="polishing" @click="polishCustomCharacter">AI 润色</el-button><el-button size="small" :loading="referenceLoading" @click="generateReference">生成角色参考图</el-button><el-button size="small" :loading="referenceUploadLoading" @click="chooseReferenceImage">上传角色参考图</el-button><input ref="referenceFileInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/webp" aria-label="选择角色参考图" @change="uploadReferenceImage" /></div><small class="upload-hint">PNG、JPG 或 WebP，最大 5 MB</small></div>
          <div class="field"><label>服装</label><input v-model="customCharacter.clothing" maxlength="500" placeholder="喜欢戴红色围巾" /></div>
          <div class="field"><label>标志特征</label><input v-model="customCharacter.features" maxlength="500" placeholder="左耳有一颗小星星形状的斑点" /></div>
          <div v-if="customCharacter.refImageUrl" class="reference-preview"><img :src="customCharacter.refImageUrl" alt="角色参考图" /><span>角色参考图</span></div>
        </div>
        <div v-else class="empty auto-extract"><div class="empty-icon">🔍</div><p>下一步将从故事内容中提取角色，并自动生成分镜与插画。</p></div>
      </template>

      <template v-else-if="step === 3">
        <h1>故事正在变成画面 <span>🎨</span></h1>
        <p class="intro">{{ bookId ? `绘本编号 ${bookId}` : '正在创建绘本' }} · {{ autoTask ? '一键创作任务' : `${tasks.length} 页插画任务` }}</p>
        <div class="task-summary"><div class="progress-track"><div class="progress-fill" :style="{ width: `${autoTask ? autoTask.progress : overallTaskProgress}%` }"></div></div><div class="task-summary-copy"><b>{{ autoTask ? (autoTask.status === 'FAILED' ? '一键创作失败' : autoTask.stageLabel || '一键创作处理中') : activeTaskCount ? `正在生成，${activeTaskCount} 页处理中` : '插画任务已结束' }}</b><span>{{ autoTask ? autoTask.progress : overallTaskProgress }}%</span></div></div>
        <div class="task-list"><div v-for="task in tasks" :key="task.id" class="task-row"><span>第 {{ pages.find(page => Number(page.id) === Number(task.pageId))?.pageNo || '—' }} 页</span><div class="mini-progress"><i :style="{ width: `${task.status === 'SUCCESS' ? 100 : task.progress}%` }"></i></div><b :class="`task-${task.status.toLowerCase()}`">{{ task.status === 'SUCCESS' ? '完成' : task.status === 'FAILED' ? '失败' : task.status === 'PROCESSING' ? `${task.progress}%` : '等待中' }}</b><small v-if="task.errorMsg">{{ task.errorMsg }}</small></div></div>
        <p v-if="!autoTask && !activeTaskCount" class="task-complete">正在打开页面编辑器…</p>
      </template>

      <template v-else>
        <div class="editor-heading"><div><h1>编辑你的绘本 <span>✨</span></h1><p class="intro">调整文字和页面顺序，修改会自动保存。</p></div><span class="save-state" :class="{ saving: autoSaving }">{{ autoSaving ? '保存中…' : saveState }}</span></div>
        <section class="book-metadata" aria-label="绘本信息">
          <div class="field"><label for="book-title">绘本标题</label><input id="book-title" v-model="bookTitle" maxlength="120" placeholder="输入绘本标题" /></div>
          <div class="field"><label for="book-description">封面语</label><textarea id="book-description" v-model="bookDescription" rows="2" maxlength="500" placeholder="输入绘本封面语"></textarea></div>
        </section>
        <BookFlipReader :book-id="bookId" :pages="pages" :title="bookTitle" />
        <section class="page-editor">
          <div class="editor-list-heading"><h2>页面内容</h2><el-button size="small" :disabled="pageStructureBusy" @click="addPage">添加页面</el-button></div>
          <article v-for="(page, index) in pages" :key="page.id || `new-${index}`" class="edit-page">
            <div class="edit-page-head"><b>第 {{ page.pageNo }} 页</b><div class="page-actions"><el-button text :disabled="pageStructureBusy || index === 0" aria-label="上移页面" @click="movePage(index, -1)">↑</el-button><el-button text :disabled="pageStructureBusy || index === pages.length - 1" aria-label="下移页面" @click="movePage(index, 1)">↓</el-button><el-button text type="danger" :disabled="pageStructureBusy" aria-label="删除页面" @click="deletePage(index)">删除</el-button></div></div>
            <div class="edit-page-body">
              <button v-if="page.imageUrl" type="button" class="edit-image-preview" :aria-label="`查看第 ${page.pageNo} 页完整插画`" @click="previewImage(page)"><img class="edit-image" :src="page.imageUrl" :alt="`第 ${page.pageNo} 页插画`" :style="{ aspectRatio: `${pageLayout.width} / ${pageLayout.height}` }" /><span>点击查看大图</span></button>
              <div v-else class="edit-image image-placeholder" :style="{ aspectRatio: `${pageLayout.width} / ${pageLayout.height}` }">{{ page.imageStatus === 'FAILED' ? '插画生成失败' : '插画待生成' }}</div>
              <div class="edit-fields">
                <div class="field"><label>故事文字</label><textarea v-model="page.storyText" rows="3" maxlength="2000" placeholder="填写这一页的故事文字"></textarea></div>
                <div class="field"><label>画面描述</label><textarea v-model="page.scenePrompt" rows="2" maxlength="1200" placeholder="描述这一页的插画画面"></textarea></div>
                <div class="edit-page-foot">
                  <small v-if="page.imageError && !imageTask(page)" class="image-error">{{ page.imageError }}</small>
                  <span v-if="!tasksKnown" class="image-status">正在同步插画状态…</span>
                  <span v-else-if="imageTask(page)" class="image-status" role="status">{{ imageTask(page)?.status === 'PENDING' ? '插画排队中' : '插画生成中' }} · {{ imageTask(page)?.progress || 0 }}%</span>
                  <el-button size="small" :loading="retryingPageId === (page.id ?? -page.pageNo)" :disabled="!tasksKnown || !!imageTask(page) || busy || autoSaving || pageImageUploadId !== null || (retryingPageId !== null && retryingPageId !== (page.id ?? -page.pageNo))" @click="retryPage(page)">{{ page.imageUrl ? '重新生成插画' : '生成插画' }}</el-button>
                </div>
              </div>
            </div>
            <div class="edit-page-upload"><el-button size="small" :disabled="!tasksKnown || !!imageTask(page) || retryingPageId !== null" :loading="pageImageUploadId === page.id" @click="choosePageImage(page)">替换插画</el-button></div>
          </article>
        </section>
        <input ref="pageImageFileInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/webp" aria-label="选择页面插画" @change="uploadPageImage" />
        <el-dialog v-model="previewVisible" :title="`第 ${previewPage?.pageNo || ''} 页插画`" width="min(1000px, calc(100vw - 32px))" append-to-body destroy-on-close><img v-if="previewPage?.imageUrl" class="edit-image-full" :src="previewPage.imageUrl" :alt="`第 ${previewPage.pageNo} 页完整插画`" /></el-dialog>
        <div class="final-actions"><el-button type="primary" :loading="busy" @click="saveFinal">{{ busy ? '保存中…' : '保存到我的绘本' }}</el-button></div>
      </template>

      <div class="wizard-footer"><button v-if="step > 1" class="btn btn-secondary" :disabled="busy || autoBookActive || (step === 3 && activeTaskCount > 0)" @click="back">← 上一步</button><span v-else></span><div class="right"><button v-if="step === 1" class="btn btn-secondary" :disabled="busy" @click="autoCreate">✦ 一键全自动</button><button v-if="step < 3" class="btn btn-primary" :disabled="busy" @click="step === 1 ? generateStory() : prepareStoryboard()">{{ busy ? '处理中…' : '下一步 →' }}</button><button v-if="step === 3 && !autoTask && activeTaskCount === 0" class="btn btn-primary" @click="step = 4">进入页面编辑 →</button><button v-if="step === 3 && autoTask?.status === 'FAILED'" class="btn btn-primary" :disabled="busy" @click="autoCreate">{{ busy ? '重新提交中…' : '重试一键创作' }}</button></div></div>
    </div>
    <div class="wizard-progress"><div :style="{ width: `${progress}%` }"></div></div>
  </div>
</template>

<style scoped>
.form-error{font-size:12px;color:#c45144;background:#fff0ed;padding:9px 11px;border-radius:6px;margin:0 0 15px}.required{color:#d95b45}.count{justify-self:end;color:#a99c9b}.field-actions{display:flex;gap:8px;flex-wrap:wrap}.visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.upload-hint{display:block;margin-top:6px;color:#988e89;font-size:11px}.character-grid{align-items:stretch}.preset-filters{grid-column:1/-1;display:flex;gap:6px;flex-wrap:wrap;margin-bottom:2px}.preset-filter{padding:6px 12px;border:1px solid #e8ddd6;border-radius:5px;background:#fff;color:#7b706a;font:inherit;font-size:12px;cursor:pointer}.preset-filter.active{border-color:#f29a75;background:#fff3ed;color:#c3684a}.character-card{width:100%;font:inherit;color:inherit;background:#fff;cursor:pointer}.preset-avatar{width:90px;height:90px;object-fit:cover;border-radius:8px;margin:0 auto 9px}.character-card p{line-height:1.5}.auto-extract{background:#fff}.reference-preview{display:grid;gap:6px;width:min(260px,100%);margin:4px 0 16px;color:#81766f;font-size:12px}.reference-preview img{display:block;width:100%;aspect-ratio:1/1;object-fit:cover;border-radius:8px;border:1px solid #eee3db}.wizard-progress{height:4px;background:#f0e5df;border-radius:99px;margin:18px auto 0;max-width:600px;overflow:hidden}.wizard-progress>div{height:100%;background:#ff865b;transition:width .3s}.task-summary{max-width:650px;margin:15px auto 22px}.progress-track,.mini-progress{height:8px;overflow:hidden;border-radius:99px;background:#f2e9e4}.progress-fill,.mini-progress i{display:block;height:100%;border-radius:inherit;background:#77bf98;transition:width .25s}.task-summary-copy{display:flex;justify-content:space-between;margin-top:8px;color:#766c69;font-size:12px}.task-list{max-width:650px;max-height:290px;overflow:auto;margin:auto;border-top:1px solid #f0e8e3}.task-row{display:grid;grid-template-columns:65px minmax(80px,1fr) 60px;align-items:center;gap:10px;padding:10px 4px;border-bottom:1px solid #f0e8e3;color:#6e6562;font-size:12px}.task-row small{grid-column:1/-1;color:#be5d50}.task-row b{text-align:right;font-size:11px}.task-success{color:#4b9872}.task-failed{color:#be5d50}.task-processing{color:#bc8a45}.task-pending{color:#968d89}.mini-progress{height:6px}.mini-progress i{background:#f2ad73}.task-complete{text-align:center;color:#867b77;font-size:12px}.editor-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.editor-heading .intro{margin-top:5px}.save-state{flex:none;color:#879784;font-size:11px}.save-state.saving{color:#9a8170}.book-metadata{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr);gap:14px;margin-top:18px}.book-metadata .field{margin:0}.book-metadata input,.book-metadata textarea{width:100%}.book-metadata textarea{resize:vertical}.page-editor{margin-top:26px}.editor-list-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.editor-list-heading h2{margin:0;font-size:16px}.edit-page{padding:15px 0;border-top:1px solid #efe5df}.edit-page-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:11px;color:#5d5452;font-size:12px}.page-actions{display:flex;align-items:center}.page-actions :deep(.el-button){min-width:32px}.edit-page-body{display:grid;grid-template-columns:minmax(150px,30%) minmax(0,1fr);gap:15px;align-items:start}.edit-image{display:block;width:100%;object-fit:contain;object-position:center;border-radius:6px;background:#f7eee7}.edit-image-preview{display:block;width:100%;padding:0;border:0;border-radius:6px;background:#f7eee7;overflow:hidden;cursor:zoom-in}.edit-image-preview span{display:block;padding:7px;color:#988e89;font-size:11px}.edit-image-preview:focus-visible{outline:2px solid #eb8059;outline-offset:3px}.edit-image-full{display:block;width:100%;height:min(74vh,1000px);object-fit:contain;object-position:center}.edit-page-foot{flex-wrap:wrap}.image-placeholder{display:grid;place-items:center;color:#a2948a;font-size:12px}.edit-fields .field{margin-bottom:9px}.edit-fields textarea{width:100%;resize:vertical}.edit-page-foot{min-height:28px;display:flex;align-items:center;justify-content:space-between;gap:8px}.image-error{color:#c45144;font-size:11px}.image-status{color:#988e89;font-size:11px}.edit-page-upload{display:flex;justify-content:flex-end;margin-top:8px}.final-actions{display:flex;justify-content:flex-end;margin-top:14px}
@media(max-width:640px){.book-metadata{grid-template-columns:1fr}.edit-page-body{grid-template-columns:1fr}.edit-image{max-width:360px}.editor-heading{align-items:flex-start}.task-row{grid-template-columns:55px minmax(60px,1fr) 48px;gap:7px}}
/* Fill the space beside each illustration with two equally sized writing areas. */
.edit-page-body { align-items: stretch; }
.edit-fields { display: grid; grid-template-rows: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 9px; min-height: 0; }
.edit-fields .field { display: grid; grid-template-rows: auto minmax(0, 1fr); min-height: 0; margin: 0; }
.edit-fields textarea { width: 100%; height: 100%; min-height: 0; resize: none; }
@media (max-width: 640px) {
  .edit-fields { min-height: 340px; }
}
</style>
