export interface RequestToken<T = unknown> {
  scope: string
  contextKey: T
  sequence: number
  epoch: number
}

export interface RequestGate {
  begin<T>(scope: string, contextKey: T): RequestToken<T>
  isCurrent<T>(token: RequestToken<T>, contextKey: T): boolean
  invalidate(): void
}

export function createRequestGate(): RequestGate
