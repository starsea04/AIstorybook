import { createRouter, createWebHistory, RouterView } from 'vue-router'

const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', component: RouterView, children: [
    { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { requiresAuth: true } },
    { path: 'create', name: 'create', component: () => import('../views/CreateView.vue'), meta: { requiresAuth: true } },
    { path: 'books', name: 'books', component: () => import('../views/BooksView.vue'), meta: { requiresAuth: true } },
    { path: 'community', name: 'community', component: () => import('../views/CommunityView.vue') },
    { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { requiresAuth: true } },
    { path: 'login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: 'register', name: 'register', component: () => import('../views/LoginView.vue') },
    { path: 'admin/login', name: 'admin-login', component: () => import('../views/LoginView.vue'), meta: { adminLogin: true } },
    { path: 'admin/:section?', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { requiresAdmin: true } },
    { path: ':pathMatch(.*)*', redirect: '/' }
  ] }
] })
router.beforeEach(to => {
  const token = localStorage.getItem('storybook_token')
  const role = localStorage.getItem('storybook_role')
  if (to.meta.requiresAuth && !token) return '/login'
  if (to.meta.requiresAdmin && role !== 'ROLE_ADMIN') return token ? '/' : '/admin/login'
})
export default router
