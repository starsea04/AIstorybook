<script setup lang="ts">
import BookCover from '../components/BookCover.vue'
import { computed, onMounted, ref } from 'vue'
import { authApi, bookApi } from '../api'
import { useSessionStore } from '../stores/session'

type Book = { id: number; title?: string; description?: string; status?: string; coverUrl?: string; updatedAt?: string; pages?: import('../types').StoryPage[] }
const session = useSessionStore()
const books = ref<Book[]>([])
const loading = ref(true)
const errorMessage = ref('')
const nickname = ref(session.nickname)

async function loadHome() {
  loading.value = true
  errorMessage.value = ''
  const outcomes = await Promise.allSettled([authApi.profile(), bookApi.list()])
  try {
    if (outcomes[0].status === 'fulfilled') {
      const response: any = outcomes[0].value
      if (response?.code === 0 && response.data) nickname.value = response.data.nickname || response.data.username || session.nickname
    }
    if (outcomes[1].status === 'fulfilled') {
      const response: any = outcomes[1].value
      if (response?.code !== 0) throw new Error(response?.message || '作品列表加载失败')
      books.value = Array.isArray(response.data) ? response.data : []
    } else {
      throw outcomes[1].reason
    }
  } catch (error: any) {
    errorMessage.value = error?.message || '创作数据加载失败'
  } finally {
    loading.value = false
  }
}

const recent = computed(() => [...books.value].sort((a, b) => {
  const dateA = new Date(a.updatedAt || 0).getTime() || 0
  const dateB = new Date(b.updatedAt || 0).getTime() || 0
  return dateB - dateA
}).slice(0, 3))
const publishedCount = computed(() => books.value.filter(book => book.status === 'PUBLISHED').length)
const pageCount = computed(() => books.value.reduce((sum, book) => sum + (Array.isArray(book.pages) ? book.pages.length : 0), 0))

function normalizeStatus(status?: string) {
  return ({ DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下架' } as Record<string, string>)[status || ''] || status || '草稿'
}

function displayDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
}

onMounted(loadHome)
</script>

<template>
  <div class="page home-page">
    <section class="hero"><div class="hero-copy"><h1>{{ nickname }}，让想象力<em>长出翅膀</em> ✨</h1><p>每一个小小的灵感，都值得拥有一本属于自己的绘本。<br>和 AI 一起，把你的故事变成五彩斑斓的奇妙世界。</p><router-link to="/create" class="btn btn-primary">开始创作我的绘本 <span>→</span></router-link></div><div class="hero-art"><span class="spark s1">✦</span><span class="spark s2">✧</span><div class="book"></div></div></section>
    <div v-if="errorMessage" class="home-error" role="alert"><span>{{ errorMessage }}</span><el-button size="small" @click="loadHome">重试</el-button></div>
    <div class="stat-row">
      <div class="stat"><div class="stat-icon">📚</div><div><strong>{{ loading ? '—' : books.length }}</strong><small>我的绘本</small></div></div>
      <div class="stat"><div class="stat-icon">🌟</div><div><strong>{{ loading ? '—' : publishedCount }}</strong><small>已发布绘本</small></div></div>
      <div class="stat"><div class="stat-icon">✦</div><div><strong>{{ loading ? '—' : pageCount }}</strong><small>故事页面</small></div></div>
    </div>
    <div class="section-heading"><h2>最近的创作</h2><router-link to="/books" class="btn btn-ghost">查看全部 →</router-link></div>
    <div v-if="loading" class="home-loading"><div class="loader"></div><p>正在整理你的故事书架…</p></div>
    <div v-else-if="!recent.length" class="home-empty"><div class="empty-icon">📖</div><p>还没有创作绘本，写下第一个故事吧。</p><router-link to="/create" class="btn btn-primary">开始创作</router-link></div>
    <div v-else class="book-grid">
      <article v-for="book in recent" :key="book.id" class="book-card">
        <BookCover class="cover home-cover" :book="book" />
        <div class="book-info"><h3>{{ book.title || '未命名绘本' }}</h3><p>{{ book.description || '还没有添加简介。' }}</p><div class="card-foot"><span class="tag" :class="book.status === 'PUBLISHED' ? 'published' : 'draft'">{{ normalizeStatus(book.status) }}</span><small>{{ displayDate(book.updatedAt) }}</small></div><router-link :to="`/books`" class="recent-link">在我的绘本中查看 →</router-link></div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.hero h1{overflow-wrap:anywhere}.hero h1 em{margin-left:5px}.home-error{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:14px 0;padding:10px 12px;border:1px solid #efd2c6;border-radius:8px;background:#fff8f4;color:#a15f49;font-size:12px}.home-cover{overflow:hidden}.home-cover.has-cover{padding:0}.home-cover img{width:100%;height:100%;object-fit:cover}.home-loading,.home-empty{padding:35px;text-align:center;color:#998b84}.home-loading .loader{margin-left:auto;margin-right:auto}.recent-link{display:inline-block;margin-top:11px;color:#e8794f;font-size:11px;font-weight:700;text-decoration:none}
</style>
