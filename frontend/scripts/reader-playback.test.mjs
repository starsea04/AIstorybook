import test from 'node:test'
import assert from 'node:assert/strict'
import { ReaderPlayback } from '../src/lib/readerPlayback.ts'

const page = (overrides = {}) => ({ bookId: 11, id: 1, text: '小企鹅准备参加赛跑。', position: 0, hasNextLeaf: true, hasNextStory: true, ...overrides })
const flush = () => new Promise(resolve => setImmediate(resolve))
function harness(synthesize = async () => new Blob(['audio'])) {
  let now = 0, sequence = 0
  const timers = new Map(), turns = [], requests = [], changes = [], revoked = []
  const audio = { src: '', currentTime: 0, duration: 12, onended: null, onerror: null, ontimeupdate: null, onloadedmetadata: null,
    pause() {}, async play() {}, load() {}, removeAttribute() { this.src = '' } }
  const player = new ReaderPlayback({ audio,
    synthesize: (p, signal) => { requests.push({page: p, signal}); return synthesize(p, signal) },
    advance: narrated => { turns.push(narrated); return true }, changed: state => changes.push(state),
    setTimer: (run, delay) => { const id = ++sequence; timers.set(id, {at: now + delay, run}); return id },
    clearTimer: id => timers.delete(id), createUrl: () => `blob:${++sequence}`, revokeUrl: url => revoked.push(url),
  })
  function tick(ms) {
    const until = now + ms
    for (;;) {
      const next = [...timers].filter(([,t]) => t.at <= until).sort((a,b) => a[1].at - b[1].at)[0]
      if (!next) break
      now = next[1].at; timers.delete(next[0]); next[1].run()
    }
    now = until
  }
  player.setPage(page())
  return { player, audio, turns, requests, changes, revoked, tick }
}

test('silent automatic reading waits exactly 5 seconds after each settled page', () => {
  const h = harness(); h.player.setAuto(true); h.tick(4999); assert.equal(h.turns.length, 0)
  h.tick(1); assert.deepEqual(h.turns, [false]); h.tick(20000); assert.equal(h.turns.length, 1)
  h.player.setPage(page({id:2,position:2})); h.tick(5000); assert.equal(h.turns.length, 2)
  assert.equal(h.requests.length, 0)
})
test('loading and a long voice never advance until audio ended', async () => {
  let resolve; const h = harness(() => new Promise(r => {resolve=r}))
  h.player.setNarration(true); h.player.setAuto(true); h.tick(60000); assert.equal(h.turns.length, 0)
  resolve(new Blob(['audio'])); await flush(); assert.equal(h.player.state.phase, 'playing')
  h.tick(60000); assert.equal(h.turns.length, 0)
  h.audio.onended(); h.tick(349); assert.equal(h.turns.length, 0); h.tick(1); assert.deepEqual(h.turns, [true])
})
test('short narration advances on ended, without an extra five-second timer', async () => {
  const h=harness(); h.player.setNarration(true); h.player.setAuto(true); await flush()
  h.tick(1000); h.audio.onended(); h.tick(350); assert.deepEqual(h.turns,[true])
})
test('pausing narration pauses automatic advancement; resume retains current time', async () => {
  const h=harness(); h.player.setNarration(true); h.player.setAuto(true); await flush()
  h.audio.currentTime=4; const oldEnded=h.audio.onended; h.player.pause(); oldEnded(); h.tick(20000)
  assert.equal(h.turns.length,0); assert.equal(h.player.state.phase,'paused')
  await h.player.resume(); assert.equal(h.audio.currentTime,4); h.audio.onended(); h.tick(350); assert.equal(h.turns.length,1)
})
test('turning narration off cancels audio callbacks and starts a fresh fixed delay', async () => {
  const h=harness(); h.player.setNarration(true); h.player.setAuto(true); await flush()
  const oldEnded=h.audio.onended; h.player.setNarration(false); oldEnded(); h.tick(4999)
  assert.equal(h.turns.length,0); h.tick(1); assert.deepEqual(h.turns,[false]); assert.equal(h.revoked.length,1)
})
test('turning narration on cancels a nearly expired silent timer', async () => {
  const h=harness(); h.player.setAuto(true); h.tick(4999); h.player.setNarration(true); await flush()
  h.tick(50000); assert.equal(h.turns.length,0); h.audio.onended(); h.tick(350); assert.deepEqual(h.turns,[true])
})
test('the final page finishes speaking before automatic reading stops', async () => {
  const h=harness(); h.player.setPage(page({hasNextLeaf:false,hasNextStory:false})); h.player.setNarration(true); h.player.setAuto(true)
  await flush(); assert.equal(h.player.state.auto,true); assert.equal(h.player.state.phase,'playing')
  h.audio.onended(); h.tick(5000); assert.equal(h.player.state.auto,false); assert.equal(h.player.state.phase,'ended'); assert.equal(h.turns.length,0)
})
test('late audio for a page that was left cannot replace the new page audio', async () => {
  const resolve=[]; const h=harness(() => new Promise(r => resolve.push(r)))
  h.player.setNarration(true); h.player.setAuto(false); h.player.suspend(); h.player.setPage(page({id:2,position:2,text:'新的故事页'}))
  assert.equal(h.requests[0].signal.aborted,true); resolve[1](new Blob(['new'])); await flush(); const url=h.audio.src
  resolve[0](new Blob(['old'])); await flush(); assert.equal(h.audio.src,url); assert.equal(h.player.state.phase,'playing')
})
test('unmount aborts synthesis and cancels all timers', async () => {
  let resolve; const h=harness(() => new Promise(r => {resolve=r}))
  h.player.setNarration(true); h.player.setAuto(true); h.player.dispose(); resolve(new Blob(['late'])); await flush(); h.tick(60000)
  assert.equal(h.requests[0].signal.aborted,true); assert.equal(h.audio.src,''); assert.equal(h.turns.length,0)
})
test('speech failure stops automatic reading and exposes a retryable error', async () => {
  const h=harness(async () => {throw new Error('语音服务繁忙')}); h.player.setNarration(true); h.player.setAuto(true); await flush(); h.tick(60000)
  assert.equal(h.player.state.phase,'error'); assert.equal(h.player.state.auto,false); assert.equal(h.turns.length,0)
  assert.equal(h.player.state.error,'语音服务繁忙')
})
test('portrait art and text leaves belong to one narration, and advance by story', async () => {
  const h=harness(); h.player.setNarration(true); await flush(); h.player.setPage(page({position:1})); h.player.setAuto(true)
  assert.equal(h.requests.length,1); h.audio.onended(); h.tick(350); assert.deepEqual(h.turns,[true])
})
test('blank narration pages wait five seconds without contacting Azure', () => {
  const h=harness(); h.player.setPage(page({text:'  '})); h.player.setNarration(true); h.player.setAuto(true)
  assert.equal(h.player.state.phase,'empty'); h.tick(4999); assert.equal(h.turns.length,0); h.tick(1)
  assert.deepEqual(h.turns,[true]); assert.equal(h.requests.length,0)
})
