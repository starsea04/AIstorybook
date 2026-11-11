<script setup lang="ts">
import BookCover from '../components/BookCover.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Refresh, ChatDotRound, Reading, Star, CollectionTag } from '@element-plus/icons-vue'
import { communityApi } from '../api'
import BookFlipReader from '../components/BookFlipReader.vue'
import type { StoryPage } from '../types'

type CommunityBook = {
  id: number
  title?: string
  description?: string
  coverUrl?: string
  ageGroup?: string
  imageStyle?: string
  textLang?: string
  pages?: StoryPage[]
  likeCount?: number
  viewCount?: number
  isFavorited?: boolean
  updatedAt?: string
  publishedAt?: string
  publishTime?: string
  [key: string]: any
}
type CommunityComment = { id: number; nickname?: string; content: string; createdAt?: string; parentId?: number | null }
type CommunityPage = { records: CommunityBook[]; total: number; page: number; size: number }

const books = ref<CommunityBook[]>([])
const loading = ref(false)
const errorMessage = ref('')
const query = ref('')
const committedQuery = ref('')
const sortMode = ref<'latest' | 'popular'>('latest')
const viewMode = ref<'community' | 'favorites'>('community')
const imageStyleFilter = ref('')
const ageGroupFilter = ref('')
const textLangFilter = ref('')
const imageStyles = ['水彩', '卡通', '油画', '剪纸', '3D 渲染', '像素']
const ageGroups = ['3-6岁', '7-10岁', '11-14岁']
const pageNo = ref(1)
const pageSize = 12
const total = ref(0)
const detailVisible = ref(false)
const detailLoading = ref(false)
const activeBook = ref<CommunityBook | null>(null)
const commentText = ref('')
const replyingToId = ref<number | null>(null)
const commentBusy = ref(false)
const commentsLoading = ref(false)
const comments = ref<CommunityComment[]>([])
const likedIds = ref(new Set<number>())
const topLevelComments = computed(() => {
  const ids = new Set(comments.value.map(comment => Number(comment.id)))
  return comments.value.filter(comment => !comment.parentId || !ids.has(Number(comment.parentId)))
})

function repliesFor(commentId: number) {
  return comments.value.filter(comment => Number(comment.parentId) === commentId)
}

const replyingTo = computed(() => comments.value.find(comment => comment.id === replyingToId.value))

function dataOf(response: any, fallback: string) {
  if (!response || response.code !== 0) throw new Error(response?.message || fallback)
  return response.data
}

async function loadBooks() {
  loading.value = true
  errorMessage.value = ''
  try {
    const params = {
      page: pageNo.value,
      size: pageSize,
      sort: sortMode.value,
      ...(committedQuery.value ? { keyword: committedQuery.value } : {}),
      ...(imageStyleFilter.value ? { imageStyle: imageStyleFilter.value } : {}),
      ...(ageGroupFilter.value ? { ageGroup: ageGroupFilter.value } : {}),
      ...(textLangFilter.value ? { textLang: textLangFilter.value } : {})
    }
    const request = viewMode.value === 'favorites' ? communityApi.favorites(params) : communityApi.list(params)
    const data = dataOf(await request, viewMode.value === 'favorites' ? '收藏列表加载失败' : '社区作品加载失败') as CommunityPage | CommunityBook[]
    if (Array.isArray(data)) {
      books.value = data
      total.value = data.length
    } else {
      books.value = Array.isArray(data?.records) ? data.records : []
      total.value = Number(data?.total || 0)
      pageNo.value = Number(data?.page || pageNo.value)
    }
  } catch (error: any) {
    errorMessage.value = error?.message || '社区作品加载失败'
  } finally {
    loading.value = false
  }
}

function searchBooks() {
  committedQuery.value = query.value.trim()
  pageNo.value = 1
  void loadBooks()
}

const visibleBooks = computed(() => books.value)

watch(sortMode, () => {
  pageNo.value = 1
  void loadBooks()
})

watch(viewMode, () => {
  pageNo.value = 1
  void loadBooks()
})

watch([imageStyleFilter, ageGroupFilter, textLangFilter], () => {
  pageNo.value = 1
  void loadBooks()
})

async function openBook(book: CommunityBook) {
  detailVisible.value = true
  detailLoading.value = true
  comments.value = []
  replyingToId.value = null
  commentsLoading.value = true
  commentText.value = ''
  try {
    const [detailResult, commentsResult] = await Promise.allSettled([communityApi.detail(book.id), communityApi.comments(book.id)])
    if (detailResult.status === 'rejected') throw detailResult.reason
    const data = dataOf(detailResult.value, '绘本详情加载失败')
    activeBook.value = data && typeof data === 'object' ? data : book
    if (commentsResult.status === 'fulfilled') {
      const list = dataOf(commentsResult.value, '评论列表加载失败')
      comments.value = Array.isArray(list) ? list : []
    } else {
      ElMessage.error((commentsResult.reason as any)?.message || '评论列表加载失败')
    }
  } catch (error: any) {
    activeBook.value = null
    ElMessage.error(error?.message || '绘本详情加载失败')
  } finally {
    detailLoading.value = false
    commentsLoading.value = false
  }
}

async function likeBook(book: CommunityBook) {
  try {
    const data = dataOf(await communityApi.like(book.id), '点赞失败')
    const nextLiked = typeof data?.liked === 'boolean' ? data.liked : !likedIds.value.has(book.id)
    const listedBook = books.value.find(item => item.id === book.id)
    const currentCount = Number(listedBook?.likeCount ?? book.likeCount ?? 0)
    const hasServerCount = data?.likeCount !== undefined && data.likeCount !== null && Number.isFinite(Number(data.likeCount))
    const nextCount = hasServerCount
      ? Number(data.likeCount)
      : Math.max(0, currentCount + (nextLiked ? 1 : -1))
    book.likeCount = nextCount
    if (listedBook) listedBook.likeCount = nextCount
    if (activeBook.value?.id === book.id) activeBook.value.likeCount = nextCount
    if (nextLiked) likedIds.value = new Set(likedIds.value).add(book.id)
    else {
      const nextLikedIds = new Set(likedIds.value)
      nextLikedIds.delete(book.id)
      likedIds.value = nextLikedIds
    }
    ElMessage.success(nextLiked ? '已为这本绘本点赞' : '已取消点赞')
  } catch (error: any) {
    ElMessage.error(error?.message || '点赞失败')
  }
}

async function favoriteBook(book: CommunityBook) {
  try {
    const data = dataOf(await communityApi.favorite(book.id), '收藏操作失败')
    const favorited = typeof data?.favorited === 'boolean' ? data.favorited : !book.isFavorited
    book.isFavorited = favorited
    const listedBook = books.value.find(item => item.id === book.id)
    if (listedBook) listedBook.isFavorited = favorited
    if (activeBook.value?.id === book.id) activeBook.value.isFavorited = favorited
    ElMessage.success(favorited ? '已收藏这本绘本' : '已取消收藏')
    if (viewMode.value === 'favorites' && !favorited) await loadBooks()
  } catch (error: any) {
    ElMessage.error(error?.message || '收藏操作失败')
  }
}

async function submitComment() {
  const book = activeBook.value
  const content = commentText.value.trim()
  if (!book) return
  if (!content) return ElMessage.warning('请先写下评论内容')
  if (content.length > 500) return ElMessage.warning('评论不能超过 500 个字符')
  commentBusy.value = true
  try {
    const parentId = replyingToId.value || undefined
    const data = dataOf(await communityApi.comment(book.id, content, parentId), '评论提交失败')
    comments.value.unshift({
      id: data?.id || Date.now(),
      nickname: data?.nickname,
      content: typeof data?.content === 'string' ? data.content : content,
      createdAt: data?.createdAt,
      parentId: data?.parentId ?? parentId
    })
    commentText.value = ''
    replyingToId.value = null
    ElMessage.success('评论已提交')
  } catch (error: any) {
    ElMessage.error(error?.message || '评论提交失败')
  } finally {
    commentBusy.value = false
  }
}

function startReply(comment: CommunityComment) {
  replyingToId.value = comment.id
  commentText.value = ''
}

function displayDate(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN')
}

function languageLabel(value?: string) {
  return String(value || '').toUpperCase() === 'EN' ? 'English' : '中文'
}

onMounted(loadBooks)
</script>

<template>
  <div class="page community-page">
    <div class="community-heading">
      <div><h1 class="page-title">故事星球 <span>🌍</span></h1><p class="page-subtitle">来读读创作者们分享的绘本</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="loadBooks">刷新</el-button>
    </div>
    <div class="community-toolbar">
      <el-input v-model="query" clearable :prefix-icon="Search" placeholder="搜索标题或正文" @keyup.enter="searchBooks" @clear="searchBooks" />
      <el-button type="primary" :icon="Search" @click="searchBooks">搜索</el-button>
      <el-select v-model="imageStyleFilter" clearable placeholder="插画风格" aria-label="按插画风格筛选" class="community-filter">
        <el-option v-for="style in imageStyles" :key="style" :label="style" :value="style" />
      </el-select>
      <el-select v-model="ageGroupFilter" clearable placeholder="适龄范围" aria-label="按适龄范围筛选" class="community-filter">
        <el-option v-for="age in ageGroups" :key="age" :label="age" :value="age" />
      </el-select>
      <el-select v-model="textLangFilter" clearable placeholder="文本语言" aria-label="按文本语言筛选" class="community-filter">
        <el-option label="中文" value="ZH" />
        <el-option label="English" value="EN" />
      </el-select>
      <el-radio-group v-model="sortMode" aria-label="排序方式">
        <el-radio-button value="latest">最新发布</el-radio-button>
        <el-radio-button value="popular">最受欢迎</el-radio-button>
      </el-radio-group>
      <el-radio-group v-model="viewMode" aria-label="作品范围">
        <el-radio-button value="community">社区</el-radio-button>
        <el-radio-button value="favorites">我的收藏</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="errorMessage" class="community-state" role="alert"><el-empty description="社区内容暂时无法加载"><template #extra><p>{{ errorMessage }}</p><el-button type="primary" @click="loadBooks">重试</el-button></template></el-empty></div>
    <div v-else-if="loading && !books.length" class="community-state"><el-skeleton :rows="4" animated /></div>
      <div v-else-if="!visibleBooks.length" class="community-state"><el-empty :description="viewMode === 'favorites' ? '还没有收藏绘本' : '还没有找到公开绘本'" /></div>
    <div v-else class="community-grid">
      <article v-for="book in visibleBooks" :key="book.id" class="community-book" tabindex="0" @click="openBook(book)" @keyup.enter="openBook(book)">
        <BookCover class="community-cover" :book="book" />
        <div class="community-book-body">
          <div class="book-card-title"><h2>{{ book.title || '未命名绘本' }}</h2><span class="book-card-tags"><el-tag v-if="book.ageGroup" size="small" effect="plain">{{ book.ageGroup }}</el-tag><el-tag v-if="book.textLang" size="small" effect="plain" type="info">{{ languageLabel(book.textLang) }}</el-tag></span></div>
          <p>{{ book.description || '创作者还没有添加故事简介。' }}</p>
          <div class="community-book-foot"><span class="book-date">{{ displayDate(book.publishedAt || book.publishTime || book.updatedAt) || '公开作品' }}</span><div class="book-actions"><button class="like-action" :class="{ liked: likedIds.has(book.id) }" :aria-label="`${likedIds.has(book.id) ? '取消点赞' : '点赞'}，当前 ${book.likeCount || 0}`" @click.stop="likeBook(book)"><el-icon><Star /></el-icon>{{ book.likeCount || 0 }}</button><button class="favorite-action" :class="{ favorited: book.isFavorited }" :aria-label="book.isFavorited ? '取消收藏' : '收藏绘本'" :title="book.isFavorited ? '取消收藏' : '收藏绘本'" @click.stop="favoriteBook(book)"><el-icon><CollectionTag /></el-icon></button></div></div>
        </div>
      </article>
    </div>
    <div v-if="total > pageSize" class="community-pagination"><el-pagination v-model:current-page="pageNo" :page-size="pageSize" :total="total" layout="prev, pager, next, total" background @current-change="loadBooks" /></div>

    <el-dialog v-model="detailVisible" :title="activeBook?.title || '绘本详情'" width="min(920px, calc(100vw - 28px))" destroy-on-close>
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="activeBook">
          <div class="detail-meta"><span>{{ activeBook.ageGroup || '绘本' }}</span><span>{{ activeBook.imageStyle || '插画' }}</span><span>{{ activeBook.textLang ? languageLabel(activeBook.textLang) : '中文' }}</span><span>{{ activeBook.pages?.length || 0 }} 页</span><span>{{ activeBook.viewCount || 0 }} 次阅读</span></div>
          <p class="detail-description">{{ activeBook.description || '暂无简介' }}</p>
          <el-button type="primary" plain :icon="Star" @click="likeBook(activeBook)">{{ likedIds.has(activeBook.id) ? '取消喜欢' : '喜欢' }} · {{ activeBook.likeCount || 0 }}</el-button>
          <el-button plain :type="activeBook.isFavorited ? 'warning' : 'default'" :icon="CollectionTag" @click="favoriteBook(activeBook)">{{ activeBook.isFavorited ? '取消收藏' : '收藏绘本' }}</el-button>
          <section class="story-pages">
            <h3>故事页面</h3>
            <el-empty v-if="!activeBook.pages?.length" description="暂时没有页面内容" :image-size="64" />
            <BookFlipReader v-else :book-id="activeBook.id" :pages="activeBook.pages || []" :title="activeBook.title || '社区绘本'" />
          </section>
          <section class="comment-section">
            <h3><el-icon><ChatDotRound /></el-icon> {{ replyingTo ? `回复 ${replyingTo.nickname || '读者'}` : '写一条评论' }}</h3>
            <el-input v-model="commentText" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="replyingTo ? '写下你的回复' : '分享你读完后的感受'" />
            <div class="comment-submit"><el-button v-if="replyingTo" @click="replyingToId = null; commentText = ''">取消回复</el-button><el-button type="primary" :loading="commentBusy" @click="submitComment">{{ replyingTo ? '提交回复' : '提交评论' }}</el-button></div>
            <div v-loading="commentsLoading" class="comment-list">
              <el-empty v-if="!commentsLoading && !comments.length" description="还没有评论" :image-size="48" />
              <article v-for="item in topLevelComments" :key="item.id" class="comment-item"><div class="comment-author">{{ item.nickname || '读者' }}<small>{{ displayDate(item.createdAt) }}</small></div><p>{{ item.content }}</p><button class="reply-action" type="button" @click="startReply(item)">回复</button><article v-for="reply in repliesFor(item.id)" :key="reply.id" class="comment-reply"><div class="comment-author">{{ reply.nickname || '读者' }}<small>{{ displayDate(reply.createdAt) }}</small></div><p>{{ reply.content }}</p></article></article>
            </div>
          </section>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.community-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.community-heading .page-title span{font-size:21px}.community-toolbar{display:flex;align-items:center;gap:9px;margin:24px 0 20px;flex-wrap:wrap}.community-toolbar>.el-input{max-width:300px}.community-filter{width:140px}.community-toolbar .el-radio-group{margin-left:auto}.community-state{min-height:210px;padding:30px 0}.community-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}.community-book{overflow:hidden;border:1px solid #eaded6;border-radius:10px;background:#fff;cursor:pointer;transition:transform .18s,box-shadow .18s}.community-book:hover,.community-book:focus-visible{transform:translateY(-2px);box-shadow:0 8px 22px rgba(79,60,50,.1);outline:none}.community-cover{position:relative;display:grid;place-items:center;aspect-ratio:16/10;overflow:hidden;background:#f7e6d8}.community-cover img{width:100%;height:100%;object-fit:cover}.cover-placeholder{font-size:52px}.page-count{position:absolute;right:10px;bottom:10px;display:flex;align-items:center;gap:5px;padding:5px 8px;border-radius:5px;background:rgba(255,255,255,.92);color:#5f656c;font-size:11px}.community-book-body{padding:14px 15px 12px}.book-card-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.book-card-title h2{overflow:hidden;margin:0;color:#3d3a38;font-size:16px;text-overflow:ellipsis;white-space:nowrap}.book-card-tags{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:4px}.community-book-body>p{min-height:38px;margin:9px 0 13px;color:#807871;font-size:12px;line-height:1.6;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;overflow:hidden}.community-book-foot{display:flex;align-items:center;justify-content:space-between;border-top:1px solid #f0e8e2;padding-top:10px}.book-actions{display:flex;align-items:center;gap:6px}.book-date{color:#a09791;font-size:11px}.like-action,.favorite-action{display:flex;align-items:center;justify-content:center;gap:5px;min-width:30px;min-height:30px;padding:4px 7px;border:0;background:transparent;color:#c98165;font-size:12px;cursor:pointer}.like-action.liked{color:#dc614d}.favorite-action.favorited{color:#d18b22}.community-pagination{display:flex;justify-content:center;margin-top:24px}.detail-body{min-height:100px}.detail-meta{display:flex;flex-wrap:wrap;gap:8px;color:#82786f;font-size:12px}.detail-meta span{padding:5px 8px;border:1px solid #eee3db;border-radius:4px}.detail-description{margin:13px 0;color:#665e57;font-size:13px;line-height:1.7}.story-pages{margin-top:24px}.story-pages h3,.comment-section h3{display:flex;align-items:center;gap:7px;margin:0 0 12px;font-size:15px}.comment-section{margin-top:24px;padding-top:18px;border-top:1px solid #eee8e2}.comment-submit{display:flex;justify-content:flex-end;gap:8px;margin-top:9px}.comment-list{display:grid;gap:9px;margin-top:14px}.comment-item{padding:10px 0;border-top:1px solid #f0e8e2}.comment-author{display:flex;justify-content:space-between;gap:12px;color:#65584f;font-size:12px;font-weight:700}.comment-author small{color:#a39a94;font-weight:400}.comment-item p{margin:7px 0 0;color:#514c46;font-size:13px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.reply-action{margin:7px 0 0;padding:0;border:0;background:transparent;color:#cb7658;font:inherit;font-size:11px;cursor:pointer}.comment-reply{margin:10px 0 0 16px;padding:9px 11px;border-left:2px solid #f1c6a4;background:#fff9f2;border-radius:0 6px 6px 0}
@media(max-width:900px){.community-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:640px){.community-heading{align-items:flex-start}.community-toolbar>.el-input{max-width:none;flex:1 1 65%}.community-filter{width:calc(33.333% - 7px);flex:1 1 calc(33.333% - 7px)}.community-toolbar .el-radio-group{width:100%;margin-left:0}.community-toolbar .el-radio-button{flex:1}.community-grid{grid-template-columns:1fr}}
</style>
