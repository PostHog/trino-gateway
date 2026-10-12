# Design

> **Routing modes:** Official upstream routing functionality is retained. With
> `transactionAwareness.enabled: false` and
> `transactionAwareness.pool.enabled: false` (the defaults), the stock routing
> behavior described below applies. When both flags are `true`, stock routing
> selectors, routing rules, route cookies, and default-group fallback are
> **bypassed**, not removed. Use [pooled hostname routing](pooled-routing.md)
> instead; a pool failure never falls back to stock routing.

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

1. Determine to which Trino cluster a query should be routed to.
2. Prepare a request to send to Trino by adding `Via` headers. By default, most
   headers are forwarded to Trino unmodified, and the Gateway appends its own
   `X-Forwarded-*` proxy headers. However, when `routing.forwardedHeadersEnabled`
   is configured to `false` in `ProxyRequestHandler`, the Gateway explicitly
   strips all client-provided legacy (`X-Forwarded-*`) and modern (`Forwarded`)
   headers case-insensitively, and skips adding its own proxy headers. This
   allows the Trino backend to respond with `nextUri` values that point directly
   to the backend instead of routing back through the load balancer or gateway.
   `routing.forwardedProto` and `routing.forwardedPort` replace the connection
   scheme and port in the Gateway's own `X-Forwarded-Proto` and
   `X-Forwarded-Port` headers, for deployments where a load balancer in front
   of the Gateway terminates TLS.
3. Some request URI require special handling. For example, a
   request which submit a new query, Trino Gateway retrieves the queryId from the
   response from Trino. Some requests to the web UI require setting a session
   cookie to ensure OIDC works. These are done by chaining asynchronous
   operations using `Future`.
4. The execution of requests to Trino and the response to the client are handled
   by `airlift.jaxrs.AsyncResponseHandler`.
