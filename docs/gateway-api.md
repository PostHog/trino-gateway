# Gateway API

The REST API for Trino Gateway can be used to update routing configuration for
the Trino clusters. Note that the API calls do not perform actions on the
clusters themselves.

The example commands are for a Trino Gateway server running at
`http://localhost:8080`.

If there are duplicate `proxyTo` URLs in the configuration, the `Name` in the
**Query History** page of the UI might not show correctly.

## Create a Trino cluster

```shell
curl -X POST http://localhost:8080/gateway/backend/modify/add \
 -H "Content-Type: application/json" \
 -d '{  "name": "trino-3",
        "proxyTo": "http://localhost:8083",
        "active": true,
        "routingGroup": "adhoc",
        "tags": ["env:prod"]
    }'
```

If the Trino cluster URL is different from the `proxyTo` URL, for example if
they are internal and external hostnames used, you can use the optional
`externalUrl` field to override the link in the **Active Backends** page.

```shell
curl -X POST http://localhost:8080/gateway/backend/modify/add \
 -H "Content-Type: application/json" \
 -d '{  "name": "trino-3",
        "proxyTo": "http://localhost:8083",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8084",
        "tags": ["env:prod"]
    }'
```

## Update a Trino cluster

```shell
curl -X POST http://localhost:8080/gateway/backend/modify/update \
 -H "Content-Type: application/json" \
 -d '{  "name": "trino-3",
        "proxyTo": "http://localhost:8083",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8084",
        "tags": ["env:prod"]
    }'
```

## List all Trino clusters

```shell
curl -X GET http://localhost:8080/gateway/backend/all
```

Returns a JSON array of Trino cluster:

```json
[
    {
        "name": "trino-1",
        "proxyTo": "http://localhost:8081",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8081",
        "tags": ["env:prod"]
    },
    {
        "name": "trino-2",
        "proxyTo": "http://localhost:8082",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8082",
        "tags": ["env:test", "size:XL"]
    },
    {
        "name": "trino-3",
        "proxyTo": "http://localhost:8083",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8084",
        "tags": []
    }
]
```

## Delete a Trino cluster

```shell
curl -X POST -d "trino3" http://localhost:8080/gateway/backend/modify/delete
```

## Deactivate a Trino cluster

```shell
curl -X POST http://localhost:8080/gateway/backend/deactivate/trino-2
```

## List all active Trino clusters

```shell
curl -X GET http://localhost:8080/gateway/backend/active
```

Returns a JSON array of active Trino clusters:

```json
[
    {
        "name": "trino-1",
        "proxyTo": "http://localhost:8081",
        "active": true,
        "routingGroup": "adhoc",
        "externalUrl": "http://localhost:8081",
        "tags": ["env:prod"]
    }
]
```

## Activate a Trino cluster

```shell
curl -X POST http://localhost:8080/gateway/backend/activate/trino-2
```

## Hostname routing configuration

Hostname-to-pool placement is configured through `routing.hostnameToPool` and
`routing.hostnameSuffixToPool`. See [Hostname routing](routing-rules.md).
The legacy `/webapp/getRoutingRules` and `/webapp/updateRoutingRules` endpoints
are no longer available.
