# Provider resilience and recovery runbook

This runbook covers the dependency-ready DOCGEN-11 resilience boundary in LLM
Gateway. It applies to `EXTERNAL_PROVIDER_MODE=LIVE`. Fixture and disabled
modes make no external model-provider call.

## Runtime policy

Every admitted generation has one provider attempt. Automatic retries are
deliberately set to zero until DOCGEN-09 supplies durable operation
idempotency and final DOCGEN-10 controls account for every attempt against the
trusted user and cost budget. Operators and callers must not add an
uncoordinated retry loop.

The live adapter enforces:

- one caller-visible deadline, `OPENAI_CALL_TIMEOUT_MS` (default and maximum
  120 seconds, sized for admitted large CV evidence prompts);
- a socket read deadline equal to the call deadline and a connect timeout that
  cannot exceed it;
- a zero-queue bounded worker pool, `OPENAI_MAX_CONCURRENT_CALLS` (default 8,
  allowed 1–32);
- a byte limit before provider JSON parsing,
  `OPENAI_MAX_RESPONSE_BYTES` (default 1 MiB, allowed 1 KiB–2 MiB);
- a consecutive-failure circuit, default 3 failures and 30 seconds open;
- one half-open recovery probe after the open interval;
- no prompt, provider response body, credential or provider error detail in
  logs or client errors.

Authentication and account-quota failures open the circuit immediately.
Timeout, rate-limit, capacity, invalid-response and provider-unavailable
failures count towards the configured threshold. A bounded request rejected by
the provider does not mark the provider unhealthy.

## Stable failure contract

| Code | HTTP | Meaning | Circuit effect |
| --- | ---: | --- | --- |
| `PROVIDER_AUTHENTICATION_FAILED` | 503 | Runtime provider credentials/configuration were rejected. | Opens immediately. |
| `PROVIDER_RATE_LIMITED` | 503 | Provider capacity was rate limited. | Counts as a failure. |
| `PROVIDER_QUOTA_EXHAUSTED` | 503 | Provider account quota is exhausted. | Opens immediately. |
| `PROVIDER_TIMEOUT` | 504 | The hard provider-call deadline or transport timeout elapsed. | Counts as a failure. |
| `PROVIDER_UNAVAILABLE` | 503 | Connection or provider service failed. | Counts as a failure. |
| `PROVIDER_CAPACITY_EXHAUSTED` | 503 | The bounded local provider pool is full. | Counts as a failure. |
| `INVALID_PROVIDER_RESPONSE` | 502 | Response bytes, JSON, model metadata, usage or output violated the boundary. | Counts as a failure. |
| `PROVIDER_REQUEST_REJECTED` | 502 | Provider rejected a bounded request for a non-transient reason. | Does not open the circuit. |
| `PROVIDER_CIRCUIT_OPEN` | 503 | Calls are paused during provider recovery. | No provider activity. |
| `PROVIDER_CALL_CANCELLED` | 503 | The waiting application thread was interrupted. | No automatic retry. |

Only a positive provider `Retry-After` delta of at most one hour is forwarded.
Provider response bodies are never forwarded.

## Readiness semantics

`GET /actuator/health/readiness` includes `providerCircuit`.

- `UP/CLOSED`: provider calls are eligible.
- `OUT_OF_SERVICE/OPEN`: calls fail before provider activity and include a
  bounded `Retry-After`.
- `UP/HALF_OPEN`: the recovery interval elapsed and exactly one call may probe
  the provider. Other simultaneous probes fail closed.
- Non-live modes remain `UP`; their health detail identifies the active mode
  and does not imply that external calls are enabled.

The health details expose only mode, circuit state, consecutive failure count,
retry delay and `automaticRetries=0`.

## Client disconnect and cancellation

An interrupted request thread cancels its worker future and returns the stable
cancelled failure when a response channel still exists. A TCP client
disconnect is not guaranteed to interrupt an already running blocking
provider call. The socket deadline and bounded pool therefore cap orphaned
work.

Cancellation cannot prove that the external provider did not finish and bill
the request. Until DOCGEN-09 provides durable operation replay, a caller must
not immediately retry an ambiguous disconnect. Support should correlate the
non-payload request ID and check the owning workflow state first.

## Operator recovery

1. Check `/actuator/health/readiness` and the stable failure code/counts. Do not
   enable payload logging.
2. For authentication, verify the runtime secret, organisation, project,
   region and approved privacy settings without printing their values.
3. For quota, check the approved provider account and the Job Seeker Copilot
   budget owner. Do not bypass the quota by changing project identity.
4. For rate limiting or outage, honour `Retry-After` or the circuit delay. Do
   not restart repeatedly to reset the in-memory circuit.
5. For invalid or oversized responses, preserve the non-payload provider
   request ID and deployed model/configuration versions. Never copy prompt or
   response content into tickets.
6. After correction or cooldown, allow the single half-open probe. A valid
   response closes the circuit; a qualifying failure reopens it.
7. If ambiguous cost or downstream side effects may exist, follow DOCGEN-09
   recovery once that durable operation workflow is delivered.

## Verification

Automated tests cover deadline cancellation, 401/403, rate limit and
`Retry-After`, account quota, 5xx outage, connection reset, oversized response,
circuit opening, single half-open probe, recovery and stable public errors.
They use mocks only and make no live or paid model request.
