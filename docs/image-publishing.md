# Container image publishing

The `Publish ECR image` workflow builds the `main` branch of
`PostHog/trino-gateway` and publishes a multi-architecture image to the
`trino-gateway` repository in the internal Amazon ECR registry. It also
supports manual runs from `main`. It does not publish to Docker Hub, GHCR, or
any other registry.

The workflow uses Java 25 and the existing `docker/build.sh` image builder and
container smoke tests for `linux/amd64` and `linux/arm64`. The normal CI and
transaction-test workflows continue to run separately. The publishing workflow
does not replace those checks.

## Registry access

The workflow authenticates with GitHub OIDC and assumes the IAM role named by
the repository secret `TRINO_GATEWAY_AWS_ECR_PUBLISH_IAM_ROLE`. That role is provisioned
outside this repository, trusts only `refs/heads/main` of this repository, and
may push only to the `trino-gateway` ECR repository. The registry hostname is
taken from the ECR login step, so no account identifier is committed here. The role ARN
is a secret rather than a variable because this repository is public and
variables are readable by anyone. The workflow fails early when the secret is
unset.

## Tags and immutability

Images include OCI source and revision labels. Each run publishes a single
`sha-<full-commit>` tag pointing at an OCI index with both architectures. The
ECR repository enforces tag immutability and carries no floating tags such as
`main` or `latest`.

A rerun for a commit that already has a published tag does not push again. It
verifies the existing image and reports its digest, because a rebuild can
produce different bytes when base images or package dependencies change. Pin
deployments to `@sha256:<digest>`; the publication summary gives the resulting
multi-architecture digest.

## Consumers

Deployments pull from the internal registry with account-scoped ECR pull
grants configured alongside the repository. An image upgrade is a reviewed
digest change in the deployment configuration, not an automatic rollout.
