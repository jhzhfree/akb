# External trace replay: fail-closed compatibility notice

`GET /api/v1/external/queries/{queryId}` no longer returns stored trace content.
Authenticated, correctly scoped requests reaching the handler are rejected with a
security error (403); earlier credential, scope, quota or knowledge-base failures
retain their existing behavior. This applies to ordinary service keys, delegated
keys, workload credentials and keys resolved from embed tokens.

A query ID is not an access grant. The former read used only `query_id` and could
return another caller's answer, selected context, citations and diagnostic payloads.
Current trace metadata does not establish all necessary current content permissions.
The endpoint therefore performs no trace read until owner, knowledge-base and
content authorization are implemented. Clients must stop relying on external replay;
query/search/stream response contracts are unchanged.

The console routes are not an alternative external replay API. Their separate
security patch restricts trace and qualification reads to authenticated,
unrestricted administrators until record-level authorization is available.
Knowledge-base history is not restricted to administrators by that patch and
still requires a separate content-ACL review. See
that patch's tests for the internal boundary; external tests do not establish it.
The service catalog and console laboratory label external replay as disabled in
the existing summary field; endpoint metadata keys and paths remain unchanged.
This is a text label only: the laboratory action remains clickable and can send
a request, which the server rejects. The UI control itself is not disabled.

Offline regression coverage: `ExternalTraceBoundaryTest` checks ordinary,
delegated and workload rejection without any facade access, and preserves earlier
credential and knowledge-base validation failures. These unit tests do not
prove deployment authentication, internal authorization or live database behavior.
