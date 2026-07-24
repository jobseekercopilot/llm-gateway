#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contract() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root/src/main/openapi/system-data-service.json" \
       "$repository_root/src/main/openapi/system-data-service.SOURCE" \
       "$repository_root/src/main/openapi/SHA256SUMS" \
       "$destination/"
}

"$repository_root/scripts/verify-contracts.sh" "$repository_root/src/main/openapi" >/dev/null

copy_contract "$temporary_dir/missing"
rm "$temporary_dir/missing/system-data-service.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/missing" >/dev/null 2>&1; then
    echo "contract policy negative test accepted a missing producer contract" >&2
    exit 1
fi

copy_contract "$temporary_dir/checksum-drift"
jq '.info.description = "unreviewed drift"' \
    "$temporary_dir/checksum-drift/system-data-service.json" \
    > "$temporary_dir/checksum-drift/changed.json"
mv "$temporary_dir/checksum-drift/changed.json" \
   "$temporary_dir/checksum-drift/system-data-service.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/checksum-drift" >/dev/null 2>&1; then
    echo "contract policy negative test accepted checksum drift" >&2
    exit 1
fi

copy_contract "$temporary_dir/llm-operation"
jq 'del(.paths["/internal/fixtures/llm/respond"].post)' \
    "$temporary_dir/llm-operation/system-data-service.json" \
    > "$temporary_dir/llm-operation/changed.json"
mv "$temporary_dir/llm-operation/changed.json" \
   "$temporary_dir/llm-operation/system-data-service.json"
(cd "$temporary_dir/llm-operation" && sha256sum system-data-service.json > SHA256SUMS)
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/llm-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of the LLM fixture operation" >&2
    exit 1
fi

copy_contract "$temporary_dir/request-field"
jq 'del(.components.schemas.FixtureLlmRequest.properties.promptHash)' \
    "$temporary_dir/request-field/system-data-service.json" \
    > "$temporary_dir/request-field/changed.json"
mv "$temporary_dir/request-field/changed.json" \
   "$temporary_dir/request-field/system-data-service.json"
(cd "$temporary_dir/request-field" && sha256sum system-data-service.json > SHA256SUMS)
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/request-field" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of the prompt hash" >&2
    exit 1
fi

copy_contract "$temporary_dir/response-field"
jq 'del(.components.schemas.FixtureLlmResponse.properties.totalTokens)' \
    "$temporary_dir/response-field/system-data-service.json" \
    > "$temporary_dir/response-field/changed.json"
mv "$temporary_dir/response-field/changed.json" \
   "$temporary_dir/response-field/system-data-service.json"
(cd "$temporary_dir/response-field" && sha256sum system-data-service.json > SHA256SUMS)
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/response-field" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of total token usage" >&2
    exit 1
fi

copy_contract "$temporary_dir/source-revision"
sed 's/revision=c687e41/revision=0000000/' \
    "$temporary_dir/source-revision/system-data-service.SOURCE" \
    > "$temporary_dir/source-revision/changed.SOURCE"
mv "$temporary_dir/source-revision/changed.SOURCE" \
   "$temporary_dir/source-revision/system-data-service.SOURCE"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/source-revision" >/dev/null 2>&1; then
    echo "contract policy negative test accepted unreviewed producer revision metadata" >&2
    exit 1
fi

echo "contract policy tests passed"
