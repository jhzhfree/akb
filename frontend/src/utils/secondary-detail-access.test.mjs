import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { isDetailSectionRestricted } from './knowledge-detail-access.mjs'
const source = readFileSync(new URL('../components/VersionGovernancePanel.vue', import.meta.url), 'utf8')
function harness(getDetail) {
  const refs = Object.fromEntries(['selected','chain','evidence','chainRestricted','evidenceRestricted','detailError','evidenceError','chainLoading','evidenceLoading'].map(k => [k, { value: null }]))
  let calls = 0
  const methods = source.slice(source.indexOf('async function select('), source.indexOf('\nfunction applyGroup'))
    .replace('row:VersionSnapshotRow', 'row').replace('row:VersionSnapshotRow, kb:number, request:number', 'row, kb, request')
    .replace("row.objectType as 'CARD' | 'CLAIM'", 'row.objectType')
  const select = new Function(...Object.keys(refs), 'props', 'getKnowledgeObjectDetail', 'getVersionEvidence', 'isDetailSectionRestricted', `let selectionRequest = 0; ${methods}; return select`)(...Object.values(refs), {knowledgeBaseId: 1}, getDetail, async () => { calls++; return {data:{data:{items:[{evidenceCode:'e'}]}}} }, isDetailSectionRestricted)
  return {refs, select, calls: () => calls}
}
const row = { objectType:'CARD', objectCode:'a' }
test('known omitted sections clear stale data and prevent independent evidence call', async () => {
  const h = harness(async () => ({data:{data:{object:{objectCode:'a'},detailsRestricted:true, omittedSections:['versions','evidence'],versions:[{secret:1}]}}}))
  h.refs.chain.value = [{old:1}]; h.refs.evidence.value = [{old:1}]
  await h.select(row)
  assert.equal(h.calls(),0); assert.deepEqual(h.refs.chain.value,[]); assert.deepEqual(h.refs.evidence.value,[])
  assert.equal(h.refs.chainRestricted.value,true); assert.equal(h.refs.evidenceRestricted.value,true)
})
test('legacy detail without marker retains versions and independent evidence', async () => {
  const h = harness(async () => ({data:{data:{object:{objectCode:'a'},versions:[{versionNo:1}]}}}))
  await h.select(row)
  assert.equal(h.calls(),1); assert.equal(h.refs.chain.value.length,1); assert.equal(h.refs.evidence.value.length,1)
})
test('failed detail never falls back to unvalidated ancillary endpoints', async () => {
  const h = harness(async () => {throw new Error('403')})
  await h.select(row)
  assert.equal(h.calls(),0); assert.match(h.refs.detailError.value,/无法确认/)
})
test('late detail from prior selection cannot overwrite restricted selection', async () => {
  let resolve
  const pending = new Promise(r => {resolve = r})
  let n=0
  const h = harness(() => ++n === 1 ? pending : Promise.resolve({data:{data:{object:{objectCode:'a'},detailsRestricted:true}}}))
  const first = h.select(row)
  await h.select({...row, objectCode:'b'})
  resolve({data:{data:{object:{objectCode:'a'},versions:[{old:1}]}}}); await first
  assert.equal(h.calls(),0); assert.deepEqual(h.refs.chain.value,[]); assert.equal(h.refs.evidenceRestricted.value,true)
})
test('review source wiring hides omitted evidence and replaces false zero labels', () => {
  const review = readFileSync(new URL('../components/ReviewPanel.vue', import.meta.url), 'utf8')
  assert.ok(review.includes("isDetailSectionRestricted(props.reviewDetail, 'evidence')"))
  assert.ok(review.includes('if(reviewEvidenceRestricted.value)return []'))
  assert.ok(review.includes('selectedReviewCode.value && !reviewEvidenceRestricted.value'))
  assert.ok(review.includes("reviewEvidenceRestricted ? '因权限未加载' : reviewEvidence.length"))
  assert.ok(review.includes('因权限未加载证据'))
})

test('null, empty and objectless details never trigger evidence endpoint', async () => {
  for (const detail of [null, {}, {versions:[]}, {object:{}}]) {
    const h = harness(async () => ({data:{data:detail}}))
    await h.select(row)
    assert.equal(h.calls(),0); assert.match(h.refs.detailError.value,/无法确认/)
  }
})
function reviewHarness() {
  const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
  const methods = app.slice(app.indexOf('let reviewSelectionRequest = 0'), app.indexOf('async function adjudicateConflicted'))
    .replace('id:number','id').replace('row:ReviewRow','row')
    .replace("type as 'CARD'|'CLAIM'",'type').replace(' as unknown as Record<string,unknown>','')
  const refs = Object.fromEntries(['selectedReview','reviewDetail','selectedConflictId','conflictDetail','conflictDetailLoading','knowledgeBaseId'].map(k => [k,{value:null}]))
  const pending = []
  const api = () => new Promise(resolve => pending.push(resolve))
  const actions = new Function(...Object.keys(refs),'getConflictTargets','getKnowledgeObjectDetail','unwrap','err', `${methods}; return {selectReview,selectConflict,clearReviewSelection}`)(...Object.values(refs),api,api,v=>v,()=>{})
  return {refs,pending,...actions}
}
test('review selection clears old evidence and rejects late response after switching', async () => {
  const h = reviewHarness(); h.refs.reviewDetail.value={evidence:['old']}
  const first=h.selectReview(row)
  assert.equal(h.refs.reviewDetail.value,null)
  const second=h.selectReview({...row,objectCode:'b'})
  h.pending[1]({detailsRestricted:true}); await second
  h.pending[0]({evidence:['old']}); await first
  assert.deepEqual(h.refs.reviewDetail.value,{detailsRestricted:true})
})
test('review close/reset invalidates pending detail and conflict selection callbacks', async () => {
  const h=reviewHarness(); const first=h.selectReview(row)
  h.clearReviewSelection(); h.pending[0]({evidence:['old']}); await first
  assert.equal(h.refs.reviewDetail.value,null)
  const conflict=h.selectConflict(1); const review=h.selectReview(row)
  h.pending[1]({sides:['old']}); await conflict
  assert.equal(h.refs.conflictDetail.value,null)
  h.pending[2]({detailsRestricted:true}); await review
  assert.equal(h.refs.reviewDetail.value.detailsRestricted,true)
})
test('knowledge-base change and adjudication reset review selection', () => {
  const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
  assert.match(app,/v=>\{requestGate\.invalidate\(\);\s*clearReviewSelection\(\)/)
  assert.match(app,/ElMessage.success\('裁决已提交'\);\s*clearReviewSelection\(\)/)
  assert.match(app,/ElMessage.success\('冲突裁决已提交'\);\s*clearReviewSelection\(\)/)
})

test('reset after conflict adjudication prevents pending conflict from repopulating detail', async () => {
  const h=reviewHarness()
  const pending=h.selectConflict(42)
  h.clearReviewSelection()
  h.pending[0]({sides:[{objectCode:'old'}]}); await pending
  assert.equal(h.refs.selectedConflictId.value,null)
  assert.equal(h.refs.conflictDetail.value,null)
  assert.equal(h.refs.conflictDetailLoading.value,false)
})
