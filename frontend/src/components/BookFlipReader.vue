<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PageFlip } from 'page-flip/dist/js/page-flip.module.js'
import pageLayout from '../../../shared/book-page.json'
import type { StoryPage } from '../types'
import { bookApi } from '../api'
import { ReaderPlayback, type PlaybackState, type ReadingPage } from '../lib/readerPlayback'

const props = withDefaults(defineProps<{ pages: StoryPage[]; title?: string; bookId?: number | null }>(), { title: '我的绘本' })
const reader = ref<HTMLElement | null>(null)
const stage = ref<HTMLElement | null>(null)
const speechAudio = ref<HTMLAudioElement | null>(null)
const leafIndex = ref(0)
const portrait = ref(false)
const ready = ref(false)
const busy = ref(false)
const playback = ref<PlaybackState>({ auto: false, narration: false, phase: 'off', error: '', elapsed: 0, duration: 0 })
const playing = computed(() => playback.value.auto)
const narration = computed(() => playback.value.narration)
const isFullscreen = ref(false)
const current = computed(() => Math.floor(leafIndex.value / 2))
const canPrev = computed(() => ready.value && leafIndex.value > 0)
const canNext = computed(() => ready.value && leafIndex.value < props.pages.length * 2 - (portrait.value ? 1 : 2))
const progress = computed(() => props.pages.length ? (leafIndex.value + (portrait.value ? 1 : 2)) / (props.pages.length * 2) * 100 : 0)
const status = computed(() => props.pages.length
  ? `第 ${current.value + 1} 页 · 共 ${props.pages.length} 页${portrait.value ? (leafIndex.value % 2 ? ' · 文字' : ' · 插画') : ''}`
  : '暂无页面')
let book: PageFlip | undefined
let observer: ResizeObserver | undefined
let motion: MediaQueryList | undefined
let player: ReaderPlayback | undefined
let automaticTurn = false
let refreshFrame = 0
let layoutFrame = 0
let pendingRefresh = false
let disposed = false

function stopPlay() {
  player?.setAuto(false)
}

const speechStatus = computed(() => ({ off: '语音已关闭', loading: '正在准备本页语音…', playing: '正在朗读本页', paused: '朗读已暂停', ended: '本页朗读完成', empty: '本页没有故事文字', error: playback.value.error }[playback.value.phase]))
function formatTime(value: number) { const seconds = Math.max(0, Math.floor(value)); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
function syncPlayback() {
  if (busy.value || !props.pages.length) return
  const page = props.pages[current.value]
  if (!page) return
  player?.setPage({ bookId: props.bookId ?? undefined, id: page.id, text: page.storyText || '', position: leafIndex.value,
    hasNextLeaf: canNext.value, hasNextStory: current.value < props.pages.length - 1 })
}

async function synthesize(page: ReadingPage, signal: AbortSignal) {
  if (!localStorage.getItem('storybook_token')) throw new Error('请先登录后使用语音朗读')
  if (!page.bookId || !page.id) throw new Error('请先保存绘本页面，再开启语音朗读')
  try { return await bookApi.speech(page.bookId, page.id, page.text, signal) }
  catch (failure: any) {
    if (signal.aborted) throw failure
    if (failure?.response?.data instanceof Blob) {
      try { const result = JSON.parse(await failure.response.data.text()); if (result.message) throw new Error(result.message) }
      catch (error) { if (error instanceof Error && !(error instanceof SyntaxError)) throw error }
    }
    throw new Error(failure?.response?.status === 401 || failure?.response?.status === 403 ? '登录已过期，请重新登录后朗读' : '暂时无法生成语音，请稍后重试')
  }
}

function manualMove(direction: -1 | 1) { stopPlay(); move(direction) }
function toggleNarration() { player?.setNarration(!narration.value) }
function toggleSpeechPause() {
  if (playback.value.phase === 'playing' || playback.value.phase === 'loading') player?.pause()
  else void player?.resume()
}

function syncBook() {
  if (!book || disposed) return
  leafIndex.value = book.getCurrentPageIndex()
  portrait.value = book.getOrientation() === 'portrait'
  syncPlayback()
}

function move(direction: -1 | 1) {
  if (!book || busy.value || (direction > 0 ? !canNext.value : !canPrev.value)) return false
  player?.suspend()
  if (motion?.matches) {
    if (direction > 0) book.turnToNextPage()
    else book.turnToPrevPage()
  } else if (direction > 0) book.flipNext('bottom')
  else book.flipPrev('bottom')
  if (motion?.matches) { automaticTurn = false; syncBook() }
  return true
}

function advance(narrated: boolean) {
  if (!book || busy.value || !canNext.value) return false
  automaticTurn = true
  // In portrait, art and text are two leaves of one story page. Narrate once,
  // then advance to the next story instead of repeating its text on both leaves.
  if (narrated) {
    if (motion?.matches) { book.turnToPage((current.value + 1) * 2); automaticTurn = false; syncBook() }
    else book.flip((current.value + 1) * 2, 'bottom')
    return true
  }
  return move(1)
}

function jump(index: number) {
  stopPlay()
  if (!book || busy.value || index < 0 || index >= props.pages.length) return
  player?.suspend()
  if (motion?.matches) book.turnToPage(index * 2)
  else book.flip(index * 2, 'bottom')
  if (motion?.matches || index === current.value) syncBook()
}

function togglePlay() {
  player?.setAuto(!playing.value)
}

function onKey(event: KeyboardEvent) {
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
  const target = event.target
  if (target instanceof HTMLElement && target.closest('input, textarea, select, [contenteditable="true"]')) return
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault()
    stopPlay()
    move(event.key === 'ArrowRight' ? 1 : -1)
  }
}

function focusReader(event: PointerEvent) {
  if (!(event.target instanceof HTMLElement) || event.target.closest('button, a, input, textarea')) return
  reader.value?.focus({ preventScroll: true })
}

function element(tag: string, className: string, text?: string) {
  const node = document.createElement(tag)
  node.className = className
  if (text !== undefined) node.textContent = text
  return node
}

function makeLeaves(): HTMLElement[] {
  // StPageFlip reparents/clones pages; it owns these nodes, separately from Vue's VDOM.
  // User/model strings use textContent, never innerHTML.
  return props.pages.flatMap((page, index) => {
    const art = element('div', 'flip-leaf flip-art')
    art.dataset.storyPage = String(index + 1)
    if (page.imageUrl) {
      const image = document.createElement('img')
      image.src = page.imageUrl
      image.alt = `${props.title}第 ${index + 1} 页插画`
      image.draggable = false
      image.decoding = 'async'
      image.loading = Math.abs(index - current.value) <= 1 ? 'eager' : 'lazy'
      art.append(image)
    } else art.append(element('span', 'flip-image-pending', '本页插画尚未生成'))
    const copy = element('div', 'flip-leaf flip-copy')
    copy.dataset.storyPage = String(index + 1)
    const content = element('div', 'flip-copy-content')
    content.append(element('span', 'flip-kicker', `第 ${index + 1} 页`))
    content.append(element('p', 'flip-story', page.storyText || '本页暂无故事文字'))
    if (page.summary) content.append(element('p', 'flip-summary', page.summary))
    copy.append(content, element('small', 'flip-page-number', `${index + 1} / ${props.pages.length}`))
    return [art, copy]
  })
}

function sizeStage() {
  if (!stage.value) return
  const width = stage.value.clientWidth
  if (width <= 0) return // Dialogs can mount while still hidden.
  const pageWidth = Math.min(width < 560 ? width : width / 2, 500)
  stage.value.style.height = `${pageWidth * pageLayout.height / pageLayout.width}px`
  if (book) { book.update(); syncBook() }
  else if (props.pages.length) refreshPages()
}

function scheduleLayout() {
  cancelAnimationFrame(layoutFrame)
  layoutFrame = requestAnimationFrame(sizeStage)
}

function destroyBook() {
  ready.value = false
  if (book) {
    for (const name of ['init', 'flip', 'changeOrientation', 'changeState']) book.off(name)
    book.destroy()
    book = undefined
  }
  stage.value?.replaceChildren()
  busy.value = false
}

function refreshPages() {
  if (!stage.value || disposed) return
  if (busy.value) { pendingRefresh = true; return }
  pendingRefresh = false
  if (!props.pages.length) { stopPlay(); player?.suspend(); destroyBook(); leafIndex.value = 0; return }
  if (stage.value.clientWidth <= 0) return
  const target = Math.min(leafIndex.value, props.pages.length * 2 - 1)
  const leaves = makeLeaves()
  if (book) {
    // updateFromHtml retains the old index; move inside new bounds before shrinking.
    book.getRender().finishAnimation()
    if (leafIndex.value >= leaves.length) book.turnToPage(0)
    book.updateFromHtml(leaves)
    book.turnToPage(target)
    syncBook()
    return
  }
  const host = element('div', 'flip-book')
  stage.value.replaceChildren(host)
  book = new PageFlip(host, {
    width: 400, height: 400 * pageLayout.height / pageLayout.width,
    size: 'stretch', minWidth: 280, maxWidth: 500, minHeight: 200, maxHeight: 625,
    startPage: target, autoSize: false, usePortrait: true, showCover: false,
    drawShadow: true, maxShadowOpacity: 0.25,
    flippingTime: motion?.matches ? 1 : 800, showPageCorners: !motion?.matches,
    mobileScrollSupport: true, useMouseEvents: true,
  })
  book.on('init', () => { ready.value = true; syncBook() })
  book.on('flip', syncBook)
  book.on('changeOrientation', syncBook)
  book.on('changeState', event => {
    const wasBusy = busy.value
    busy.value = event.data === 'flipping' || event.data === 'user_fold'
    if (busy.value && !wasBusy) {
      if (!automaticTurn) stopPlay()
      player?.suspend()
    }
    if (event.data === 'read') {
      automaticTurn = false
      syncBook()
      if (pendingRefresh) scheduleRefresh()
    }
  })
  book.loadFromHTML(leaves)
  ready.value = true
  syncBook()
}

function scheduleRefresh() {
  cancelAnimationFrame(refreshFrame)
  refreshFrame = requestAnimationFrame(refreshPages)
}

function syncMotion() {
  if (!book) return
  book.getSettings().flippingTime = motion?.matches ? 1 : 800
  book.getSettings().showPageCorners = !motion?.matches
}

async function toggleFullscreen() {
  if (!reader.value) return
  try {
    if (document.fullscreenElement === reader.value) await document.exitFullscreen()
    else await reader.value.requestFullscreen()
  } catch { isFullscreen.value = false }
}
function syncFullscreen() {
  isFullscreen.value = document.fullscreenElement === reader.value
  scheduleLayout()
}

watch(() => JSON.stringify([props.bookId, props.title, props.pages.map(p => [p.id, p.pageNo, p.storyText, p.summary, p.imageUrl])]), scheduleRefresh)
onMounted(() => {
  const audio = speechAudio.value!
  player = new ReaderPlayback({ audio, synthesize, advance, changed: state => { playback.value = state } })
  motion = window.matchMedia('(prefers-reduced-motion: reduce)')
  motion.addEventListener('change', syncMotion)
  observer = new ResizeObserver(scheduleLayout)
  if (stage.value) observer.observe(stage.value)
  document.addEventListener('fullscreenchange', syncFullscreen)
  sizeStage()
})
onBeforeUnmount(() => {
  disposed = true
  player?.dispose()
  observer?.disconnect()
  motion?.removeEventListener('change', syncMotion)
  document.removeEventListener('fullscreenchange', syncFullscreen)
  cancelAnimationFrame(refreshFrame)
  cancelAnimationFrame(layoutFrame)
  destroyBook()
})
</script>

<template>
  <section ref="reader" class="reader-wrap" tabindex="0" role="region" :aria-label="`${title}阅读器`" @keydown="onKey" @pointerdown="focusReader">
    <audio ref="speechAudio" preload="auto" hidden />
    <div class="reader-heading">
      <div class="reader-title"><span class="book-mark" aria-hidden="true">✦</span><strong>{{ title }}</strong></div>
      <button class="fullscreen-control" type="button" :aria-label="isFullscreen ? '退出全屏' : '进入全屏阅读'" @click="toggleFullscreen">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="!isFullscreen" d="M8 4H4v4M16 4h4v4M20 16v4h-4M4 16v4h4" /><path v-else d="M4 8h4V4M20 8h-4V4M16 20v-4h4M8 20v-4H4" /></svg>
      </button>
    </div>
    <div class="reader-progress" aria-hidden="true"><span :style="{ width: `${progress}%` }"></span></div>
    <div class="flip-frame">
      <div ref="stage" class="flip-stage" :class="{ 'is-empty': !pages.length }" aria-hidden="true"></div>
      <p v-if="!pages.length" class="flip-empty">暂无绘本页面</p>
      <div class="flip-nav" aria-label="阅读控制">
        <button type="button" aria-label="上一页" :disabled="!canPrev || busy" @click="manualMove(-1)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg></button>
        <button class="play-control" type="button" :aria-label="playing ? '暂停自动翻页' : '自动翻页'" :aria-pressed="playing" :title="playing ? '暂停自动翻页' : narration ? '本页语音播放完毕后翻页' : '每页停留 5 秒'" :disabled="!ready || busy || (!canNext && !playing && !narration)" @click="togglePlay"><svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="!playing" d="m9 6 10 6-10 6z" /><path v-else d="M8 6v12M16 6v12" /></svg></button>
        <small aria-live="polite">{{ status }}</small>
        <button type="button" aria-label="下一页" :disabled="!canNext || busy" @click="manualMove(1)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg></button>
      </div>
      <div class="reader-audio" aria-label="语音朗读控制">
        <div class="reader-audio-actions">
          <button type="button" class="narration-toggle" :class="{ enabled: narration }" :aria-pressed="narration" :disabled="!ready || !pages.length || busy" @click="toggleNarration">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 5 6 9H3v6h3l5 4V5ZM15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14" /></svg>{{ narration ? '关闭语音朗读' : '开启语音朗读' }}
          </button>
          <button v-if="narration && playback.phase !== 'empty'" type="button" class="speech-pause" :disabled="busy" @click="toggleSpeechPause">{{ playback.phase === 'playing' || playback.phase === 'loading' ? '暂停朗读' : playback.phase === 'ended' ? '重播本页' : playback.phase === 'error' ? '重试朗读' : '继续朗读' }}</button>
          <span v-if="narration && playback.duration > 0" class="speech-time">{{ formatTime(playback.elapsed) }} / {{ formatTime(playback.duration) }}</span>
        </div>
        <div v-if="narration && playback.duration > 0" class="speech-progress" role="progressbar" aria-label="本页朗读进度" :aria-valuenow="Math.floor(playback.elapsed)" :aria-valuemax="Math.ceil(playback.duration)" aria-valuemin="0"><span :style="{width: `${Math.min(100, playback.elapsed / playback.duration * 100)}%`}" /></div>
        <p v-if="narration" class="speech-status" :class="{ error: playback.phase === 'error' }" role="status">{{ speechStatus }}</p>
        <p class="reader-timing">{{ narration ? '自动翻页开启后，等待本页朗读完毕再翻页；空白页停留 5 秒。' : '仅自动翻页：每页停留 5 秒。开启语音后，将按实际朗读时长翻页。' }}</p>
      </div>
    </div>
    <div class="flip-accessible" aria-live="polite">{{ pages[current]?.storyText }}<span v-if="pages[current]?.imageUrl">本页配有故事插画。</span></div>
    <nav v-if="pages.length" class="flip-thumbs" aria-label="选择页面">
      <button v-for="(item, index) in pages" :key="item.id ?? index" type="button" class="flip-thumb" :class="{ active: index === current }" :disabled="!ready || busy" :aria-label="`跳转到第 ${index + 1} 页`" :aria-current="index === current ? 'page' : undefined" @click="jump(index)">
        <img v-if="item.imageUrl" :src="item.imageUrl" alt="" loading="lazy" /><span v-else>{{ index + 1 }}</span>
      </button>
    </nav>
  </section>
</template>

<style scoped>
.reader-wrap{max-width:1000px;margin:0 auto;color:var(--ink,#392d3a);outline:none}
.reader-wrap:focus-visible{outline:2px solid #eb8059;outline-offset:5px;border-radius:12px}
.reader-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:9px;padding:0 3px}
.reader-title{display:flex;align-items:center;gap:9px;min-width:0;font-size:14px}.reader-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.book-mark{display:grid;place-items:center;width:27px;height:27px;flex:none;border-radius:9px;background:#ffeadc;color:#f1764e}
.fullscreen-control{display:grid;place-items:center;width:34px;height:34px;flex:none;border-radius:10px;background:#fff;color:#8e7d76;border:1px solid #efe1d9}
.fullscreen-control svg,.flip-nav svg{width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.reader-progress{height:4px;overflow:hidden;border-radius:99px;background:#f1e7df}.reader-progress span{display:block;height:100%;border-radius:inherit;background:#ff8a59;transition:width .3s ease}
.flip-frame{position:relative;margin-top:14px;padding:24px 25px 17px;border:2px solid #58454a;border-radius:23px;background:#ffefcf;box-shadow:5px 6px 0 #e8b99d,0 13px 28px #64453421}
.flip-stage{position:relative;width:100%;filter:drop-shadow(0 7px 9px #5c43371f)}.flip-stage.is-empty{height:0!important}.flip-empty{padding:75px 0;text-align:center;color:#9b7f6a}
.flip-stage :deep(.flip-book){width:100%;height:100%;min-width:0!important;min-height:0!important}
.flip-stage :deep(.flip-leaf){overflow:hidden;background:#fffdf9;box-sizing:border-box}
.flip-stage :deep(.flip-art){background:#fffdf9}
.flip-stage :deep(.flip-art img){display:block;width:100%;height:100%;object-fit:contain;object-position:center center;user-select:none}
.flip-stage :deep(.flip-image-pending){display:grid;height:100%;place-items:center;color:#a28f83;font-size:14px}
.flip-stage :deep(.flip-copy){padding:7% 6% 5%;background:linear-gradient(90deg,#fbf4eb,#fffdf9 12%);container-type:inline-size}
.flip-stage :deep(.flip-copy-content){display:flex;flex-direction:column;justify-content:safe center;align-items:center;gap:16px;height:calc(100% - 28px);overflow-y:auto;text-align:center;overscroll-behavior:contain}
.flip-stage :deep(.flip-kicker){flex:none;padding:5px 11px;border-radius:99px;background:#fff1dd;color:#b17a48;font-size:11px;font-weight:700}
.flip-stage :deep(.flip-story){margin:0;font-size:clamp(15px,6cqw,20px);line-height:1.85;font-weight:650;overflow-wrap:anywhere;white-space:pre-wrap}
.flip-stage :deep(.flip-summary){margin:0;color:#98877d;font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.flip-stage :deep(.flip-page-number){display:block;height:28px;padding-top:12px;text-align:center;color:#b2a298;font-size:11px}
.flip-nav{display:flex;align-items:center;justify-content:center;gap:15px;margin-top:18px}.flip-nav button{display:grid;place-items:center;width:39px;height:39px;flex:none;border-radius:50%;background:#fff;color:#e77b56;box-shadow:0 3px 0 #e9d5c2}.flip-nav button:disabled{opacity:.42;cursor:not-allowed}.flip-nav .play-control{background:#fff8ea;color:#a4764b}.flip-nav small{min-width:122px;text-align:center;color:#9b7f6a;font-size:12px}
.flip-nav button:focus-visible,.flip-thumb:focus-visible,.fullscreen-control:focus-visible{outline:2px solid #eb8059;outline-offset:2px}
.reader-audio{max-width:650px;margin:17px auto 0;padding-top:13px;border-top:1px solid #e7d3bb}.reader-audio-actions{display:flex;justify-content:center;align-items:center;gap:10px;flex-wrap:wrap}.reader-audio button{display:inline-flex;align-items:center;gap:6px;padding:8px 12px;border:1px solid #e9d5c2;border-radius:9px;background:#fffaf2;color:#8f6646;font-size:12px}.reader-audio button:disabled{opacity:.5;cursor:not-allowed}.reader-audio button:focus-visible{outline:2px solid #eb8059;outline-offset:2px}.reader-audio .narration-toggle.enabled{background:#f8dcc4;border-color:#e8b18d;color:#965a35}.narration-toggle svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.speech-time{font-size:11px;color:#9b7f6a;font-variant-numeric:tabular-nums}.speech-progress{height:3px;border-radius:10px;background:#ecdac5;margin:11px 0}.speech-progress span{display:block;height:100%;border-radius:inherit;background:#df9566;transition:width .2s linear}.speech-status,.reader-timing{margin:8px 0 0;text-align:center;color:#9b7f6a;font-size:11px;line-height:1.6}.speech-status.error{color:#b34740}.reader-timing{color:#a48a73}
.flip-thumbs{display:flex;gap:8px;max-width:100%;justify-content:safe center;overflow-x:auto;margin:14px auto 0;padding:2px 2px 6px}
.flip-thumb{display:grid;place-items:center;width:44px;height:55px;flex:none;padding:0;overflow:hidden;border:2px solid transparent;border-radius:7px;background:#faf0e4;color:#9d7153;font-size:11px}.flip-thumb img{width:100%;height:100%;object-fit:contain;object-position:center}.flip-thumb.active{border-color:#f07b54;box-shadow:0 2px 0 #e9bc9d}
.flip-accessible{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}
.reader-wrap:fullscreen{max-width:none;height:100vh;overflow-x:hidden;overflow-y:auto;scrollbar-gutter:stable both-edges;padding:24px;background:#fff9f0}.reader-wrap:fullscreen .flip-frame{width:min(100%,1100px);margin:20px auto 0}
/* Rotating leaves and shadows must not enlarge the fullscreen scroll area. */
.reader-wrap:fullscreen .flip-stage{overflow:hidden}
@media(max-width:640px){.flip-frame{padding:12px 12px 14px;border-radius:19px}.flip-nav{gap:8px}.flip-nav button{width:34px;height:34px}.flip-nav small{min-width:0;flex:1;font-size:11px}.reader-wrap:fullscreen{padding:14px}.flip-thumbs{justify-content:flex-start}}
@media(prefers-reduced-motion:reduce){.reader-progress span{transition:none}}
</style>
