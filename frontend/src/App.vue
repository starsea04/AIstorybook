<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'

const route = useRoute(), router = useRouter(), session = useSessionStore()
const mobileNavOpen = ref(false)
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
watch(() => route.fullPath, () => { mobileNavOpen.value = false })
</script>

<template>
  <div v-if="authPage" class="auth-shell"><router-view /></div>
  <div v-else-if="adminPage" class="admin-shell"><router-view /></div>
  <div v-else class="app-shell">
    <aside id="primary-sidebar" class="sidebar" :class="{ 'mobile-open': mobileNavOpen }">
      <router-link to="/" class="brand"><span class="brand-mark">✎</span><span>奇妙绘本<small>STORYBOOK STUDIO</small></span></router-link>
      <div class="side-label">创作空间</div>
      <nav><router-link v-for="item in nav" :key="item.to" :to="item.to" class="nav-item" exact-active-class="router-link-exact-active" @click="mobileNavOpen = false"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}</router-link></nav>
      <div class="side-card"><div class="star-burst">✦</div><strong>灵感来了？</strong><p>把脑海里的小故事，变成一本真正的绘本吧！</p><router-link to="/create" class="side-cta">现在开始 <span>→</span></router-link></div>
      <div class="sidebar-bottom"><div class="avatar">{{ session.nickname.slice(0,1) }}</div><div class="user-mini"><b>{{ session.nickname }}</b><small>绘本小作者</small></div><button class="icon-btn" aria-label="退出登录" @click="logout">↗</button></div>
    </aside>
    <button v-if="mobileNavOpen" class="mobile-nav-backdrop" type="button" aria-label="关闭导航" @click="mobileNavOpen = false"></button>
    <main class="main-area"><header class="topbar"><button class="mobile-nav-toggle" type="button" :aria-expanded="mobileNavOpen" aria-controls="primary-sidebar" @click="mobileNavOpen = !mobileNavOpen"><span aria-hidden="true">☰</span>我的创作乐园</button><div class="crumb">我的创作空间 <span>/</span> <b>{{ nav.find(n => n.to === route.path)?.label || '故事乐园' }}</b></div><div class="top-actions"><span class="welcome-chip">🌈 今天也来创造快乐吧</span><router-link to="/profile" class="top-avatar">{{ session.nickname.slice(0,1) }}</router-link></div></header><router-view /></main>
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
.mobile-nav-toggle, .mobile-nav-backdrop { display: none; }
@media (max-width: 640px) {
  .app-shell > .sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 41;
    display: flex;
    width: min(82vw, 280px);
    height: 100vh;
    height: 100dvh;
    padding: 28px 18px 18px;
    gap: 18px;
    transform: translateX(-100%);
    transition: transform .2s ease;
    box-shadow: 8px 0 28px rgba(57, 45, 58, .12);
  }
  .app-shell > .sidebar.mobile-open { transform: translateX(0); }
  .app-shell > .sidebar .brand span:not(.brand-mark) { font-size: 18px; }
  .app-shell > .sidebar .side-label { display: block; font-size: 11px; }
  .app-shell > .sidebar .nav-item { justify-content: flex-start; padding: 12px 14px; font-size: 14px; }
  .app-shell > .sidebar .nav-icon { font-size: 20px; }
  .app-shell > .sidebar .side-card { display: block; }
  .app-shell > .sidebar .sidebar-bottom .user-mini { display: grid; }
  .app-shell > .sidebar .sidebar-bottom .icon-btn { display: block; }
  .mobile-nav-toggle {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    flex: none;
    padding: 8px 10px;
    border: 1px solid #f0e4dc;
    border-radius: 7px;
    background: #fffdf9;
    color: #665762;
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;
  }
  .mobile-nav-toggle span { font-size: 16px; line-height: 1; }
  .mobile-nav-backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    display: block;
    width: 100%;
    height: 100%;
    background: rgba(35, 28, 34, .34);
  }
  .main-area { min-width: 0; width: 100%; }
  .topbar { gap: 10px; padding: 0 12px; }
  .crumb { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>
