# Service-credential admission

A controller can publish one service-credential namespace alongside a tenant's persistent principals:

```json
{
  "operationId": "publish-example",
  "stepId": "principals",
  "controllerEpoch": 1,
  "revision": "revision-2",
  "principals": ["warehouse-one", "warehouse-one.root"],
  "service_principal_prefix": "warehouse-one.svc_"
}
```

Use the existing tenant-principal publication endpoint and administration authentication.
The namespace is optional.
An omitted or null namespace removes the previous namespace when the publication replaces the tenant's mapping.
The write response, read response, and idempotent replay include `service_principal_prefix`.
Persistent-principal counts and hashes continue to describe the explicit list only.
The controller must include the namespace in its publication revision and idempotency payload.

The database label follows `[a-z0-9]([a-z0-9-]*[a-z0-9])?`, with a maximum length of 63 characters.
A declared namespace matches only that label followed by `.svc_` and exactly 24 lowercase hexadecimal characters.
The Gateway resolves the matched namespace through the controller's explicit tenant mapping.
It does not infer the tenant from the hostname, label, or grant ID.
Unpublished, malformed, pending, and revoked namespaces reject new independent work.

A namespace and its explicit principals are replaced in one transaction under the existing pool admission lock.
The database enforces one owner per namespace and one namespace per tenant in each pool.
Conflicts with another tenant's explicit service principal are rejected in either publication order.
The namespace adds no per-grant writes, and admission uses indexed lookups in the shared database.
Normal member admission, drain barriers, existing query bindings, and transaction ownership continue to apply.

## Authentication boundary

This is a deny-only routing restriction.
The coordinator still authenticates the service grant and enforces its organization permissions, expiry, and revocation.
A published namespace alone grants no Trino access.

Keep the same credential ID and secret for the lifetime of a query or transaction.
The Gateway includes the Basic credential in its existing ownership fingerprint.
Use the control plane's expiry-only renewal (`rotate_secret: false`) for active service grants.
Rotating the secret mid-query changes ownership and rejects subsequent continuation requests.
No username-only ownership exception is introduced.

## Deployment

Apply PostgreSQL migration V12 and deploy this Gateway version to every serving replica before a controller publishes service namespaces.
Existing publication requests without the optional field retain their previous behavior.
Older Gateway versions ignore the field and cannot admit these dynamically minted principals through the tenant gate.
Next deploy the controller and coordinator support, then enable service-credential clients after confirming the entire route supports it.
Persistent-user authentication remains configured alongside service authentication.

Before rollback, stop service-credential clients and allow their work to finish.
Clear the namespace with a normal principal publication that omits the field, then roll back the controller or Gateway.
Keep V12 in the schema; deleting admission state while requests are active is not a supported rollback.

## Local regression tests

`TestPoolStore` verifies namespace publication, API echo, idempotent replay, replacement, malformed identities, cross-tenant conflicts, and concurrent claims against a shared PostgreSQL database.
`TestPooledRouting` and `TestTransactionStore` retain coverage of query ownership, continuations, and drain behavior.
Use the local test database variables documented in [transaction awareness](design/transaction-awareness.md#testing).
The tests create and remove only randomly named schemas.
