<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi, bookApi } from '../api'
import { useSessionStore } from '../stores/session'

type Profile = {
  id?: number
  username?: string
  email?: string
  nickname?: string
  role?: string
  avatar?: string | null
  bookCount?: number
  likeCount?: number
}

type Book = {
  id: number
  title?: string
  status?: string
  updatedAt?: string
  pages?: unknown[]
}

const session = useSessionStore()
const profile = ref<Profile>({ nickname: session.nickname, role: session.role })
const books = ref<Book[]>([])
const loading = ref(true)
const profileError = ref('')
const booksError = ref('')
const booksLoaded = ref(false)
const editingNickname = ref(false)
const editingPassword = ref(false)
const savingProfile = ref(false)
const savingPassword = ref(false)
const uploadingAvatar = ref(false)
const nicknameDraft = ref('')
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const avatarInput = ref<HTMLInputElement | null>(null)

const nickname = computed(() => profile.value.nickname || profile.value.username || session.nickname)
const publishedCount = computed(() => books.value.filter(book => book.status === 'PUBLISHED').length)
const pageCount = computed(() => books.value.reduce((sum, book) => sum + (Array.isArray(book.pages) ? book.pages.length : 0), 0))
const bookCount = computed(() => profile.value.bookCount ?? (booksLoaded.value ? books.value.length : 0))
const likeCount = computed(() => profile.value.likeCount ?? 0)
const recentBooks = computed(() => [...books.value]
  .sort((a, b) => (new Date(b.updatedAt || 0).getTime() || 0) - (new Date(a.updatedAt || 0).getTime() || 0))
  .slice(0, 5))

function statusLabel(status?: string) {
  return ({ DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下架' } as Record<string, string>)[status || ''] || status || '草稿'
}

function roleLabel(role?: string) {
  return role === 'ROLE_ADMIN' ? '管理员' : role === 'ROLE_USER' || role === 'USER' ? '绘本小作者' : role || '—'
}

function displayDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
}

async function load() {
  loading.value = true
  profileError.value = ''
  booksError.value = ''
  const [profileResult, booksResult] = await Promise.allSettled([authApi.profile(), bookApi.list()])

  if (profileResult.status === 'fulfilled') {
    const response: any = profileResult.value
    if (response?.code === 0 && response.data) {
      profile.value = response.data
      session.setProfile(profile.value.nickname || profile.value.username || session.nickname, profile.value.avatar || '')
    } else {
      profileError.value = response?.message || '个人资料加载失败'
    }
  } else {
    profileError.value = profileResult.reason?.message || '个人资料加载失败'
  }

  if (booksResult.status === 'fulfilled') {
    const response: any = booksResult.value
    if (response?.code === 0 && Array.isArray(response.data)) {
      books.value = response.data
      booksLoaded.value = true
    } else {
      booksError.value = response?.message || '作品统计加载失败'
      booksLoaded.value = false
    }
  } else {
    booksError.value = booksResult.reason?.message || '作品统计加载失败'
    booksLoaded.value = false
  }

  loading.value = false
}

onMounted(load)

function toggleNicknameEditor() {
  editingNickname.value = !editingNickname.value
  editingPassword.value = false
  nicknameDraft.value = nickname.value
}

function togglePasswordEditor() {
  editingPassword.value = !editingPassword.value
  editingNickname.value = false
  passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
}

async function saveNickname() {
  const next = nicknameDraft.value.trim()
  if (!next || next.length > 80) return ElMessage.warning('昵称不能为空且不能超过 80 个字符')
  savingProfile.value = true
  try {
    const response: any = await authApi.updateNickname(next)
    if (response?.code !== 0) throw new Error(response?.message || '昵称更新失败')
    profile.value = { ...profile.value, ...response.data, nickname: response.data?.nickname || next }
    session.setProfile(profile.value.nickname || next, profile.value.avatar || '')
    editingNickname.value = false
    ElMessage.success('昵称已更新')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '昵称更新失败')
  } finally { savingProfile.value = false }
}

async function savePassword() {
  const form = passwordForm.value
  if (!form.currentPassword || form.newPassword.length < 8 || form.newPassword.length > 72) return ElMessage.warning('请输入当前密码；新密码长度须为 8 到 72 个字符')
  if (form.newPassword !== form.confirmPassword) return ElMessage.warning('两次输入的新密码不一致')
  savingPassword.value = true
  try {
    const response: any = await authApi.updatePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword })
    if (response?.code !== 0) throw new Error(response?.message || '密码修改失败')
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    editingPassword.value = false
    ElMessage.success('密码已修改')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '密码修改失败')
  } finally { savingPassword.value = false }
}

function selectAvatar() { avatarInput.value?.click() }

async function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) return ElMessage.warning('请选择 PNG、JPG 或 WebP 图片')
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('头像图片不能超过 5 MB')
  uploadingAvatar.value = true
  try {
    const response: any = await authApi.uploadAvatar(file)
    if (response?.code !== 0 || !response.data?.avatar) throw new Error(response?.message || '头像上传失败')
    profile.value = { ...profile.value, avatar: response.data.avatar }
    session.setProfile(nickname.value, response.data.avatar)
    ElMessage.success('头像已更新')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '头像上传失败')
  } finally { uploadingAvatar.value = false }
}
</script>

<template>
  <div class="page profile-page">
    <div class="profile-heading">
      <div>
        <h1 class="page-title">我的创作档案</h1>
        <p class="page-subtitle">查看你的账号信息与绘本创作记录。</p>
      </div>
      <router-link to="/create" class="btn btn-primary">＋ 新建绘本</router-link>
    </div>

    <div v-if="profileError || booksError" class="profile-error" role="alert">
      <span>{{ [profileError, booksError].filter(Boolean).join('；') }}</span>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <section class="profile-identity" aria-label="账号信息">
      <button class="profile-avatar" type="button" :disabled="uploadingAvatar" :aria-label="uploadingAvatar ? '正在上传头像' : '更换头像'" @click="selectAvatar">
        <img v-if="profile.avatar" :src="profile.avatar" alt="账号头像" />
        <span v-else aria-hidden="true">{{ nickname.slice(0, 1) }}</span>
      </button>
      <input ref="avatarInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/webp" @change="uploadAvatar" />
      <div class="identity-copy">
        <h2>{{ nickname }}</h2>
        <p>{{ profile.username ? `@${profile.username}` : '绘本创作者' }}</p>
      </div>
      <el-tag type="success" effect="light">{{ roleLabel(profile.role) }}</el-tag>
      <div class="identity-details">
        <div><small>用户编号</small><strong>{{ profile.id ?? '—' }}</strong></div>
        <div><small>登录账号</small><strong>{{ profile.username || '—' }}</strong></div>
        <div><small>邮箱</small><strong>{{ profile.email || '—' }}</strong></div>
      </div>
      <div class="profile-controls">
        <button class="btn btn-ghost" type="button" @click="toggleNicknameEditor">编辑昵称</button>
        <button class="btn btn-ghost" type="button" @click="togglePasswordEditor">修改密码</button>
      </div>
    </section>

    <form v-if="editingNickname" class="profile-editor" @submit.prevent="saveNickname">
      <label>公开昵称<input v-model="nicknameDraft" maxlength="80" autocomplete="nickname" /></label>
      <div class="editor-actions"><button class="btn btn-primary" :disabled="savingProfile">{{ savingProfile ? '正在保存…' : '保存昵称' }}</button><button class="btn btn-ghost" type="button" @click="editingNickname = false">取消</button></div>
    </form>

    <form v-if="editingPassword" class="profile-editor password-editor" @submit.prevent="savePassword">
      <label>当前密码<input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" /></label>
      <label>新密码<input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" /></label>
      <label>确认新密码<input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" /></label>
      <div class="editor-actions"><button class="btn btn-primary" :disabled="savingPassword">{{ savingPassword ? '正在保存…' : '更新密码' }}</button><button class="btn btn-ghost" type="button" @click="editingPassword = false">取消</button></div>
    </form>

    <div class="section-heading"><h2>创作数据</h2></div>
    <div class="stat-row">
      <div class="stat"><div class="stat-icon">📚</div><div><strong>{{ loading ? '—' : bookCount }}</strong><small>累计绘本</small></div></div>
      <div class="stat"><div class="stat-icon">🌟</div><div><strong>{{ loading || !booksLoaded ? '—' : publishedCount }}</strong><small>已发布绘本</small></div></div>
      <div class="stat"><div class="stat-icon">✦</div><div><strong>{{ loading || !booksLoaded ? '—' : pageCount }}</strong><small>故事页面</small></div></div>
      <div class="stat"><div class="stat-icon">♥</div><div><strong>{{ loading ? '—' : likeCount }}</strong><small>累计获赞</small></div></div>
    </div>

    <div class="section-heading recent-heading"><h2>最近更新</h2><router-link to="/books" class="btn btn-ghost">全部绘本 →</router-link></div>
    <div v-if="loading" class="profile-empty"><div class="loader"></div><p>正在加载创作记录…</p></div>
    <div v-else-if="booksLoaded && !recentBooks.length" class="profile-empty"><div class="empty-icon">📖</div><p>还没有创作绘本，写下第一个故事吧。</p><router-link to="/create" class="btn btn-primary">开始创作</router-link></div>
    <div v-else-if="booksLoaded" class="recent-list">
      <router-link v-for="book in recentBooks" :key="book.id" to="/books" class="recent-book">
        <span class="recent-book-icon">📖</span>
        <span class="recent-book-name">{{ book.title || '未命名绘本' }}</span>
        <span class="tag" :class="book.status === 'PUBLISHED' ? 'published' : 'draft'">{{ statusLabel(book.status) }}</span>
        <small>{{ displayDate(book.updatedAt) }}</small>
      </router-link>
    </div>
  </div>
</template>

<style scoped>
.profile-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.profile-identity{display:grid;grid-template-columns:58px minmax(120px,1fr) auto;align-items:center;gap:13px;margin-top:26px;padding:20px;background:#fff;border:1px solid #f0e4dc;border-radius:14px}.profile-avatar{width:54px;height:54px;display:grid;place-items:center;overflow:hidden;border-radius:50%;background:#f8b6a8;color:#fff;font-size:21px;font-weight:800}.profile-avatar img{width:100%;height:100%;object-fit:cover}.identity-copy h2{margin:0;font-size:18px;overflow-wrap:anywhere}.identity-copy p{margin:4px 0 0;color:#a69ba1;font-size:12px}.identity-details{grid-column:1/-1;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding-top:16px;border-top:1px solid #f1e8e0}.identity-details div{display:grid;gap:5px;min-width:0}.identity-details small{color:#a69ba1;font-size:10px}.identity-details strong{font-size:12px;font-weight:600;overflow-wrap:anywhere}.profile-controls{grid-column:1/-1;display:flex;gap:8px}.profile-editor{display:flex;align-items:flex-end;gap:12px;margin-top:10px;padding:14px 16px;background:#fff;border:1px solid #f0e4dc;border-radius:10px}.profile-editor label{display:grid;flex:1;gap:5px;min-width:0;color:#756773;font-size:11px}.profile-editor input{width:100%;min-height:36px;padding:7px 10px;border:1px solid #e6d9d3;border-radius:6px;color:#392d3a}.editor-actions{display:flex;gap:7px;flex:none}.visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.profile-error{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:16px 0 0;padding:10px 12px;border:1px solid #efd2c6;border-radius:8px;background:#fff8f4;color:#a15f49;font-size:12px}.profile-empty{padding:30px;text-align:center;color:#998b84}.profile-empty .loader{margin:0 auto}.profile-empty p{font-size:12px}.recent-heading{margin-top:31px}.recent-list{background:#fff;border:1px solid #f0e4dc;border-radius:10px;overflow:hidden}.recent-book{min-height:54px;display:grid;grid-template-columns:30px minmax(0,1fr) auto 100px;align-items:center;gap:11px;padding:9px 14px;color:inherit;text-decoration:none;border-bottom:1px solid #f4ede8}.recent-book:last-child{border-bottom:0}.recent-book:hover{background:#fffaf6}.recent-book-icon{width:28px;height:28px;display:grid;place-items:center;border-radius:7px;background:#fff1d1}.recent-book-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;font-weight:700}.recent-book small{text-align:right;color:#a69ba1;font-size:10px}
@media(max-width:640px){.profile-heading{align-items:flex-start}.profile-heading .btn{white-space:nowrap}.profile-identity{grid-template-columns:50px minmax(0,1fr) auto;padding:15px;gap:10px}.profile-avatar{width:46px;height:46px}.identity-details{grid-template-columns:1fr;gap:10px}.profile-editor{align-items:stretch;flex-direction:column}.password-editor{display:grid;grid-template-columns:1fr}.editor-actions{flex-wrap:wrap}.recent-book{grid-template-columns:28px minmax(0,1fr) auto;gap:8px}.recent-book small{grid-column:2/-1;text-align:left}.recent-book .tag{grid-column:3;grid-row:1}}
</style>
