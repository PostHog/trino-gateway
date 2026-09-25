#!/usr/bin/env bash
#
# Assemble one annotated OCI image index from per-architecture OCI layout
# tarballs (as produced by `docker build --output type=oci`) into a local OCI
# layout directory that `crane push --index <dir> <ref>` publishes as-is.
#
# Usage: oci-index.sh <output-dir> <revision> <source-url> <arch>=<layout.tar>...

set -euo pipefail

if [[ $# -lt 4 ]]; then
    echo "Usage: $0 <output-dir> <revision> <source-url> <arch>=<layout.tar>..." >&2
    exit 1
fi

output_dir=$1
revision=$2
source_url=$3
shift 3

mkdir -p "${output_dir}/blobs/sha256"
printf '{"imageLayoutVersion":"1.0.0"}\n' > "${output_dir}/oci-layout"

entries=()
for spec in "$@"; do
    arch=${spec%%=*}
    tarball=${spec#*=}
    extract_dir=$(mktemp -d)
    tar -xf "$tarball" -C "$extract_dir"
    cp -n "${extract_dir}"/blobs/sha256/* "${output_dir}/blobs/sha256/" 2>/dev/null || true
    count=$(jq '.manifests | length' "${extract_dir}/index.json")
    if [[ "$count" != "1" ]]; then
        echo "${tarball}: expected exactly one manifest in the OCI layout, found ${count}" >&2
        exit 1
    fi
    entries+=("$(jq -c --arg arch "$arch" '.manifests[0]
        | {mediaType, digest, size, platform: {os: "linux", architecture: $arch}}' \
        "${extract_dir}/index.json")")
    rm -rf "$extract_dir"
done

jq -n \
    --arg revision "$revision" \
    --arg source "$source_url" \
    --argjson manifests "$(printf '%s\n' "${entries[@]}" | jq -s '.')" \
    '{
        schemaVersion: 2,
        mediaType: "application/vnd.oci.image.index.v1+json",
        manifests: $manifests,
        annotations: {
            "org.opencontainers.image.revision": $revision,
            "org.opencontainers.image.source": $source
        }
    }' > "${output_dir}/index.json"
