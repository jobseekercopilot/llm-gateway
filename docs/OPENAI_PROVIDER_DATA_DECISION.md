# OpenAI provider data decision

Decision evidence date: 2026-07-25

Required runtime policy version: `openai-api-data-controls-2026-07-25`

Maximum review interval: 93 days

Status: **technical controls implemented; operator approval evidence pending**

This is the LLM Gateway operating decision and evidence template for LLM-02.
It is not legal, contractual or regulatory certification. `LIVE` must remain
disabled until the exact OpenAI organisation/project settings, applicable
agreement, named owner and user-facing data decision have been reviewed and
recorded in a durable decision referenced by the deployment.

## Current official evidence

The following facts were re-checked against official OpenAI sources on the
evidence date:

- API inputs and outputs are not used to train OpenAI models by default unless
  an organisation explicitly opts in to data sharing.
- `/v1/chat/completions` has no normal application-state retention for this
  text-only use, but default abuse-monitoring logs can contain prompts and
  responses and are retained for up to 30 days. OpenAI documents legal and
  safety-related exceptions.
- Modified Abuse Monitoring (MAM) and Zero Data Retention (ZDR) require prior
  OpenAI approval and additional responsibilities. They are configured at
  organisation/project level, not by an ordinary generation request.
- ZDR causes `store` to be treated as `false`. This gateway explicitly sends
  `store=false` in every case so model output is not opted into stored chat
  completions, distillation or evaluations.
- Prompt caching can retain encrypted cache state for up to 24 hours. Current
  model families and account controls affect which cache options are
  available; this gateway does not claim that `store=false` disables abuse
  monitoring or prompt caching.
- Data residency is selected for a new OpenAI project and requires the matching
  regional API hostname. The UK region provides regional storage but OpenAI
  does not currently list UK regional processing. Non-US regional residency
  requires an approved enhanced data-control arrangement and an applicable
  amendment.

Primary sources:

- [OpenAI API data controls and endpoint retention](https://developers.openai.com/api/docs/guides/your-data#data-retention-controls-for-abuse-monitoring)
- [OpenAI API data residency controls](https://developers.openai.com/api/docs/guides/your-data#data-residency-controls)
- [OpenAI Chat Completions API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [OpenAI business/API model-training policy](https://openai.com/policies/how-your-data-is-used-to-improve-model-performance/)
- [OpenAI Services Agreement](https://openai.com/policies/services-agreement/)
- [OpenAI Data Processing Addendum](https://openai.com/policies/data-processing-addendum/)

The named owner must review the agreement actually governing the configured
account. Source links alone are not proof of account eligibility, settings or
contract acceptance.

## Runtime decision boundary

LIVE startup fails unless all of the following are declared:

| Setting | Required outcome |
| --- | --- |
| `OPENAI_ORGANIZATION_ID` | Exact `org-`/`org_` organisation target |
| `OPENAI_PROJECT_ID` | Exact `proj_` project target; sent on every request |
| `OPENAI_DATA_REGION` | One explicit supported region; must match the endpoint hostname |
| `OPENAI_DATA_CONTROL_MODE` | `STANDARD_30_DAY_ABUSE_MONITORING`, `MODIFIED_ABUSE_MONITORING` or `ZERO_DATA_RETENTION` |
| `OPENAI_DATA_SHARING_MODE` | Must be `DISABLED` |
| `OPENAI_PRIVACY_POLICY_VERSION` | Must equal the reviewed version at the top of this document |
| `OPENAI_PRIVACY_DECISION_ID` | Durable reference to the approved account/project evidence |
| `OPENAI_PRIVACY_OWNER` | Named person accountable for the decision |
| `OPENAI_PRIVACY_REVIEW_ON` | ISO date that is not expired or more than 93 days away |

The endpoint must be exactly
`https://<declared-host>/v1/chat/completions`, without a port, query,
fragment or embedded credentials. Supported declarations map to OpenAI's
current hosts:

| Region | Host | Additional runtime rule |
| --- | --- | --- |
| `GLOBAL` | `api.openai.com` | No residency claim |
| `UNITED_STATES` | `us.api.openai.com` | Project eligibility still requires evidence |
| `EUROPE` | `eu.api.openai.com` | MAM or ZDR required |
| `AUSTRALIA` | `au.api.openai.com` | MAM or ZDR required |
| `CANADA` | `ca.api.openai.com` | MAM or ZDR required |
| `JAPAN` | `jp.api.openai.com` | MAM or ZDR required |
| `INDIA` | `in.api.openai.com` | MAM or ZDR required |
| `SINGAPORE` | `sg.api.openai.com` | MAM or ZDR required |
| `SOUTH_KOREA` | `kr.api.openai.com` | MAM or ZDR required |
| `UNITED_KINGDOM` | `gb.api.openai.com` | MAM or ZDR required; storage is not a regional-processing claim |
| `UNITED_ARAB_EMIRATES` | `ae.api.openai.com` | MAM or ZDR and provider approval required |

The adapter sends an allowlisted text-only payload: model, two separated
messages, temperature, maximum completion tokens, `store=false`, and strict
response format only when requested. Organisation and project headers pin the
declared target. Credentials, prompts, responses, owner names, organisation
IDs and project IDs are not emitted by application logs. The provider
`x-request-id` is sanitised and retained in metadata logs for incident support.

Framework loggers known to render HTTP bodies, request DTOs or validation
rejected values are held above DEBUG even if a generic process-level `DEBUG`
variable is present. A startup guard also rejects any direct configuration
override that makes one of those logger categories effectively DEBUG or TRACE.

## Approval evidence required before LIVE

The durable decision referenced by `OPENAI_PRIVACY_DECISION_ID` must contain:

1. Screenshot/export or equivalent administrator evidence for the exact
   organisation and project, including data sharing, retention control and
   region.
2. The exact API endpoint and model snapshot approved for use.
3. The applicable agreement/DPA review and any MAM, ZDR or regional amendment.
4. A named owner, approval date, review date and rollback decision.
5. Confirmation that the configured project-scoped credential belongs to that
   project and has the minimum required permissions.
6. The approved minimum model-bound field list from DOCGEN-18.
7. The approved user notice and retention/deletion statement.

Do not commit credentials, full account screenshots, personal data or contract
documents to this repository. Store the evidence in the approved restricted
record system and configure only its reference.

## Retention, deletion and incident responsibilities

- **LLM Gateway:** does not persist prompts or model output. It logs bounded
  operational metadata only and must keep payload-capable framework loggers
  above DEBUG; startup fails if that protection is overridden.
- **Calling service:** DOCGEN-18 owns model-bound field minimisation, consent or
  notice, stored document retention, user deletion propagation and proof that
  logs/metrics/exports do not retain model content.
- **OpenAI account owner:** owns project settings, data-sharing opt-in state,
  MAM/ZDR and region eligibility, agreement/amendment evidence and provider
  support escalation.
- **Deletion:** deleting application records does not provide an API to delete
  default OpenAI abuse-monitoring logs. The user notice must disclose the
  approved provider retention mode and its exceptions. Do not promise
  immediate provider-log deletion.
- **Incident:** disable LIVE, revoke/rotate the project credential, preserve
  correlation and provider request IDs without prompt content, notify the
  privacy/security owner, assess affected requests, contact provider support
  when required and follow the approved user/regulatory notification process.

## Rollback and review

Rollback is `EXTERNAL_PROVIDER_MODE=DISABLED`; fixture mode remains forbidden
in production. A provider policy, agreement, model/endpoint eligibility or
account-setting change invalidates the decision immediately. Otherwise the
runtime review date forces a re-review within 93 days. Updating the required
policy version requires code, documentation, tests and a reviewed pull request.

## LLM-02 acceptance status

| Acceptance criterion | Status |
| --- | --- |
| Exact API/account settings, retention, training and region evidenced | API facts and runtime evidence schema complete; actual account/project evidence pending |
| Named owner approves minimum-data/provider-use decision | Runtime requires a named owner and durable decision; human approval pending |
| Runtime/tests enforce approved settings or fail closed | Implemented for the gateway foundation |
| Deletion/incident/user-notice responsibilities documented | Gateway/provider responsibilities documented; DOCGEN-18 remains for product-wide execution |
