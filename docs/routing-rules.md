# Hostname routing

Gateway selects new independent work using only one `Host` header. It ignores
`X-Trino-Routing-Group`, `Forwarded`, `X-Forwarded-*`, credentials, and query
properties for pool selection. Authentication and authorization stay on Trino.
Existing transactions and query continuations must retain their durable owner;
hostname selection must not redistribute in-flight work.

```yaml
routing:
  hostnameSuffixToPool:
    dw.example.test: pool-one
  hostnameToPool:
    warehouse-two.dw.example.test: pool-two
```

Exact hostname entries take priority. A suffix entry matches exactly one DNS
label before the suffix, such as `warehouse-one.dw.example.test`. It does not
match the apex `dw.example.test` or `a.b.dw.example.test`. Add an explicit exact
entry if an apex or operational hostname should route to a pool.

Only use a suffix mapping when all tenants under that domain share its pool.
A database-name label is not a pool identifier. Where tenants have distinct
durable placements, generate exact hostname entries from the control plane's
stored warehouse-to-pool assignments. Never infer placement from database-name
spelling, credentials, SQL, or an unverified request header. Publish a consistent
configuration across replicas before admitting new work at a new placement.

The selector lowercases ASCII DNS names and removes one root-zone trailing dot.
A request may include a decimal port from 1 through 65535; the port does not
change pool selection. Configuration keys cannot include ports or wildcards.
Malformed names, empty pool identifiers, and duplicate canonical configuration
keys fail provider creation. Configuration is immutable for that selector;
changes require replacing the running configuration, not refreshing a rules file.

Missing, duplicate, or malformed Host values return HTTP 400. An otherwise valid
hostname without a matching mapping returns HTTP 421. Empty maps deny all
selection. There is no header, default-group, file-rule, or external-router
fallback. Legacy `routingRules` configuration and rule management endpoints have
been removed.

## Proxy trust boundary

The public proxy must preserve the routed Host. Do not enable server-side
forwarded-header processing for untrusted callers: it can replace the hostname
before the selector sees the request. Discard client `Forwarded` and
`X-Forwarded-*` values and synthesize coordinator-facing forwarding metadata from
the same validated public host and fixed TLS termination configuration. Restrict
direct coordinator access. Host remains client-controlled routing information,
not authentication evidence. Gateway's tenant-principal admission restriction
must continue to match the coordinator's own qualification and authorization.

See the reproducible regression tests in `TestHostnameRoutingGroupSelector`.
Run them with Java 25:

```shell
./mvnw -pl gateway-ha -am -Dtest=TestHostnameRoutingGroupSelector \
  -Dsurefire.failIfNoSpecifiedTests=false -Dskip.pnpm test
```

## Upgrade boundary

This is a pooled-only configuration. PostgreSQL, a shared identity key, a
separate administration token, and the pooled lifecycle are required. Disabling
transaction awareness or the pool is rejected at startup. Each coordinator must
expose the process identity used by the pool protocol.

Replace legacy routing rules and route-cookie settings before starting this
version. Remove default-group and router-module settings. Register and certify
pool members through the pool API. An active registration alone is not eligible.

Use a coordinated Gateway upgrade. Complete existing work before replacing the
old routing configuration. Ownership now includes the canonical request host;
old credential bindings are not interchangeable with new bindings. Do not mix
old and new Gateway routing versions or move a tenant's host mapping while its
work is in flight. Never delete unresolved ledger rows to permit an upgrade.
Historical Flyway migrations and pool lock rows remain required.
