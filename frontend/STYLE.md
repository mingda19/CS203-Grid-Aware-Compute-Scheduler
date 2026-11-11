# Frontend style guide

How the app looks and how its pages are built. Home, Jobs, Approvals (list and plan page) and Schedule follow it. New pages and changes to those pages must too.

The rule behind every section: **each page has its own purpose, but the same thing always looks the same.** If you are about to style something that already exists on another page (a status, a table, an error), reuse the existing piece. Do not make a new one.

Before you write frontend code, read the Next.js docs in `node_modules/next/dist/docs/`. This is Next 16 and it has breaking changes (see `AGENTS.md`).

---

## 1. Colour

One accent colour, and four tones for state. All of them are theme tokens in `src/app/globals.css`, so light and dark mode work without extra classes.

| Use | Token classes |
| --- | --- |
| Page, surfaces, borders | `bg-background`, `bg-card`, `bg-muted`, `border-border` |
| The one accent (buttons, selected, "mine", on track) | `bg-primary`, `text-primary`, `bg-primary/10`, `bg-primary/15` |
| Secondary text | `text-muted-foreground` |
| Warning (waiting for a person, estimated, attention) | `bg-warning/10`, `border-warning/40`, `text-warning-foreground` |
| Error or destructive | `bg-destructive/10`, `border-destructive/40`, `text-destructive-foreground` |
| Success (after an action) | `bg-success/10`, `border-success/40`, `text-success-foreground` |

Rules:
- **Never use a raw Tailwind hue** (`emerald-`, `amber-`, `blue-`, `teal-`, `rose-`, `red-`) for state or decoration. Use a token.
- **For text in warning, error and success colours, use the `-foreground` token** (`text-destructive-foreground`). Plain `text-destructive` is too dark in dark mode.
- **Colour never carries meaning alone.** A state always has an icon and text too.
- Contrast: `--primary` is `emerald-700` in light mode (white text on it passes AA) and `emerald-500` in dark. Do not switch a primary button to `emerald-600`.
- Someone else's data is **muted, not hidden.** The Schedule calendar draws your jobs in the accent and everyone else's in `bg-muted-foreground/15` with a `border-muted-foreground/70` edge. Do not use `bg-muted` alone for this: in light mode it is almost the same shade as the page.

## 2. Type

One font family (Geist Sans). Numbers that must line up use `tabular-nums`. `font-mono` is only for IDs and hashes.

| Use | Classes |
| --- | --- |
| Content heading (plan job names, dialog title) | `text-lg font-semibold leading-7` |
| Card or section title | `text-sm font-semibold leading-5` |
| Key figure (a stat, the plan's savings) | `text-3xl font-semibold leading-9 tabular-nums` (`text-2xl` below `sm`) |
| Body, table cells, a row's first line | `text-sm leading-5` (first line also `font-medium`) |
| Captions, table headers, labels, a row's second line | `text-xs leading-4 text-muted-foreground` |

- Weights: 400, 500 and 600. **No `font-bold`, no `tracking-tight`.**
- **No arbitrary sizes** (`text-[10px]`, `text-[11px]`). 12 px (`text-xs`) is the smallest text.
- A unit sits on the same line as its number, in caption size ("$42.10 /MWh").

## 3. Page anatomy

```
Toolbar:   [filters / segments ............ actions]    optional
Notice:    one Alert                                      optional
Content:   one or more Cards
```

- **No visible page title.** The sidebar already says where you are. Every page still has one `<h1 className="sr-only">` for screen readers. (The plan page is the exception: its job names are its heading, and it keeps a back link.)
- Blocks are separated by `space-y-6`.
- The toolbar is one row. Filters and segments go on the left, actions on the right. If it must wrap, whole groups wrap and a single control never sits alone on a line.
- **One primary action per screen** ("New job", "Review", "Approve"). Everything else is outline or ghost.
- Buttons in a toolbar are `size="sm"`. Icon buttons are `variant="ghost" size="icon" className="size-9"` with an `aria-label` and a `title`.
- **Refresh** is a ghost icon button that spins while loading. It sits in the toolbar's action group. It is never an outline button and never has its own line.
- The word for creating a job is **"New job"**, on every button.

## 4. Surfaces

- The one surface is the shadcn **`Card`** (`rounded-lg border bg-card shadow-sm`).
- A card with text inside uses `p-4 sm:p-5`.
- **A card that holds a table or a row list is flush** (`p-0`). The rows carry the padding, so hover and focus reach the edges.
- Inside a card, split sections with `divide-y`, a `Separator` or a `border-t`. **Do not nest a bordered box in a bordered box.**
- A full-height surface (the calendar) is a `Card` with `isolate`. `isolate` keeps its `z-index`es from painting over the top bar and its notification panel.

## 5. Tables and lists

**Tables** use the shadcn `Table` (`src/components/ui/table.tsx`). It scrolls sideways inside its own box, so the page never does.
- Header row: `h-10 px-4 text-xs font-medium`. Name the zone in time columns: `Start (CT)`, using `MARKET_ZONE_LABEL`.
- Cells: `px-4 py-3 text-sm`. A name column uses `max-w-0 truncate` and a `title`.
- Numbers and money: `tabular-nums`, right-aligned.
- A selected row: `data-state="selected"` with `data-[state=selected]:bg-primary/10`.
- Hide low-value columns on small screens (`hidden md:table-cell`). Do not shrink the text.

**Row lists** (Approvals, the Schedule list) are a `ul` with `divide-y` inside a flush Card.
- Row: `px-4 py-3`, the whole row is the link or button, hover is `hover:bg-muted/50`.
- Line 1: `text-sm font-medium`, truncating. Line 2: `text-xs text-muted-foreground`, parts joined with " · ".
- The right side (figure, status pill) is `shrink-0`. Stack the figure above the pill so the row works at 320 px.
- Extra actions go in a "⋯" menu, not as more buttons in the row.

## 6. Status

Use **`StatusBadge`** (`src/components/ui/status-badge.tsx`) for every status. It has one size, an icon, a label and one of four tones: `neutral` (quiet or finished), `primary` (on track), `warning` (waiting for a person), `destructive` (went wrong).

Do not build a pill by hand. Get the label, icon and tone from `src/lib/job-status.ts`:

```tsx
<StatusBadge {...jobStatus(job.status)} />        // a job
<StatusBadge {...planStatus(plan.reviewState)} /> // a plan
<StatusBadge {...entryStatus(entry.status)} />    // a calendar entry
```

A new status goes into the map in that file. The same state must look the same on every page.

`EstimatedPriceBadge` marks anything priced from estimates, not forecasts. Reuse it.

## 7. States

Every list or card handles loading, error and empty the same way.

| State | Page level | Inside a card |
| --- | --- | --- |
| Loading | `Skeleton`s shaped like the content | same |
| Error | `Alert variant="destructive"` with the message and an outline `sm` **Retry** button | a muted "Could not load …" line and an outline `sm` Retry |
| Empty | one muted `text-sm` line and at most one button. No large icons. | same |
| Success after an action | `Alert variant="success"` with an icon, `role="status"` | n/a |
| Warning | `Alert variant="warning"` with an icon | n/a |

Do not show loading as plain text ("Loading…").

## 8. Controls

- **Segmented choice** (Mine/All, Pending/Approved, Week/Month/List): use **`Segmented`** (`src/components/ui/segmented.tsx`). Do not build joined buttons by hand.
- **On/off filters** in a toolbar are `Toggle`s with an icon and a `title`, tinted with `data-[state=on]:bg-primary/15`.
- Text links are `Button variant="link"` (accent colour, underline on hover). No bare underlined `<a>`.
- Every icon-only control has an `aria-label`. Focus rings come from the shared components, so do not remove them.

## 9. Dialogs and details

- Sizes: `max-w-md` for details and confirmations, `max-w-xl` for forms.
- A **details** dialog is a definition list: label on the left (`text-muted-foreground`), value on the right, `grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm`. Long values wrap (`min-w-0 break-words`). Look at `EntryDialog.tsx` or the Jobs view dialog.
- Footer: a destructive action on the left, the main action on the right. **No "Close" button** when the × and Esc already close the dialog.
- The title says the action: "New job", "Edit job".
- IDs appear in details only, in `font-mono text-xs text-muted-foreground`. They are not table columns.

## 10. Dates, money and units

- **Dates:** `formatShortDateTime()` from `src/lib/date-utils.ts`. It gives "Wed 11 Nov, 09:00" (24-hour, no zone) and adds the year only when it is not the current one. Put the full date in a `title` with `formatMarketDateTime()`.
- **The time zone is stated once per surface:** in the column header, in the calendar's corner, or after a range in a dialog ("… 12:00 CT"). Never repeat it in every cell.
- **All times are the market zone (Central).** Convert with the helpers in `date-utils.ts`. Do not use `new Date().toLocaleString()` for display.
- **Money:** `money()` from `src/lib/format.ts`. Savings read "Saves $x" or "Costs $x more", with the percentage in brackets: "Saves $12.40 (8.1%)".

## 11. Never overflow

The most common bug in this app was text, badges or buttons running past their box. For every layout:

1. A grid or flex child that holds text gets `min-w-0`.
2. Long text uses `truncate` and a `title`. Use `line-clamp-2` only where two lines are fine.
3. Badges and pills are `whitespace-nowrap shrink-0`. If space is short, the neighbour truncates, never the badge.
4. A control and the control it belongs to sit in one non-wrapping container.
5. Card headers: the title block is `min-w-0 flex-1`, the actions are `shrink-0`.
6. Tables sit in the `Table` component. Charts sit in a parent with `min-w-0`.
7. No fixed pixel widths on content. Use `w-full`, `max-w-*` and grid columns.
8. The page itself never scrolls sideways.

Check every change at **320, 375, 768, 1024 and 1280 px**, in **light and dark**.

## 12. Before you open a PR

1. In `frontend/`: `npm run lint`, `npx tsc --noEmit`, `npm test` and `npm run build` all pass.
2. Search `src` outside the Forecast, Audit and Admin pages. Each of these must find nothing:
   `font-bold`, `tracking-tight`, `text-\[1[01]px\]`, `(bg|text)-(teal|blue|rose|amber)-`, `text-emerald-`, `bg-emerald-`.
3. Look at the page at the five widths above, in both themes. Force an error, an empty list and a slow load, and compare them with the table in section 7.
4. Say in the PR what you checked and what you did not.

## 13. Where things live

| Need | Use |
| --- | --- |
| Status pill and its label, icon and tone | `src/components/ui/status-badge.tsx`, `src/lib/job-status.ts` |
| Segmented choice | `src/components/ui/segmented.tsx` |
| Alert (default, destructive, warning, success) | `src/components/ui/alert.tsx` |
| Estimated-price flag, help marker | `estimated-price-badge.tsx`, `help-tip.tsx` |
| Dates and the zone label | `src/lib/date-utils.ts` |
| Money | `src/lib/format.ts` |
| Loading data on a card (loading, error, retry) | `src/components/home/use-load.ts` |
| Stat card, needs-approval card | `src/components/home/` |

## 14. Not covered yet

The Forecast, Audit and Admin pages were not part of this pass and still use older styling (raw colours, bold titles, small text). When you change one, bring it up to this guide rather than copying what is there.

The sign-in page shows a two-layer illustration (`public/login/`) beside the card, hidden below `lg`. The panel background `#f1f8f8`, the ground fill `#0e1313` and the sun's colours match the source image and are the only raw colours allowed on that page.
