import { readFileSync, writeFileSync } from 'node:fs'

// Upstream 2.0.7 destroy() removes DOM/listeners but leaves its RAF loop running.
// Keep the pinned library's renderer and animation; only cancel that loop on teardown.
const file = new URL('../node_modules/page-flip/dist/js/page-flip.module.js', import.meta.url)
let source = readFileSync(file, 'utf8')
const patches = [
  ['start(){this.update();const t=e=>{this.render(e),requestAnimationFrame(t)};requestAnimationFrame(t)}',
    'start(){this.update();this.stopped=false;const t=e=>{if(this.stopped)return;this.render(e);this.frameId=requestAnimationFrame(t)};this.frameId=requestAnimationFrame(t)}stop(){this.stopped=true;cancelAnimationFrame(this.frameId)}'],
  ['destroy(){this.ui.destroy(),this.block.remove()}',
    'destroy(){this.render.stop();this.ui.destroy(),this.block.remove()}'],
]
for (const [before, after] of patches) {
  if (source.includes(after)) continue
  if (source.split(before).length !== 2) throw new Error('StPageFlip changed: review its teardown patch before upgrading.')
  source = source.replace(before, after)
}
writeFileSync(file, source)
