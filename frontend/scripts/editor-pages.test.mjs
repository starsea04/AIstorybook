import test from 'node:test'
import assert from 'node:assert/strict'
import { editablePage, activeImageTask, mergePolledImages, mergeSavedImages } from '../src/lib/editorPages.ts'

test('completed tasks do not lock a page with stale GENERATING status',()=>{
 const page={id:7,pageNo:1,imageStatus:'GENERATING',imageUrl:'/old.png',storyText:'故事'}
 assert.equal(activeImageTask(page,[{id:1,pageId:7,status:'SUCCESS'}]),undefined)
 assert.equal(activeImageTask(page,[{id:2,pageId:7,status:'PROCESSING'}]).id,2)
})
test('polling updates the image without discarding typing or changing page order',()=>{
 const page={id:7,pageNo:2,storyText:'正在输入的故事',scenePrompt:'new scene',imageStatus:'GENERATING'}
 const added={pageNo:1,storyText:'新页面'};const local=[added,page]
 mergePolledImages(local,[{id:7,pageNo:1,storyText:'旧文字',scenePrompt:'old',imageStatus:'SUCCESS',imageUrl:'/new.png'}])
 assert.equal(page.storyText,'正在输入的故事');assert.equal(page.scenePrompt,'new scene');assert.equal(page.pageNo,2)
 assert.equal(page.imageStatus,'SUCCESS');assert.equal(page.imageUrl,'/new.png');assert.equal(local[0],added)
})
test('saving excludes all server-owned image state',()=>{
 const draft=editablePage({id:7,pageNo:1,storyText:'text',imageUrl:'/old.png',imageStatus:'GENERATING',imageError:'old'})
 assert.equal('imageUrl' in draft,false);assert.equal('imageStatus' in draft,false);assert.equal('imageError' in draft,false)
})
test('new page receives its server id without overwriting subsequent edits',()=>{
 const page={pageNo:1,storyText:'edited while saving'};const added={pageNo:2,storyText:'added while saving'}
 mergeSavedImages([page,added],[page],[{id:99,pageNo:1,storyText:'old',imageStatus:'READY'}])
 assert.equal(page.id,99);assert.equal(page.storyText,'edited while saving');assert.equal(added.id,undefined)
})
test('save results do not resurrect a page deleted during the request',()=>{
 const removed={id:7,pageNo:1,storyText:'removed'};const remaining={id:8,pageNo:1,storyText:'keep'}
 mergeSavedImages([remaining],[removed,remaining],[{id:7,pageNo:1,storyText:'old'},{id:8,pageNo:2,storyText:'older'}])
 assert.equal(remaining.id,8);assert.equal(remaining.storyText,'keep');assert.equal(remaining.pageNo,1)
})
test('late text-save response cannot revert a completed generation',()=>{
 const page={id:7,pageNo:1,storyText:'latest',imageUrl:'/new.png',imageStatus:'SUCCESS'}
 mergeSavedImages([page],[page],[{id:7,pageNo:1,storyText:'old',imageUrl:'/old.png',imageStatus:'GENERATING'}])
 assert.equal(page.imageUrl,'/new.png');assert.equal(page.imageStatus,'SUCCESS')
})
