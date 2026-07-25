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
3. **Gateway-side strengthened by LLM-01 and DOCGEN-10.** Request fields,
   tokens, temperature, JSON Schema and provider output have explicit bounds;
   task-specific conservative input/output admission now runs before adapter
   use and rejects unknown tasks.
4. **Gateway foundation addressed by DOCGEN-11.** Live calls now have one hard
   caller deadline, a zero-queue concurrency bound, pre-parse response byte
   limit, stable provider-neutral failures, an open/half-open/recovery circuit,
   readiness state and documented disconnect/cancellation semantics.
   Automatic retries are explicitly zero. Durable idempotency and attempt-cost
   aggregation remain blocked on DOCGEN-09 and final DOCGEN-10 controls.
5. **Gateway-side addressed by LLM-01.** Existing adapter parsing remains
   isolated behind a provider-neutral interface and maps to documented stable
   internal failures. A future Responses API transport migration is recorded
   separately as BACKLOG-LLM-01.
6. **Gateway foundation addressed by DOCGEN-10.** V2 now returns the actual
   model ID, deployment/admission/pricing versions, conservative preflight
   estimate and rounded-up micro-USD provider-cost estimate from actual token
   usage. LIVE pins standard service tier and fails startup on fixture/zero-rate
   policy. Trusted per-user budget/concurrency enforcement, warning thresholds,
   AI Credit exhaustion and retry/regeneration aggregation remain blocked on
   PAY-03, PAY-12 and DOCGEN-09.
7. **Gateway-side addressed by LLM-01.** DISABLED is the default, FIXTURE is
   forbidden in production, LIVE is forbidden in test/fixture profiles and
   requires plausible credentials, explicit model, HTTPS endpoint and positive
   timeouts. Obsolete mock configuration fails startup.
8. **Partially addressed by LLM-01.** V2 provides the bounded domain-neutral
   envelope and refusal/filter failure mapping. Domain output validation and
   the approved content-safety policy remain open.
9. **Partially addressed by DOCGEN-10 and DOCGEN-11.** Completion logs include
   latency, actual tokens and versioned estimated cost; failure logs include
   only the stable class, duration, `attempt=1` and `automaticRetries=0`; the
   readiness contributor exposes bounded circuit state/counts. Durable
   time-series counters, alerts and SLOs remain DOCGEN-19.
10. **Gateway-side foundation addressed by LLM-02.** LIVE now fails closed
    without an exact organisation/project, region-matched endpoint, explicit
    retention/data-sharing declaration, current decision reference, named
    owner and review date. Requests send `store=false` and payload-capable
    framework loggers are held above DEBUG with a fail-closed startup guard.
    Actual account/project evidence, owner approval and DOCGEN-18 end-to-end
    minimum-data, notice and deletion execution remain beta blockers.
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

The dated provider evidence and operating checklist are in
[`OPENAI_PROVIDER_DATA_DECISION.md`](OPENAI_PROVIDER_DATA_DECISION.md).
Provider failure, readiness, cancellation and recovery procedure is in
[`PROVIDER_RESILIENCE_RUNBOOK.md`](PROVIDER_RESILIENCE_RUNBOOK.md).
Account/project settings, applicable agreement and named-owner approval remain
open evidence. This is not legal or GDPR certification.
