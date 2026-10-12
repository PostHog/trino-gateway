# Design

There are two types of requests: one is a request to Trino Gateway, and the
other is a request that needs to be forwarded to Trino.

## Request forwarding

Trino Gateway forwards some pre-defined URIs automatically to Trino. You can
configure additional URIs to forward with the `extraWhitelistPaths`
configuration.

In order to support additional URIs that are only known at runtime, the
`RouterPreMatchContainerRequestFilter` is used to process every request before
the actual resource matching occurs. If the requests URI matches, the request
is forwarded to `RouteToBackendResource`.

Flow of request forwarding:

1. Validate one public `Host` and resolve its configured pool. The selector never
   uses routing-group headers, cookies, SQL properties, or external rules. Unknown
   hosts and inactive pools have no default destination.
2. For new independent work, use the pool's admitted members and obtain a durable
   admission after checking authoritative lifecycle and tenant state. A member's
   validation receipt and process identity govern admission; cached health only
   orders candidates. For existing transactions and queries, use the exact
   recorded owner incarnation in that same pool, including permitted draining
   continuations. Never recover an unknown owner through ordinary load balancing.
3. Strip client `Forwarded` and `X-Forwarded-*` metadata and construct the trusted
   coordinator-facing host, scheme, and port. Preserve the public host used for
   credential qualification. Forward the standard Trino client protocol without
   introducing a new client header or replacing coordinator authentication.
4. Verify the selected process and dispatch within the original bounded request
   budget. Candidate reselection is permitted only before dispatch. Do not retry
   a possibly accepted statement on another coordinator.
5. Observe statement and continuation responses, bind query/transaction identity,
   and record result and request obligations durably before completing the
   supported response path. Async forwarding alone is not proof of result
   delivery or completed work; unknown outcomes remain drain blockers.
6. Keep supported authentication handshakes on their recorded coordinator without
   letting an authentication cookie select query compute. Gateway UI/API login
   remains separate from pooled SQL admission, whose current Basic principal
   restriction does not establish OAuth/Bearer query support.

The asynchronous HTTP response machinery delivers requests and responses; the
PostgreSQL lifecycle ledger establishes ownership and admission. A health cache,
query-history link, local request count, or shutdown delay cannot replace that
ledger. See [routing logic](routing-logic.md), [operation](operation.md), and the
[transaction design](design/transaction-awareness.md) for boundaries and unresolved
outcome handling. These documents describe the contract, not published deployment
or execution results.
