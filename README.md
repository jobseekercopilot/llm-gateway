# LLM Gateway

The provider-neutral external-model boundary for Job Seeker Copilot. API
`2.0.0` separates trusted instructions from untrusted input, accepts explicit
text or strict JSON Schema output contracts, enforces request/response bounds
and supports fail-closed disabled, live and deterministic fixture modes.

The service is still **not beta-ready** until retry/quota/cost controls,
provider privacy decisions and all approved consumers are completed. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md) and
[`docs/PROVIDER_NEUTRAL_CONTRACT.md`](docs/PROVIDER_NEUTRAL_CONTRACT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the reviewed API `2.0.0`
snapshot. New consumers use `POST /api/v2/generations`. The deprecated v1
endpoint remains only for a bounded CV consumer migration.

The System Data fixture client is generated during Maven `generate-sources`
from the reviewed, checksum-protected producer contract under
`src/main/openapi`. Generated sources and binaries are build outputs and are
not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

## Configuration

The safe default is `EXTERNAL_PROVIDER_MODE=DISABLED`.

- `FIXTURE` requires the System Data URL, dataset ID/version and scenario, and
  cannot start in a production profile.
- `LIVE` requires runtime-only `OPENAI_API_KEY`, explicit `OPENAI_MODEL`, an
  HTTPS endpoint and positive timeouts, and cannot start in test/E2E profiles.
- The removed `LLM_MOCK_MODE` setting is rejected rather than silently ignored.

Never commit a credential. CI and automated tests use fixture or mocked
behaviour and must not make live or paid provider requests.

## Build

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
./scripts/test-api-contract-policy.sh
mvn -B --no-transfer-progress clean verify
./scripts/verify-api-contract.sh contracts/openapi.json target/openapi.json
docker build --tag local/llm-gateway .
```

These commands are the clean-clone verification contract. They require no
sibling repository, local `libs/` directory, generated JAR or preinstalled
Job Seeker Copilot artifact. The tests use mocks or local application
endpoints and do not make a live or paid provider request.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
