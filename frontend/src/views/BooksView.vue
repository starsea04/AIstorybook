<script setup lang="ts">
import BookCover from '../components/BookCover.vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { bookApi } from '../api'
import type { StoryPage } from '../types'
import BookFlipReader from '../components/BookFlipReader.vue'

type Book = {
  id: number
  title?: string
  description?: string
  status?: string
  coverUrl?: string
  updatedAt?: string
  pages?: StoryPage[]
  [key: string]: any
}

const books = ref<Book[]>([])
const loading = ref(true)
const errorMessage = ref('')
const search = ref('')
const statusFilter = ref('ALL')
const sortOrder = ref<'newest' | 'oldest'>('newest')
const selectedBook = ref<Book | null>(null)
const detailVisible = ref(false)
const router = useRouter()
const actionLoading = ref('')

function normalizeStatus(value?: string) {
  return ({ DRAFT: '草稿', PENDING: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下架' } as Record<string, string>)[value || ''] || value || '草稿'
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response: any = await bookApi.list()
    if (response?.code !== 0) throw new Error(response?.message || '作品列表加载失败')
    books.value = Array.isArray(response.data) ? response.data : []
  } catch (error: any) {
    errorMessage.value = error?.message || '作品列表加载失败'
  } finally {
    loading.value = false
  }
}

const statusOptions = computed(() => {
  const statuses = [...new Set(books.value.map(book => normalizeStatus(book.status)))].filter(Boolean)
  return statuses
})

const visibleBooks = computed(() => {
  const term = search.value.trim().toLocaleLowerCase()
  return books.value
    .filter(book => statusFilter.value === 'ALL' || normalizeStatus(book.status) === statusFilter.value)
    .filter(book => !term || [book.title, book.description].some(value => String(value || '').toLocaleLowerCase().includes(term)))
    .sort((a, b) => {
      const dateA = new Date(a.updatedAt || 0).getTime() || 0
      const dateB = new Date(b.updatedAt || 0).getTime() || 0
      return sortOrder.value === 'newest' ? dateB - dateA : dateA - dateB
    })
})

function displayDate(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
}

function openBook(book: Book) {
  selectedBook.value = book
  detailVisible.value = true
}

function continueEdit(book: Book) {
  detailVisible.value = false
  void router.push({ path: '/create', query: { id: String(book.id) } })
}

function replaceBook(updated: any) {
  if (!updated?.id) return
  const index = books.value.findIndex(book => Number(book.id) === Number(updated.id))
  if (index >= 0) books.value[index] = updated
  if (selectedBook.value && Number(selectedBook.value.id) === Number(updated.id)) selectedBook.value = updated
}

async function submitBook(book: Book) {
  const key = `submit-${book.id}`
  actionLoading.value = key
  try {
    const response: any = await bookApi.submit(book.id)
    if (response?.code !== 0) throw new Error(response?.message || '提交审核失败')
    replaceBook(response.data)
    ElMessage.success('绘本已提交审核')
  } catch (error: any) {
    ElMessage.error(error?.message || '提交审核失败')
  } finally {
    actionLoading.value = ''
  }
}

async function deleteBook(book: Book) {
  try {
    await ElMessageBox.confirm('删除后将无法恢复，确定要删除这本绘本吗？', '删除绘本', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  const key = `delete-${book.id}`
  actionLoading.value = key
  try {
    const response: any = await bookApi.remove(book.id)
    if (response?.code !== 0) throw new Error(response?.message || '删除绘本失败')
    books.value = books.value.filter(item => Number(item.id) !== Number(book.id))
    if (selectedBook.value?.id === book.id) {
      selectedBook.value = null
      detailVisible.value = false
    }
    ElMessage.success('绘本已删除')
  } catch (error: any) {
    ElMessage.error(error?.message || '删除绘本失败')
  } finally {
    actionLoading.value = ''
  }
}

async function exportBook(book: Book, format: 'pdf' | 'zip') {
  const key = `export-${format}-${book.id}`
  actionLoading.value = key
  try {
    const response: any = await bookApi.export(book.id, format)
    const blob = response instanceof Blob ? response : new Blob([response])
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    const safeTitle = String(book.title || '未命名绘本')
      .replace(/[\\/:*?"<>|\u0000-\u001F\u007F]/g, '_')
      .replace(/\s+/g, ' ').trim().replace(/^[. ]+|[. ]+$/g, '') || '未命名绘本'
    link.download = `《${safeTitle.replace(/^《|》$/g, '')}》.${format}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    ElMessage.success(`${format.toUpperCase()} 导出已开始`)
  } catch (error: any) {
    ElMessage.error(error?.message || `${format.toUpperCase()} 导出失败`)
  } finally {
    actionLoading.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page books-page">
    <div class="books-heading"><div><h1 class="page-title">我的绘本 <span>📚</span></h1><p class="page-subtitle">你的故事和创作进度都在这里。</p></div><router-link to="/create" class="btn btn-primary">＋ 新建绘本</router-link></div>
    <div class="books-toolbar">
      <div class="books-count">全部作品 <small>{{ visibleBooks.length }} / {{ books.length }} 本</small></div>
      <el-input v-model="search" clearable placeholder="搜索标题或简介" class="books-search" />
      <el-select v-model="statusFilter" aria-label="按状态筛选" class="books-select">
        <el-option label="全部状态" value="ALL" />
        <el-option v-for="status in statusOptions" :key="status" :label="status" :value="status" />
      </el-select>
      <el-select v-model="sortOrder" aria-label="排序方式" class="books-select">
        <el-option label="最近更新" value="newest" />
        <el-option label="最早更新" value="oldest" />
      </el-select>
    </div>
    <div v-if="loading" class="empty"><div class="loader"></div><p>正在打开你的故事书架…</p></div>
    <div v-else-if="errorMessage" class="empty"><div class="empty-icon">🛠️</div><p>{{ errorMessage }}</p><button class="btn btn-secondary" @click="load">重试</button></div>
    <div v-else-if="!books.length" class="empty"><div class="empty-icon">📖</div><p>你的书架还是空的，写下第一个故事吧！</p><router-link to="/create" class="btn btn-primary">开始创作</router-link></div>
    <div v-else-if="!visibleBooks.length" class="empty"><div class="empty-icon">🔎</div><p>没有符合条件的绘本。</p><el-button @click="search = ''; statusFilter = 'ALL'">清除筛选</el-button></div>
    <div v-else class="book-grid">
      <article v-for="book in visibleBooks" :key="book.id" class="book-card">
        <BookCover class="cover book-cover" :book="book" />
        <div class="book-info">
          <h3>{{ book.title || '未命名绘本' }}</h3><p>{{ book.description || '还没有添加简介。' }}</p>
          <div class="card-foot"><span class="tag" :class="normalizeStatus(book.status) === '已发布' ? 'published' : 'draft'">{{ normalizeStatus(book.status) }}</span><small>{{ displayDate(book.updatedAt) }}</small></div>
          <div class="book-actions"><div><el-button size="small" type="primary" plain @click="openBook(book)">查看绘本</el-button><el-button size="small" @click="continueEdit(book)">继续编辑</el-button></div><span>{{ book.pages?.length || 0 }} 页</span></div>
        </div>
      </article>
    </div>

    <el-dialog v-model="detailVisible" :title="selectedBook?.title || '绘本详情'" width="min(820px, calc(100vw - 28px))" destroy-on-close>
      <div v-if="selectedBook" class="book-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="状态">{{ normalizeStatus(selectedBook.status) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ displayDate(selectedBook.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="简介" :span="2">{{ selectedBook.description || '暂无简介' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-actions">
          <el-button type="primary" @click="continueEdit(selectedBook)">继续编辑</el-button>
          <el-button v-if="['DRAFT', 'REJECTED'].includes(selectedBook.status || 'DRAFT')" :loading="actionLoading === `submit-${selectedBook.id}`" @click="submitBook(selectedBook)">提交审核</el-button>
          <el-button :loading="actionLoading === `export-pdf-${selectedBook.id}`" @click="exportBook(selectedBook, 'pdf')">导出 PDF</el-button>
          <el-button :loading="actionLoading === `export-zip-${selectedBook.id}`" @click="exportBook(selectedBook, 'zip')">导出 ZIP</el-button>
          <el-button type="danger" plain :loading="actionLoading === `delete-${selectedBook.id}`" @click="deleteBook(selectedBook)">删除绘本</el-button>
        </div>
        <h3>阅读绘本</h3>
        <BookFlipReader v-if="selectedBook.pages?.length" :book-id="selectedBook.id" :pages="selectedBook.pages" :title="selectedBook.title || '我的绘本'" />
        <el-empty v-else description="这本绘本还没有页面内容" :image-size="64" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.books-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.books-heading .page-title span{font-size:20px}.books-toolbar{display:flex;align-items:center;gap:10px;margin:27px 0 15px}.books-count{margin-right:auto;font-size:16px;font-weight:700;white-space:nowrap}.books-count small{margin-left:7px;color:#938983;font-size:11px;font-weight:400}.books-search{max-width:260px}.books-select{width:145px}.book-cover{overflow:hidden}.book-cover.has-cover{padding:0}.book-cover img{width:100%;height:100%;object-fit:cover}.book-actions{display:flex;align-items:center;justify-content:space-between;margin-top:12px;color:#9a9089;font-size:11px}.book-actions>div{display:flex;gap:5px}.book-detail h3{margin:20px 0 8px;font-size:15px}.detail-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}.detail-page{display:grid;grid-template-columns:minmax(180px,34%) minmax(0,1fr);gap:14px;padding:13px 0;border-top:1px solid #eee4dc}.detail-page img{width:100%;max-height:220px;aspect-ratio:4/3;object-fit:cover;border-radius:6px;background:#faf3ed}.detail-page-copy b{font-size:12px;color:#a66e51}.detail-page-copy p{margin:7px 0;color:#4f4742;font-size:13px;line-height:1.7;white-space:pre-wrap;overflow-wrap:anywhere}.detail-page-copy small{color:#95877d;font-size:11px;line-height:1.5}
@media(max-width:700px){.books-toolbar{align-items:stretch;flex-wrap:wrap}.books-count{width:100%}.books-search{max-width:none;flex:1 1 100%}.books-select{flex:1;width:auto}.detail-page{grid-template-columns:1fr}.detail-page img{max-height:230px}}
</style>
