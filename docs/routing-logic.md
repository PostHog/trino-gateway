# Routing logic

## Hostname-only pool selection

Every proxied request must present one valid `Host` header that identifies a
configured pool. [Hostname routing](routing-rules.md) describes exact-host and
single-tenant-label suffix mappings, normalization, and rejected authorities.
Host chooses placement; it never authenticates a tenant. A database-name label
must map to the control plane's durable pool assignment, not an inferred pool.

There is no default-group fallback, routing-rule engine, external selector,
client `X-Trino-Routing-Group` selection, or cookie-based route selection. A
missing pool or a pool without eligible members fails closed. Legacy backend
activation and a healthy cache entry cannot bypass pool admission.

## New independent statements

For a statement without an existing transaction, Gateway obtains the pool's
current admitted members from PostgreSQL. Member activation requires the pool
lifecycle's validation receipt, required revisions, and observed coordinator
process identity. Gateway checks current pool mode, membership, phase, and the
tenant admission restriction inside the admission transaction before dispatch.
The validation certificate authorizes lifecycle admission; it is not a TLS
certificate or a substitute for credential verification.

Candidate ordering is shuffled, then may prefer members with healthy cached
status. Health is advisory: it cannot admit an uncertified member or reject an
otherwise eligible member merely because its cached status is unknown or stale.
Bounded reselection is allowed only before dispatch, within the original request
deadline. After dispatch, Gateway does not replay or move a statement whose
acceptance or outcome is uncertain.

## Durable transaction and query ownership

PostgreSQL records the coordinator incarnation that owns a transaction or query,
together with owner identity and admission/result obligations. Statements in an
existing transaction and subsequent result polls, metadata requests, or
cancellations use that durable binding. The request hostname must identify the
same pool as the binding; changing Host or sending a routing-group header cannot
move existing work.

Unknown transaction ownership is rejected. A known query or transaction can
continue on its draining owner while lifecycle admission permits it, but cannot
be moved to a newly active member or a restarted coordinator with another
incarnation. Database unavailability, an identity mismatch, or an uncertain
outcome fails closed rather than choosing an arbitrary healthy coordinator.

A result URI's query identifier locates the binding; it is not proof that the
caller owns it. Gateway enforces the supported owner credentials or recorded
result capability for each operation. A client without credentials may use only
a continuation capability already recorded for that query, not arbitrary query
metadata or cancellation by identifier.

## Authentication protocol affinity

Gateway's own UI/API OAuth or form authentication is distinct from the Trino
query protocol. Browser session cookies may still be needed for authentication;
they do not choose a compute pool or override durable query ownership.

Where Trino OAuth2 token-exchange transport is enabled, the CLI/driver polls
`/oauth2/token/{authId}`, the browser visits
`/oauth2/token/initiate/{authIdHash}`, and the identity provider returns to
`/oauth2/callback`. That coordinator-local handshake must stay on the coordinator
that issued its challenge. Gateway's shared handshake binding is constrained to
the Host-selected pool; an unavailable or invalid binding requires
reauthentication, not an arbitrary cookie-selected coordinator.

This transport affinity does **not** provide OAuth/Bearer query admission through
the current pooled tenant adapter. That adapter derives one Basic credential
principal, qualified exactly as the coordinator will qualify it, and performs a
deny-only lookup in controller-published tenant bindings. The coordinator verifies
the credential and authorization. UI OAuth login, a Bearer claim, or handshake
pinning does not supply a verified Basic principal to that restriction.

## Forwarding URLs and trust

The internal member URL is the dispatch destination; an external URL can provide
links to the coordinator UI. Neither URL is a routing selector. Preserve the
public Host and regenerate coordinator-facing forwarding metadata from trusted
configuration, never client `Forwarded` or `X-Forwarded-*` values. Result
continuations must return through Gateway so durable ownership and result
obligations remain observable. See [security](security.md) and
[operation](operation.md).
