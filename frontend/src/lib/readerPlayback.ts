export type SpeechPhase = 'off' | 'loading' | 'playing' | 'paused' | 'ended' | 'empty' | 'error'
export interface PlaybackState { auto: boolean; narration: boolean; phase: SpeechPhase; error: string; elapsed: number; duration: number }
export interface ReadingPage {
  bookId?: number; id?: number; text: string; position: number
  hasNextLeaf: boolean; hasNextStory: boolean
}
type AudioPort = Pick<HTMLAudioElement, 'src' | 'currentTime' | 'duration' | 'pause' | 'play' | 'load' | 'removeAttribute' | 'onended' | 'onerror' | 'ontimeupdate' | 'onloadedmetadata'>
interface Options {
  audio: AudioPort
  synthesize: (page: ReadingPage, signal: AbortSignal) => Promise<Blob>
  advance: (narrated: boolean) => boolean
  changed: (state: PlaybackState) => void
  setTimer?: (callback: () => void, delay: number) => ReturnType<typeof setTimeout>
  clearTimer?: (id: ReturnType<typeof setTimeout>) => void
  createUrl?: (blob: Blob) => string
  revokeUrl?: (url: string) => void
}

/** One scheduler owns both modes: a fixed delay OR audio completion, never both. */
export class ReaderPlayback {
  readonly state: PlaybackState = { auto: false, narration: false, phase: 'off', error: '', elapsed: 0, duration: 0 }
  private page?: ReadingPage
  private pageKey = ''
  private suspended = false
  private disposed = false
  private revision = 0
  private request?: AbortController
  private timer?: ReturnType<typeof setTimeout>
  private url = ''
  private options: Options
  constructor(options: Options) { this.options = options }
  private emit() { if (!this.disposed) this.options.changed({ ...this.state }) }
  private clearTimer() {
    if (this.timer !== undefined) (this.options.clearTimer ?? clearTimeout)(this.timer)
    this.timer = undefined
  }
  private get canAdvance() { return this.state.narration ? this.page?.hasNextStory : this.page?.hasNextLeaf }

  setPage(page: ReadingPage) {
    if (this.disposed) return
    const key = JSON.stringify([page.bookId, page.id, page.text])
    const changed = key !== this.pageKey || (!this.state.narration && page.position !== this.page?.position)
    this.page = page; this.pageKey = key
    if (!changed && !this.suspended) return
    this.suspended = false
    this.cancelAudio()
    this.clearTimer()
    if (this.state.narration) void this.startSpeech()
    else { this.state.phase = 'off'; this.scheduleFixed(); this.emit() }
  }

  setAuto(enabled: boolean) {
    if (this.disposed || !this.page) return
    this.clearTimer()
    this.state.auto = enabled
    if (enabled && !this.suspended) {
      if (!this.state.narration || this.state.phase === 'empty') this.scheduleFixed()
      else if (this.state.phase === 'paused') void this.resume()
      else if (this.state.phase === 'ended' || this.state.phase === 'error') void this.startSpeech()
    }
    this.emit()
  }

  setNarration(enabled: boolean) {
    if (this.disposed) return
    this.clearTimer(); this.cancelAudio()
    this.state.narration = enabled
    this.state.error = ''
    if (enabled && !this.suspended) void this.startSpeech()
    else { this.state.phase = 'off'; if (!enabled) this.scheduleFixed(); this.emit() }
  }

  pause() {
    if (!this.state.narration || this.disposed) return
    this.clearTimer()
    this.revision++
    this.request?.abort(); this.request = undefined
    this.options.audio.pause()
    this.state.phase = 'paused'; this.emit()
  }

  async resume() {
    if (this.disposed || this.suspended || !this.state.narration) return
    this.clearTimer()
    if (!this.url || this.state.phase === 'ended' || this.state.phase === 'error') return this.startSpeech()
    const revision = ++this.revision
    this.state.error = ''
    this.bindAudio(revision)
    await this.play(revision)
  }

  /** Called before either a manual turn or an animated automatic turn. */
  suspend() {
    if (this.disposed) return
    this.suspended = true
    this.clearTimer(); this.cancelAudio()
    this.state.phase = this.state.narration ? 'loading' : 'off'; this.emit()
  }

  private cancelAudio() {
    this.revision++
    this.request?.abort(); this.request = undefined
    const audio = this.options.audio
    audio.onended = null; audio.onerror = null; audio.ontimeupdate = null; audio.onloadedmetadata = null
    audio.pause(); audio.removeAttribute('src'); audio.load()
    if (this.url) (this.options.revokeUrl ?? URL.revokeObjectURL)(this.url)
    this.url = ''; this.state.elapsed = 0; this.state.duration = 0
  }

  private async startSpeech() {
    if (this.disposed || this.suspended || !this.page || !this.state.narration) return
    this.clearTimer(); this.cancelAudio(); this.state.error = ''
    if (!this.page.text.trim()) {
      this.state.phase = 'empty'; this.scheduleFixed(); this.emit(); return
    }
    const revision = this.revision
    const request = new AbortController()
    this.request = request
    this.state.phase = 'loading'; this.emit()
    try {
      const blob = await this.options.synthesize({ ...this.page }, request.signal)
      if (!this.active(revision)) return
      this.request = undefined
      this.url = (this.options.createUrl ?? URL.createObjectURL)(blob)
      this.options.audio.src = this.url
      this.bindAudio(revision)
      await this.play(revision)
    } catch (error) { if (this.active(revision)) this.fail(error) }
  }

  private active(revision: number) { return !this.disposed && !this.suspended && this.state.narration && revision === this.revision }
  private bindAudio(revision: number) {
    const audio = this.options.audio
    const progress = () => {
      if (!this.active(revision)) return
      this.state.elapsed = Number.isFinite(audio.currentTime) ? audio.currentTime : 0
      this.state.duration = Number.isFinite(audio.duration) ? audio.duration : 0
      this.emit()
    }
    audio.ontimeupdate = progress; audio.onloadedmetadata = progress
    audio.onerror = () => { if (this.active(revision)) this.fail(new Error('音频播放失败，请重试')) }
    audio.onended = () => {
      if (!this.active(revision) || this.state.phase === 'paused') return
      progress(); this.state.phase = 'ended'
      if (this.state.auto) {
        if (this.canAdvance) this.scheduleAdvance(350)
        else this.state.auto = false
      }
      this.emit()
    }
  }
  private async play(revision: number) {
    try {
      await this.options.audio.play()
      if (this.active(revision)) { this.state.phase = 'playing'; this.emit() }
    } catch (error) {
      if (this.active(revision)) this.fail(new Error(error instanceof Error && error.name === 'NotAllowedError'
        ? '浏览器暂停了声音，请点击“继续朗读”播放' : '音频无法播放，请点击“重试朗读”'))
    }
  }
  private fail(error: unknown) {
    this.clearTimer(); this.options.audio.pause()
    this.state.auto = false; this.state.phase = 'error'
    this.state.error = error instanceof Error ? error.message : '语音生成失败，请重试'
    this.emit()
  }
  private scheduleFixed() {
    if (!this.state.auto || this.suspended || this.disposed) return
    if (!this.canAdvance) { this.state.auto = false; return }
    if (!this.state.narration || this.state.phase === 'empty') this.scheduleAdvance(5000)
  }
  private scheduleAdvance(delay: number) {
    this.clearTimer()
    this.timer = (this.options.setTimer ?? setTimeout)(() => {
      this.timer = undefined
      if (!this.state.auto || this.suspended || this.disposed) return
      if (!this.canAdvance) { this.state.auto = false; this.emit(); return }
      const narrated = this.state.narration
      this.suspend()
      if (!this.options.advance(narrated) && this.page) this.setPage(this.page)
    }, delay)
  }
  dispose() {
    this.disposed = true; this.state.auto = false
    this.clearTimer(); this.cancelAudio()
  }
}
