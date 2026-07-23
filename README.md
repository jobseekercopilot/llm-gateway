# LLM Gateway

The single current external-model boundary for Job Seeker Copilot. It exposes
an internal generation endpoint and supports an OpenAI live adapter plus
deterministic fixture behaviour for safe testing.

This migration baseline is **not beta-ready**. Its internal contract leaks
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

## Configuration

Live mode reads the provider credential from `OPENAI_API_KEY`. Never commit a
credential. CI and automated tests must use deterministic fixture behaviour and
must not make paid provider requests.

## Build

```bash
mvn -B clean verify
```

The command currently fails in a clean clone because the System Data client is
referenced from an untracked local `libs/` directory. Compiled clients must not
be committed as the fix.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
