import { http } from './http'
import type { BookDraft } from '../types'
import type { AdminAnalytics } from '../types/adminAnalytics'

export const authApi = {
  login: (data: { username: string; password: string }) => http.post('/api/v1/auth/login', data),
  register: (data: Record<string, string>) => http.post('/api/v1/auth/register', data),
  profile: () => http.get('/api/v1/users/me'),
  updateNickname: (nickname: string) => http.patch('/api/v1/users/me/nickname', { nickname }),
  updatePassword: (data: { currentPassword: string; newPassword: string }) => http.patch('/api/v1/users/me/password', data),
  uploadAvatar: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return http.post('/api/v1/users/me/avatar', body)
  }
}
export const bookApi = {
  speech: (bookId: number, pageId: number, text: string, signal: AbortSignal) => http.post<unknown, Blob>(`/api/v1/books/${bookId}/pages/${pageId}/speech`, { text }, { responseType: 'blob', signal, timeout: 55000 }),
  generateStory: (data: Record<string, unknown>) => http.post('/api/v1/books/story', data),
  autoCreate: (data: Record<string, unknown>) => http.post('/api/v1/books/auto', data),
  autoTask: (taskId: number | string) => http.get(`/api/v1/books/auto/${encodeURIComponent(String(taskId))}`),
  get: (id: number) => http.get(`/api/v1/books/${id}`),
  extractCharacters: (bookId: number) => http.post(`/api/v1/books/${bookId}/characters/extract`),
  saveCharacters: (bookId: number, characters: Record<string, unknown>[]) => http.put(`/api/v1/books/${bookId}/characters`, { characters }),
  polishCharacter: (bookId: number, character: Record<string, unknown>) => http.post(`/api/v1/books/${bookId}/characters/polish`, character),
  generateCharacterReference: (bookId: number, character: Record<string, unknown>) => http.post(`/api/v1/books/${bookId}/characters/reference`, { character }),
  uploadCharacterReference: (bookId: number, file: File) => {
    const body = new FormData()
    body.append('file', file)
    return http.post(`/api/v1/books/${bookId}/characters/reference-image`, body)
  },
  generateStoryboard: (bookId: number) => http.post(`/api/v1/books/${bookId}/storyboard`, {}, { timeout: 180000 }),
  generateImages: (bookId: number, pageId?: number) => http.post(`/api/v1/books/${bookId}/images`, { pageId }, { timeout: 180000 }),
  imageTasks: (bookId: number) => http.get(`/api/v1/books/${bookId}/tasks`),
  retryImage: (bookId: number, pageId: number, regenerate = false) => http.post(`/api/v1/books/${bookId}/images/retry`, { pageId, regenerate }, { timeout: 180000 }),
  save: (book: BookDraft) => http.post('/api/v1/books', book),
  list: () => http.get('/api/v1/books/mine'),
  remove: (id: number) => http.delete(`/api/v1/books/${id}`),
  submit: (id: number) => http.post(`/api/v1/books/${id}/submit`),
  uploadPageImage: (bookId: number, pageId: number, file: File) => {
    const body = new FormData()
    body.append('file', file)
    return http.post(`/api/v1/books/${bookId}/pages/${pageId}/image`, body)
  },
  export: (id: number, format: 'pdf' | 'zip') => http.get(`/api/v1/books/${id}/export/${format}`, { responseType: 'blob' })
}
export const presetApi = { list: (category?: string) => http.get('/api/v1/presets', { params: category ? { category } : undefined }) }
export const communityApi = { list: (params?: Record<string, unknown>) => http.get('/api/v1/community/books', { params }), favorites: (params?: Record<string, unknown>) => http.get('/api/v1/community/favorites', { params }), detail: (id: number) => http.get(`/api/v1/community/books/${id}`), comments: (id: number) => http.get(`/api/v1/community/books/${id}/comments`), like: (id: number) => http.post(`/api/v1/community/books/${id}/like`), favorite: (id: number) => http.post(`/api/v1/community/books/${id}/favorite`), comment: (id: number, content: string, parentId?: number) => http.post(`/api/v1/community/books/${id}/comments`, { content, ...(parentId ? { parentId } : {}) }) }
const adminResourcePath = (path: string, id: number | string) => `${path}/${encodeURIComponent(String(id))}`
export const adminApi = {
  dashboard: () => http.get('/api/admin/dashboard'),
  dashboardAnalytics: (params: { days: number; userId?: number }, signal?: AbortSignal) => http.get<unknown, { code: number; message: string; data: AdminAnalytics }>('/api/admin/dashboard/analytics', { params, signal }),
  users: () => http.get('/api/admin/users'),
  userDetails: (id: number | string) => http.get(adminResourcePath('/api/admin/users', id)),
  setUserEnabled: (id: number, enabled: boolean) => http.put(`/api/admin/users/${id}`, { enabled }),
  resetUserPassword: (id: number | string, newPassword?: string) => http.post(`${adminResourcePath('/api/admin/users', id)}/reset-password`, newPassword ? { newPassword } : {}),
  deleteUser: (id: number | string) => http.delete(adminResourcePath('/api/admin/users', id)),
  presets: () => http.get('/api/admin/presets'),
  createPreset: (data: Record<string, unknown>) => http.post('/api/admin/presets', data),
  updatePreset: (id: number | string, data: Record<string, unknown>) => http.put(adminResourcePath('/api/admin/presets', id), data),
  deletePreset: (id: number | string) => http.delete(adminResourcePath('/api/admin/presets', id)),
  uploadPresetImage: (id: number | string, file: File) => {
    const body = new FormData()
    body.append('file', file)
    return http.post(`${adminResourcePath('/api/admin/presets', id)}/image`, body)
  },
  reviewQueue: () => http.get('/api/admin/books/review'),
  review: (id: number, data: Record<string, unknown>) => http.post(`/api/admin/books/${id}/review`, data),
  models: () => http.get('/api/admin/models'),
  saveModel: (data: Record<string, unknown>) => http.put('/api/admin/models', data),
  testModel: (data: Record<string, unknown>) => http.post('/api/admin/models/test', data),
  prompts: () => http.get('/api/admin/prompts'),
  createPrompt: (data: Record<string, unknown>) => http.post('/api/admin/prompts', data),
  updatePrompt: (id: number | string, data: Record<string, unknown>) => http.put(adminResourcePath('/api/admin/prompts', id), data),
  deletePrompt: (id: number | string) => http.delete(adminResourcePath('/api/admin/prompts', id)),
  words: () => http.get('/api/admin/sensitive-words'),
  setWordsEnabled: (ids: number[], enabled: boolean) => http.post('/api/admin/sensitive-words/status', { ids, enabled }),
  wordHits: (params?: Record<string, unknown>) => http.get('/api/admin/sensitive-word-hits', { params }),
  createWord: (data: Record<string, unknown>) => http.post('/api/admin/sensitive-words', data),
  updateWord: (id: number | string, data: Record<string, unknown>) => http.put(adminResourcePath('/api/admin/sensitive-words', id), data),
  deleteWord: (id: number | string) => http.delete(adminResourcePath('/api/admin/sensitive-words', id)),
  logs: () => http.get('/api/admin/logs')
}
