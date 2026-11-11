<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'

const route = useRoute(), router = useRouter(), session = useSessionStore()
const authPage = computed(() => ['login', 'register', 'admin-login'].includes(String(route.name)))
const adminPage = computed(() => route.name === 'admin')
const nav = computed(() => [
  { to: '/', icon: '⌂', label: '创作乐园' },
  { to: '/create', icon: '✦', label: '开始创作' },
  { to: '/books', icon: '▤', label: '我的绘本' },
  { to: '/community', icon: '♡', label: '故事星球' },
  ...(session.role === 'ROLE_ADMIN' ? [{ to: '/admin', icon: '⚙', label: '管理工作台' }] : [])
])
function logout() { session.logout(); router.push('/login') }
</script>

<template>
  <div v-if="authPage" class="auth-shell"><router-view /></div>
  <div v-else-if="adminPage" class="admin-shell"><router-view /></div>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <router-link to="/" class="brand"><span class="brand-mark">✎</span><span>奇妙绘本<small>STORYBOOK STUDIO</small></span></router-link>
      <div class="side-label">创作空间</div>
      <nav><router-link v-for="item in nav" :key="item.to" :to="item.to" class="nav-item" exact-active-class="router-link-exact-active"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}</router-link></nav>
      <div class="side-card"><div class="star-burst">✦</div><strong>灵感来了？</strong><p>把脑海里的小故事，变成一本真正的绘本吧！</p><router-link to="/create" class="side-cta">现在开始 <span>→</span></router-link></div>
      <div class="sidebar-bottom"><div class="avatar">{{ session.nickname.slice(0,1) }}</div><div class="user-mini"><b>{{ session.nickname }}</b><small>绘本小作者</small></div><button class="icon-btn" aria-label="退出登录" @click="logout">↗</button></div>
    </aside>
    <main class="main-area"><header class="topbar"><div class="crumb">我的创作空间 <span>/</span> <b>{{ nav.find(n => n.to === route.path)?.label || '故事乐园' }}</b></div><div class="top-actions"><span class="welcome-chip">🌈 今天也来创造快乐吧</span><router-link to="/profile" class="top-avatar">{{ session.nickname.slice(0,1) }}</router-link></div></header><router-view /></main>
  </div>
</template>

<style>
/* Keep navigation and the account footer at a stable viewport height on every route. */
.app-shell > .sidebar {
  position: sticky;
  top: 0;
  align-self: flex-start;
  height: 100vh;
  height: 100dvh;
  overflow-y: auto;
}
.app-shell > .sidebar > * { flex-shrink: 0; }
/* The root link is an ancestor match on every child route; only exact links stay highlighted. */
.nav-item.router-link-active:not(.router-link-exact-active):not(:hover) {
  background: transparent;
  color: #877b85;
}
</style>
