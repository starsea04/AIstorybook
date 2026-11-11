import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSessionStore = defineStore('session', () => {
  const token = ref(localStorage.getItem('storybook_token') || '')
  const role = ref(localStorage.getItem('storybook_role') || 'ROLE_USER')
  const nickname = ref(localStorage.getItem('storybook_nickname') || '小小创作者')
  const avatar = ref(localStorage.getItem('storybook_avatar') || '')
  function setSession(t: string, r = 'ROLE_USER', n = '小小创作者', a = '') {
    token.value = t; role.value = r; nickname.value = n; avatar.value = a
    localStorage.setItem('storybook_token', t); localStorage.setItem('storybook_role', r)
    localStorage.setItem('storybook_nickname', n); localStorage.setItem('storybook_avatar', a)
  }
  function setProfile(n: string, a: string | null = null) {
    nickname.value = n
    localStorage.setItem('storybook_nickname', n)
    if (a !== null) { avatar.value = a; localStorage.setItem('storybook_avatar', a) }
  }
  function logout() {
    token.value = ''; role.value = 'ROLE_USER'; nickname.value = '小小创作者'; avatar.value = ''
    localStorage.removeItem('storybook_token'); localStorage.removeItem('storybook_role')
    localStorage.removeItem('storybook_nickname'); localStorage.removeItem('storybook_avatar')
  }
  return { token, role, nickname, avatar, setSession, setProfile, logout }
})
