"use client"

import * as React from "react"
import { Bot, ChevronDown, RotateCcw, SendHorizontal, ShieldCheck, TriangleAlert } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import { cleanUserMessage, MAX_MESSAGE_CHARS, REDACTION_LABELS, type RedactionType } from "@/lib/assistant/redact"
import { Markdown } from "./markdown"
import { useAssistantChat, type ChatMessage } from "./use-assistant-chat"

export const ASSISTANT_PROMPTS = [
  "How do I create a job?",
  "What does Flexible vs Locked mean?",
  "How do approvals work?",
  "Why was my job not placed?",
  "How do I read the price forecast?",
  "What can admins change?",
]

const describe = (types: RedactionType[]) => types.map((t) => REDACTION_LABELS[t]).join(", ")

function AssistantAvatar({ small = false }: { small?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground",
        small ? "size-8" : "size-14",
      )}
    >
      <Bot className={small ? "size-4" : "size-6"} />
    </span>
  )
}

function MessageRow({ message, canRetry, onRetry }: { message: ChatMessage; canRetry: boolean; onRetry: () => void }) {
  if (message.role === "user") {
    return (
      <div className="flex flex-col items-end gap-1">
        <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-lg bg-primary/10 px-3 py-2 text-sm leading-5">
          {message.content}
        </div>
        {message.redacted && message.redacted.length > 0 && (
          <p className="flex max-w-[85%] items-start gap-1 text-xs leading-4 text-muted-foreground">
            <ShieldCheck className="mt-px size-3.5 shrink-0" aria-hidden="true" />
            <span className="min-w-0">Removed before sending: {describe(message.redacted)}</span>
          </p>
        )}
      </div>
    )
  }
  return (
    <div className="flex flex-col items-start gap-1">
      {message.content ? (
        <div className="min-w-0 max-w-[90%] break-words rounded-lg bg-muted px-3 py-2 text-sm leading-5">
          <Markdown text={message.content} />
        </div>
      ) : message.state === "streaming" ? (
        <div role="status" aria-label="Assistant is replying" className="w-3/4 space-y-2 rounded-lg bg-muted px-3 py-3">
          <Skeleton className="h-3 w-full bg-background" />
          <Skeleton className="h-3 w-2/3 bg-background" />
        </div>
      ) : null}
      {message.state === "refusal" && (
        <p className="text-sm text-muted-foreground">I can&apos;t help with that. Try asking about using GACS.</p>
      )}
      {message.state === "truncated" && (
        <p className="text-xs leading-4 text-muted-foreground">The answer was cut short. Ask a narrower question for the rest.</p>
      )}
      {message.state === "error" && (
        <div className="flex max-w-full flex-wrap items-center gap-2">
          <p className="min-w-0 text-sm text-muted-foreground">{message.error}</p>
          <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry} disabled={!canRetry}>
            Retry
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * The floating help assistant: a round avatar in the corner that opens a collapsible chat panel.
 * Mounted once in the dashboard shell, so it is on every signed-in page.
 */
export function AssistantWidget() {
  const [open, setOpen] = React.useState(false)
  const [input, setInput] = React.useState("")
  const chat = useAssistantChat()
  const panelId = React.useId()
  const titleId = React.useId()
  const inputId = React.useId()
  const inputRef = React.useRef<HTMLTextAreaElement>(null)
  const bubbleRef = React.useRef<HTMLButtonElement>(null)
  const logRef = React.useRef<HTMLDivElement>(null)

  React.useEffect(() => {
    if (open) inputRef.current?.focus()
  }, [open])

  // Grow the input with its text, wrapped lines included, up to max-h-32; then it scrolls.
  React.useLayoutEffect(() => {
    const box = inputRef.current
    if (!box) return
    box.style.height = "auto"
    // border-box sizing: add the border, or a 1-line message gets a scrollbar.
    box.style.height = `${box.scrollHeight + box.offsetHeight - box.clientHeight}px`
  }, [input, open])

  React.useEffect(() => {
    const log = logRef.current
    if (log) log.scrollTop = log.scrollHeight
  }, [chat.messages, open])

  const collapse = () => {
    setOpen(false)
    bubbleRef.current?.focus()
  }

  const submit = (text: string) => {
    if (chat.send(text)) setInput("")
  }

  const stopped = chat.blocker?.code === "budget_exhausted" || chat.blocker?.code === "not_configured" || chat.blocker?.code === "unauthorized"
  // Shown while typing, so the user sees what will be removed before they send it.
  const pendingRedactions = input.trim() ? cleanUserMessage(input).types : []

  return (
    <>
      {open && (
        <section
          id={panelId}
          role="dialog"
          aria-modal="false"
          aria-labelledby={titleId}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.stopPropagation()
              collapse()
            }
          }}
          className="fixed inset-x-2 bottom-20 top-20 isolate z-40 flex flex-col overflow-hidden rounded-lg border bg-card text-card-foreground shadow-lg sm:inset-x-auto sm:bottom-24 sm:right-6 sm:top-auto sm:h-[min(36rem,calc(100dvh-8rem))] sm:w-96"
        >
          <header className="flex items-center gap-3 border-b px-4 py-3">
            <AssistantAvatar small />
            <div className="min-w-0 flex-1">
              <h2 id={titleId} className="truncate text-sm font-semibold leading-5">GACS Assistant</h2>
              <p className="truncate text-xs leading-4 text-muted-foreground">Help with using GACS</p>
            </div>
            <div className="flex shrink-0 items-center">
              <Button
                variant="ghost"
                size="icon"
                className="size-9"
                aria-label="Clear chat"
                title="Clear chat"
                disabled={chat.messages.length === 0}
                onClick={chat.clear}
              >
                <RotateCcw className="size-4" />
              </Button>
              <Button variant="ghost" size="icon" className="size-9" aria-label="Collapse assistant" title="Collapse" onClick={collapse}>
                <ChevronDown className="size-4" />
              </Button>
            </div>
          </header>

          <div ref={logRef} role="log" aria-live="polite" aria-busy={chat.busy} className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
            {chat.messages.length === 0 ? (
              <div className="space-y-3">
                <p className="text-sm text-muted-foreground">Ask how to use GACS. I can&apos;t see your jobs, plans or account.</p>
                <div className="flex flex-col items-stretch gap-2">
                  {ASSISTANT_PROMPTS.map((prompt) => (
                    <Button
                      key={prompt}
                      variant="outline"
                      size="sm"
                      className="h-auto justify-start whitespace-normal py-2 text-left"
                      disabled={stopped || chat.busy}
                      onClick={() => submit(prompt)}
                    >
                      {prompt}
                    </Button>
                  ))}
                </div>
              </div>
            ) : (
              chat.messages.map((message) => (
                <MessageRow key={message.id} message={message} canRetry={!chat.busy && !stopped} onRetry={chat.retry} />
              ))
            )}
          </div>

          {chat.blocker && (
            <div className="border-t p-3">
              <Alert variant="warning">
                <TriangleAlert className="size-4" aria-hidden="true" />
                <AlertDescription>{chat.blocker.message}</AlertDescription>
              </Alert>
            </div>
          )}

          <form
            className="border-t p-3"
            onSubmit={(event) => {
              event.preventDefault()
              submit(input)
            }}
          >
            <div className="flex items-end gap-2">
              <label htmlFor={inputId} className="sr-only">Message GACS Assistant</label>
              <textarea
                id={inputId}
                ref={inputRef}
                rows={1}
                maxLength={MAX_MESSAGE_CHARS}
                value={input}
                disabled={stopped}
                placeholder="Ask about GACS…"
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    submit(input)
                  }
                }}
                className="max-h-32 min-h-10 min-w-0 flex-1 resize-none overflow-y-auto rounded-md border border-input bg-background px-3 py-2 text-sm leading-5 ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:opacity-50"
              />
              <Button
                type="submit"
                size="icon"
                className="size-10 shrink-0"
                aria-label="Send"
                title="Send"
                disabled={!input.trim() || chat.busy || stopped}
              >
                <SendHorizontal className="size-4" />
              </Button>
            </div>
            <p className="mt-2 flex items-start gap-1 text-xs leading-4 text-muted-foreground" aria-live="polite">
              {pendingRedactions.length > 0 ? (
                <>
                  <ShieldCheck className="mt-px size-3.5 shrink-0" aria-hidden="true" />
                  <span className="min-w-0">Will be removed before sending: {describe(pendingRedactions)}</span>
                </>
              ) : input.length > MAX_MESSAGE_CHARS - 100 ? (
                <span className="tabular-nums">{input.length} / {MAX_MESSAGE_CHARS} characters</span>
              ) : (
                <span>Messages are not saved. Don&apos;t share passwords or personal data.</span>
              )}
            </p>
          </form>
        </section>
      )}

      <Button
        ref={bubbleRef}
        type="button"
        size="icon"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={open ? "Collapse GACS Assistant" : "Open GACS Assistant"}
        title={open ? "Collapse GACS Assistant" : "GACS Assistant"}
        onClick={() => (open ? collapse() : setOpen(true))}
        className="fixed bottom-4 right-4 z-40 size-14 rounded-full p-0 shadow-lg sm:bottom-6 sm:right-6"
      >
        {open ? <ChevronDown className="size-6" /> : <AssistantAvatar />}
      </Button>
    </>
  )
}
