# Service-credential admission

Service grants use the existing tenant-principal publication and admission protocol.
For a qualified grant named `warehouse-one.svc_<24 lowercase hex>`, the Gateway looks up the already-published bare root principal `warehouse-one` in `pool_tenant_principal`.
That stored mapping determines the tenant; the Gateway never infers an organization from a hostname, database label, or grant ID.
No per-grant publication, extra namespace field, schema migration, or new binding revision is needed.
Enabling or disabling service authentication does not change the controller's principal projection or tenant publication lifecycle.

The database label follows `[a-z0-9]([a-z0-9-]*[a-z0-9])?`, with a maximum length of 63 characters.
Only that label followed by `.svc_` and exactly 24 lowercase hexadecimal characters receives root-principal lookup.
Other identities, including persistent logins such as `warehouse-one.svc_reporter`, retain exact-principal lookup.
Malformed grant names receive no root-principal lookup.
Unpublished identities and grants belonging to pending or revoked tenants reject new independent work.

Admission combines the root mapping with any explicitly published exact grant mapping.
Both mappings must identify the same single admitted tenant; conflicting owners fail closed even when both tenants are admitted.
The existing pool lock protects principal replacement and query admission.
The root lookup uses the existing principal index and adds no per-grant writes.
Normal member admission, drain barriers, existing query bindings, and transaction ownership continue to apply.

## Authentication boundary

This is a deny-only routing restriction.
The coordinator still authenticates the service grant and enforces its organization permissions, expiry, and revocation.
A published root principal alone grants no Trino access.

Keep the same credential ID and secret for the lifetime of a query or transaction.
The Gateway includes the Basic credential in its existing ownership fingerprint.
Use the control plane's expiry-only renewal (`rotate_secret: false`) for active service grants.
Rotating the secret mid-query changes ownership and rejects subsequent continuation requests.
No username-only ownership exception is introduced.

## Deployment

Deploy this Gateway version to every serving replica before enabling service-credential clients.
Principal publication requests, responses, and recorded idempotent-step results retain their existing schema, so old replicas can still decode and replay those steps during a rolling deployment.
Older Gateway versions cannot admit dynamically minted grants through the tenant gate.
Next deploy the controller and coordinator support, then enable clients after the entire route supports it.
Persistent-user authentication remains configured alongside service authentication.

Before rollback, stop service-credential clients and allow their work to finish.
No principal republication or namespace cleanup is required when rolling back authentication support or the Gateway.

## Local regression tests

`TestPoolStore` verifies routing through existing root bindings, strict old-replica step decoding, idempotent replay, replacement, malformed identities, cross-tenant conflicts, and concurrent root claims against a shared PostgreSQL database.
`TestPooledRouting` and `TestTransactionStore` retain coverage of query ownership, continuations, and drain behavior.
Use the local test database variables documented in [transaction awareness](design/transaction-awareness.md#testing).
The tests create and remove only randomly named schemas.
