# F22 — AI Assist (prompt + voice) — plan

> Split out of CLAUDE.md. Read before building any Gemini-facing UX.
> Status: **planned**. Nothing here is implemented yet.
> Build order: **1.** edit screen (+ voice) · **2.** cook-mode Q&A (+ forking) · **3.** Discover NL filtering.

## Why

The target users are **not tech-savvy and deliberately imprecise** — they "wing it" on
quantities and adjust recipes while cooking. The app already gives them many ways to
edit, but each one asks them to find the right field first. AI Assist inverts that:
they say what they want in their own words, and the app finds the field.

Two principles fall out of that, and everything below follows them:

1. **Say it, don't find it.** A prompt box (typed or spoken) replaces "navigate to the
   right row, tap, open the sheet, find the field".
2. **Show the change before it happens.** Non-technical users will not trust an edit they
   cannot see. Every AI mutation is previewed as before → after and must be accepted.

**Imprecision is a supported input, not an error.** `IngredientEntity` already allows a
free-text `quantityDisplay` with a null `quantityValue` ("a handful", "a splash"). The
assistant must preserve vague quantities verbatim and never force a number. It may
*offer* ("about 15 g?") — never insist.

---

## Architecture — edit operations, not rewritten recipes

The critical decision. Two options for "edit this recipe with a prompt":

| Approach | Verdict |
|---|---|
| Send recipe → Gemini returns the **whole modified recipe** → diff client-side | Model silently rewrites unrelated lines; large payload; slow; diffing is guesswork |
| Send recipe + prompt → Gemini returns a **small list of edit operations** | Precise, cheap, reviewable, maps 1:1 onto ops the ViewModel already has |

Go with **operations**. They compose directly with the existing `EditDraft` API
(`updateIngredientAnywhere`, `deleteIngredientAnywhere`, `addIngredient`,
`moveIngredientToSection`, `updateStepAnywhere`, `reorderIngredient`, ...), so the AI path
reuses the edit path rather than forking it. It also means an AI edit is just a draft
change — **the existing Save/Cancel already provides undo.**

### Operation schema

Every op names a **stable id** from the recipe that was sent, plus a one-sentence
`reason` used as the diff caption.

```jsonc
{ "op": "updateIngredient", "id": "ing-3", "reason": "Doubled the garlic",
  "fields": { "name", "quantityDisplay", "quantityValue", "quantityUnit",
              "isOptional", "shoppingNote" } }
{ "op": "addIngredient",    "sectionId": "sec-1", "after": "ing-2", "reason": "...",
  "fields": { } }
{ "op": "deleteIngredient", "id": "ing-5", "reason": "..." }
{ "op": "updateStep",       "id": "step-2", "instruction": "...", "reason": "..." }
{ "op": "addStep",          "sectionId": "sec-1", "after": "step-1",
                            "instruction": "...", "reason": "..." }
{ "op": "deleteStep",       "id": "step-4", "reason": "..." }
{ "op": "updateMeta",       "reason": "...",
  "fields": { "title", "description", "baseServings", "prepTimeMinutes",
              "cookTimeMinutes", "tags" } }
```

**Deliberately omitted: a bulk `scaleAll`.** "Double it" while *cooking* is the existing
yield scaler (non-destructive, already built). Only route scaling through AI when the user
wants it **persisted**, and then emit explicit per-ingredient `updateIngredient` ops so
every change is visible in the diff. Never hide a bulk mutation behind one line.

### Safety rules (server-side, non-negotiable)
- **Reject ops whose `id` is not in the recipe that was sent.** A hallucinated id drops
  that op — it does not fail the request.
- **Cap the op count** (~25). A prompt wanting more is a rewrite, not an edit.
- **Partial success is success** — apply the valid ops, report the dropped ones. Same
  lesson as the title/sections fixes: never discard a good result over one bad field.
- Ops are applied to the **draft**, never straight to Room.

---

## Surface 1 — Edit screen (build first)

**Entry:** a persistent prompt bar pinned at the bottom of the detail screen in edit mode
— text field + mic + send. Pinned, not inline, so it is reachable without scrolling (the
same mistake as "Format with Gemini" sitting below a long text area).

**Flow:** prompt -> `editRecipeWithPrompt` -> ops -> **change-preview sheet**.

The preview sheet is the whole feature. For each op show:
- the **target line** as it appears in the recipe (so they recognise it),
- **before -> after**, with changed words emphasised,
- the `reason` caption,
- a per-op toggle, plus **Apply all** / **Cancel**.

On apply, ops feed the existing draft mutators, the body re-renders live, and Save/Cancel
behave exactly as today.

**Blank-box problem:** non-technical users freeze at an empty prompt. Ship 4-5 tappable
example chips seeded from the actual recipe — *"double the garlic"*, *"swap butter for
oil"*, *"make it serve 6"*, *"add a step to rest the dough"*.

## Surface 2 — Voice input (small; do right after Surface 1)

Voice is **an input method for the same box**, not a second pipeline. Transcribe -> put the
text in the prompt field -> let the user see and correct it -> send.

- Android: `SpeechRecognizer` / `RecognizerIntent` (on-device, free, no Gemini cost)
- iOS: `SFSpeechRecognizer` (needs `NSSpeechRecognitionUsageDescription` + mic permission)

Always show the transcript before sending — speech errors on ingredient names are common,
and silent failure is worse than a slow tap. This matters most in Cooking Mode, where
hands are messy (already a stated design principle).

## Surface 3 — Cooking Mode Q&A (+ end-of-cook "save what you changed")

**Entry:** a mic/ask button on the step screen. **Read-only — it must never mutate the recipe,
not even the draft.** Nothing is written to Room while the user is cooking.

**Context sent (deliberately small, for latency and cost):** recipe title, the *current*
step text, that step's resolved ingredients (scaled, in the active unit), and adjacent step
titles. Not the whole recipe.

Answers: 1–3 sentences, plain language, optionally spoken back via TTS since hands are busy.

### Capturing deviations

When the user states a change — *"I used 3 cloves instead of 2"*, *"skipped the cream"* — the
assistant emits the **same edit ops as Surface 1** into a **session-scoped buffer**
(`CookSession.pendingOps`, held in the ViewModel, cleared on exit). It does **not** apply them.
Ops are keyed to the ids of the recipe being cooked.

### The end-of-cook prompt

The "All done" page already exists (separate page, Back button, heart prompt). Add a **third
block, below the heart, entirely skippable**:

> **You changed 3 things while cooking.** Save them as a variation?
> · Garlic — 2 cloves → **3 cloves**
> · Cream — *removed*
> · Step 4 — "simmer 10 min" → **"simmer 15 min"**
> `[ Save as variation ]` `[ Not now ]`

Rules:
- **Skip is the safe default.** "Not now" discards silently — no confirm, no nag, no badge.
- The block is **absent entirely** when the buffer is empty. Most cooks see the page unchanged.
- **Never replaces the original.** Saving always creates a new recipe.
- Variation name is auto-suggested from the ops (*"More garlic"*), editable, 20-char cap
  (`MAX_VARIANT_NAME_LEN`).

### Saving — ids must be remapped

`duplicateAsVariant` mints **fresh ids for every section / ingredient / step**, remapping the
scale anchor, step→ingredient refs and substitute groups. So the pending ops — keyed to the
*original* ids — cannot be applied after the copy without translation.

**Duplicate first, then apply ops through the id map.** Extend `duplicateAsVariant` to return
the `ingredientIdMap` / `stepIdMap` / `sectionIdMap` alongside the new id; translate each op's
`id` before applying. (Surface 1 needs the same plumbing when an AI edit lands on a variation,
so this is shared work, not cook-mode-specific.)

### Two edge cases that will bite

- **Variation cap.** `MAX_VARIANTS = 4`. If the base is full, offer *"Replace a variation"*
  (picker) or *"Save as a new recipe"* — never silently drop the user's changes.
- **Cooking a received recipe.** `canAddVariant` is already `isOwner && …`, so a received
  recipe can't take a variation. The end-of-cook save falls through to the **fork** path below
  and the button reads **"Save as my own version"**. One code path, two labels.

---

## Forking someone else's recipe (received → my own version)

The problem: a user wants to adapt a recipe someone shared with them, but **someone else's
recipe must never be editable**.

### Why received recipes are read-only (the real reason)

It isn't only an ownership principle — it's data integrity. A received recipe is a **live
reference**, not a copy: `RecipeSyncService.syncReceivedRecipes()` re-reads
`shared_recipes/{recipeId}` and **replaces the same Room rows**, because `cacheReceivedRecipe`
deliberately preserves the canonical ids. **Any local edit would be silently destroyed on the
next refresh.** That is the argument to give the user, and it's why "just let them edit it
locally" is not an option.

### The rule

> **A variation of someone else's recipe is a recipe of your own, not a branch of theirs.**

Mechanically this is a **fork**: `duplicateAsVariant` already does ~95% of it — fresh ids
throughout, `authorId = currentUid`, `visibility = "private"`, `isReceived = false`, fully
self-contained. Only the parent pointer is wrong for this case.

### ⚠️ Do NOT reuse `parentRecipeId` across an ownership boundary

`duplicateAsVariant` sets `parentRecipeId = source.parentRecipeId ?: source.id`. If the source
is received, that parent is a **foreign canonical id**, and three things break:

1. The parent row is **replaced wholesale** on every received-refresh.
2. The user can **Remove** the received recipe — the parent row disappears.
3. List queries filter `parentRecipeId IS NULL`, so an orphaned variation shows in **no list**
   and has no parent to reach it from. **The user's own work becomes invisible.** Data loss.

### Instead: attribution fields, not hierarchy

A fork is a **base recipe** (`parentRecipeId = null`) carrying provenance in new, purely
descriptive columns on `RecipeEntity`:

| Field | Purpose |
|---|---|
| `forkedFromRecipeId: String?` | canonical id of the source mirror |
| `forkedFromAuthorId: String?` | original author's uid |
| `forkedFromAuthorName: String?` | display name, for the "Adapted from …" line |
| `forkedFromVersion: Int?` | source `version` at fork time — enables *"the original was updated"* |

These affect **no query and no hierarchy**, so nothing breaks when the source is removed,
changed, or unshared. The user's own variations then branch off their fork normally via
`parentRecipeId`, entirely inside their own tree.

Additive columns → a plain `ALTER TABLE` migration; SwiftData auto-migrates on iOS.

### UX

On a received recipe the actions become **Remove · Cooking Mode · "Make my own version"** —
*not* "Edit". The wording matters: it sets the right expectation instead of implying they're
changing the shared copy.

Tap → copy lands in **My Recipes** as a private base, titled *"Butter Chicken (my version)"*,
opens straight in edit mode, and shows a permanent **"Adapted from B's Butter Chicken"** line
on the detail screen.

Optional follow-on, once `forkedFromVersion` exists: *"B updated the original since you forked
this — view what changed."* Show it; never auto-merge.

### Re-sharing a fork — decide this now, it's cheap now and expensive later

If C forks B's recipe and shares it publicly, B's work is being redistributed.

**Recommendation: make provenance sticky, don't block sharing.** Carry
`forkedFromAuthorName` into the `shared_recipes` mirror and render the "Adapted from …" line
on shared cards and detail, **not removable by the forker**. Blocking re-sharing would be
over-policing for a friends-and-family app; unattributed re-sharing turns the community tab
into a laundering machine. Sticky attribution is the cheap middle.

## Surface 4 — Discover (lowest priority — and scope it down)

**Honest recommendation: do not put AI curation on the Discover feed.** Browsing is
latency-sensitive and users are not articulating intent when they open a tab; `DiscoverRanker`
already handles meal-time, cuisine affinity and cooked-recency deterministically, for free,
offline. Swapping that for a paid network round-trip is a poor trade.

**Do this instead: natural-language *search*.** Intent is explicit, so AI earns its keep:
> "what can I make with paneer in 20 minutes?"

Implement as **query -> structured filters** (tags, max total time, must-have ingredients),
then apply those filters to the *existing* ranker. Cheap, debuggable, degrades to plain text
search offline, and does not touch the feed.

---

## Cloud Functions to add

| Function | Input | Output |
|---|---|---|
| `editRecipeWithPrompt` | `{ recipe: slimRecipe, prompt }` | `{ operations[], notes }` |
| `askCookingQuestion` | `{ recipeTitle, step, ingredients[], question }` | `{ answer, operations[] }` — ops are **buffered**, never applied |
| `parseSearchQuery` (Surface 4) | `{ query }` | `{ tags[], maxMinutes, mustInclude[] }` |

`slimRecipe` = ids, names, quantity displays, step text, section ids. **Strip conversions,
changelog, notes, imageUrl** — irrelevant to editing and they inflate every call.

Reuse the existing hardening: the `callGemini` JSON-repair path, and the
validate-don't-throw principle from the title/section fixes.

## Cross-cutting requirements

- **Offline.** The app is offline-first by design. AI affordances must **disable with a
  visible reason** when there is no connectivity, and never block a manual path. Every AI
  action needs a non-AI equivalent.
- **Cost/latency.** `gemini-2.5-flash`, `thinkingBudget: 0`. Never send the whole recipe
  where a step will do. Expect ~1-3 s; show progress and keep the UI usable.
- **Abuse.** All three are callable functions — add **App Check** before any public release,
  and rate-limit per uid.
- **Never auto-apply.** No AI mutation lands without an explicit user tap. Applies to
  Surfaces 1 and 3 without exception.

## Build order (confirmed with the user)

**Phase 1 — Edit screen.** Surface 1 (edit ops + preview sheet), then Surface 2 (voice into the
same box). The preview sheet is the part most likely to need reshaping once real non-technical
users touch it, so ship this to the testers before building anything else.

**Phase 2 — Cook mode.** Surface 3 (Q&A + the skippable end-of-cook "save as a variation"),
which needs the **fork** work above — do the `forkedFrom*` columns and the
"Make my own version" action as part of this phase, not later.

**Phase 3 — Discover.** Surface 4, natural-language **filtering** only. Explicitly **not** AI
curation of the feed — `DiscoverRanker` keeps ordering the results.

### Confirmed decisions

- Gemini returns **edit operations only**, never a rewritten recipe.
- Every AI change is **shown before it applies**; nothing auto-applies.
- The end-of-cook save prompt is **optional and skippable**, appears **only at the end**, and
  **saves as a variation** — it never overwrites the original.
- Discover gets **user-driven AI filtering**, not AI curation.
