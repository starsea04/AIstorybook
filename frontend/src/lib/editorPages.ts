import type { StoryPage } from '../types'

export interface EditorImageTask { id: number; pageId: number; status: string; progress: number; retryCount?: number; errorMsg?: string }

export function activeImageTask(page: StoryPage, tasks: EditorImageTask[]) {
  return tasks.find(task => task.pageId === page.id && ['PENDING', 'PROCESSING'].includes(task.status))
}

export function editablePage(page: StoryPage) {
  // Images belong to generation/upload endpoints. Never write a stale image
  // state back through the text autosave endpoint.
  return { id: page.id, pageNo: page.pageNo, storyText: page.storyText, scenePrompt: page.scenePrompt,
    summary: page.summary, characters: page.characters }
}

export function applyImageState(local: StoryPage, remote: StoryPage) {
  local.imageUrl = remote.imageUrl
  local.imageStatus = remote.imageStatus
  local.imageError = remote.imageError
}

export function mergePolledImages(local: StoryPage[], remote: StoryPage[]) {
  const byId = new Map(remote.filter(page => page.id != null).map(page => [page.id, page]))
  for (const page of local) {
    const saved = byId.get(page.id)
    if (saved) applyImageState(page, saved)
  }
}

export function mergeSavedImages(local: StoryPage[], submitted: StoryPage[], remote: StoryPage[]) {
  // Keep references to the submitted editor rows, so an added/reordered page
  // receives its server ID without replacing edits made while save was pending.
  for (const [index, page] of submitted.entries()) {
    if (!local.includes(page)) continue
    const saved = remote.find(item => item.pageNo === index + 1)
    if (saved) {
      const newPage = page.id == null
      page.id = saved.id
      // A delayed text-save response may predate the latest polling result.
      if (newPage) applyImageState(page, saved)
    }
  }
}
