# System Data contract governance

System Data is the producer for the fixture client used by LLM Gateway. LLM
Gateway builds that client from reviewed source during Maven
`generate-sources`; no compiled client JAR is committed or loaded from
`libs/`.

## Current pin

| Property | Value |
| --- | --- |
| Producer | `jobseekercopilot/system-data-service` |
| Producer revision | `c687e416e0055adbe9123a099a94bd17ecbc8e46` |
| Producer path | `api/openapi.json` |
| Contract version | `1.0.0` |
| SHA-256 | `817afdea8af835e37b1379c47c6730811f3ef5703b29be048b092e676dcc7675` |
| Generator | OpenAPI Generator `7.5.0`, Java `resttemplate` library |

`src/main/openapi/system-data-service.SOURCE` records the source revision and
checksum. `SHA256SUMS` protects the reviewed contract bytes. Maven validates
the OpenAPI document and generates into `target/generated-sources`; generated
sources and binaries are disposable build outputs.

## Compatibility boundary

LLM Gateway fixture mode currently requires:

- `POST /internal/fixtures/llm/respond` / `llm`;
- request operation, fixture key and prompt hash;
- provider, model, response and finish-reason metadata;
- input, output and total token counts;
- creation time, fixture key and fixture-mode evidence.

`scripts/verify-contracts.sh` checks provenance, checksum and this semantic
boundary. `scripts/test-contract-policy.sh` proves that missing, drifted or
incompatible inputs fail closed.

This contract is only for explicitly enabled non-production fixture behavior.
It does not approve live provider configuration, privacy, prompt, output,
resilience or cost controls. Those remain owned by LLM-01, LLM-02 and the
other document-generation beta issues.

## Updating the pin

1. Merge and verify the producer change in System Data.
2. Record the exact merged `develop` revision and producer-owned contract.
3. Review the API diff for compatibility and fixture-mode isolation.
4. Copy the exact producer artifact into `src/main/openapi`.
5. Update `system-data-service.SOURCE` and `SHA256SUMS`.
6. Update policy assertions only when the consumer change is intentional.
7. Run:

   ```bash
   ./scripts/test-contract-policy.sh
   ./scripts/verify-contracts.sh
   mvn -B --no-transfer-progress clean verify
   docker build --tag local/llm-gateway .
   ```

8. Merge only after pull-request and post-merge `develop` CI pass.

If a producer change is incompatible, retain the previous reviewed pin until
the consumer is ready. Rollback is a normal revert to the previous contract,
source metadata and checksum as one change; never substitute an untracked
generated JAR.
