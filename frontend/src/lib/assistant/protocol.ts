/**
 * What /api/chat sends back. A successful reply is a stream of newline-delimited JSON events; a
 * request turned away before streaming is a plain JSON body with an error code.
 */

export type AssistantErrorCode =
  | "unauthorized"
  | "rate_limited"
  | "budget_exhausted"
  | "not_configured"
  | "invalid_request"
  | "upstream"

export type AssistantEvent =
  | { type: "text"; text: string }
  | { type: "done"; stop: "end" | "max_tokens" | "refusal" }
  | { type: "error"; code: AssistantErrorCode; message: string }

export interface AssistantErrorBody {
  error: AssistantErrorCode
  message: string
}
