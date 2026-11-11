<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { StoryPage } from '../types'

const props = defineProps<{ book: { id: number; title?: string; coverUrl?: string; pages?: StoryPage[] } }>()
const failed = ref<string[]>([])
const candidates = computed(() => {
  const pages = (props.book.pages || [])
    .filter(page => page.imageUrl && (!page.imageStatus || page.imageStatus === 'SUCCESS'))
    .map(page => page.imageUrl as string)
  // Older API responses can lack coverUrl. Pick a stable page for that book.
  const start = pages.length ? Math.abs(Math.imul(props.book.id, 2654435761)) % pages.length : 0
  return [...new Set([props.book.coverUrl, ...pages.slice(start), ...pages.slice(0, start)])]
    .filter((url): url is string => !!url?.trim() && url !== 'null')
})
const source = computed(() => candidates.value.find(url => !failed.value.includes(url)))
watch(() => props.book.id, () => { failed.value = [] })
function imageFailed() {
  if (source.value) failed.value = [...failed.value, source.value]
}
</script>

<template>
  <div class="storybook-cover" :class="{ 'has-cover': source }">
    <img v-if="source" :key="source" :src="source" :alt="`${book.title || '绘本'}封面，选自内页插画`" loading="lazy" @error="imageFailed" />
    <div v-else class="cover-pending"><strong>{{ book.title || '未命名绘本' }}</strong><small>{{ candidates.length ? '插画暂时无法加载' : '内页插画生成后显示封面' }}</small></div>
  </div>
</template>

<style scoped>
.storybook-cover{overflow:hidden;position:relative;width:100%;padding:0;background:#faf0e4}
.storybook-cover img{position:absolute;inset:0;display:block;width:100%;height:100%;min-width:0;min-height:0;object-fit:cover;object-position:center center}
.cover-pending{display:flex;height:100%;min-height:160px;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:24px;text-align:center}
.cover-pending strong{font-size:19px;color:#715647}.cover-pending small{font-size:12px;font-weight:400;color:#9a8576}
</style>
