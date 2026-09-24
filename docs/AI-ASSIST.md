# F22 — AI Assist (prompt + voice) — plan

> Split out of CLAUDE.md. Read before building any Gemini-facing UX.
> Status: **planned**. Nothing here is implemented yet.

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

## Surface 3 — Cooking Mode Q&A

**Entry:** a mic/ask button on the step screen. **Read-only — it must never mutate the recipe.**

**Context sent (deliberately small, for latency and cost):** recipe title, the *current*
step text, that step's resolved ingredients (scaled, in the active unit), and adjacent step
titles. Not the whole recipe.

Answers: 1-3 sentences, plain language, optionally spoken back via TTS since hands are busy.

**The valuable follow-on:** when the user says *"I used 3 cloves instead of 2"*, do not
silently edit. Record it and offer **"Save this change to the recipe?"** at the end of
cooking — which lands them in the Surface 1 preview sheet. This directly serves "modify it
as and when they use it" without risking an edit mid-cook.

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
| `askCookingQuestion` | `{ recipeTitle, step, ingredients[], question }` | `{ answer }` |
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

## Suggested order

1. **Surface 1** (edit ops + preview sheet) — highest value; directly serves "wing it" users
2. **Surface 2** (voice) — small once the box exists; big usability win in the kitchen
3. **Surface 3** (cook-mode Q&A + "save this change?")
4. **Surface 4** (NL search — *not* feed curation)

Ship 1 to the testers before building 2-4; the preview sheet is the part most likely to need
reshaping once real non-technical users touch it.
