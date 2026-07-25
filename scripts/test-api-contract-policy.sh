#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contract() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root/contracts/openapi.json" "$destination/openapi.json"
}

"$repository_root/scripts/verify-api-contract.sh" "$repository_root/contracts/openapi.json" >/dev/null

copy_contract "$temporary_dir/v2-operation"
jq 'del(.paths["/api/v2/generations"])' "$temporary_dir/v2-operation/openapi.json" \
    > "$temporary_dir/v2-operation/changed.json"
mv "$temporary_dir/v2-operation/changed.json" "$temporary_dir/v2-operation/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/v2-operation/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted removal of v2 generation" >&2
    exit 1
fi

copy_contract "$temporary_dir/token-bound"
jq '.components.schemas.GenerationLimits.properties.maxOutputTokens.maximum = 10000' \
    "$temporary_dir/token-bound/openapi.json" > "$temporary_dir/token-bound/changed.json"
mv "$temporary_dir/token-bound/changed.json" "$temporary_dir/token-bound/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/token-bound/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted a relaxed output-token bound" >&2
    exit 1
fi

copy_contract "$temporary_dir/provider-leak"
jq '.components.schemas.GenerationResponse.properties.provider = {"type":"string"}' \
    "$temporary_dir/provider-leak/openapi.json" > "$temporary_dir/provider-leak/changed.json"
mv "$temporary_dir/provider-leak/changed.json" "$temporary_dir/provider-leak/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/provider-leak/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted provider leakage in v2" >&2
    exit 1
fi

copy_contract "$temporary_dir/unknown-fields"
jq '.components.schemas.GenerationRequest.additionalProperties = true' \
    "$temporary_dir/unknown-fields/openapi.json" > "$temporary_dir/unknown-fields/changed.json"
mv "$temporary_dir/unknown-fields/changed.json" "$temporary_dir/unknown-fields/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/unknown-fields/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted unknown v2 request fields" >&2
    exit 1
fi

copy_contract "$temporary_dir/legacy-removal"
jq '.paths["/api/v1/generate"].post.deprecated = false' \
    "$temporary_dir/legacy-removal/openapi.json" > "$temporary_dir/legacy-removal/changed.json"
mv "$temporary_dir/legacy-removal/changed.json" "$temporary_dir/legacy-removal/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/legacy-removal/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted an unmarked legacy endpoint" >&2
    exit 1
fi

copy_contract "$temporary_dir/failure-contract"
jq 'del(.paths["/api/v2/generations"].post.responses["422"])' \
    "$temporary_dir/failure-contract/openapi.json" > "$temporary_dir/failure-contract/changed.json"
mv "$temporary_dir/failure-contract/changed.json" "$temporary_dir/failure-contract/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/failure-contract/openapi.json" >/dev/null 2>&1; then
    echo "API contract negative test accepted removal of a stable v2 failure response" >&2
    exit 1
fi

echo "API contract policy negative tests passed"
