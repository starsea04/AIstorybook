export type TextLang = 'ZH' | 'EN'
export interface StoryPage { id?: number; pageNo: number; storyText: string; scenePrompt?: string; summary?: string; imageUrl?: string; imageStatus?: string; imageError?: string; characters?: number[] }
export interface BookDraft { id?: number; title: string; description: string; ageGroup: string; storyStyle: string; imageStyle: string; textLang: TextLang; status?: string; pages: StoryPage[]; characters?: Record<string, unknown>[] }
