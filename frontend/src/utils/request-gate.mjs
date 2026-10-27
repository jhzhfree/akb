/**
 * Keeps only the newest request per scope and knowledge-base context eligible
 * to update UI state. Invalidating on context changes also handles switching
 * away and back before an older request has completed.
 */
export function createRequestGate() {
  let epoch = 0
  const sequences = new Map()

  return {
    begin(scope, contextKey) {
      const sequence = (sequences.get(scope) ?? 0) + 1
      sequences.set(scope, sequence)
      return { scope, contextKey, sequence, epoch }
    },
    isCurrent(token, contextKey) {
      return token.epoch === epoch
        && token.contextKey === contextKey
        && sequences.get(token.scope) === token.sequence
    },
    invalidate() {
      epoch += 1
      sequences.clear()
    },
  }
}
