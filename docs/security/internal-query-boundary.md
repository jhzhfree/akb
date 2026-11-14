# Internal query boundary hardening

## Verified boundary

`KnowledgeBaseAccessInterceptor` reads URI variables and query/form parameters, not JSON bodies. `PermissionInterceptor` has no retrieve/answer/trace rule. Thus these checks must be explicit in `KnowledgeQueryController`.

- `/retrieve`, `/answer` and `/query` now require a positive integral `knowledgeBaseId`, authorize read on exactly that ID, and forward it as a Long. For the Map-based `/retrieve` and `/answer` routes, decimal JSON values, overflow, missing values and structured values are rejected before service calls. `/query` retains its existing Long DTO deserialization: Jackson may coerce a fractional number before the controller runs. This patch authorizes and forwards that same resulting positive ID, but does not introduce strict JSON numeric parsing for the DTO route.
- A current ACTIVE authenticated user with an ID is required. Ordinary users must have a server ACL subject matching their login userCode; an overriding `X-Authenticated-User-Id` cannot select another subject. Resolver lookup reads ACTIVE subject/role/department tags directly and does not depend on CLIENT/SHADOW enforcement mode.
- Caller `permissionTags` is discarded. The same server ACL resolver used by `/query` supplies the effective tags; `[]` stays explicit (public only), null is accepted only for an active ADMIN or KNOWLEDGE_ADMIN, matching the resolver's explicit existing unrestricted-role contract. Ordinary users with unresolved/null ACL fail closed. Snake-case lookalikes are removed. All other existing Map options remain compatible.
- JSON duplicate keys are already collapsed by Jackson before this boundary; the single resulting canonical ID is both authorized and forwarded. No raw JSON is forwarded or re-parsed downstream.
- `/trace/{qid}` and `/answer-qualification/{qid}` expose raw bundles without a reliable owner/current source-ACL projection. They now reject all but an active, identified platform ADMIN with unrestricted resolver output. KNOWLEDGE_ADMIN is not globally authorized to read all KBs and is rejected. Anonymous/auth-disabled access is rejected, including existing deployments that previously relied on it.
- `AuthInterceptor` obtains AuthContext from `AuthService.authenticate`, which fetches the current session and current user from DB, checks session expiry/status and user ACTIVE status on every request. It does not trust a request-provided role. The controller also checks ACTIVE status and user ID.

## Compatibility / limits

Normal query results remain available; non-platform-admin trace and qualification detail buttons now receive 403 until a persisted ownership plus current source-ACL projection is implemented. Same-KB access is deliberately insufficient. No persistence/schema changes, credential changes or external AI calls are included.

This is not a claim that all internal read surfaces are secured. Qualification summaries, object evidence/history projections and resolver deployment trust settings require separate review. The shared `AclContextResolver` itself is unchanged. These three routes reject unresolved ACL for ordinary users, including configurations disabling identity DB resolution; callers must authenticate and bind the ACL subject. Other controllers using that resolver require separate review and are not covered by this patch. `/query` now uses the same guard, ignoring its legacy permission-tags header while keeping the HTTP signature compatible.

`InternalQueryBoundaryTest` exercises the controller using mocked dependencies only, without database, network or real AI providers. It is not an end-to-end authentication test.

## Query-route regression

The prior `/query` route called `resolve(permissionTagsHeader)` directly. `currentSubjectCode` prioritizes X-Authenticated-User-Id over the signed-in user code, so a caller could select another subject. The shared guard now rejects that mismatch before querying ACL tags. Regression tests use the real resolver plus servlet request context to check the impersonation header and identity-DB-disabled/trusted-header configuration, in addition to mocked controller tests. These are offline tests, not live deployment validation.

## Private memory boundary

`includeMemory=true` forces the ACL subject to match the active login userCode for every role, including ADMIN and KNOWLEDGE_ADMIN. Platform-wide knowledge permissions do not authorize reading another user’s personal memory. A missing/blank userCode or overriding authenticated-user header is rejected before the service can recall memory. Existing administrator queries without memory retain the prior knowledge ACL contract. Tests cover all roles with a real resolver/header mismatch, administrators with no usable subject, and the own-memory success path.

## Historical answers

`/traces/{kb}` returns stored original queries and generated answers, with no current source-level ACL projection. It now shares the raw-trace guard: only an identified, currently ACTIVE platform ADMIN with an unrestricted resolver result may read it. The route additionally validates the KB ID and calls `assertRead` explicitly, retaining disabled/archived KB denial rather than relying only on the interceptor. Anonymous callers and all non-platform-admin roles, including KNOWLEDGE_ADMIN and the original query author, receive 403 until ownership/current-content-ACL filtering exists. Ordinary users' query-history views will therefore stop returning history; this is an intentional fail-closed compatibility restriction, not a completed per-user history feature. Administrator pagination and the legacy `limit` fallback remain unchanged. Qualification summaries remain outside this change.
