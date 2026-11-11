<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatLineSquare, Checked, Connection, Cpu, DataBoard, Delete, Document, Edit, Plus, Refresh, Tickets, User, Warning, View, Lock
} from '@element-plus/icons-vue'
import { adminApi } from '../api'
import { useSessionStore } from '../stores/session'
import AdminDashboardCharts from '../components/AdminDashboardCharts.vue'

type AdminSection = 'dashboard' | 'users' | 'presets' | 'review' | 'models' | 'prompts' | 'words' | 'logs'
type Row = Record<string, any>

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const section = computed<AdminSection>(() => {
  const candidate = String(route.params.section || 'dashboard')
  return (['dashboard', 'users', 'presets', 'review', 'models', 'prompts', 'words', 'logs'] as string[]).includes(candidate)
    ? candidate as AdminSection
    : 'dashboard'
})

const menus = [
  { key: 'dashboard', label: '数据看板', icon: DataBoard },
  { key: 'users', label: '用户与权限', icon: User },
  { key: 'presets', label: '预设角色', icon: View },
  { key: 'review', label: '内容审核', icon: Checked },
  { key: 'models', label: '模型配置', icon: Cpu },
  { key: 'prompts', label: '提示词模板', icon: ChatLineSquare },
  { key: 'words', label: '敏感词库', icon: Warning },
  { key: 'logs', label: '操作日志', icon: Tickets }
] as const

const titles: Record<AdminSection, string> = {
  dashboard: '数据看板', users: '用户与权限', presets: '预设角色', review: '内容审核', models: '模型配置',
  prompts: '提示词模板', words: '敏感词库', logs: '操作日志'
}
const loading = ref(false)
const errorMessage = ref('')
const dashboard = ref<Row>({})
const dashboardRefresh = ref(0)
const users = ref<Row[]>([])
const userBusyId = ref<number | null>(null)
const presets = ref<Row[]>([])
const reviewQueue = ref<Row[]>([])
const models = ref<Row[]>([])
const prompts = ref<Row[]>([])
const words = ref<Row[]>([])
const logs = ref<Row[]>([])
const search = ref('')
const wordTab = ref('dictionary')
const selectedWords = ref<Row[]>([])
const wordTable = ref<any>(null)
const wordHits = ref<Row[]>([])
const wordHitTotal = ref(0)
const wordHitLoading = ref(false)
const wordHitKeyword = ref('')
const wordHitContext = ref('')
const wordHitPage = ref(1)
const wordHitPageSize = ref(20)

function responseData(response: any, fallback: string) {
  if (!response || response.code !== 0) throw new Error(response?.message || fallback)
  return response.data
}

function listData(data: any): Row[] {
  return Array.isArray(data) ? data : []
}

async function loadSection() {
  loading.value = true
  errorMessage.value = ''
  search.value = ''
  try {
    switch (section.value) {
      case 'dashboard': dashboardRefresh.value++; dashboard.value = responseData(await adminApi.dashboard(), '看板数据加载失败') || {}; break
      case 'users': users.value = listData(responseData(await adminApi.users(), '用户列表加载失败')); break
      case 'presets': presets.value = listData(responseData(await adminApi.presets(), '预设角色列表加载失败')); break
      case 'review': reviewQueue.value = listData(responseData(await adminApi.reviewQueue(), '审核队列加载失败')); break
      case 'models': models.value = listData(responseData(await adminApi.models(), '模型配置加载失败')); break
      case 'prompts': prompts.value = listData(responseData(await adminApi.prompts(), '提示词模板加载失败')); break
      case 'words':
        words.value = listData(responseData(await adminApi.words(), '敏感词列表加载失败'))
        selectedWords.value = []
        await loadWordHits(1)
        break
      case 'logs': logs.value = listData(responseData(await adminApi.logs(), '操作日志加载失败')); break
    }
  } catch (error: any) {
    errorMessage.value = error?.message || `${titles[section.value]}加载失败`
  } finally {
    loading.value = false
  }
}

watch(section, () => { void loadSection() }, { immediate: true })

function go(key: string) {
  if (key !== section.value) void router.push(key === 'dashboard' ? '/admin' : `/admin/${key}`)
}

function logout() {
  session.logout()
  void router.push('/admin/login')
}

const metricCards = computed(() => [
  { key: 'users', label: '用户总数', icon: User, tint: 'blue' },
  { key: 'books', label: '作品总数', icon: Document, tint: 'violet' },
  { key: 'pending', label: '待审核作品', icon: Checked, tint: 'amber' },
  { key: 'todayStories', label: '今日新增绘本', icon: ChatLineSquare, tint: 'green' },
  { key: 'todayImages', label: '今日成功插画', icon: Cpu, tint: 'rose' },
  { key: 'autoBooks', label: '一键创作作品', icon: Tickets, tint: 'cyan' }
])

const filteredUsers = computed(() => filterRows(users.value, search.value, ['username', 'nickname', 'email', 'role']))
const filteredPresets = computed(() => filterRows(presets.value, search.value, ['name', 'category', 'description', 'appearancePrompt']))
const filteredReviews = computed(() => filterRows(reviewQueue.value, search.value, ['title', 'description', 'userId', 'id']))
const filteredModels = computed(() => filterRows(models.value, search.value, ['configKey', 'modelName', 'baseUrl']))
const filteredPrompts = computed(() => filterRows(prompts.value, search.value, ['templateKey', 'name', 'content']))
const filteredWords = computed(() => filterRows(words.value, search.value, ['word', 'category', 'level', 'status']))
const filteredLogs = computed(() => filterRows(logs.value, search.value, ['username', 'userId', 'module', 'action', 'detail', 'ip']))

function filterRows(rows: Row[], query: string, fields: string[]) {
  const value = query.trim().toLocaleLowerCase()
  if (!value) return rows
  return rows.filter(row => fields.some(field => String(row[field] ?? '').toLocaleLowerCase().includes(value)))
}

function isWordEnabled(status: unknown) {
  return Number(status) === 0
}

async function loadWordHits(page = wordHitPage.value) {
  wordHitLoading.value = true
  try {
    const response = await adminApi.wordHits({
      keyword: wordHitKeyword.value.trim(), context: wordHitContext.value,
      page, size: wordHitPageSize.value
    })
    const result = responseData(response, '敏感词命中记录加载失败') || {}
    wordHits.value = listData(result.items)
    wordHitTotal.value = Number(result.total || 0)
    wordHitPage.value = Number(result.page || page)
    wordHitPageSize.value = Number(result.size || wordHitPageSize.value)
  } catch (error: any) {
    ElMessage.error(error?.message || '敏感词命中记录加载失败')
  } finally {
    wordHitLoading.value = false
  }
}

function handleWordSelectionChange(selection: Row[]) {
  selectedWords.value = selection
}

async function setSelectedWordsEnabled(enabled: boolean) {
  const ids = selectedWords.value.map(row => Number(row.id)).filter(id => Number.isInteger(id) && id > 0)
  if (!ids.length) return ElMessage.warning('请先选择敏感词')
  if (!enabled) {
    try {
      await ElMessageBox.confirm(`确定停用选中的 ${ids.length} 个敏感词吗？`, '批量停用', {
        type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  try {
    responseData(await adminApi.setWordsEnabled(ids, enabled), '敏感词状态更新失败')
    const selected = new Set(ids)
    words.value = words.value.map(row => selected.has(Number(row.id)) ? { ...row, status: enabled ? 0 : 1 } : row)
    selectedWords.value = []
    wordTable.value?.clearSelection()
    ElMessage.success(enabled ? '已批量启用敏感词' : '已批量停用敏感词')
  } catch (error: any) {
    ElMessage.error(error?.message || '敏感词状态更新失败')
  }
}

async function updateUserEnabled(user: Row, enabled: boolean) {
  const id = Number(user.id)
  if (!Number.isInteger(id) || id <= 0) {
    ElMessage.error('用户记录缺少有效编号')
    return
  }
  if (userBusyId.value !== null) return
  if (!enabled) {
    try {
      await ElMessageBox.confirm(`确定禁用账号“${user.username || id}”吗？该用户将无法登录。`, '禁用用户', {
        type: 'warning', confirmButtonText: '禁用', cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }

  userBusyId.value = id
  try {
    const result = responseData(await adminApi.setUserEnabled(id, enabled), '用户状态更新失败')
    user.enabled = typeof result?.enabled === 'boolean' ? result.enabled : enabled
    ElMessage.success(enabled ? '用户已启用' : '用户已禁用')
  } catch (error: any) {
    ElMessage.error(error?.message || '用户状态更新失败')
  } finally {
    userBusyId.value = null
  }
}

const userDialog = ref(false)
const userDetailBusy = ref(false)
const selectedUser = ref<Row | null>(null)

async function openUserDetails(user: Row) {
  selectedUser.value = user
  userDialog.value = true
  userDetailBusy.value = true
  try {
    const detail = responseData(await adminApi.userDetails(Number(user.id)), '用户详情加载失败')
    selectedUser.value = { ...user, ...(detail || {}) }
  } catch (error: any) {
    ElMessage.error(error?.message || '用户详情加载失败')
  } finally {
    userDetailBusy.value = false
  }
}

async function resetUserPassword(user: Row) {
  try {
    await ElMessageBox.confirm(`确定重置账号“${user.username || user.id}”的密码吗？`, '重置密码', { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' })
    const result = responseData(await adminApi.resetUserPassword(Number(user.id)), '密码重置失败') || {}
    if (result.temporaryPassword) await ElMessageBox.alert(`临时密码：${result.temporaryPassword}`, '密码已重置', { confirmButtonText: '知道了' })
    else ElMessage.success('密码已重置')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.message || '密码重置失败')
  }
}

async function deleteUser(user: Row) {
  try {
    await ElMessageBox.confirm(`删除账号“${user.username || user.id}”后将无法登录，确定继续吗？`, '删除用户', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    responseData(await adminApi.deleteUser(Number(user.id)), '用户删除失败')
    users.value = users.value.filter(row => row !== user)
    ElMessage.success('用户已删除')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.message || '用户删除失败')
  }
}

const presetDialog = ref(false)
const presetBusy = ref(false)
const presetTarget = ref<Row | null>(null)
const presetImage = ref<File | null>(null)
const presetDraft = reactive({ name: '', category: 'OTHER', avatarUrl: '', appearancePrompt: '', description: '', sort: '0', status: '0' })

function openPresetEditor(preset?: Row) {
  presetTarget.value = preset || null
  presetDraft.name = String(preset?.name || '')
  presetDraft.category = String(preset?.category || 'OTHER')
  presetDraft.avatarUrl = String(preset?.avatarUrl || '')
  presetDraft.appearancePrompt = String(preset?.appearancePrompt || '')
  presetDraft.description = String(preset?.description || '')
  presetDraft.sort = String(preset?.sort ?? 0)
  presetDraft.status = String(preset?.status ?? 0)
  presetImage.value = null
  presetDialog.value = true
}

function validatePreset() {
  if (!presetDraft.name.trim()) return '请填写角色名称'
  if (!presetDraft.avatarUrl.trim() && !presetImage.value) return '请填写头像地址或选择图片文件'
  if (!presetDraft.appearancePrompt.trim()) return '请填写外观提示词'
  if (!['BOY', 'GIRL', 'ANIMAL', 'FANTASY', 'OTHER'].includes(presetDraft.category)) return '请选择有效角色类别'
  return ''
}

async function savePreset() {
  const validation = validatePreset()
  if (validation) return ElMessage.warning(validation)
  presetBusy.value = true
  const payload = { name: presetDraft.name.trim(), category: presetDraft.category, avatarUrl: presetDraft.avatarUrl.trim(), appearancePrompt: presetDraft.appearancePrompt.trim(), description: presetDraft.description.trim(), sort: Number(presetDraft.sort || 0), status: Number(presetDraft.status || 0) }
  try {
    const response = presetTarget.value ? await adminApi.updatePreset(resourceIdentity(presetTarget.value, 'name'), payload) : await adminApi.createPreset(payload)
    const saved = responseData(response, '预设角色保存失败')
    let savedRow = { ...(saved || payload) }
    if (presetImage.value && savedRow.id) {
      const imageResponse = responseData(await adminApi.uploadPresetImage(savedRow.id, presetImage.value), '预设角色图片上传失败')
      savedRow = { ...savedRow, ...(imageResponse || {}) }
    }
    if (presetTarget.value) presets.value = presets.value.map(row => row === presetTarget.value ? { ...row, ...savedRow } : row)
    else presets.value = [...presets.value, savedRow]
    presetDialog.value = false
    ElMessage.success(presetTarget.value ? '预设角色已更新' : '预设角色已创建')
  } catch (error: any) {
    ElMessage.error(error?.message || '预设角色保存失败')
  } finally {
    presetBusy.value = false
  }
}

function selectPresetImage(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
    ElMessage.warning('角色图片仅支持 PNG、JPG 或 WebP')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('预设角色图片不能超过 2 MB')
    return
  }
  presetImage.value = file
}

async function deletePreset(preset: Row) {
  try {
    await ElMessageBox.confirm(`确定删除预设角色“${preset.name}”吗？`, '删除预设角色', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    responseData(await adminApi.deletePreset(resourceIdentity(preset, 'name')), '预设角色删除失败')
    presets.value = presets.value.filter(row => row !== preset)
    ElMessage.success('预设角色已删除')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.message || '预设角色删除失败')
  }
}

function handleUserSwitchChange(user: Row, value: string | number | boolean) {
  void updateUserEnabled(user, Boolean(value))
}

function displayDate(value: unknown) {
  if (!value) return '—'
  const date = new Date(String(value))
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const reviewDialog = ref(false)
const selectedBook = ref<Row | null>(null)
const reviewBusy = ref(false)
const rejectReason = ref('')

function openReview(book: Row) {
  selectedBook.value = book
  rejectReason.value = ''
  reviewDialog.value = true
}

async function submitReview(approved: boolean) {
  if (!selectedBook.value?.id) return
  if (!approved && !rejectReason.value.trim()) {
    ElMessage.warning('请填写驳回理由')
    return
  }
  reviewBusy.value = true
  try {
    responseData(await adminApi.review(Number(selectedBook.value.id), {
      approved,
      rejectReason: approved ? '' : rejectReason.value.trim()
    }), '审核操作失败')
    reviewQueue.value = reviewQueue.value.filter(book => book.id !== selectedBook.value?.id)
    reviewDialog.value = false
    ElMessage.success(approved ? '作品已通过审核' : '作品已驳回')
    if (section.value === 'dashboard') dashboard.value = responseData(await adminApi.dashboard(), '看板数据加载失败') || {}
  } catch (error: any) {
    ElMessage.error(error?.message || '审核操作失败')
  } finally {
    reviewBusy.value = false
  }
}

const modelDialog = ref(false)
const modelBusy = ref(false)
const modelTestBusy = ref(false)
const modelDraft = reactive({ configKey: '', baseUrl: '', modelName: '', temperature: '', maxTokens: '', imageSize: '', supportsImageReference: false })
const selectedModel = ref<Row | null>(null)

function editModel(model: Row) {
  selectedModel.value = model
  modelDraft.configKey = String(model.configKey || '')
  modelDraft.baseUrl = String(model.baseUrl || '')
  modelDraft.modelName = String(model.modelName || '')
  const params = model.params && typeof model.params === 'object' ? model.params : {}
  modelDraft.temperature = String(model.temperature ?? params.temperature ?? '')
  modelDraft.maxTokens = String(model.maxTokens ?? params.maxTokens ?? '')
  modelDraft.imageSize = String(model.imageSize ?? params.imageSize ?? '')
  modelDraft.supportsImageReference = Boolean(model.supportsImageReference ?? params.supportsImageReference)
  modelDialog.value = true
}

async function saveModel() {
  if (!modelDraft.configKey) return
  modelBusy.value = true
  try {
    const params: Row = {}
    if (modelDraft.temperature !== '') params.temperature = Number(modelDraft.temperature)
    if (modelDraft.maxTokens !== '') params.maxTokens = Number(modelDraft.maxTokens)
    if (modelDraft.imageSize) params.imageSize = modelDraft.imageSize
    params.supportsImageReference = modelDraft.supportsImageReference
    const payload: Row = {
      configKey: modelDraft.configKey,
      baseUrl: modelDraft.baseUrl,
      modelName: modelDraft.modelName,
      params
    }
    const result = responseData(await adminApi.saveModel(payload), '模型配置提交失败')
    const updated = result && typeof result === 'object' ? result : payload
    models.value = models.value.map(model => model.configKey === modelDraft.configKey ? { ...model, ...updated } : model)
    modelDialog.value = false
    ElMessage.success('模型配置更新请求已提交')
  } catch (error: any) {
    ElMessage.error(error?.message || '模型配置提交失败')
  } finally {
    modelBusy.value = false
  }
}

const promptDialog = ref(false)
const promptBusy = ref(false)
const promptTarget = ref<Row | null>(null)
const promptDraft = reactive({ templateKey: '', name: '', content: '' })

function openPromptEditor(prompt?: Row) {
  promptTarget.value = prompt || null
  promptDraft.templateKey = String(prompt?.templateKey || '')
  promptDraft.name = String(prompt?.name || '')
  promptDraft.content = String(prompt?.content || '')
  promptDialog.value = true
}

function resourceIdentity(row: Row, fallbackKey: string): number | string {
  const id = row.id ?? row[fallbackKey]
  if (id === undefined || id === null || String(id).trim() === '') throw new Error('记录缺少可用于更新的标识')
  return id
}

function validatePrompt() {
  const key = promptDraft.templateKey.trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$/.test(key)) return '模板键需以字母或数字开头，且仅可包含字母、数字、点、下划线、冒号和连字符'
  if (!promptDraft.name.trim()) return '请填写模板名称'
  if (!promptDraft.content.trim()) return '请填写提示词内容'
  const duplicate = prompts.value.some(row => row !== promptTarget.value && String(row.templateKey || '').toLowerCase() === key.toLowerCase())
  return duplicate ? '模板键已存在' : ''
}

async function savePrompt() {
  const validation = validatePrompt()
  if (validation) return ElMessage.warning(validation)
  promptBusy.value = true
  const payload = { templateKey: promptDraft.templateKey.trim(), name: promptDraft.name.trim(), content: promptDraft.content.trim() }
  try {
    const response = promptTarget.value
      ? await adminApi.updatePrompt(resourceIdentity(promptTarget.value, 'templateKey'), payload)
      : await adminApi.createPrompt(payload)
    const result = responseData(response, '提示词模板保存失败')
    const saved = result && typeof result === 'object' && !Array.isArray(result) ? result : payload
    if (promptTarget.value) prompts.value = prompts.value.map(row => row === promptTarget.value ? { ...row, ...saved } : row)
    else prompts.value = [...prompts.value, saved]
    promptDialog.value = false
    ElMessage.success(promptTarget.value ? '提示词模板已更新' : '提示词模板已创建')
  } catch (error: any) {
    ElMessage.error(error?.message || '提示词模板保存失败')
  } finally {
    promptBusy.value = false
  }
}

async function deletePrompt(prompt: Row) {
  try {
    await ElMessageBox.confirm(`确定删除“${prompt.name || prompt.templateKey}”吗？`, '删除提示词模板', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    responseData(await adminApi.deletePrompt(resourceIdentity(prompt, 'templateKey')), '提示词模板删除失败')
    prompts.value = prompts.value.filter(row => row !== prompt)
    ElMessage.success('提示词模板已删除')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.message || '提示词模板删除失败')
  }
}

const wordDialog = ref(false)
const wordBusy = ref(false)
const wordTarget = ref<Row | null>(null)
const wordDraft = reactive({ word: '', category: '', level: '', status: '' })

function openWordEditor(word?: Row) {
  wordTarget.value = word || null
  wordDraft.word = String(word?.word || '')
  wordDraft.category = String(word?.category || '')
  wordDraft.level = String(word?.level || '')
  wordDraft.status = String(word?.status ?? 0)
  wordDialog.value = true
}

function validateWord() {
  const value = wordDraft.word.trim()
  if (!value) return '请填写敏感词'
  if (value.length > 100) return '敏感词不能超过 100 个字符'
  const duplicate = words.value.some(row => row !== wordTarget.value && String(row.word || '').toLocaleLowerCase() === value.toLocaleLowerCase())
  return duplicate ? '该敏感词已存在' : ''
}

async function saveWord() {
  const validation = validateWord()
  if (validation) return ElMessage.warning(validation)
  wordBusy.value = true
  const payload = {
    word: wordDraft.word.trim(),
    category: wordDraft.category.trim(),
    level: wordDraft.level.trim(),
    status: Number(wordDraft.status || 0)
  }
  try {
    const response = wordTarget.value
      ? await adminApi.updateWord(resourceIdentity(wordTarget.value, 'word'), payload)
      : await adminApi.createWord(payload)
    const result = responseData(response, '敏感词保存失败')
    const saved = result && typeof result === 'object' && !Array.isArray(result) ? result : payload
    if (wordTarget.value) words.value = words.value.map(row => row === wordTarget.value ? { ...row, ...saved } : row)
    else words.value = [...words.value, saved]
    wordDialog.value = false
    ElMessage.success(wordTarget.value ? '敏感词已更新' : '敏感词已添加')
  } catch (error: any) {
    ElMessage.error(error?.message || '敏感词保存失败')
  } finally {
    wordBusy.value = false
  }
}

async function deleteWord(word: Row) {
  try {
    await ElMessageBox.confirm(`确定删除敏感词“${word.word}”吗？`, '删除敏感词', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    responseData(await adminApi.deleteWord(resourceIdentity(word, 'word')), '敏感词删除失败')
    words.value = words.value.filter(row => row !== word)
    ElMessage.success('敏感词已删除')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.message || '敏感词删除失败')
  }
}

async function testModelConnection() {
  if (!modelDraft.baseUrl.trim() || !modelDraft.modelName.trim()) return ElMessage.warning('请先填写 Base URL 和模型名称')
  modelTestBusy.value = true
  try {
    const payload: Row = { configKey: modelDraft.configKey, baseUrl: modelDraft.baseUrl.trim(), modelName: modelDraft.modelName.trim() }
    const result = responseData(await adminApi.testModel(payload), '模型连接测试失败')
    if (result && typeof result === 'object' && (result.success === false || result.connected === false)) {
      throw new Error(result.message || '模型连接测试失败')
    }
    ElMessage.success(result?.message || '模型连接测试成功')
  } catch (error: any) {
    ElMessage.error(error?.message || '模型连接测试失败')
  } finally {
    modelTestBusy.value = false
  }
}
</script>

<template>
  <div class="admin-console">
    <aside class="admin-sidebar">
      <div class="admin-brand"><span class="brand-mark">Q</span><span>奇妙绘本<small>OPERATIONS</small></span></div>
      <div class="sidebar-caption">平台运营</div>
      <el-menu :default-active="section" class="admin-menu" @select="go">
        <el-menu-item v-for="item in menus" :key="item.key" :index="item.key">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
      <div class="sidebar-foot">AI 绘本创作平台 · 管理端</div>
    </aside>

    <main class="admin-main">
      <header class="admin-header">
        <div class="header-title"><span>运营中心</span><el-icon><Document /></el-icon><h1>{{ titles[section] }}</h1></div>
        <div class="header-actions">
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新当前页面" @click="loadSection">刷新</el-button>
          <el-divider direction="vertical" />
          <span class="admin-user"><el-avatar :size="30">{{ session.nickname.slice(0, 1) }}</el-avatar><b>{{ session.nickname }}</b></span>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </header>

      <section class="admin-content">
        <div v-if="errorMessage" class="error-panel" role="alert">
          <div><el-icon><Warning /></el-icon><span>{{ errorMessage }}</span></div>
          <el-button type="primary" plain @click="loadSection">重试</el-button>
        </div>

        <div v-loading="loading" class="section-body">
          <template v-if="section === 'dashboard'">
            <div class="section-intro"><div><h2>平台概况</h2><p>平台实时总览 · 今日指标按北京时间统计</p></div></div>
            <div class="metric-grid">
              <el-card v-for="metric in metricCards" :key="metric.label" shadow="never" class="metric-card">
                <div class="metric-head"><span>{{ metric.label }}</span><span class="metric-icon" :class="metric.tint"><el-icon><component :is="metric.icon" /></el-icon></span></div>
                <strong>{{ dashboard[metric.key] ?? '—' }}</strong>
              </el-card>
            </div>
            <AdminDashboardCharts :refresh-key="dashboardRefresh" />
          </template>

          <template v-else-if="section === 'users'">
            <div class="section-intro"><div><h2>用户与权限</h2><p>查看用户资料、重置密码、管理账号状态。</p></div><el-input v-model="search" clearable placeholder="搜索账号、昵称或邮箱" class="search-input" /></div>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredUsers" v-loading="loading" row-key="id">
                <el-table-column prop="id" label="用户 ID" width="110" />
                <el-table-column prop="username" label="账号" min-width="150" />
                <el-table-column prop="nickname" label="昵称" min-width="150" />
                <el-table-column prop="email" label="邮箱" min-width="200" />
                <el-table-column prop="role" label="角色" width="140">
                  <template #default="scope"><el-tag effect="plain" :type="String(scope.row.role).includes('ADMIN') ? 'warning' : 'info'">{{ scope.row.role || '—' }}</el-tag></template>
                </el-table-column>
                <el-table-column prop="enabled" label="状态与操作" width="175">
                  <template #default="scope"><div class="user-state-control"><el-tag :type="scope.row.enabled ? 'success' : 'danger'">{{ scope.row.enabled ? '启用' : '禁用' }}</el-tag><el-switch :model-value="Boolean(scope.row.enabled)" :loading="userBusyId === Number(scope.row.id)" :aria-label="`${scope.row.enabled ? '禁用' : '启用'}账号 ${scope.row.username || scope.row.id}`" @change="handleUserSwitchChange(scope.row, $event)" /></div></template>
                </el-table-column>
                <el-table-column label="操作" width="230" fixed="right"><template #default="scope"><el-button link type="primary" :icon="View" @click="openUserDetails(scope.row)">详情</el-button><el-button link type="warning" :icon="Lock" @click="resetUserPassword(scope.row)">重置密码</el-button><el-button link type="danger" :icon="Delete" @click="deleteUser(scope.row)">删除</el-button></template></el-table-column>
                <template #empty><el-empty description="暂无用户记录" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>

          <template v-else-if="section === 'presets'">
            <div class="section-intro"><div><h2>预设角色</h2><p>维护创作页可选的角色名称、分类、头像和外观提示词。</p></div><div class="section-tools"><el-input v-model="search" clearable placeholder="搜索角色名称、分类或描述" class="search-input" /><el-button type="primary" :icon="Plus" @click="openPresetEditor()">新建角色</el-button></div></div>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredPresets" v-loading="loading" row-key="id">
                <el-table-column label="头像" width="80"><template #default="scope"><el-avatar shape="square" :size="42" :src="scope.row.avatarUrl">{{ String(scope.row.name || '?').slice(0, 1) }}</el-avatar></template></el-table-column>
                <el-table-column prop="name" label="名称" min-width="160" />
                <el-table-column prop="category" label="分类" width="120" />
                <el-table-column prop="description" label="描述" min-width="230" />
                <el-table-column prop="sort" label="排序" width="90" />
                <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="Number(scope.row.status) === 0 ? 'success' : 'info'">{{ Number(scope.row.status) === 0 ? '启用' : '停用' }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openPresetEditor(scope.row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="deletePreset(scope.row)">删除</el-button></template></el-table-column>
                <template #empty><el-empty description="暂无预设角色" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>

          <template v-else-if="section === 'review'">
            <div class="section-intro"><div><h2>内容审核</h2><p>打开作品可查看故事内容并提交审核结果。</p></div><el-input v-model="search" clearable placeholder="搜索作品标题或作者编号" class="search-input" /></div>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredReviews" v-loading="loading" row-key="id">
                <el-table-column prop="id" label="作品 ID" width="110" />
                <el-table-column prop="title" label="作品标题" min-width="220"><template #default="scope"><b>{{ scope.row.title || `未命名作品 #${scope.row.id}` }}</b></template></el-table-column>
                <el-table-column prop="userId" label="作者 ID" width="130" />
                <el-table-column prop="textLang" label="语言" width="100"><template #default="scope">{{ scope.row.textLang || '—' }}</template></el-table-column>
                <el-table-column prop="updatedAt" label="更新时间" min-width="190"><template #default="scope">{{ displayDate(scope.row.updatedAt) }}</template></el-table-column>
                <el-table-column label="页面" width="90"><template #default="scope">{{ Array.isArray(scope.row.pages) ? scope.row.pages.length : 0 }}</template></el-table-column>
                <el-table-column label="操作" width="110" fixed="right"><template #default="scope"><el-button type="primary" link @click="openReview(scope.row)">查看并审核</el-button></template></el-table-column>
                <template #empty><el-empty description="当前没有待审核作品" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>

          <template v-else-if="section === 'models'">
            <div class="section-intro"><div><h2>模型配置</h2><p>模型编辑通过现有更新接口提交，密钥字段不会从接口读取或显示。</p></div><el-input v-model="search" clearable placeholder="搜索模型类型或名称" class="search-input" /></div>
            <el-alert class="api-note" type="info" :closable="false" show-icon>
              当前 GET 接口返回的 base URL 和模型名来自环境变量占位配置；现有 PUT 实现仅回显提交内容，不会持久化到数据库。
            </el-alert>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredModels" v-loading="loading" row-key="configKey">
                <el-table-column prop="configKey" label="类型" width="130"><template #default="scope"><el-tag effect="plain">{{ scope.row.configKey || '—' }}</el-tag></template></el-table-column>
                <el-table-column prop="modelName" label="模型名称" min-width="200" />
                <el-table-column prop="baseUrl" label="Base URL" min-width="260" />
                <el-table-column label="参考图能力" min-width="140"><template #default="scope">{{ scope.row.supportsImageReference ?? scope.row.params?.supportsImageReference ? '支持' : '未配置' }}</template></el-table-column>
                <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button type="primary" link @click="editModel(scope.row)">编辑</el-button></template></el-table-column>
                <template #empty><el-empty description="暂无模型配置" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>

          <template v-else-if="section === 'prompts'">
            <div class="section-intro"><div><h2>提示词模板</h2><p>管理用于故事和分镜生成的提示词内容。</p></div><div class="section-tools"><el-input v-model="search" clearable placeholder="搜索模板名称、键或内容" class="search-input" /><el-button type="primary" :icon="Plus" @click="openPromptEditor()">新建模板</el-button></div></div>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredPrompts" v-loading="loading" row-key="templateKey">
                <el-table-column prop="templateKey" label="模板键" min-width="190" />
                <el-table-column prop="name" label="模板名称" min-width="180" />
                <el-table-column label="内容预览" min-width="300"><template #default="scope"><span class="content-preview">{{ scope.row.content || '—' }}</span></template></el-table-column>
                <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openPromptEditor(scope.row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="deletePrompt(scope.row)">删除</el-button></template></el-table-column>
                <template #empty><el-empty description="暂无提示词模板" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>

          <template v-else-if="section === 'words'">
            <div class="section-intro"><div><h2>敏感词库</h2><p>维护内容审核使用的敏感词、分类和处理级别。</p></div></div>
            <el-tabs v-model="wordTab" class="word-tabs">
              <el-tab-pane label="词库管理" name="dictionary">
                <div class="section-intro word-toolbar">
                  <el-input v-model="search" clearable placeholder="搜索词语、分类或级别" class="search-input" />
                  <div class="section-tools">
                    <el-button :disabled="!selectedWords.length" @click="setSelectedWordsEnabled(true)">批量启用</el-button>
                    <el-button :disabled="!selectedWords.length" @click="setSelectedWordsEnabled(false)">批量停用</el-button>
                    <el-button type="primary" :icon="Plus" @click="openWordEditor()">添加敏感词</el-button>
                  </div>
                </div>
                <el-card shadow="never" class="table-card">
                  <el-table ref="wordTable" :data="filteredWords" v-loading="loading" row-key="id" @selection-change="handleWordSelectionChange">
                    <el-table-column type="selection" width="48" reserve-selection />
                    <el-table-column prop="word" label="敏感词" min-width="220" />
                    <el-table-column prop="category" label="分类" min-width="160" />
                    <el-table-column prop="level" label="级别" min-width="140" />
                    <el-table-column label="状态" width="110"><template #default="scope"><el-tag effect="plain" :type="isWordEnabled(scope.row.status) ? 'success' : 'info'">{{ isWordEnabled(scope.row.status) ? '启用' : '停用' }}</el-tag></template></el-table-column>
                    <el-table-column label="操作" width="150" fixed="right"><template #default="scope"><el-button link type="primary" :icon="Edit" @click="openWordEditor(scope.row)">编辑</el-button><el-button link type="danger" :icon="Delete" @click="deleteWord(scope.row)">删除</el-button></template></el-table-column>
                    <template #empty><el-empty description="词库当前为空" :image-size="72" /></template>
                  </el-table>
                </el-card>
              </el-tab-pane>
              <el-tab-pane label="命中记录" name="hits">
                <div class="section-intro word-toolbar">
                  <el-input v-model="wordHitKeyword" clearable placeholder="搜索操作人、词语、位置或目标 ID" class="search-input" @keyup.enter="loadWordHits(1)" />
                  <div class="section-tools">
                    <el-select v-model="wordHitContext" clearable placeholder="全部检测位置" class="context-filter">
                      <el-option label="故事输入" value="STORY_INPUT" /><el-option label="故事生成" value="STORY_OUTPUT" />
                      <el-option label="作品保存" value="BOOK_SAVE" /><el-option label="作品提交" value="BOOK_SUBMIT" />
                      <el-option label="评论" value="COMMENT" /><el-option label="审核标题" value="BOOK_REVIEW_TITLE" />
                      <el-option label="审核简介" value="BOOK_REVIEW_DESCRIPTION" /><el-option label="审核页面" value="BOOK_REVIEW_PAGE" />
                      <el-option label="角色设定" value="CHARACTER_SAVE" /><el-option label="分镜提示" value="IMAGE_PROMPT" />
                    </el-select>
                    <el-button :icon="Refresh" :loading="wordHitLoading" @click="loadWordHits(1)">查询</el-button>
                  </div>
                </div>
                <el-card shadow="never" class="table-card">
                  <el-table :data="wordHits" v-loading="wordHitLoading" row-key="id">
                    <el-table-column prop="actorName" label="操作人" min-width="150"><template #default="scope">{{ scope.row.actorName || '匿名' }}<small v-if="scope.row.actorId"> (#{{ scope.row.actorId }})</small></template></el-table-column>
                    <el-table-column prop="context" label="命中位置" min-width="190" />
                    <el-table-column prop="targetId" label="目标 ID" width="110" />
                    <el-table-column prop="matchedWord" label="命中词语" min-width="180" />
                    <el-table-column label="时间" min-width="190"><template #default="scope">{{ displayDate(scope.row.createdAt) }}</template></el-table-column>
                    <template #empty><el-empty description="暂无命中记录" :image-size="72" /></template>
                  </el-table>
                  <el-pagination v-if="wordHitTotal > wordHitPageSize" class="word-hit-pagination" background layout="total, prev, pager, next" :total="wordHitTotal" :page-size="wordHitPageSize" :current-page="wordHitPage" @current-change="loadWordHits" />
                </el-card>
              </el-tab-pane>
            </el-tabs>
          </template>

          <template v-else>
            <div class="section-intro"><div><h2>操作日志</h2><p>按当前接口返回的审计记录展示。</p></div><el-input v-model="search" clearable placeholder="搜索操作人、模块、动作或 IP" class="search-input" /></div>
            <el-card shadow="never" class="table-card">
              <el-table :data="filteredLogs" v-loading="loading" row-key="id">
                <el-table-column prop="id" label="记录 ID" width="110" />
                <el-table-column prop="userId" label="操作人 ID" width="130" />
                <el-table-column prop="module" label="模块" min-width="140" />
                <el-table-column prop="action" label="动作" min-width="160" />
                <el-table-column prop="ip" label="IP" min-width="150" />
                <el-table-column prop="costMs" label="耗时 (ms)" width="130" />
                <el-table-column label="详情" min-width="220"><template #default="scope"><span class="content-preview">{{ displayValue(scope.row.detail) }}</span></template></el-table-column>
                <el-table-column prop="createdAt" label="时间" min-width="190"><template #default="scope">{{ displayDate(scope.row.createdAt) }}</template></el-table-column>
                <template #empty><el-empty description="暂无操作日志" :image-size="72" /></template>
              </el-table>
            </el-card>
          </template>
        </div>
      </section>
    </main>

    <el-dialog v-model="reviewDialog" title="作品审核" width="min(860px, calc(100vw - 28px))" destroy-on-close>
      <div v-if="selectedBook" class="review-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="作品 ID">{{ selectedBook.id }}</el-descriptions-item>
          <el-descriptions-item label="作者 ID">{{ selectedBook.userId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ selectedBook.title || '未命名作品' }}</el-descriptions-item>
          <el-descriptions-item label="配文语言">{{ selectedBook.textLang || '—' }}</el-descriptions-item>
          <el-descriptions-item label="故事梗概" :span="2">{{ selectedBook.description || '—' }}</el-descriptions-item>
        </el-descriptions>
        <h3>绘本页面 ({{ Array.isArray(selectedBook.pages) ? selectedBook.pages.length : 0 }})</h3>
        <el-scrollbar max-height="300px">
          <article v-for="page in selectedBook.pages || []" :key="page.id || page.pageNo" class="review-page">
            <b>第 {{ page.pageNo }} 页</b>
            <p>{{ page.storyText || '—' }}</p>
            <small>{{ page.summary || '' }}</small>
            <a v-if="page.imageUrl" :href="page.imageUrl" target="_blank" rel="noreferrer">查看插画</a>
          </article>
          <el-empty v-if="!selectedBook.pages?.length" description="作品没有页面内容" :image-size="56" />
        </el-scrollbar>
        <el-form-item label="驳回理由" class="reject-field"><el-input v-model="rejectReason" type="textarea" :rows="2" maxlength="300" show-word-limit placeholder="驳回时必填" /></el-form-item>
      </div>
      <template #footer>
        <el-button @click="reviewDialog = false">关闭</el-button>
        <el-button type="danger" :loading="reviewBusy" @click="submitReview(false)">驳回</el-button>
        <el-button type="primary" :loading="reviewBusy" @click="submitReview(true)">通过</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="userDialog" title="用户详情" width="min(620px, calc(100vw - 28px))" destroy-on-close>
      <el-skeleton v-if="userDetailBusy" :rows="5" animated />
      <el-descriptions v-else-if="selectedUser" :column="2" border>
        <el-descriptions-item label="用户 ID">{{ selectedUser.id }}</el-descriptions-item>
        <el-descriptions-item label="账号">{{ selectedUser.username || '—' }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ selectedUser.nickname || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ selectedUser.email || '—' }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ selectedUser.role || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ selectedUser.enabled ? '启用' : '禁用' }}</el-descriptions-item>
        <el-descriptions-item label="作品数">{{ selectedUser.bookCount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="评论数">{{ selectedUser.commentCount ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="最后登录">{{ displayDate(selectedUser.lastLoginAt) }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ displayDate(selectedUser.createdAt) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button @click="userDialog = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="presetDialog" :title="presetTarget ? '编辑预设角色' : '新建预设角色'" width="min(680px, calc(100vw - 28px))" destroy-on-close>
      <el-form label-position="top" @submit.prevent="savePreset">
        <div class="form-grid">
          <el-form-item label="角色名称" required><el-input v-model="presetDraft.name" maxlength="100" /></el-form-item>
          <el-form-item label="分类" required><el-select v-model="presetDraft.category"><el-option label="男孩" value="BOY" /><el-option label="女孩" value="GIRL" /><el-option label="动物" value="ANIMAL" /><el-option label="幻想" value="FANTASY" /><el-option label="其他" value="OTHER" /></el-select></el-form-item>
        </div>
        <el-form-item label="头像地址"><el-input v-model="presetDraft.avatarUrl" maxlength="500" placeholder="/files/presets/example.png" /></el-form-item>
        <el-form-item label="上传形象图"><input class="preset-file-input" type="file" accept="image/png,image/jpeg,image/webp" @change="selectPresetImage" /><small class="field-help">PNG、JPG 或 WebP，最大 2 MB{{ presetImage ? `：${presetImage.name}` : '' }}</small></el-form-item>
        <el-form-item label="外观提示词" required><el-input v-model="presetDraft.appearancePrompt" type="textarea" :rows="4" maxlength="20000" show-word-limit /></el-form-item>
        <el-form-item label="角色描述"><el-input v-model="presetDraft.description" maxlength="500" /></el-form-item>
        <div class="form-grid"><el-form-item label="排序"><el-input v-model="presetDraft.sort" inputmode="numeric" /></el-form-item><el-form-item label="状态"><el-select v-model="presetDraft.status"><el-option label="启用" value="0" /><el-option label="停用" value="1" /></el-select></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="presetDialog = false">取消</el-button><el-button type="primary" :loading="presetBusy" @click="savePreset">保存角色</el-button></template>
    </el-dialog>

    <el-dialog v-model="modelDialog" title="编辑模型配置" width="min(560px, calc(100vw - 28px))">
      <el-form label-position="top" @submit.prevent="saveModel">
        <el-form-item label="配置类型"><el-input v-model="modelDraft.configKey" disabled /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="modelDraft.baseUrl" placeholder="由后端配置的服务地址" /></el-form-item>
        <el-form-item label="模型名称"><el-input v-model="modelDraft.modelName" placeholder="模型标识" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="Temperature"><el-input v-model="modelDraft.temperature" inputmode="decimal" /></el-form-item>
          <el-form-item label="Max tokens"><el-input v-model="modelDraft.maxTokens" inputmode="numeric" /></el-form-item>
        </div>
        <el-form-item label="图像尺寸"><el-input v-model="modelDraft.imageSize" placeholder="例如 1024x1024" /></el-form-item>
        <el-form-item><el-checkbox v-model="modelDraft.supportsImageReference">支持参考图输入</el-checkbox></el-form-item>
        <el-alert type="warning" :closable="false" show-icon title="当前服务端 PUT 接口会回显配置，但尚未持久化；密钥由服务端环境变量管理。" />
      </el-form>
      <template #footer><el-button :icon="Connection" :loading="modelTestBusy" @click="testModelConnection">测试连接</el-button><el-button @click="modelDialog = false">取消</el-button><el-button type="primary" :loading="modelBusy" @click="saveModel">提交更新</el-button></template>
    </el-dialog>

    <el-dialog v-model="promptDialog" :title="promptTarget ? '编辑提示词模板' : '新建提示词模板'" width="min(760px, calc(100vw - 28px))" destroy-on-close>
      <el-form label-position="top" @submit.prevent="savePrompt">
        <el-form-item label="模板键" required><el-input v-model="promptDraft.templateKey" :disabled="Boolean(promptTarget?.id)" maxlength="100" placeholder="例如 story_gen" /><div class="field-hint">仅允许字母、数字、点、下划线、冒号和连字符；创建后作为稳定标识。</div></el-form-item>
        <el-form-item label="模板名称" required><el-input v-model="promptDraft.name" maxlength="120" show-word-limit placeholder="例如 故事生成" /></el-form-item>
        <el-form-item label="提示词内容" required><el-input v-model="promptDraft.content" type="textarea" :rows="12" maxlength="12000" show-word-limit resize="vertical" placeholder="填写完整的模型提示词，可保留 {{变量}} 占位符。" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="promptDialog = false">取消</el-button><el-button type="primary" :loading="promptBusy" @click="savePrompt">保存模板</el-button></template>
    </el-dialog>

    <el-dialog v-model="wordDialog" :title="wordTarget ? '编辑敏感词' : '添加敏感词'" width="min(540px, calc(100vw - 28px))" destroy-on-close>
      <el-form label-position="top" @submit.prevent="saveWord">
        <el-form-item label="敏感词" required><el-input v-model="wordDraft.word" maxlength="100" show-word-limit placeholder="输入完整词条" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="wordDraft.category" maxlength="80" placeholder="例如 暴力、隐私" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="处理级别"><el-select v-model="wordDraft.level" clearable filterable allow-create default-first-option placeholder="选择或输入级别"><el-option label="拦截" value="BLOCK" /><el-option label="警告" value="WARN" /><el-option label="审核" value="REVIEW" /></el-select></el-form-item>
          <el-form-item label="状态"><el-select v-model="wordDraft.status" placeholder="选择状态"><el-option label="启用" value="0" /><el-option label="停用" value="1" /></el-select></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="wordDialog = false">取消</el-button><el-button type="primary" :loading="wordBusy" @click="saveWord">保存词条</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-console{--admin-ink:#24313d;--admin-muted:#748291;--admin-line:#e5eaf0;--admin-bg:#f5f7fa;display:flex;min-height:100vh;background:var(--admin-bg);color:var(--admin-ink)}
.admin-sidebar{width:236px;flex:none;display:flex;flex-direction:column;background:#202c39;color:#d9e1e9;padding:22px 12px 16px}
@media(min-width:681px){.admin-sidebar{position:sticky;top:0;align-self:flex-start;height:100vh;height:100dvh;overflow-y:auto}.admin-sidebar>*{flex-shrink:0}}
.admin-brand{display:flex;align-items:center;gap:11px;padding:2px 12px 28px;color:#fff;font-size:16px;font-weight:700}
.admin-brand small{display:block;margin-top:3px;color:#94a2b1;font-size:9px;font-weight:600;letter-spacing:1px}
.brand-mark{display:grid;place-items:center;width:34px;height:34px;border-radius:8px;background:#4d82b7;color:#fff;font-size:17px;font-weight:800}
.sidebar-caption{padding:0 13px 8px;color:#8694a2;font-size:11px}
.admin-menu{border-right:0;background:transparent}
.admin-menu :deep(.el-menu-item){height:43px;margin:3px 0;border-radius:6px;color:#b8c3ce;font-size:13px}
.admin-menu :deep(.el-menu-item .el-icon){font-size:16px}
.admin-menu :deep(.el-menu-item:hover){background:#2c3a48;color:#fff}
.admin-menu :deep(.el-menu-item.is-active){background:#35506a;color:#fff}
.sidebar-foot{margin-top:auto;padding:16px 12px 0;border-top:1px solid #34424f;color:#8794a1;font-size:10px}
.admin-main{min-width:0;flex:1;display:flex;flex-direction:column}
.admin-header{height:64px;flex:none;display:flex;align-items:center;justify-content:space-between;gap:18px;padding:0 28px;background:#fff;border-bottom:1px solid var(--admin-line)}
.header-title{display:flex;align-items:center;gap:10px;min-width:0}.header-title>span{color:#8a97a4;font-size:12px}.header-title .el-icon{color:#a9b3bd;font-size:14px}.header-title h1{margin:0;font-size:16px;font-weight:650;white-space:nowrap}
.header-actions{display:flex;align-items:center;gap:12px}.admin-user{display:flex;align-items:center;gap:8px;color:#536170;font-size:12px;white-space:nowrap}
.admin-content{width:100%;max-width:1440px;margin:0 auto;padding:27px 28px 44px}.section-body{min-height:240px}.section-intro{display:flex;align-items:center;justify-content:space-between;gap:18px;margin:0 0 18px}.section-intro h2{margin:0 0 5px;font-size:19px;font-weight:650}.section-intro p{margin:0;color:var(--admin-muted);font-size:12px}.search-input{max-width:300px}.section-tools{display:flex;align-items:center;gap:9px}.field-hint{margin-top:5px;color:var(--admin-muted);font-size:11px;line-height:1.5}
.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.metric-card{border-color:var(--admin-line);border-radius:7px}.metric-card :deep(.el-card__body){padding:17px 19px}.metric-head{display:flex;align-items:center;justify-content:space-between;color:#647282;font-size:12px}.metric-card strong{display:block;margin-top:13px;color:#263442;font-size:27px;font-weight:650;line-height:1.1}.metric-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:7px;font-size:16px}.metric-icon.blue{background:#e8f1fb;color:#4d83b8}.metric-icon.violet{background:#f0edfa;color:#7865ad}.metric-icon.amber{background:#fff4df;color:#bd8831}.metric-icon.green{background:#e8f6ef;color:#4e9c72}.metric-icon.rose{background:#fdecef;color:#c96878}.metric-icon.cyan{background:#e4f4f7;color:#4597a3}
.word-toolbar{align-items:center;margin-top:12px}.word-toolbar .search-input{max-width:320px}.context-filter{width:190px}.word-hit-pagination{justify-content:flex-end;margin-top:16px}
.data-note,.table-card{margin-top:17px;border-color:var(--admin-line);border-radius:7px}.data-note :deep(.el-card__header){padding:14px 18px}.panel-heading{display:flex;align-items:center;justify-content:space-between}.panel-heading h3{margin:0 0 3px;font-size:13px}.panel-heading span{color:var(--admin-muted);font-size:11px}.api-note{margin:0 0 14px}.error-panel{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0 0 16px;padding:13px 15px;border:1px solid #f1c5c5;border-radius:6px;background:#fff7f7;color:#9a4343;font-size:13px}.error-panel>div{display:flex;align-items:center;gap:8px}.content-preview{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#5c6875}.user-state-control{display:flex;align-items:center;gap:10px}.review-detail h3{margin:20px 0 10px;font-size:14px}.review-page{display:grid;grid-template-columns:90px minmax(0,1fr);gap:5px 12px;padding:12px 10px;border-bottom:1px solid var(--admin-line);font-size:12px}.review-page b{grid-row:span 3;color:#5b6d7e}.review-page p{margin:0;color:#263441;line-height:1.6;white-space:pre-wrap}.review-page small{color:#8793a0;line-height:1.5}.review-page a{color:#3477b5;text-decoration:none}.reject-field{margin:18px 0 0}.prompt-detail pre{max-height:50vh;overflow:auto;margin:14px 0 0;padding:16px;border:1px solid var(--admin-line);border-radius:6px;background:#f7f9fb;color:#3c4956;font:12px/1.7 ui-monospace,SFMono-Regular,Consolas,monospace;white-space:pre-wrap;word-break:break-word}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
@media(max-width:900px){.admin-sidebar{width:208px}.admin-content{padding:22px 18px}.admin-header{padding:0 18px}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:680px){.admin-console{display:block}.admin-sidebar{width:auto;padding:12px 10px 8px}.admin-brand{padding:2px 8px 12px}.sidebar-caption,.sidebar-foot{display:none}.admin-menu{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));overflow-x:auto}.admin-menu :deep(.el-menu-item){height:53px;display:flex;flex-direction:column;justify-content:center;gap:3px;margin:0;padding:4px 3px;font-size:10px;line-height:1.2;white-space:normal;text-align:center}.admin-menu :deep(.el-menu-item .el-icon){margin:0;font-size:16px}.admin-menu :deep(.el-menu-item span){margin:0}.admin-header{height:auto;min-height:58px;padding:10px 13px}.header-title>span,.header-title>.el-icon,.header-actions :deep(.el-divider),.admin-user b{display:none}.header-title h1{font-size:15px}.header-actions{gap:7px}.admin-content{padding:17px 12px 30px}.section-intro{align-items:flex-start;flex-direction:column}.section-intro h2{font-size:17px}.search-input{max-width:none;width:100%}.section-tools{width:100%;align-items:stretch;flex-direction:column}.section-tools>.el-button{align-self:flex-start}.word-toolbar .section-tools{flex-direction:row;flex-wrap:wrap}.word-toolbar .section-tools>.el-button{align-self:auto}.context-filter{width:100%}.metric-grid{gap:9px}.metric-card :deep(.el-card__body){padding:13px}.metric-head{font-size:11px}.metric-card strong{font-size:23px}.metric-icon{width:29px;height:29px}.form-grid{grid-template-columns:1fr}.review-page{grid-template-columns:70px minmax(0,1fr)}.table-card :deep(.el-card__body){padding:8px}}
</style>
