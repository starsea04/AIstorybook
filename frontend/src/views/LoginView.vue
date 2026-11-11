<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { useSessionStore } from '../stores/session'

const route = useRoute(), router = useRouter(), session = useSessionStore()
const loading = ref(false), errorMessage = ref('')
const admin = computed(() => route.name === 'admin-login'), register = computed(() => route.name === 'register')
const form = ref({ username: admin.value ? 'admin' : '', email: '', password: '' })

async function submit() {
  errorMessage.value = ''
  if (!form.value.username || !form.value.password) { errorMessage.value = '请填写账号和密码'; return ElMessage.warning(errorMessage.value) }
  if (register.value && !form.value.email) { errorMessage.value = '请填写邮箱'; return ElMessage.warning(errorMessage.value) }
  loading.value = true
  try {
    const response: any = register.value
      ? await authApi.register({ ...form.value, nickname: form.value.username })
      : await authApi.login({ username: form.value.username, password: form.value.password })
    if (response?.code !== 0) throw new Error(response?.message || '账号或密码错误')
    const data = response.data || {}, user = data.user || {}
    if (!data.token) throw new Error('登录服务没有返回访问凭证')
    const role = String(user.role || 'ROLE_USER').startsWith('ROLE_') ? user.role : `ROLE_${user.role || 'USER'}`
    if (admin.value && role !== 'ROLE_ADMIN') throw new Error('此账号没有管理员权限')
    session.setSession(data.token, role, user.nickname || user.username || form.value.username)
    ElMessage.success(register.value ? '注册成功，欢迎来到绘本星球' : '欢迎回来')
    await router.push(admin.value ? '/admin' : '/')
  } catch (error: any) {
    errorMessage.value = error?.message || '请求失败，请确认服务已启动后重试'
    if (error?.response?.data?.message) errorMessage.value = error.response.data.message
    ElMessage.error(errorMessage.value)
  } finally { loading.value = false }
}
</script>
<template>
  <div class="auth-card">
    <router-link to="/" class="brand"><span class="brand-mark">✎</span><span>奇妙绘本<small>STORYBOOK STUDIO</small></span></router-link>
    <h1>{{ admin ? '运营工作台登录' : (register ? '加入绘本星球' : '欢迎回来') }}</h1>
    <p class="auth-sub">{{ admin ? '管理作品与创作生态' : '让每一个故事，都有被看见的机会' }}</p>
    <form @submit.prevent="submit">
      <div class="field"><label>账号 / 用户名</label><input v-model="form.username" autocomplete="username" placeholder="请输入用户名" /></div>
      <div v-if="register" class="field"><label>邮箱</label><input v-model="form.email" type="email" autocomplete="email" placeholder="name@example.com" /></div>
      <div class="field"><label>密码</label><input v-model="form.password" type="password" :autocomplete="register ? 'new-password' : 'current-password'" placeholder="至少 6 位密码" /></div>
      <p v-if="errorMessage" role="alert" class="form-error">{{ errorMessage }}</p>
      <button class="btn btn-primary auth-submit" :disabled="loading">{{ loading ? '正在进入...' : (register ? '创建我的账号' : '进入奇妙世界 →') }}</button>
    </form>
    <p class="auth-link" v-if="!admin">{{ register ? '已经有账号？' : '还没有账号？' }} <router-link :to="register ? '/login' : '/register'">{{ register ? '直接登录' : '免费注册' }}</router-link></p>
    <p class="auth-link" v-if="!admin && !register"><router-link to="/admin/login">管理员入口</router-link></p>
    <p class="auth-link" v-else><router-link to="/login">返回用户登录</router-link></p>
  </div>
</template>
<style scoped>.form-error{font-size:12px;color:#c45144;background:#fff0ed;padding:9px 11px;border-radius:9px;margin:0 0 10px}</style>
