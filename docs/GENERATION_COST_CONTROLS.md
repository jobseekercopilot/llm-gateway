# Generation admission and provider-cost controls

Evidence date: 2026-07-25

Status: **gateway foundation implemented; user quota and AI Credit policy
dependencies remain**

This is the LLM Gateway operating record for the dependency-ready part of
DOCGEN-10. It is not a customer price, AI Credit valuation, invoice or promise
that the estimate will equal a provider bill.

## Current official evidence

OpenAI publishes model-specific input and output token prices in its
[model catalogue](https://developers.openai.com/api/docs/models). The
[Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
documents `max_completion_tokens` as the output ceiling and says that explicitly
selecting the `default` service tier uses standard model pricing.

Prices, model aliases and service tiers can change. This repository therefore
does not hard-code a current vendor price. A reviewed deployment must configure
the exact model ID, internal deployment-policy version, dated pricing version
and both token rates. A rate change is an operator-controlled configuration
change and requires a fresh evidence record.

## Admission before provider cost

Every v1 and v2 request is mapped to a domain command and admitted against a
server-owned task policy before the provider client is called.

The conservative input estimate is:

```text
UTF-8 bytes(task)
+ UTF-8 bytes(trusted instructions)
+ UTF-8 bytes(untrusted input)
+ UTF-8 bytes(strict JSON Schema, when present)
+ configured provider-envelope reserve
```

One input token cannot represent less than one source byte, and the explicit
reserve covers message/envelope overhead. This deliberately overestimates many
ordinary English requests. It is a hard preflight ceiling, not a tokenizer or
billing estimate.

The default reviewed task policies are:

| Task | Maximum conservative input estimate | Maximum requested output |
| --- | ---: | ---: |
| `DOCUMENT_DRAFT` | 60,000 tokens | 4,096 tokens |
| `CV_GENERATION` | 60,000 tokens | 4,096 tokens |
| `COVER_LETTER_GENERATION` | 60,000 tokens | 4,096 tokens |
| `GENERAL_GENERATION` | 20,000 tokens | 2,048 tokens |

An unknown task or request over either server ceiling returns
`GENERATION_LIMIT_EXCEEDED` before provider activity. The provider response is
also rejected if its model differs from the reviewed model, its actual input
usage exceeds the admitted task ceiling, its output usage exceeds the caller
or server ceiling, or total usage is inconsistent.

## Versioned cost estimate

The live adapter pins `service_tier=default` and rejects a response that reports
another tier. It uses the provider-reported model and actual input/output token
counts. The v2 response and non-payload completion log include:

- actual model ID;
- internal model-deployment version;
- admission-policy version;
- pricing version;
- conservative admission estimate;
- estimated provider cost in micro-USD;
- currency `USD`.

For each token class, the calculator rounds up independently:

```text
class cost in micro-USD =
ceil(actual tokens × configured micro-USD rate per 1,000,000 tokens
     ÷ 1,000,000)
```

The input and output class estimates are then added. Cached-input discounts are
not subtracted, so the estimate is conservative when a provider applies such a
discount. Taxes, negotiated discounts, batch/flex/priority pricing, failed
attempts without usage metadata and non-token fees are not represented.
DOCGEN-11 therefore keeps automatic provider retries at zero. Each admitted
request has exactly one observable provider attempt until durable operation
identity and per-attempt budget aggregation are available.

## Runtime configuration

The safe FIXTURE defaults use `fixture-model`, zero rates and an explicit
`non-billable-fixture-1` pricing version. LIVE startup rejects those defaults.

| Variable | LIVE requirement |
| --- | --- |
| `GENERATION_ADMISSION_POLICY_VERSION` | Stable reviewed task-policy version |
| `GENERATION_MODEL_ID` | Exact model expected in the provider response; must match `OPENAI_MODEL` |
| `GENERATION_MODEL_DEPLOYMENT_VERSION` | Stable internal deployment/approval version |
| `GENERATION_PRICING_VERSION` | Stable dated pricing evidence version; cannot be a non-billable fixture version |
| `GENERATION_INPUT_RATE_MICRO_USD_PER_MILLION_TOKENS` | Positive reviewed input rate |
| `GENERATION_OUTPUT_RATE_MICRO_USD_PER_MILLION_TOKENS` | Positive reviewed output rate |
| `GENERATION_INPUT_TOKEN_RESERVE` | Envelope reserve from 0 to 4,096 |
| `<TASK>_MAX_ESTIMATED_INPUT_TOKENS` | Positive server-owned input ceiling |
| `<TASK>_MAX_OUTPUT_TOKENS` | Server ceiling from 1 to 4,096 |

The durable deployment record must link the configured values to the reviewed
model catalogue or applicable account price and name an owner. Changing a
model, service tier, price or task budget invalidates that record.

## Remaining DOCGEN-10 dependencies

This gateway cannot safely enforce a user quota from a caller-supplied user ID.
The following remain beta blockers:

- [PAY-03](https://github.com/jobseekercopilot/payment-gateway/issues/4):
  authenticated user and service identity;
- [PAY-12](https://github.com/jobseekercopilot/payment-service/issues/5):
  approved customer price, AI Credit valuation, warning thresholds and
  disclosure policy;
- [DOCGEN-09](https://github.com/jobseekercopilot/document-generation-gateway/issues/7):
  durable operation identity, duplicate suppression, retry/regeneration cost
  aggregation and side-effect recovery.

Until those contracts are approved, the metadata is for operational cost
evidence only. It must not debit credits or be presented as the user's charge.
