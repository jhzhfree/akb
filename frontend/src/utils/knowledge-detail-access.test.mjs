import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { isDetailSectionRestricted, detailSectionCount } from './knowledge-detail-access.mjs'

const sections = ['evidence', 'conflicts', 'reviews', 'assessments', 'versions', 'graph']
test('all server-omitted sections show permission status instead of zero', () => {
  const detail = { detailsRestricted: true, omittedSections: sections }
  for (const section of sections) {
    assert.equal(isDetailSectionRestricted(detail, section), true)
    assert.equal(detailSectionCount(detail, section, 0), '因权限未加载')
  }
})
test('ordinary and legacy details retain true empty and populated counts', () => {
  for (const detail of [null, {}, { detailsRestricted: false }]) {
    assert.equal(isDetailSectionRestricted(detail, 'evidence'), false)
    assert.equal(detailSectionCount(detail, 'evidence', 0), 0)
    assert.equal(detailSectionCount(detail, 'evidence', 4), 4)
  }
})
test('partial omission does not hide loaded sections', () => {
  const detail = { detailsRestricted: true, omittedSections: ['versions'] }
  assert.equal(isDetailSectionRestricted(detail, 'versions'), true)
  assert.equal(isDetailSectionRestricted(detail, 'evidence'), false)
  assert.equal(detailSectionCount(detail, 'evidence', 3), 3)
})
test('explicit omission is respected even without global marker', () => {
  assert.equal(isDetailSectionRestricted({ omittedSections: ['graph'] }, 'graph'), true)
})
test('restricted flag without list conservatively marks ancillary sections', () => {
  for (const section of sections) {
    assert.equal(isDetailSectionRestricted({ detailsRestricted: true }, section), true)
    assert.equal(isDetailSectionRestricted({ detailsRestricted: true, omittedSections: [] }, section), true)
  }
})
test('workspace connects all six section guards and retains authorized main content', () => {
  // Source wiring check, not a browser or rendered-component test.
  const source = readFileSync(new URL('../components/KnowledgeDetailWorkspace.vue', import.meta.url), 'utf8')
  for (const section of sections) assert.ok(source.includes(`restricted('${section}')`))
  assert.ok(source.includes('{{ content }}'))
  assert.ok(source.includes('当前正文可见；部分附属信息因权限未加载'))
  assert.ok(source.includes('<VersionDiffPanel v-else'))
})
