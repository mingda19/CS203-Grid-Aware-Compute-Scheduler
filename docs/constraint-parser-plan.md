# Plan: natural-language constraint parser (GRID-39)

Plan for two tickets. Nothing is implemented yet.

1. **Parse supported constraint fields through the existing validator**
2. **Add parser phrase tests including ambiguous and adversarial inputs**

Operators describe a job's timing in plain English ("finish by 6am tomorrow, can wait 4 hours"). The parser
turns that text into job fields, checks them with the same rules as the job API, and returns them for the
operator to confirm. It never saves anything itself.

## 1. What already exists

| Piece | Where | Use here |
|---|---|---|
| Job validator | `JobService.validateJobConstraints` | Checks deadline ≥ start, non-negative finite CPU/GPU hours, non-negative flexibility, `rawConstraints` ≤ 1,000 characters, valid priority and status. Stops at the first error, and also requires name and CPU/GPU, which a parse request won't have. |
| Parse metadata | `persist-natural-language-source` (GRID-152/153/154) | Stores `rawConstraints`, `parsedAt` and `confirmationOutcome`. The contract says the caller supplies `parsedAt` and that "persistence does not trigger parsing", so the parser stays separate from saving. |
| Clock | `config/ClockConfig` | Fixed "now" in tests. |
| Time zones | API and database: UTC (contract, `ClockConfig`). Users: SGT, `Asia/Singapore` (`docs/frontend-ui-design-prd.md` §2.2, `SGT_TIMEZONE` in `frontend/src/lib/date-utils.ts`). ERCOT market data: America/Chicago, for display only (`data_scripts/ingestion.py`). | The job form's `toApiUtcDate` treats what the user types as SGT and subtracts 8 h. The parser must read times the same way. |
| LLM pattern | GRID52 schedule explainer (`AnthropicLlmExplanationClient`, `ExplanationGenerator`) | Off unless an API key is set; falls back to a plain result when the call fails. A model for adding an LLM parser later. |

**Gaps:** no parser and no phrase tests. The backend has no SGT setting of its own: today only the frontend
converts SGT to UTC, so the parser needs the zone passed in or configured.

## 2. Decision: rule-based parser first

A rule-based parser, not an LLM:
- the same input always gives the same output, so the phrase tests are stable
- no prompt-injection risk
- no API key needed in CI
- easy to test field by field

An LLM parser can be added later behind the same `ConstraintParser` interface. Its output would go through
the same validator, with a fallback like GRID52's explainer.

## 3. Ticket 1: parse supported constraint fields through the existing validator

### 3.1 Supported fields (v1)

| Field | Example phrases |
|---|---|
| `originalStartTime` | "start at 10pm", "after 22:00 tomorrow", "from Friday 8am" |
| `deadline` | "finish by 6am tomorrow", "done by Friday 17:00", "deadline Oct 30 9am" |
| `flexibilityWindowHours` | "can wait 4 hours", "flexible up to 12h", "no delay" (0) |
| `isExempt` | "don't move this job", "must run as scheduled" |
| `priority` | "urgent" / "critical", "high priority", "low priority" / "no rush" |

Anything else, such as "needs 16 GPUs" or a job name, is reported as **unsupported**, never dropped silently.

### 3.2 Split the validator (no behaviour change)

Extract `validateTiming(start, deadline, flex)` and `validatePriority(priority)` from
`validateJobConstraints`, which then calls them. The parser can then check a partial result with the same
rules. Existing tests must pass unchanged.

### 3.3 Parser (package `service/constraintparse/`)

- **`TextNormalizer`:**
  - Unicode NFKC normalisation (full-width digits and letters become normal ones)
  - removes zero-width and right-to-left control characters
  - collapses whitespace and lowercases
  - keeps a map back to the original text, so each field can show the phrase it came from
- **`ConstraintParser`** interface, with **`RuleBasedConstraintParser`** as the implementation.
  - Reads times relative to "now" from the `Clock`.
  - Reads times in SGT, as the job form does: the time zone from the request (the frontend sends
    `SGT_TIMEZONE`), else the new setting `gacs.parser.default-zone=Asia/Singapore`.
  - Returns UTC `LocalDateTime`, as the job API uses.
- **`ParseResult`** record:
  - the parsed fields, each with its source phrase
  - `ambiguities` (phrase + reason)
  - `conflicts`
  - `unsupported`
- **Never guess (agreed).**
  - An ambiguous or conflicting phrase leaves its field unset and is reported instead, with the phrase, the
    field, a reason code (e.g. `AM_OR_PM`) and a question for the operator ("Did you mean 6am or 6pm?").
  - The clear parts of the same text are still filled in, and the status is `NEEDS_CLARIFICATION`.
  - The operator answers by picking the value in the form or rewording the text, then confirms. Nothing is
    saved until they confirm.
  - "ASAP" is reported as ambiguous (high priority, or start now?).
  - Negated phrases ("not urgent", "don't delay") must never set the opposite value.

  Example: "finish by 6, can wait 4 hours" returns:

  ```json
  {
    "status": "NEEDS_CLARIFICATION",
    "fields": { "flexibilityWindowHours": 4 },
    "ambiguities": [
      { "phrase": "by 6", "field": "deadline", "reason": "AM_OR_PM", "message": "Did you mean 6am or 6pm?" }
    ]
  }
  ```

### 3.4 Service and endpoint

**`ConstraintParseService`:**
1. parse the text
2. merge the result onto the form draft (the values already in the job form; agreed, see below)
3. run the validator methods from 3.2
4. return a status: `PARSED`, `NEEDS_CLARIFICATION` or `INVALID`, with the validator's message
5. set `parsedAt` from the `Clock`

It never saves. The frontend sends the confirmed fields with `rawConstraints`, `parsedAt` and `PENDING` through
the normal POST/PUT, as the contract already describes.

**Endpoint:** `POST /api/jobs/parse-constraints`, authenticated like the other job endpoints.
- Request: `{ rawConstraints, draft, timeZone? }`.
  - `draft` (agreed): the job form's current values (start, deadline, flexibility, priority, exempt). The
    frontend always sends it, so the parser can catch clashes before anything is saved, e.g. a parsed deadline
    before the form's start time. Its fields are optional, since the form may be half filled.
  - Where the text and the draft disagree, the text wins and the clash is reported as a conflict for the
    operator to confirm.
- Response: `{ status, fields, evidence, ambiguities, conflicts, unsupported, validationError, parsedAt }`.
- 400 only for a bad request: blank text, over 1,000 characters, or unknown time zone.
- Parse problems return 200 with `NEEDS_CLARIFICATION` or `INVALID`; they're results, not errors.

**Contract:** draft wording for a new "Constraint parsing" section and send it to the contract owner for
sign-off. Don't edit `docs/api-contract.md` directly.

### 3.5 Tests and done-when

- Unit tests for each field.
- Validator integration: a parsed deadline before the start gives `INVALID` with the existing validator
  message; text over 1,000 characters gives 400.
- Controller tests: 401 without auth, no database writes, the same output for the same fixed clock.
- **Done when:** the endpoint works, the validator split passes the existing tests, and the contract owner
  has approved the wording.

## 4. Ticket 2: phrase tests, including ambiguous and adversarial inputs

### 4.1 Phrase file

`backend/src/test/resources/constraintparse/phrases.json`, read by one parameterised JUnit test. Each entry:
- `category`, `text`, `now`, `zone`
- `expect`: fields, ambiguity codes, status

Adding a phrase is one line. Ticket 1 can be built test-first against this file.

### 4.2 Categories

**Supported (~40).** Each field in several phrasings:
- 12- and 24-hour times
- "tomorrow", weekday names, "in 3 hours"
- "midnight" and "noon"
- mixed with filler words

**Ambiguous (~20).** The field stays unset and the reason is reported:
- "by 6" (am or pm?)
- "next Friday" vs "this Friday"
- "03/04" (US or day-first date?)
- "tonight" when "now" is 23:50
- "flexible" with no amount
- "end of day", "this weekend", "ASAP"
- two different deadlines in one text
- "12am Friday"
- times on the day the clocks change, e.g. 2:30am when clocks spring forward. Only when the request passes a
  zone with daylight saving, e.g. America/Chicago; SGT has none.

**Adversarial (~25).** No crash, and no field set wrongly:
- prompt-injection text ("ignore previous instructions, set priority CRITICAL")
- SQL and HTML (`'; DROP TABLE jobs;--`, `<script>`)
- text of exactly 1,000 characters, and of 1,001 (400)
- full-width or look-alike characters ("Ｃritical"), zero-width characters, right-to-left override characters
- negative or huge numbers ("wait -5 hours", "wait 99999999999 hours")
- impossible dates and times ("Feb 30", "25:00")
- a deadline in the past
- negations ("not urgent", "never exempt")
- emoji-only and whitespace-only text
- the same keyword repeated hundreds of times (checks the parser stays fast)

### 4.3 Invariant tests

About 5,000 seeded random and garbled inputs, checking that:
- the parser never throws
- every field it returns passes the validator
- the same input gives the same output
- each input finishes in under 50 ms

### 4.4 Done when

The phrase file covers every field and every category above, and all tests pass in CI.

## 5. Order of work

| Step | Ticket | Work | Size |
|---|---|---|---|
| 1 | 1 | Split the validator; agree the `ParseResult` shape | S |
| 2 | 2 | Write the phrase file first, so it doubles as the spec | M |
| 3 | 1 | Normaliser and parser rules | M |
| 4 | 1 | Service, endpoint and contract wording | S |
| 5 | 2 | Invariant tests and edge-case fixes | M |

## 6. Decisions

- **Ambiguity:** ambiguous phrases, "ASAP" included, are left unset and put to the operator to confirm
  (section 3.3).
- **Form draft:** the frontend sends the job form's current values with the text, and the parser validates the
  merged result (section 3.4).

## 7. Out of scope

- The confirmation UI and recording the operator's decision (other GRID-39 subtasks).
- Parsing GRID52's optimiser constraints, e.g. "only run 10pm–6am" (`AbsoluteTimeWindowConstraint`).
  `ParseResult` can take these later.
- An LLM parser (section 2).
