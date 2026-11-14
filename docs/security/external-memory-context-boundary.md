# External memory-context authorization boundary

## Confirmed issue

`ExternalKnowledgeFacade.memoryContext` previously called `recallByIdentity` directly. Unlike the public memory recall API, this skipped both `ExternalAccessService.assertScope(MEMORY_READ)` and the exact `AGENT` scope `READ` grant. It also converted runtime exceptions into an empty successful context. Non-delegated external retrieval and SSE could therefore consume memory without independent memory authorization.

## Enforcement

External context injection now uses `AgentMemoryService.recallContext`:

1. Require enabled memory service, usable credential, persisted credential ID, active bound principal. Missing legacy principal metadata fails closed rather than guessing identity.
2. Preserve the existing delegated-user rejection; it remains before memory access in the facade as well.
3. Invoke the existing unified `assertScope(MEMORY_READ)` implementation.
4. Resolve namespace and Agent identity exclusively from the server-authenticated key; require a nonempty Agent identity.
5. Apply the same `requireSharedScopeAccess` used by `recall`, with exact namespace, `AGENT`, actual Agent code, and `READ`. Context injection uses the helper in strict principal-only mode. Legacy key-grant fallback is disabled because stale key grants cannot be proven to be removed on principal revocation; a different Agent grant or residual key grant cannot authorize this identity. For direct recall/write/delete operations using this shared helper, a bound principal is also authoritative: key fallback is allowed only for truly unbound legacy credentials.
6. Only then recall/render context. Authorization, disabled-service and backend failures propagate. An actual authorized empty result may still yield empty context, but failure cannot become successful empty content.

Authentication remains at external ingress: `ApiKeyService.authenticate` loads the stored credential, checks usability and refreshes PAT owner permissions; workload/delegated proofs are verified by `ExternalAccessService`. This change does not introduce another authentication protocol or trust client-supplied identities.

## Credential behavior

- Legacy/service key: requires explicit `MEMORY_READ`, no read-policy denial, active principal and exact memory grant. Knowledge `READ`/`WRITE` do not imply memory access.
- Personal PAT: same requirements plus the existing active owner check. Knowledge-only PATs cannot inject memory.
- APPLICATION_WORKLOAD V2: memory is currently forbidden by `ApiKey.hasScope`, even with valid workload proof. Discovery credentials without proof are also denied by unified `assertScope`.
- Delegated user: remains unsupported and denied, never reads a shared Agent memory.
- Embed requests resolved to their backing key inherit these exact restrictions.
- `MEMORY_MANAGE` alone currently does **not** pass `hasScope(MEMORY_READ)` due its initial explicit-scope guard. This patch deliberately preserves the existing unified semantics, not silently expanding a composite capability.

## Offline regression scope

`ExternalMemoryScopeBoundaryTest` uses real facade, memory and capability enforcement with mocked persistence/AI. It covers knowledge-only scopes, disabled memory policy, workload with/without identity, missing/inactive principal, inactive PAT owner, exact Agent grants, principal grants and residual-key-grant denial after revocation, delegated denial and propagated backend failure. `DelegatedRetrievalBoundaryTest` must also remain green. No production identity, database, model service or external connector is contacted.

## Migration compatibility

V72 retained legacy key grant rows, while current grant editors replace principal grants only. Falling back to those rows for a bound principal could resurrect a revoked shared-scope grant. The shared authorization helper now prohibits that fallback whenever `principalId` is present, including direct memory APIs. Operators must explicitly review and regrant any still-required access at principal level before rollout; do not blindly copy historical grants, which could reintroduce deliberately revoked permissions. Missing grants fail closed. Truly unbound legacy credentials retain the prior direct-API compatibility path, but cannot use external memory context. This is not a claim that every legacy identity flow has been retired.

## Freshness boundary

These guarantees apply at the normal authenticated HTTP entry points: each request reloads the credential and joined principal status from the database (embed requests also reload their backing key). `recallContext` requires that authenticated principal snapshot to be ACTIVE. Memory grant checks query current grant state on each read, but their SQL does not independently rejoin principal status. This is request-boundary revocation enforcement, not a transactionally atomic guarantee against principal revocation occurring between authentication and the subsequent memory query. Internal callers must not reuse an old authenticated key snapshot across requests.
