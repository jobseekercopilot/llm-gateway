# LLM Gateway

The single current external-model boundary for Job Seeker Copilot. It exposes
an internal generation endpoint and supports an OpenAI live adapter plus
deterministic fixture behaviour for safe testing.

This service is **not beta-ready**. Its build is reproducible from committed
source, but its internal contract leaks
provider naming, forwards one unrestricted user prompt, has no structured
output enforcement, bounded retry/backoff/rate-limit/circuit-breaker policy,
maximum prompt/response size, cost controls, or complete provider privacy
decision. See [`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot.

The System Data fixture client is generated during Maven `generate-sources`
from the reviewed, checksum-protected producer contract under
`src/main/openapi`. Generated sources and binaries are build outputs and are
not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

## Configuration

Live mode reads the provider credential from `OPENAI_API_KEY`. Never commit a
credential. CI and automated tests must use deterministic fixture behaviour and
must not make paid provider requests.

## Build

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
mvn -B --no-transfer-progress clean verify
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
