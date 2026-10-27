import assert from 'node:assert/strict'
import test from 'node:test'
import { createRequestGate } from './request-gate.mjs'

test('a newer request supersedes an older request in the same scope', () => {
  const gate = createRequestGate()
  const oldRequest = gate.begin('sources', 1)
  const newRequest = gate.begin('sources', 1)

  assert.equal(gate.isCurrent(oldRequest, 1), false)
  assert.equal(gate.isCurrent(newRequest, 1), true)
})

test('requests from a different knowledge base cannot update current state', () => {
  const gate = createRequestGate()
  const request = gate.begin('documents', 1)

  assert.equal(gate.isCurrent(request, 2), false)
  assert.equal(gate.isCurrent(request, 1), true)
})

test('context invalidation prevents stale writes after switching away and back', () => {
  const gate = createRequestGate()
  const request = gate.begin('reviews', 1)
  gate.invalidate()

  assert.equal(gate.isCurrent(request, 1), false)
})

test('request scopes remain independent', () => {
  const gate = createRequestGate()
  const sources = gate.begin('sources', 1)
  gate.begin('documents', 1)

  assert.equal(gate.isCurrent(sources, 1), true)
})
