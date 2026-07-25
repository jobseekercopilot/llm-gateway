# Beta-readiness audit

Audit date: 2026-07-23

Status: **Not ready for private beta**

LLM-01 producer update: the gateway now exposes a bounded provider-neutral v2
contract, separates trusted instructions from untrusted data, supports strict
JSON Schema output mapping, rejects invalid request/provider response bounds,
documents stable failures, and fails closed by deployment mode. The deprecated
v1 endpoint remains temporarily for consumer compatibility. This closes the
gateway-side portions of findings 1, 2, 3, 5 and 7 below; consumer migration
and the other findings remain beta work.

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
- The DOCGEN-02 LLM slice replaces the `systemPath` System Data client JAR with
  deterministic source generation from an exact revision/checksum-pinned
  producer contract. Contract policy tests, Maven verification and the
  source-only container build run in CI without a sibling repository, local
  `libs/` directory or preinstalled Job Seeker Copilot artifact.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 42 dependencies, 9 vulnerable dependencies, 137
  vulnerability matches, including 17 Critical and 37 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Original blockers and current disposition

1. **Gateway-side addressed by LLM-01.** The v2 internal response contract uses
   provider-neutral naming and does not expose adapter/model identifiers.
2. **Gateway-side addressed by LLM-01; consumer migration remains.** V2 keeps
   trusted instructions and untrusted data in separate roles and maps strict
   structured output. The deprecated v1 consumer cannot express that boundary.
3. **Gateway-side addressed by LLM-01.** Request fields, tokens, temperature,
   JSON Schema and provider output now have explicit bounds.
4. There is no governed retry/backoff policy, circuit breaker, rate limit,
   concurrency limit, idempotency, or cancellation contract.
5. **Gateway-side addressed by LLM-01.** Existing adapter parsing remains
   isolated behind a provider-neutral interface and maps to documented stable
   internal failures. A future Responses API transport migration is recorded
   separately as BACKLOG-LLM-01.
6. There is no per-request cost estimate, price/model version record, user
   budget enforcement, warning threshold, or retry/regeneration cost guard.
7. **Gateway-side addressed by LLM-01.** DISABLED is the default, FIXTURE is
   forbidden in production, LIVE is forbidden in test/fixture profiles and
   requires plausible credentials, explicit model, HTTPS endpoint and positive
   timeouts. Obsolete mock configuration fails startup.
8. **Partially addressed by LLM-01.** V2 provides the bounded domain-neutral
   envelope and refusal/filter failure mapping. Domain output validation and
   the approved content-safety policy remain open.
9. Metrics do not cover provider latency/error class, rate limits, retries,
   input/output tokens, estimated cost, invalid response, or circuit state.
10. The privacy decision for data retention, training controls, processing
    region, contractual terms, and deletion is not documented.
11. The Dockerfile lacks a non-root runtime, digest-pinned bases, explicit
    resource constraints, and supply-chain scan evidence.
12. Wider document-generation consumers still need the same reproducible
    contract approach under DOCGEN-02/DOCGEN-03.
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
