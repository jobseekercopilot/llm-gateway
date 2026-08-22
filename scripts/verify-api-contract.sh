#!/usr/bin/env bash
set -euo pipefail

contract="${1:-contracts/openapi.json}"
generated="${2:-}"

if [[ ! -f "$contract" || -L "$contract" ]]; then
    echo "API contract policy: required regular file is missing or is a symlink: $contract" >&2
    exit 1
fi

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "2.0.0") and
    (.paths | keys == ["/api/v1/generate", "/api/v2/generations"]) and
    (.paths["/api/v2/generations"].post.operationId == "generateV2") and
    (.paths["/api/v2/generations"].post.requestBody.required == true) and
    (.paths["/api/v2/generations"].post.requestBody.content["application/json"].schema["$ref"]
        == "#/components/schemas/GenerationRequest") and
    (.paths["/api/v2/generations"].post.responses["200"].content["application/json"].schema["$ref"]
        == "#/components/schemas/GenerationResponse") and
    ([.paths["/api/v2/generations"].post.responses
        | to_entries[]
        | select(.key != "200")
        | .value.content["application/json"].schema["$ref"]]
        | length == 9 and all(. == "#/components/schemas/ErrorResponse")) and
    ((.paths["/api/v2/generations"].post.responses | keys)
        == ["200", "400", "401", "403", "422", "429", "500", "502", "503", "504"]) and
    (.paths["/api/v1/generate"].post.operationId == "generate") and
    (.paths["/api/v1/generate"].post.deprecated == true) and
    (.components.schemas.GenerationRequest.additionalProperties == false) and
    (.components.schemas.GenerationRequest.required
        | index("contractVersion") != null and
          index("task") != null and
          index("trustedInstructions") != null and
          index("untrustedInput") != null and
          index("output") != null and
          index("limits") != null) and
    (.components.schemas.GenerationRequest.properties.contractVersion.enum == ["2.0"]) and
    (.components.schemas.GenerationRequest.properties.task.maxLength == 64) and
    (.components.schemas.GenerationRequest.properties.trustedInstructions.maxLength == 12000) and
    (.components.schemas.GenerationRequest.properties.untrustedInput.maxLength == 170000) and
    (.components.schemas.GenerationLimits.additionalProperties == false) and
    (.components.schemas.GenerationLimits.properties.maxOutputTokens.minimum == 1) and
    (.components.schemas.GenerationLimits.properties.maxOutputTokens.maximum == 32768) and
    (.components.schemas.GenerationLimits.properties.temperature.minimum == 0) and
    (.components.schemas.GenerationLimits.properties.temperature.maximum == 1) and
    (.components.schemas.GenerationOutputContract.additionalProperties == false) and
    (.components.schemas.GenerationOutputContract.properties.format.enum == ["TEXT", "JSON_SCHEMA"]) and
    (.components.schemas.GenerationOutputContract.properties.schemaId.maxLength == 64) and
    (.components.schemas.GenerationOutputContract.properties.schemaVersion.maxLength == 32) and
    (.components.schemas.GenerationResponse.additionalProperties == false) and
    (.components.schemas.GenerationResponse.properties | has("provider") | not) and
    (.components.schemas.GenerationResponse.properties | has("model") | not) and
    (.components.schemas.GenerationResponse.properties.audit["$ref"]
        == "#/components/schemas/GenerationAudit") and
    (.components.schemas.GenerationResponse.required | index("audit") != null) and
    (.components.schemas.GenerationAudit.additionalProperties == false) and
    ((.components.schemas.GenerationAudit.properties | keys)
        == ["admissionPolicyVersion", "automaticRetryCount", "currency",
            "estimatedCostMicroUsd", "estimatedInputTokensAtAdmission",
            "modelDeploymentVersion", "modelId", "pricingVersion",
            "providerAttemptCount", "retryReason"]) and
    ((.components.schemas.GenerationAudit.required | sort)
        == ["admissionPolicyVersion", "automaticRetryCount", "currency",
            "estimatedCostMicroUsd", "estimatedInputTokensAtAdmission",
            "modelDeploymentVersion", "modelId", "pricingVersion",
            "providerAttemptCount"]) and
    (.components.schemas.GenerationAudit.properties | has("provider") | not) and
    (.components.schemas.ErrorResponse.additionalProperties == false) and
    (.components.schemas.GenerateRequest.properties.prompt.maxLength == 40000) and
    (.components.schemas.GenerateRequest.properties.maxTokens.maximum == 4096) and
    (.components.schemas.GenerateRequest.properties.temperature.maximum == 1)
' "$contract" >/dev/null

if [[ -n "$generated" ]]; then
    if [[ ! -f "$generated" || -L "$generated" ]]; then
        echo "API contract policy: generated contract is missing or is a symlink: $generated" >&2
        exit 1
    fi
    temporary_dir="$(mktemp -d)"
    trap 'rm -rf "$temporary_dir"' EXIT
    jq -S . "$contract" > "$temporary_dir/reviewed.json"
    jq -S . "$generated" > "$temporary_dir/generated.json"
    if ! cmp -s "$temporary_dir/reviewed.json" "$temporary_dir/generated.json"; then
        echo "API contract policy: generated OpenAPI differs from contracts/openapi.json" >&2
        diff -u "$temporary_dir/reviewed.json" "$temporary_dir/generated.json" >&2 || true
        exit 1
    fi
fi

echo "API contract policy: v2 provider-neutral boundary is present, bounded and compatible"
