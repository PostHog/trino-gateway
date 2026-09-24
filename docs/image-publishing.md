# Container image publishing

The `publish-ecr` job of the `ci` workflow publishes a multi-architecture
image of `PostHog/trino-gateway` to the `trino-gateway` repository in the
internal Amazon ECR registry. It runs only for a push to `main` (or a manual
run of the workflow from `main`) and only after the `maven-build` and
`transaction-tests` jobs of the same run passed. It does not publish to
Docker Hub, GHCR, or any other registry.

The job uses Java 25 and the existing `docker/build.sh` image builder and
container smoke tests for `linux/amd64` and `linux/arm64`, then pushes the
images that passed those tests. Pull requests do not build the container
image; a broken Dockerfile surfaces on the merge to `main`.

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
