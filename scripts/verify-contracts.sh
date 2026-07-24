#!/usr/bin/env bash
set -euo pipefail

contract_dir="${1:-src/main/openapi}"
contract="$contract_dir/system-data-service.json"
source_metadata="$contract_dir/system-data-service.SOURCE"
manifest="$contract_dir/SHA256SUMS"

for required_file in "$contract" "$source_metadata" "$manifest"; do
    if [[ ! -f "$required_file" || -L "$required_file" ]]; then
        echo "contract policy: required regular file is missing or is a symlink: $required_file" >&2
        exit 1
    fi
done

(
    cd "$contract_dir"
    sha256sum --check --strict SHA256SUMS
)

test "$(wc -l < "$source_metadata" | tr -d ' ')" = 4
grep -Fx 'repository=jobseekercopilot/system-data-service' "$source_metadata" >/dev/null
grep -Fx 'revision=c687e416e0055adbe9123a099a94bd17ecbc8e46' "$source_metadata" >/dev/null
grep -Fx 'path=api/openapi.json' "$source_metadata" >/dev/null
grep -Fx 'sha256=817afdea8af835e37b1379c47c6730811f3ef5703b29be048b092e676dcc7675' "$source_metadata" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.0.0") and
    (.paths["/internal/fixtures/llm/respond"].post.operationId == "llm") and
    (.paths["/internal/fixtures/llm/respond"].post.requestBody.required == true) and
    (.paths["/internal/fixtures/llm/respond"].post.requestBody.content["application/json"].schema["$ref"]
        == "#/components/schemas/FixtureLlmRequest") and
    (.paths["/internal/fixtures/llm/respond"].post.responses["200"].content["application/json"].schema["$ref"]
        == "#/components/schemas/FixtureLlmResponse") and
    (.components.schemas.FixtureLlmRequest.properties
        | has("operation") and has("fixtureKey") and has("promptHash")) and
    (.components.schemas.FixtureLlmResponse.properties
        | has("provider") and has("model") and has("response") and
          has("inputTokens") and has("outputTokens") and has("totalTokens") and
          has("finishReason") and has("createdAt") and has("fixtureKey") and
          has("fixtureMode")) and
    (.components.schemas.FixtureLlmResponse.required
        | index("inputTokens") != null and
          index("outputTokens") != null and
          index("totalTokens") != null and
          index("fixtureMode") != null)
' "$contract" >/dev/null

echo "contract policy: pinned System Data source is present, intact and compatible"
