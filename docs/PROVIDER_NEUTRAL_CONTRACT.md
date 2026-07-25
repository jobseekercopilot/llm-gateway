# Provider-neutral generation contract

LLM Gateway API `2.0.0` is the stable internal boundary between Job Seeker
Copilot domain services and external model adapters. Domain callers describe a
task, trust boundaries, an output contract and hard limits. They do not send
OpenAI request objects, credentials, provider names or model names.

## Version 2 request

`POST /api/v2/generations` requires:

```json
{
  "contractVersion": "2.0",
  "task": "DOCUMENT_DRAFT",
  "trustedInstructions": "Use only facts supported by the supplied input.",
  "untrustedInput": "{\"job\":\"source data\"}",
  "output": {
    "format": "JSON_SCHEMA",
    "schemaId": "document-output",
    "schemaVersion": "1.0",
    "jsonSchema": {
      "type": "object",
      "additionalProperties": false
    }
  },
  "limits": {
    "maxOutputTokens": 3000,
    "temperature": 0.3
  }
}
```

The adapter must preserve `trustedInstructions` and `untrustedInput` as
separate message roles. Untrusted data is never promoted into the trusted
instruction field by LLM Gateway. The caller owns its domain prompt and schema
versions; the gateway owns transport mapping and boundary enforcement.

The v2 response contains only:

- contract version;
- generated output;
- stable finish reason;
- provider-neutral token usage;
- the requested schema ID and version.

Adapter and model identifiers are operational metadata. They are available to
redacted service logs, not to v2 callers.

## Bounds

Requests fail before adapter use when they exceed these limits:

| Field | Limit |
| --- | ---: |
| Task identifier | 64 characters |
| Trusted instructions | 12,000 characters |
| Untrusted input | 40,000 characters |
| JSON Schema | 20,000 serialized characters |
| Maximum output | 1–4,096 tokens |
| Temperature | 0.0–1.0 |

Provider output above 100,000 characters, blank output or incomplete usage,
finish, adapter or model metadata is rejected as
`INVALID_PROVIDER_RESPONSE`. These character bounds complement, rather than
replace, the domain service schema and factuality validation.

Unknown JSON fields are rejected. A `TEXT` output must not supply schema
properties. A `JSON_SCHEMA` output requires a lowercase stable schema ID,
numeric semantic version and object-shaped schema.

## Mode safety

Exactly one `EXTERNAL_PROVIDER_MODE` is active:

| Mode | External activity | Startup requirements |
| --- | --- | --- |
| `DISABLED` | None | Safe default; generation returns `GENERATION_DISABLED`. |
| `FIXTURE` | System Data fixture only | Dataset ID/version, scenario and System Data URL; forbidden in `prod`/`production`. |
| `LIVE` | Configured model provider | Runtime credential of plausible length, explicit model, HTTPS endpoint and positive timeouts; forbidden in `test`, `e2e` and `fixture` profiles. |

The obsolete `LLM_MOCK_MODE` / `llm.mock-mode` setting causes startup failure,
even when set to `false`, so operators cannot mistakenly believe it controls
external activity. Credentials are never source-controlled or logged.

LIVE requires:

- `OPENAI_API_KEY`;
- `OPENAI_MODEL`;
- optional `OPENAI_ENDPOINT` (HTTPS only);
- optional positive `OPENAI_CONNECT_TIMEOUT_MS` and
  `OPENAI_READ_TIMEOUT_MS`.

## Adapter mapping

The current live adapter uses Chat Completions behind the neutral interface:

- trusted instructions map to a `developer` message;
- untrusted input maps to a `user` message;
- output limits map to `max_completion_tokens`;
- `JSON_SCHEMA` maps to strict `response_format.json_schema`;
- token usage and finish reasons map to neutral domain values;
- refusal/content filtering maps to the stable `GENERATION_REFUSED` failure.

This mapping follows the current official
[Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
and
[Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs).
The transport remains isolated so
[BACKLOG-LLM-01](https://github.com/jobseekercopilot/llm-gateway/issues/8)
can migrate it to the Responses API without changing the v2 caller contract.

## Stable failures

| Code | Meaning |
| --- | --- |
| `VALIDATION_FAILED` | Request fields violate the contract. |
| `INVALID_REQUEST` | Malformed JSON or unsupported fields. |
| `GENERATION_DISABLED` | The deployment intentionally has no active adapter. |
| `GENERATION_REFUSED` | The provider refused or filtered the requested output. |
| `PROVIDER_AUTHENTICATION_FAILED` | Runtime provider authentication failed. |
| `PROVIDER_RATE_LIMITED` | Provider capacity rejected the request. |
| `PROVIDER_TIMEOUT` | The provider did not respond within the configured deadline. |
| `PROVIDER_UNAVAILABLE` | The provider returned a server failure. |
| `INVALID_PROVIDER_RESPONSE` | Output or metadata violated the internal boundary. |

Retry, circuit, quota and cost-accounting policy remains with DOCGEN-10 and
DOCGEN-11. Provider privacy, retention and processing-region decisions remain
with LLM-02.

## Deprecated v1 migration

`POST /api/v1/generate` remains temporarily available so the current CV and
Cover Letter consumer does not break when this producer change merges. It has
the same prompt, token and temperature bounds and executes through the new
provider-neutral internal interface, but it cannot express separate trust
boundaries and still returns legacy adapter metadata.

New consumers must use v2. DOCGEN-06 and DOCGEN-07 own the CV consumer
migration. Remove v1 only after the pinned v2 client is published, all approved
consumers are migrated and compatibility tests pass. Rollback is a normal
revert to the last compatible v1 producer and consumer pins; never relax v2
bounds to preserve a legacy caller.

## Verification

Automated verification uses mocks and the System Data fixture adapter only. It
must not need a real provider credential or make a live or paid request.

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
./scripts/test-api-contract-policy.sh
mvn -B --no-transfer-progress clean verify
./scripts/verify-api-contract.sh contracts/openapi.json target/openapi.json
docker build --tag local/llm-gateway:llm-01 .
```
