# Beta-readiness audit

Audit date: 2026-07-23

Status: **Not ready for private beta**

## Verified responsibility

The gateway exposes an internal generation endpoint. Live mode currently uses
an OpenAI adapter and sends the domain service's complete assembled prompt as
one user message. Deterministic fixture mode obtains synthetic output from
System Data. A separate legacy mock switch also exists.

The gateway owns provider transport and provider error mapping. The CV and
Cover Letter Service owns domain prompts and response interpretation.

## Migration evidence

- Source was copied from the untracked service directory in the intact root
  workspace; no standalone source history was available.
- `target/`, the local System Data client JAR, generated binaries, logs,
  databases, model outputs, recordings, and environment files are excluded.
- `OPENAI_API_KEY` has no non-empty source-controlled default.
- The migration-time contract is `contracts/openapi.json`.
- Gitleaks and targeted personal-data checks passed on the source snapshot.
- No live provider request was made.
- A clean `mvn -B clean verify` fails before compilation because the
  `systemPath` System Data client JAR is absent. Ten test methods exist in
  source, but they were not executed in the clean candidate.
- The candidate container build fails at `COPY libs ./libs`; no image was
  produced.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 42 dependencies, 9 vulnerable dependencies, 137
  vulnerability matches, including 17 Critical and 38 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Confirmed blockers

1. The internal response contract uses provider-specific
   `OpenAiGenerationResult` naming and leaks implementation detail.
2. Live generation sends one unrestricted user message; there is no separate
   trusted instruction boundary or provider-enforced structured-output schema.
3. Request validation has no maximum prompt length or maximum token ceiling;
   response body size is not bounded.
4. There is no governed retry/backoff policy, circuit breaker, rate limit,
   concurrency limit, idempotency, or cancellation contract.
5. Provider errors are parsed through generic maps/casts and are not mapped to
   a complete stable internal failure taxonomy.
6. There is no per-request cost estimate, price/model version record, user
   budget enforcement, warning threshold, or retry/regeneration cost guard.
7. Production fail-closed startup behaviour for absent/invalid credentials and
   conflicting fixture/mock modes is incomplete.
8. No provider content-safety policy, maximum output validation, or
   domain-neutral structured response envelope is enforced.
9. Metrics do not cover provider latency/error class, rate limits, retries,
   input/output tokens, estimated cost, invalid response, or circuit state.
10. The privacy decision for data retention, training controls, processing
    region, contractual terms, and deletion is not documented.
11. The Dockerfile lacks a non-root runtime, digest-pinned bases, explicit
    resource constraints, and supply-chain scan evidence.
12. The build depends on an untracked generated System Data client JAR.
13. Current Spring, Tomcat, Jackson, logging, and Swagger UI dependency
    findings include untriaged Critical/High advisories.

## Required validation

Before beta, evidence must show a provider-neutral bounded contract,
schema-constrained output, fail-closed credentials/modes, safe timeout and
resilience policies, idempotency and quotas, cost/usage metrics without prompt
logging, reproducible contracts, and deterministic failure tests.

The provider retention, training, and regional-processing decision remains
open and must be based on current official provider documentation and the
applicable product agreement. This is not legal or GDPR certification.
