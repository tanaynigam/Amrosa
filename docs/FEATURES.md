# Core Features (F1–F13) — detailed spec

> Split out of CLAUDE.md to keep the auto-loaded context small. Read this when working on a specific feature.

## Core Features

### F1 — Recipe Storage

**Room DB v13** — all entities below are current.

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val sourceUrls: String,              // JSON List<String>
    val baseServings: Int,
    val baseServingsMin: Int? = null,    // yield range low (e.g. 15 for cookies)
    val baseServingsMax: Int? = null,    // yield range high (e.g. 20 for cookies)
    val scaleIngredientId: String? = null, // anchor ingredient for scaling
    val scaleStep: Double = 1.0,         // increment per +/− tap on anchor
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: String,                    // JSON List<String>
    val isCustomized: Boolean = false,
    val isImported: Boolean = false,     // URL/file import vs typed; drives "Imported by X" label
    val isReceived: Boolean = false,     // true = received from another user → Tab 2, read-only (v2)
    val needsReview: Boolean = false,    // true = imported but not yet confirmed by user
    val version: Int = 1,
    val changeLog: String = "[]",        // JSON List<RecipeChange>
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long? = null,
    val authorId: String? = null,        // Firebase UID of the ORIGINAL author (always preserved)
    val authorDisplayName: String? = null, // ORIGINAL author display name (never overwritten with "Imported")
    val visibility: String = "private",  // "private" | "shared" (specific people, F17) | "friends" (Co-Chefs) | "public" — shared/friends/public mirrored
    val sharedWith: String = "[]",        // F17: JSON List<String> recipient UIDs (ACL for visibility == "shared")
    val parentRecipeId: String? = null,  // F10: null = base recipe; else id of the base this varies (variations)
    val variantName: String? = null      // F10: e.g. "Spicy", "Vegan" — only set on variations
)
// isReceived added via MIGRATION_9_10. Tab 1 = isReceived=false; Tab 2 = isReceived=true.
// parentRecipeId + variantName added via MIGRATION_10_11 (non-destructive). See F10.
// authorId == null special-case (old "official/seeded") is retired — see Recipe Ownership Model.
```

```kotlin
@Entity(tableName = "recipe_sections")
data class RecipeSectionEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val name: String,
    val orderIndex: Int
)
```

```kotlin
@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val name: String,
    // Original quantity (preserved exactly from source)
    val quantityValue: Double?,
    val quantityUnit: String?,
    val quantityDisplay: String?,
    val groupLabel: String?,
    val isOptional: Boolean = false,
    val substituteGroupId: String?,
    val substituteRatio: Float = 1.0f,
    val orderIndex: Int,
    // F6 — unit conversions. MUST remain at end of field list (positional seeder calls).
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null,
    val shoppingNote: String? = null,   // F11: author-entered brand/comment, shown on the Shopping List
    // F15 — quantity ranges (e.g. "4–6 cloves"). null = single value. Unit/display shared
    // with the min; the range is rendered by scaling both ends. Room v14.
    val quantityValueMax: Double? = null,
    val quantityValueMaxMetric: Double? = null,
    val quantityValueMaxImperial: Double? = null
)
```

```kotlin
@Entity(tableName = "steps")
data class StepEntity(
    @PrimaryKey val id: String,
    val recipeId: String, val sectionId: String?,
    val instruction: String, val orderIndex: Int
)

@Entity(tableName = "step_ingredient_refs", primaryKeys = ["stepId", "ingredientId"])
data class StepIngredientRefEntity(val stepId: String, val ingredientId: String, val quantityDisplay: String?)

@Entity(tableName = "recipe_notes")
data class RecipeNoteEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val content: String,
    val createdAt: Long, val updatedAt: Long
)

// F11 — persisted shopping-list checks. Presence of a row = checked. Local only (never synced).
@Entity(tableName = "shopping_checks", primaryKeys = ["recipeId", "itemKey"])
data class ShoppingCheckEntity(val recipeId: String, val itemKey: String)  // itemKey = normalized ingredient name

// F13 — last-cooked timestamp per recipe (Cooking Mode "Done"). Drives Discover recency. Local only.
@Entity(tableName = "cooked_log")
data class CookedLogEntity(@PrimaryKey val recipeId: String, val cookedAt: Long)
```

**Domain model** (mapped from entities, used by ViewModels and UI):
```kotlin
data class Comment(
    val id: String,
    val recipeId: String,
    val authorId: String,
    val authorDisplayName: String,
    val content: String,
    val createdAt: Long
)
```
Comments are stored in Firestore only (`shared_recipes/{recipeId}/comments/{commentId}`), not in Room.

#### Scaling modes
- **Servings-based** (default): `baseQuantity × selectedServings / baseServings`
- **Anchor-ingredient-based**: +/− adjusts anchor by `scaleStep`; all others scale proportionally.

---

### F2 — Personal Recipes & Cloud Sync

**Firestore collections (current):**

| Collection path | Contents |
|---|---|
| `recipes/` | Was seeded recipes (pull-only). Now **empty** — seeded docs deleted, seeder disabled. |
| `personal_recipes/{uid}/recipes/{recipeId}` | User's personal recipes — push on save, pull on sign-in |
| `shared_recipes/{recipeId}` | Mirror for shared recipes. `visibility` = `"shared"` (specific people) · `"friends"` (Co-Chefs) · `"public"` — **never `"private"`** (rules reject create/update unless visibility ∈ {shared, friends, public}). Read gated by rules: public → anyone; friends → author + accepted co-chefs; **shared → author + UIDs in the doc's `sharedWith` array** (per-recipient ACL, F17). `shared` docs are excluded from Discovery/profile (those query friends/public only). Mirror reconciled with the canonical recipe: private/delete removes it; `saveEdit` re-publishes (shared/friends/public) or unpublishes. F13: `saveCount`/`likeCount` counters + `likes/{uid}`. |
| `shared_recipes/{recipeId}/comments/{commentId}` | Comments on shared recipes |
| `users/{uid}` | Public user profile — displayName, photoUrl, email, updatedAt, **fcmToken**. Created/merged on each sign-in via `SocialRepository.upsertProfile()`. Used for user search and FCM push delivery. |
| `follows/{followerId}_{followeeId}` | Co-Chef relationship. Fields: followerId, followerName, followeeId, followeeName, status ("pending"\|"accepted"), createdAt. Composite indexes required: (followeeId, status) and (followerId, status). |
| `notifications/{uid}/items/{notifId}` | Notification inbox — written client-side, read by `sendPushNotification` Cloud Function to dispatch FCM push. Fields: type, fromUid, fromDisplayName, shareId?, recipeName?, createdAt, read. |
| `shared_to/{recipientUid}/recipes/{shareId}` | Recipes shared directly to a specific user. Full recipe data + fromUid + fromDisplayName + sharedAt. Only the sender can write; only the recipient can read. |

**Sync behaviour:**
- **Pull sync** on app launch / sign-in: `pullPersonalRecipes()` fetches all docs in `personal_recipes/{uid}/recipes/`. For each, **last-write-wins by `updatedAt`**: if the local copy is newer (an offline edit not yet pushed) it is kept; otherwise the cloud copy replaces it. Then `pushAllPersonalRecipes()` pushes every local personal recipe back up.
- **Conflict resolution + clean replace**: when the cloud copy wins, content is written via `RecipeDao.replacePulledRecipe` (Android) / `replaceSyncedContent` (iOS), which **clears the recipe's synced children (sections/ingredients/steps/refs) before re-inserting** — so a child removed on another device doesn't linger as an orphan. Local-only `recipe_notes` + `shopping_checks` are preserved. (iOS additionally guards with `firestoreUpdatedAt > existing.updatedAt`; previously it updated only metadata on an existing recipe, so cross-device content edits never propagated — now fixed.)
- **Push sync** on personal recipe save: `RecipeSyncService.pushPersonalRecipe(recipeId)` → `personal_recipes/{uid}/recipes/{recipeId}`. Fire-and-forget — push failure does not block local save.
- **Sync on sign-in**: `ChefsJournalApplication.authStateFlow` observer calls `syncPersonalRecipes()` + `syncReceivedRecipes()` whenever a real user is detected.
- **Received recipes** (Tab 2) are read-only references — always take the mirror, via `replacePulledRecipe` (no timestamp guard, but still orphan-safe).
- ⚠️ *Remaining minor gap*: the editor's post-save push runs in the editor `viewModelScope` and can be cancelled mid-pop; the launch-time `pushAllPersonalRecipes()` self-heals it.

**Seeder:** `DatabaseSeeder.seedIfNeeded()` is a **complete no-op**. Fresh installs start blank. Bump seed key together with DB version if schema changes, but no seeding logic runs.

**DB versioning:** Current **DB v13**. Real Room migrations are registered in `AppContainer` (`MIGRATION_9_10` adds `isReceived`; `MIGRATION_10_11` adds `parentRecipeId` + `variantName`; `MIGRATION_11_12` adds `ingredients.shoppingNote` + the `shopping_checks` table; `MIGRATION_12_13` adds the `cooked_log` table) so user data survives schema bumps. `fallbackToDestructiveMigration()` remains only as a safety net for unhandled jumps. Because real users now have data, **prefer adding a `MIGRATION_(n)_(n+1)` (ALTER TABLE / CREATE TABLE) over relying on destructive fallback** when changing the schema.

**Version control:** Every editor save increments `version` and appends `RecipeChange(version, timestamp, summary)` to `changeLog`. Summary is auto-generated from what fields changed.

---

### F3 — Recipe Import (URL, File, Sheets, Docs)

All paths: Cloud Functions → Gemini 2.5 Flash → JSON → review sheet → Room.

#### Cloud Functions (all deployed)

| Function | Input | Description |
|---|---|---|
| `parseRecipeUrl` | `{ url }` | Regular URLs, Google Sheets, Google Docs (auto-detected) |
| `parseRecipeContent` | `{ content, type, fileName }` | XLSX (base64), CSV, plain text |
| `formatRecipeText` | `{ text }` | Freeform natural language → structured recipe (F5) |

All functions: `gemini-2.5-flash`, `thinkingBudget: 0`, `application/json` response MIME, `parseNotes` field for Gemini uncertainty notes.

**Collective step references (`stepIngredientRefs`):** the schema prompt instructs Gemini that when a step refers to ingredients as a group ("add all the paste ingredients", "combine the marinade ingredients") it must emit a `stepIngredientRef` for **every** ingredient in that group — never leave one unreferenced. As a deterministic safety net, `validateRecipe` → `linkOrphanIngredients()` (parseRecipe.js) attaches any ingredient that no step references to the **first step of its section**, so every ingredient surfaces in cooking mode. The Android side mirrors this at render time: `augmentedStepRefs()` in `RecipeDetailScreen.kt` does the same fallback for already-imported recipes (no re-import needed). Both are no-ops when refs are complete.

#### Import screen (push route — not a tab)

`ImportScreen` is a **push route** accessed from the Add Recipe FAB in My Recipes. Route: `"import?reviewId={reviewId}"`. Optional `reviewId` param auto-opens the review sheet for a specific recipe.

The screen contains:
- URL import card + Import button
- File import card (`.xlsx`, `.csv`, `.txt`; 5 MB max)
- Google Sheets / Docs hint card
- Back button in TopAppBar (it's a push route, not a tab)

#### Review flow (pending-review model)

1. Parse result → recipe immediately saved to Room with `needsReview = true, isImported = true`
2. `RecipeReviewSheet` opens
3. User reviews: **Author toggle** → **Confirm** / **Reimport** / **Edit icon**
4. **Author toggle** (segmented: `[Imported] | [My Recipe]`):
   - `Imported` (default) → on confirm, `isImported = true` → "Imported" shown as author when shared
   - `My Recipe` → on confirm, `isImported = false` → real user name shown as author when shared
5. **Confirm** → clears `needsReview`, applies `isImported` from author toggle
6. Back gesture → recipe stays in Room with `needsReview = true` — no data lost
7. My Recipes tab shows pending recipes first with "Needs review" badge; tapping re-opens the review sheet

#### `RecipeReviewSheet` (shared composable — `ui/import_recipe/RecipeReviewSheet.kt`)

```kotlin
@Composable
internal fun RecipeReviewSheet(
    parsed: ParsedRecipeData,
    primaryLabel: String,        // "Confirm" (import) or "Save Recipe" (freeform)
    secondaryLabel: String,      // "Reimport" (import) or "Reformat" (freeform)
    isSecondaryLoading: Boolean = false,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,               // non-null → pencil icon in header
    isOwnRecipe: Boolean = false,               // import flow only
    onIsOwnRecipeChange: ((Boolean) -> Unit)? = null  // non-null → shows author toggle
)
```

Freeform flow passes `null` for `onIsOwnRecipeChange` — author toggle is hidden (freeform always saves as personal).

---

### F4 — Inline Recipe Editing (F14 → F16: jiggle + tap-to-edit popups)

There is **no separate edit page**. The recipe **detail screen has a global Edit toggle** (pencil,
owners only). In edit mode the screen stays **visually identical to the read-only view** — the same
`LazyColumn` renders the **live draft as a `Recipe`** (`EditDraft.toPreviewRecipe(base)` in
`RecipeDetailViewModel.kt`, shown at 1× / original units). Every editable element **jiggles** with a
faint outline (`Modifier.editable(active, phase, onClick)` in `ui/util/EditAffordance.kt`) and **a tap
opens a bottom sheet** scoped to that item. Nothing is edited inline. Save ✓ + a ＋ add-menu live in
the pinned top bar; Cancel is the nav X.

#### Design intent & rationale — **port the *intent*, not just the widgets** (READ before iOS work)

iOS keeps implementing the mechanics without the reasoning, so it diverges. The *why* behind every
edit-mode decision, as a set of **invariants** both platforms must honour:

1. **One screen, visually identical in both modes.** The detail screen **is** the edit surface — not a
   separate form. *Why:* the old separate editor was "too confusing and crowded" and broke the user's
   place; editing should feel like marking up the recipe you're reading. So edit mode renders the **live
   draft through the same view components** (Android: `toPreviewRecipe` → the normal row composables). If
   you build a parallel edit layout, you've already lost the intent.
2. **Zero scroll shift when toggling edit — the non-negotiable.** *Why:* the literal user requirement is
   "scroll to step 3, toggle edit, **stay on step 3** and edit it right there." **Every** other rule below
   exists only to serve this. If toggling edit moves the page even slightly, the design has failed. This is
   why we: keep identical item slots + keys; swap controls **in place** (never add/remove whole sections on
   toggle); **reserve space** for edit-only adds; keep owner chrome (visibility + variation chips) **visible
   but greyed** instead of removing it; hold **constant-height** rows (yield); and only hide things that sit
   **below the fold** (Notes/Comments). Each greyed chip / reserved spacer / fixed height is plugging one
   specific source of vertical shift — not arbitrary polish.
3. **Jiggle + outline = the "editable" signal.** *Why:* a familiar, **layout-neutral** way to say "these
   elements are editable" (home-screen jiggle). It MUST use rotation + an overlay border that **don't
   reflow** — a real border/padding would violate invariant #2. It removes the need for inline form fields.
4. **Tap → a focused per-item popup, never inline fields.** *Why:* the old form's failure was showing
   *every field for every item at once*. The sheet edits **one item's** full detail (the fiddly bits:
   quantity ranges, units, optional flag, substitute links, step→ingredient links) in a calm, focused
   context, so the reading view stays clean and identical.
5. **Draft + explicit Save/Cancel.** *Why:* edits accumulate in a draft and commit only on Save (Cancel
   discards). The body shows the live draft so changes are visible immediately, but nothing persists until
   Save → predictable and reversible. (This is also why switching variations is **disabled** mid-edit — it
   would silently discard the draft.)
6. **Adds are in-context.** Both platforms append faint **ghost "＋ Add" rows** right where the item belongs
   ("add an ingredient to *this* section, here") + a top-bar ＋ as a shortcut; view mode reserves the row's
   height so adding doesn't shift. *Why:* you should add an item where it will land, not via a detached menu.
   (iOS aligned to the ghost-row pattern in "Phase G".)

The optional/substitute **view** decisions follow the same "don't bloat the read view" intent: optionals
collapse to a one-line **chip row** per section (selecting *drops the ingredient into* the recipe — opt-in
model; default-included is a per-user setting); substitutes show **swap chips under the ingredient row**
(choose as you read) instead of a separate "Options" section; a step that "uses Butter" **resolves to the
selected substitute** (shows Ghee if chosen) rather than going blank; and both choices are **remembered per
recipe** so "I always make this with ghee, no cilantro" sticks next time.

- **Bottom sheets** — `ui/detail/RecipeEditSheets.kt` (`EditBottomSheet` switches on a sealed
  `EditTarget`): **Ingredient** (name, quantity, range max `quantityValueMax`, unit, group, optional,
  shopping note, Delete — numeric quantity edits drive scaling, free-text only when blank),
  **Step** (instruction + "Uses ingredients" `FilterChip`s, Delete), **Section** (name, reorder, Delete),
  **Details** (title, description, author, variation, prep/cook, yield range, tags, sources). Existing
  items update **live** (the row behind the sheet reflects edits); "new" items (id == null) commit on Add.
- **Adding** — both **ghost "＋ Add ingredient/step/section" rows** appended in edit mode (existing rows
  untouched) and the **top-bar ＋ menu** (targets the last section).
- **View ≈ Edit layout (minimal scroll shift)** — edit keeps the *same item slots* as view where it
  matters. The **visibility chip + variation chips** stay visible but **disabled** (greyed) in edit
  (variation-switch disabled to avoid discarding unsaved edits); the yield row holds a constant height
  (`heightIn(min=48)`). In-place swaps only: yield scaler → static yield (tap = Details sheet), optional
  checkbox → plain bullet, **unit-toggle slot → "Update conversions" chip**. Ghost "＋ Add ingredient/step"
  rows have a fixed height (`AddRowHeight`) and **view mode reserves the same height** with a Spacer after
  each list. **Notes & Comments are hidden in edit** (they sit below the fold, so hiding them doesn't shift
  the visible content); the **Add-section + Delete-recipe footer sits right after the steps** (where Notes
  would be). Top-bar view actions (Share/Cart/Cooking) are replaced by ＋/Save/Cancel.
- **Optional ingredients (view)** — each section renders a **chip row at its start** (`OptionalChipsRow`),
  one chip per optional ingredient; selecting a chip drops the ingredient into the list (it shows with a
  "· optional" tag), unselecting hides it (`visibleIngredients` filters optionals by `enabledOptionals`).
  The default selected set comes from **`UserPreferences.includeOptionalsByDefault()`** (default true), a
  toggle in the **Account → Ingredients** section. The view branch iterates `recipe.sections` so the chip
  row shows even when every optional is currently hidden.
- **Substitute ingredients** — view: a group collapses to the selected option with **swap chips inline
  under that row** (`onSelectSubstitute` → `selectSubstitute`); the old separate "Options" section +
  `SubstituteSelector` were removed. Edit: the ingredient sheet has a **"Substitute for" picker** of the
  other ingredients → `RecipeDetailViewModel.linkSubstitute(ingredientId, targetId?)` (shares/creates a
  `substituteGroupId`; null unlinks), so substitutes can be created on new or existing ingredients.
  Step refs to *any* group member resolve to the **selected** member via `RecipeDetailUiState.visibleStepRefs()`
  (one row per group), so switching a substitute keeps the step's ingredient visible (StepRow + cooking mode).
- **Remembered selections** — substitute choices + which optionals are included are **persisted per recipe**
  in `UserPreferences` (`recipeSubstitutes`/`recipeEnabledOptionals`, keyed by recipeId) and restored on the
  next visit (validated against the current recipe so stale ids are ignored); `selectSubstitute`/`toggleOptional`
  write through.
- **Empty sections** — in edit mode every section renders its header + a "＋ Add ingredient" row and a
  "＋ Add step" row (even when empty), so a freshly added section can be filled in place. In **view mode**
  a section with no ingredients shows no ingredient sub-header, and a section with no steps shows no step
  header (empty sections are hidden from each list).
- **All editing logic lives in `RecipeDetailViewModel`**: `isEditMode` + `EditDraft` (metadata +
  `List<EditorSection>` from `ui/edit/EditorModels.kt`) + `enterEdit()`/`cancelEdit()`/`saveEdit()` +
  per-field/section/ingredient/step **update/add/delete/move** ops (the sheets just drive these). `saveEdit`
  maps the draft to entities, `version++`, appends `RecipeChange`, `repository.updateFullRecipe(...)`, pushes
  to `personal_recipes`, re-publishes the mirror if friends/public; the Room-Flow observer reloads the view.
- **Step→ingredient refs** editable + persisted via `EditorStep.ingredientIds`; `updateFullRecipe`
  rewrites `step_ingredient_refs`. This is what cooking mode shows inline.
- **Entry points**: detail pencil (in-screen toggle, no nav); import/freeform "Edit" + a newly created
  variation navigate to `recipe/{id}?startEdit=true` (one-shot `startEdit` nav arg → `enterEdit()` on load).
- **Known gap**: a brand-new empty section has no ingredient block yet, so its first ingredient is added
  via the top-bar ＋ menu (which targets the **last** section) rather than an in-context ghost row.

---

### F5 — Freeform Recipe Entry ✅

FAB on My Recipes tab → ModalBottomSheet → two options:
- **"Type it out"** → `FreeformEntryScreen` (pushed)
- **"Import from URL or file"** → `ImportScreen` (pushed route)

`FreeformEntryScreen`: large `OutlinedTextField` (minLines=8) → "Format with Gemini" button → `formatRecipeText` CF → `RecipeReviewSheet` (Save Recipe / Reformat / Edit).

**`FreeformViewModel` key methods:**
- `formatWithGemini()` — calls CF, sets `parsedRecipe`
- `saveRecipe()` — saves to Room (`isImported=false, isCustomized=true, needsReview=false, authorId=currentUid, authorDisplayName=displayName`), sets `savedRecipeId` → `onBack()`
- `saveAndEdit()` — saves same way, sets `editRecipeId` → `onEditClick(id)`
- `reformat()` / `dismissReview()` — UI-only state resets

---

### F6 — Ingredient Unit Conversions ✅

#### Storage
Six fields at the **end** of `IngredientEntity` (must stay last — `DatabaseSeeder` uses positional calls): `quantityValue/Unit/DisplayMetric` and `Imperial`. Gemini populates all on import/freeform. Non-convertible quantities → all null.

#### `QuantityScaler` (`ui/util/QuantityScaler.kt`)
```kotlin
enum class UnitMode { ORIGINAL, METRIC, IMPERIAL }

object QuantityScaler {
    fun scale(quantityValue: Double?, quantityUnit: String?, quantityDisplay: String?, scale: Double): String
    fun scale(ingredient: Ingredient, scaleFactor: Double, unitMode: UnitMode): String
}
```

#### Detail screen
Unit toggle (`SingleChoiceSegmentedButtonRow`: Original | Metric | Imperial) shown only when ≥1 ingredient has conversion data. Session-level — not persisted. Scaling and unit conversion computed together in parallel.

#### Gemini conversion — metric only; imperial computed in code

**Gemini only produces metric fields.** Imperial is never computed by Gemini — it is computed deterministically by `computeImperialFromMetric()` in `parseRecipe.js` after Gemini returns.

**Gemini metric rules:**
- Volume → ml or L: 1 cup = 240 ml, 1 tbsp = 15 ml, 1 tsp = 5 ml, 1 fl oz = 30 ml
- Weight → g or kg: 1 oz = 28.35 g, 1 lb = 453.6 g
- If original is already metric (g, kg, ml, L) → copy to metric fields as-is
- Non-convertible (whole eggs, cloves, "to taste", counts) → all conversion fields null

**`computeImperialFromMetric(recipe)` logic (runs after Gemini):**
- `g` or `kg` → grams first; if `< 453.6g` → oz (`grams / 28.35`); if `≥ 453.6g` → lb (`grams / 453.6`)
- `ml` or `L` → ml first; → fl oz (`ml / 29.574`)
- **Adaptive rounding via `impRound()`** so small amounts don't collapse to `0`: `≥1` → 1 decimal, `≥0.1` → 2 decimals, `<0.1` → 3 decimals (e.g. 1 g → `0.035 oz`)
- Unknown or null metric unit → imperial = null
- This guarantees imperial is never in cups/tbsp/tsp — only oz, lb, or fl oz

Gemini's imperial fields are **zeroed in `validateRecipe()`** before `computeImperialFromMetric()` runs, so Gemini output is completely ignored for imperial.

#### Quantity ranges (F15) ✅
Ingredients can carry a range like **"4–6 cloves"**. Storage (Room **v14**, `MIGRATION_13_14`): three nullable upper-bound columns on `IngredientEntity` — `quantityValueMax`, `quantityValueMaxMetric`, `quantityValueMaxImperial`. `null` = single value (the common case). The **unit and display strings are shared** between the two ends; the range is rendered at display time, not stored as a string.

- **Rendering:** `QuantityScaler.scale(value, valueMax, unit, display, scale)` (5-arg overload; the 4-arg one delegates with `max = null`). When `valueMax > value`, it scales **both ends** → e.g. at 2× "4–6 cloves" becomes "8–12 cloves". `scale(ingredient, …, unitMode)` picks the right min/max pair per unit system. Every detail/cooking display already routes through this, so ranges appear automatically.
- **Gemini import:** Gemini sets `quantityValue` = low end, `quantityValueMax` = high end, writes `quantityDisplay` as the range. It does **not** compute metric/imperial maxes. `computeMaxConversions()` in `parseRecipe.js` scales the max conversions from the min ones by the `max/min` ratio (exact — shared unit), run after `computeImperialFromMetric()` in both the import and "Update conversions" paths.
- **Editing:** the inline editor's ingredient row has a small **"to [max]"** numeric field (`quantityValueMax`); metric/imperial maxes refresh via "Update unit conversions".
- **Limitation:** the Shopping List aggregates on the min value (`quantityValue`); ranges aren't summed as ranges there.

#### "Update unit conversions" button (existing recipes) ✅

For recipes that predate conversions or have stale ones, the **Recipe Editor** has an **"Update unit conversions"** button:
- `convertIngredients` Cloud Function: input `{ ingredients: [{id, name, quantityDisplay}] }`. Gemini produces **metric** per ingredient — **weight (g/kg) for dry/solid items using density** (so imperial becomes oz/lb), **volume (ml/L) for liquids** (imperial becomes fl oz). `computeImperialFromMetric` then fills imperial. Output `{ ingredients: [{id + 6 conversion fields}] }`.
- `RecipeDetailViewModel.updateConversions()` (inline edit mode) calls it and merges results into the edit draft; the user then **Saves** to persist (push to cloud + re-publish if public).
- Button is placed **directly above the ingredients/sections** in the editor (after the metadata card).
- **`EditorIngredient` carries the 6 conversion fields** and the save mapping writes them — fixing a prior bug where editing a recipe wiped its conversions.
- **Note:** after conversion, a dry ingredient's *Metric* column is weight-based (e.g. flour `120 g`, not `480 ml`) since imperial-as-weight (oz/lb) requires metric-as-weight. Original column is unchanged.

#### Density validation + self-growing density table (`parseRecipe.js`)

To stop Gemini from producing believable-but-wrong volume→weight numbers, the server validates and overrides densities:
- **Curated `DENSITY_TABLE`** (~12 common ingredients, substring-matched) is **authoritative** — when an ingredient matches, its density overrides Gemini's grams.
- **Bounded check:** any implied density (`grams / ml`) outside **0.1–2.5 g/ml** is rejected; the ingredient falls back to a volume conversion (ml → fl oz) so a wrong weight can never slip through.
- **Self-growing learned table** (`ingredient_densities` Firestore collection, Admin-SDK-only — no client rule): `convertIngredients` reads promoted learned densities, merges them **after** the curated table, and records every fresh Gemini-derived volume→weight observation that passes the bounds (running `sumDensity` + `count`, keyed by a normalized ingredient name). A learned density is **promoted/served only after 3+ sightings** (`LEARN_PROMOTE_COUNT`) so the table grows slowly for ingredients that recur. Priority: curated → learned → Gemini's raw value.

---

### F7 — Authentication ✅

#### Auth is mandatory

The app **requires** a signed-in account. `ChefsJournalNavGraph` observes `authStateFlow()` at the outermost level:
- `currentUser == null` or anonymous → renders full-screen `AuthScreen()` with no back button and no bottom bar (the main Scaffold/NavHost do not exist)
- Real signed-in user → renders `MainAppScaffold()` with all 4 tabs

Anonymous auth has been **removed** — `signInAnonymouslyIfNeeded()` is no longer called on launch.

#### Sign-out clears local data

`AccountViewModel.signOut()`:
1. Calls `app.container.clearAllLocalData(context)` — runs Room `database.clearAllTables()` + clears `chefsjournal_sync` SharedPreferences
2. Calls `authRepository.signOut()`
3. `authStateFlow` emits null → `ChefsJournalNavGraph` recomposes to auth gate automatically

**Sign-out dialog** warns the user: *"All recipes will be removed from this device. They'll sync back automatically when you sign in again."*

#### Auth providers
- **Google Sign-In** via `play-services-auth` (one-tap)
- **Email / Password** — sign-in and sign-up (with display name)
- **Phone OTP** via `PhoneAuthProvider` (60s timeout, auto-verification supported)

All methods: if current user is anonymous → `linkWithCredential`. Otherwise fresh sign-in. (Anonymous linking kept for forward compat but anonymous sessions are no longer created on launch.)

#### `AuthRepository` (`data/auth/AuthRepository.kt`)
```kotlin
val uid: String?             // current user UID (null if not signed in)
val displayName: String?     // display name from Firebase profile
val email: String?
val isSignedIn: Boolean      // true for non-anonymous users only
authStateFlow(): Flow<FirebaseUser?>
signInWithGoogle(idToken: String)
signInWithEmail(email, password)
signUpWithEmail(name, email, password)
signInWithPhoneCredential(credential: PhoneAuthCredential)
signOut()
```

#### `AuthScreen` (`ui/auth/AuthScreen.kt`)
Signature: `AuthScreen(onBack: (() -> Unit)? = null)`
- `onBack = null` (auth gate) → no TopAppBar, no back button. Full-screen wall.
- `onBack != null` (push route, if needed) → shows TopAppBar with back arrow; pops on successful auth.

Contains: "Amrita & Ambrosia" heading, Sign In | Create Account segmented button, Google button, email/password form, phone OTP flow.

#### `AccountScreen` + `AccountViewModel` (`ui/account/`)
Tab 4. Always shows a signed-in user (auth is mandatory). Profile card · follow system · "Sign Out" button · Sync stats · DB version · About.

#### Edit display name
- Tapping the **profile card** opens an `AlertDialog` with a pre-filled `OutlinedTextField` containing the current display name
- On confirm: calls `authRepository.updateDisplayName(newName)` (Firebase `updateProfile`) + `socialRepository.upsertProfile()` (pushes new name to `users/{uid}`)
- The `AccountViewModel` exposes `updateDisplayName(name: String)` which orchestrates both calls
- Snackbar shown on success: "Name updated"
- `AuthRepository.updateDisplayName(name: String)` — wraps `FirebaseUser.updateProfile(UserProfileChangeRequest)`

#### Launch sequence (`ChefsJournalApplication.onCreate()`)
```kotlin
container = AppContainer(this)
createNotificationChannel()   // creates "chefsjournal_social" NotificationChannel (Android 8+)
appScope.launch {
    container.authRepository.authStateFlow().collect { user ->
        if (user != null && !user.isAnonymous) {
            container.seeder.seedIfNeeded()       // no-op currently
            container.syncService.sync()
            container.syncService.syncPersonalRecipes()
            container.socialRepository.upsertProfile()
            refreshFcmToken()   // fetches current FCM token → stores in users/{uid}.fcmToken
        }
    }
}
```

`refreshFcmToken()` calls `FirebaseMessaging.getInstance().token.await()` and passes it to `socialRepository.updateFcmToken(token)`. This ensures the stored token is always current on sign-in even if `onNewToken` was never triggered.

#### Firebase config
Project: `chef-s-journal-6a0fd`. Web client ID in `res/values/strings.xml` as `google_web_client_id`. SHA-1 debug fingerprint registered in Firebase Console for Google Sign-In.

---

### F8 — Visibility & Link Sharing ✅ (partially reworked)

#### Recipe visibility model (3 tiers — see F12)

Every recipe has `visibility: String = "private"` in Room. **Three tiers** (no DB migration — the
field was already a free-form String):
- **`"private"`** (default) — visible only to the owner; no Firestore mirror.
- **`"friends"`** (Co-Chefs only) — mirrored to `shared_recipes/{recipeId}` with `visibility:"friends"`;
  readable only by the author + accepted co-chefs (enforced by Firestore rules). Appears on the
  author's profile for their co-chefs.
- **`"public"`** — mirrored with `visibility:"public"`; accessible via share link and (future)
  public profile. Both friends + public are "published"; `RecipeDetailUiState.isPublished` gates
  comments + sharing.

**Published ≠ in a browse feed.** Shared/public recipes do not appear in the Shared tab or any feed.
The Shared tab (Tab 2) shows only recipes the user saved as references. Someone else sees your
recipe by: (1) the direct HTTPS link (public only), (2) you directly sending it, or
(3) **visiting your Co-Chef profile** (F12) for your friends + public recipes.

#### Merged share button (detail screen top bar)

A single **Share icon** appears in the `RecipeDetailScreen` top bar for owners only. Tapping it opens a `ShareOptionsSheet` (`ModalBottomSheet`) with two options:

**Option A — "Send to follower"** (default / top option):
- Opens `FollowerPickerSheet` immediately; does not require recipe to be public
- Recipient gets a `recipe_shared` notification and can view in `ReceivedRecipeScreen`
- Recipe remains private — direct share does not publish to `shared_recipes`

**Option B — "Share link"**:
- **Recipe is already public** → Android system share sheet opens with `https://chef-s-journal-6a0fd.web.app/shared/{recipeId}`
- **Recipe is private** → confirm dialog: *"This recipe will be visible to anyone with the link."* → `setVisibility("public")` → once `state.isPublic` becomes true, share sheet opens via `LaunchedEffect`
- Making private again: the `FilterChip` (lock / globe icon) in the recipe body toggles visibility for owners

`RecipeDetailViewModel.setVisibility(visibility)`:
1. Updates Room via `repository.setVisibility(recipeId, visibility)`
2. Updates in-memory state
3. `"public"` → `sharedRecipeService.publish(recipe)` + starts comment observer
4. `"private"` → `sharedRecipeService.unpublish(recipeId)` + stops comment observer

#### Deep links & sharing URL

Share URL format: **`https://chef-s-journal-6a0fd.web.app/shared/{recipeId}`**

**Android App Links** — `AndroidManifest.xml` has two intent filters on `MainActivity`:
1. `android:autoVerify="true"` HTTPS filter for `chef-s-journal-6a0fd.web.app/shared/**` — verified via `assetlinks.json` in Firebase Hosting. When verified, tapping the link opens the app directly with no chooser dialog.
2. `chefsjournal://shared` custom scheme — legacy fallback; works immediately without domain verification.

NavGraph composable for `"shared/{recipeId}"` handles both:
```kotlin
deepLinks = listOf(
    navDeepLink { uriPattern = "https://chef-s-journal-6a0fd.web.app/shared/{recipeId}" },
    navDeepLink { uriPattern = "chefsjournal://shared/{recipeId}" }
)
```

**Firebase Hosting** (`chef-s-journal-6a0fd.web.app`) — deployed:
- `/.well-known/assetlinks.json` — Android App Links domain verification (SHA-256 fingerprint of debug keystore)
- `/shared/{recipeId}` → `shared.html` — browser fallback page; fetches recipe from Firestore REST API, renders full recipe. Has "Open in ChefsJournal" button (tries `chefsjournal://` scheme). Works for anyone — app not required.
- `/` → `index.html` — minimal landing page.

Firestore rules updated: `shared_recipes` allows `read: if true` (unauthenticated reads for the web page).

**iOS Universal Links** — ✅ wired (pending hosting deploy):
1. `apple-app-site-association` at `hosting/public/.well-known/apple-app-site-association` (`appID 7S2FY6WF5V.com.aerion.chefsjournal`, paths `/shared/*`); served as `application/json` via a `firebase.json` header. **Requires `firebase deploy --only hosting` to go live.**
2. `Associated Domains` entitlement `applinks:chef-s-journal-6a0fd.web.app` in `ChefsJournal.entitlements`.
3. `ContentView.onOpenURL` already routes `https://chef-s-journal-6a0fd.web.app/shared/{id}` → Shared tab (so no app code change was needed beyond the entitlement).

Test Android App Links via ADB:
```
adb shell am start -W -a android.intent.action.VIEW -d "https://chef-s-journal-6a0fd.web.app/shared/RECIPE_ID" com.aerion.chefsjournal
```

#### Author attribution when sharing

`SharedRecipeService.buildDocument()` applies this rule at publish time:
- `recipe.isImported == false` → `authorDisplayName = recipe.authorDisplayName` (real name, e.g. "Tanay")
- `recipe.isImported == true` → `authorDisplayName = "Imported"` (blanket override)

`authorId` (Firebase UID) is always the real owner's UID — used for Firestore security rules.

Author is stamped when a recipe is first created:
- **Personal / freeform**: `authorId = currentUid`, `authorDisplayName = displayName ?: email`
- **Imported**: same UID/name saved in Room; overridden to "Imported" only at publish time
- **Editor fork** (seeded recipe with `authorId == null`): stamped with current user on first save

#### Shared tab (Tab 2) — "Shared Recipes"

Shows recipes that other users have directly sent to the current user via `shared_to/{uid}/recipes/`. This is a **personal inbox**, not a community browse. The same feed also appears under the My Recipes "Shared" filter chip.

- Loaded from `shared_to/{uid}/recipes/` via `SocialRepository.getReceivedRecipesSummaryFlow()` (live snapshot stream)
- `SharedInboxScreen` renders **full recipe cards** (same visual language as My Recipes): title, prep/cook times, tag chips, author row, relative timestamp
- **Author attribution:** the card shows the recipe's *original author* (`authorDisplayName`), with "· from [sender]" appended only when the sender differs from the author. e.g. User A shares a recipe authored "User A" → User B sees author "User A"; if the recipe's author is "Imported", User B sees "Imported".
- Tap → `ReceivedRecipeScreen` (review screen with "Save Recipe")
- Empty state: "No recipes shared with you yet"

##### `ReceivedRecipeSummary` (card model)
```kotlin
data class ReceivedRecipeSummary(
    val shareId: String,
    val title: String,
    val authorDisplayName: String,   // original recipe author (e.g. "Tanay" or "Imported")
    val fromDisplayName: String,     // who sent it to you
    val sharedAt: Long,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val tags: List<String>
)
data class ReceivedRecipeData(val recipe: Recipe, val fromDisplayName: String)
```

**`shared_to/{uid}/recipes/{shareId}` doc** stores both `authorDisplayName` (original recipe author, preserved by `buildSharedToDocument()`) and `fromDisplayName` (sender). Old docs missing `authorDisplayName` fall back to `fromDisplayName`.

#### `SharedRecipeDetailScreen` (visitor view — Firestore-based, deep links only)

Route: `"shared/{recipeId}"`. Entry point is **deep links only** — not reachable from any in-app tab.

`chefsjournal://shared/{recipeId}` or `https://chef-s-journal-6a0fd.web.app/shared/{recipeId}` → opens this screen. Reads from Firestore `shared_recipes`, not Room. Features:
- Yield adjuster, unit toggle (Original/Metric/Imperial)
- Read-only ingredients + steps
- **"Copy to My Recipes"** button: saves full copy to Room with new UUIDs, `visibility = "private"`, `isImported = false`, author set to current user
- Comments section (if signed in)

#### Comments

Stored in `shared_recipes/{recipeId}/comments/{commentId}`. Accessible only via `SharedRecipeDetailScreen` (deep link entry).

- **Post**: any authenticated user; max 1000 chars
- **Delete**: commenter (own comment) or recipe owner (any comment on their recipe)
- Comments are immutable once posted (no edit)
- Comment input shown in both `RecipeDetailScreen` (owner view, when recipe is public) and `SharedRecipeDetailScreen` (visitor view via deep link)

#### Firestore security rules (`firestore.rules` — deployed)

```
recipes/{recipeId}:             read = auth != null; write = false (admin SDK only)
personal_recipes/{uid}/recipes: read+write = auth.uid == uid
shared_recipes/{recipeId}:      read = true (public — web viewer + app)
                                create = auth.uid == request.resource.data.authorId
                                update/delete = auth.uid == resource.data.authorId
shared_recipes/{recipeId}/comments:
                                read = auth != null
                                create = non-anonymous + authorId == uid + content 1–1000 chars
                                delete = commenter OR recipe author (via get())
                                update = false (immutable)
users/{uid}:                    read = auth != null
                                write = auth.uid == uid
follows/{followId}:             read = auth.uid == followerId OR followeeId
                                create = auth.uid == request.resource.data.followerId
                                update = auth.uid == resource.data.followeeId (accept only)
                                delete = auth.uid == followerId OR followeeId
notifications/{uid}/items:      read+update = auth.uid == uid
                                create = non-anonymous + fromUid == auth.uid
                                         + type in [follow_request, follow_accepted, recipe_shared]
                                delete = false (never deleted by client)
shared_to/{uid}/recipes:        read = auth.uid == uid (recipient only)
                                create = auth.uid == request.resource.data.fromUid (sender)
                                update/delete = false (immutable)
```

---

### F9 — Co-Chefs (Friendship) System & Direct In-App Sharing ✅

Two sharing modes exist in parallel:
1. **Deep link sharing** (F8) — share an HTTPS link anyone can open; opens in app or browser
2. **Direct in-app sharing** (F9) — share a recipe directly to a specific co-chef; they get a push notification and can review / save the recipe

#### Co-Chef model (mutual bidirectional)

**Mutual friendship** — when B accepts A’s request, BOTH become co-chefs of each other. UI calls them “Co-Chefs” throughout (not “followers” or “friends”).

**`follows/{followerId}_{followeeId}`** — document ID encodes both parties for direct lookup

```
followerId:    String     // UID of the person who initiated
followerName:  String     // display name snapshot
followeeId:    String     // UID of the recipient
followeeName:  String
status:        "pending" | "accepted"
createdAt:     Timestamp
```

**On `acceptFollowRequest(fromUid)`:** batch-commits two Firestore ops:
1. Updates existing `follows/{fromUid_myUid}` → `status = "accepted"`
2. Creates reverse doc `follows/{myUid_fromUid}` → `status = "accepted"`

After this, `getFriendsFlow()` (queries `followerId == uid AND status == accepted`) shows count = 1 on both sides immediately.

**On `unfriend(targetUid)`:** batch-deletes both `follows/{myUid_targetUid}` AND `follows/{targetUid_myUid}`. Handles cancelling a pending outgoing request (non-existent reverse doc delete is a no-op in Firestore).

Required composite Firestore indexes:
- `follows (followeeId ASC, status ASC)` — for pending requests query
- `follows (followerId ASC, status ASC)` — for co-chef list query

**`users/{uid}`** — public user profile (written on every sign-in via `SocialRepository.upsertProfile()`)
```
displayName:  String
photoUrl:     String?
email:        String      // required for email-based user search
fcmToken:     String?     // FCM device token for push notifications
updatedAt:    Timestamp
```

**`notifications/{uid}/items/{notifId}`** — written client-side; triggers `sendPushNotification` Cloud Function
```
type:              "follow_request" | "follow_accepted" | "recipe_shared"
fromUid:           String
fromDisplayName:   String
shareId:           String?     // populated for recipe_shared
recipeName:        String?     // populated for recipe_shared
read:              Boolean
createdAt:         Timestamp
```

**`shared_to/{recipientUid}/recipes/{shareId}`** — recipe shared directly to a specific user
```
(full recipe JSON fields)
fromUid:           String
fromDisplayName:   String
sharedAt:          Timestamp
```

#### Account tab (Tab 4 — implemented)

```
AccountScreen (route "account_tab")
  ├── Profile card: display name · email  [tap → edit name AlertDialog]
  ├── Co-Chefs section (signed-in only):
  │     PendingRequestCards — "X wants to be co-chefs" · Accept ✓ / Decline ✗
  │     "Co-Chefs: N" row (tappable) → FriendsScreen
  │     PersonSearch icon + "Find Co-Chefs" → UserSearchScreen
  ├── Sync & Storage: last synced · recipe count
  ├── About: DB version · Aerion
  └── [Sign Out] → dialog → clears data + signs out
```

No notification bell. Push notifications are Android system notifications delivered via FCM.

#### Push notifications (FCM — implemented ✅)

**`ChefsJournalMessagingService`** (`service/ChefsJournalMessagingService.kt`) — extends `FirebaseMessagingService`:
- `onNewToken(token)` — saves token to `users/{uid}.fcmToken` in Firestore
- `onMessageReceived(message)` — shows system notification when app is foreground (background handled automatically by OS)
- Notification channel ID: `"chefsjournal_social"`, name: `"ChefsJournal"`, importance: DEFAULT
- Registered in `AndroidManifest.xml` with `com.google.firebase.MESSAGING_EVENT` intent filter
- `POST_NOTIFICATIONS` permission declared (required Android 13+)

**`sendPushNotification` Cloud Function** — Firestore `onDocumentCreated` trigger on `notifications/{uid}/items/{notifId}`:
1. Reads `users/{uid}.fcmToken`; skips silently if missing
2. Sends FCM via `admin.messaging().send()` with `android.notification.channelId = "chefsjournal_social"`

| type | title | body |
|---|---|---|
| `follow_request` | "New Co-Chef Request" | "[Name] wants to be co-chefs" |
| `follow_accepted` | "Co-Chef Request Accepted" | "[Name] accepted your co-chef request" |
| `recipe_shared` | "[Name] shared a recipe" | `"“[Recipe Title]”"` |

#### UserSearchScreen (push route `"user_search"`)

- `OutlinedTextField` with 300ms debounce; placeholder “Find co-chefs by name or email…”
- If query contains `@` → exact `whereEqualTo("email", query.trim().lowercase())` lookup
- Otherwise → displayName prefix range query (`displayName >= query AND <= query + ""`, capped at 20)
- `UserProfile` domain model: uid, displayName, email?, photoUrl?, createdAt
- Each result row: avatar initial circle · display name (`bodyLarge`) · email (`bodySmall`, `onSurfaceVariant`) · **Add Co-Chef** / **Requested** / **Co-Chef ✓** button

#### FriendsScreen (push route `"friends"`)

- Accessible by tapping the “Co-Chefs: N” row on Account page
- Lists all accepted co-chefs (from `getFriendsFlow()`) with initial avatar circle
- **Remove** button → confirmation dialog (“Remove Co-Chef?”) → `unfriend()` → batch-deletes both direction docs

#### ReceivedRecipeScreen (push route `"received/{shareId}"`) — review screen

This is a **review screen**, mirroring the import pending-review pattern: the shared recipe stays in the Shared feed until the user explicitly saves a copy to My Recipes.

- Loads via `SocialRepository.getReceivedRecipe()` → returns `ReceivedRecipeData(recipe, fromDisplayName)`
- “Shared by [sender]” banner in `tertiaryContainer` (uses `fromDisplayName`, not the recipe author)
- Read-only detail: yield adjuster, unit toggle (if conversions exist), sections/ingredients/steps, **Sources section** (clickable underlined URLs, same as RecipeDetailScreen)
- **“Save Recipe”** bottom bar (renamed from "Save to My Recipes"): fresh Room copy (new UUIDs), `authorId = currentUid`, `isImported = false`, `visibility = "private"`, `needsReview = false`
- On save → `popBackStack()` back to the Shared tab (the shared card remains in the feed; the saved copy now appears in My Recipes). `onSaved` is a no-arg callback `() -> Unit`.

#### Direct recipe sharing (from RecipeDetailScreen)

- Single **Share icon** in top bar (owners only) → `ShareOptionsSheet` (ModalBottomSheet)
- “Send to co-chef” → `FollowerPickerSheet`: list of accepted co-chefs with Send icon per row
- Send → `SocialRepository.shareRecipeTo()`:
  1. Writes full recipe JSON to `shared_to/{recipientUid}/recipes/{shareId}`
  2. Delivers `recipe_shared` notification → triggers FCM push
- Snackbar: “Recipe sent to [Name]”

---

### F10 — Recipe Variations ✅

Spin off up to **4 editable variations** of a recipe (e.g. "Spicy", "Vegan") without re-typing it.

**Model — linked separate copies.** A variation is a full standalone recipe (its own sections/ingredients/steps) tagged with:
- `parentRecipeId` — id of the **base** recipe (`source.parentRecipeId ?: source.id`, so variations-of-variations still group under one base)
- `variantName` — short label, **capped at 20 chars** (`MAX_VARIANT_NAME_LEN`)

**Hidden from lists.** All list queries (`getYoursRecipes`, `getPersonalRecipes`, `getAllRecipes`, `getImportedRecipes`) filter `parentRecipeId IS NULL`, so variations never appear as standalone cards. They are reached only via the base recipe's detail.

**Detail selector.** `RecipeDetailScreen` shows a horizontally-scrolling chip row (when the family has >1 member or the owner can add one):
- First chip is the base, **always labelled "Original"** (recipe titles can be long — fixed short label).
- One `FilterChip` per variation (its `variantName`); tapping switches to that recipe (`onOpenRecipe` → `recipe/{id}`).
- Trailing **"＋ Variation"** `AssistChip` (owner only, while under `MAX_VARIANTS = 4`) → name dialog → `vm.createVariant(name)` → opens the new copy in the editor.

**Duplication** — `RecipeRepository.duplicateAsVariant(sourceId, variantName, currentUid, displayName)`: deep-copies with **all ids regenerated and every internal reference remapped** (section/ingredient/step ids, step→ingredient refs, `scaleIngredientId`, `substituteGroupId`). New recipe: `version=1`, `visibility="private"`, `isReceived=false`, fresh timestamps. Returns the new id.

**Editor** preserves `parentRecipeId`; shows a "Variation name" field (with `n/20` counter) only when `parentRecipeId != null`. Deleting a base cascade-deletes its variations.

**Sync:** a personal variation syncs to `personal_recipes` with its `parentRecipeId`/`variantName` preserved (multi-device keeps the grouping). A **received** recipe becomes standalone (`parentRecipeId = null`) since the sharer's base doesn't exist locally.

---

### F11 — Shopping List ✅

A per-recipe **combined ingredient checklist**, reached via the cart icon in the `RecipeDetailScreen` top bar.

**Combine quantities.** `ShoppingAggregator` (`ui/shopping/ShoppingAggregator.kt`) groups ingredients by **normalized name** (mirrors the backend `normalizeKey`) and **sums** their amounts, so an ingredient used across several sections/steps shows as **one line with the total**. Summing is per-unit in the active `UnitMode`: Metric reduces most items to clean g/ml; same-unit groups add directly; otherwise buckets join with " + ". Non-numeric amounts ("to taste") pass through. Reuses `QuantityScaler` for formatting. One member per substitute group is included; the `itemKey` is the normalized name.

**Persisted checks.** Checking an item writes a `shopping_checks` row (`recipeId` + `itemKey`); unchecking deletes it. Checks **survive app restarts** (real shopping over trips) and are observed as a Room `Flow`. A **Reset** action clears them. `RecipeDao.deleteFullRecipe` cascades to `shopping_checks`. Personal + local only — never synced.

**Author notes (no Gemini).** `IngredientEntity.shoppingNote` is an optional author-entered brand/comment (e.g. "Amul butter", "ask for fine sugar"). It **travels with the recipe** (carried in all ingredient mappers + sync + share). Edited via an unobtrusive **"＋ Shopping note"** field per ingredient in the **Recipe Editor only** — deliberately **absent from the import review sheet and freeform screen** so authors aren't overwhelmed while creating. Shown under the combined line on the Shopping List (notes for a merged line are de-duplicated + joined). Gemini-suggested brands/substitutes are deferred for later.

**Scale.** The cart passes the detail screen's current servings/anchor as nav args (`shopping/{recipeId}?servings=&anchor=`); the screen also has its own `+/−` yield adjuster + unit toggle.

**Detail page change.** The old **checkbox + strike-through was removed from the detail ingredient list** (now bullets; optionals carry an inline include/exclude checkbox, substitutes show inline swap chips). The shopping checklist lives only on the Shopping List.

#### Cooking-mode step checklist
Separately, each ingredient in a **cooking-mode step card** is a checkbox row — tick items off as you add them (useful for spice-heavy steps). **Session-only**: the ticked set is `remember`ed at the `RecipeDetailScreen` scope, so it persists across steps and across re-entering cooking mode, and **clears when you leave the recipe**.

---

### F12 — Visibility Tiers + Co-Chef Profiles ✅ (Android; iOS pending)

**Three visibility tiers** (`private` / `friends` / `public`) — see the F8 visibility model above.
No DB migration (the `visibility` String already existed). `SharedRecipeService.buildDocument`
writes the real tier (was hardcoded `"public"`); `RecipeDetailViewModel.setVisibility` publishes
the mirror for `friends`/`public` and unpublishes for `private`. The owner's visibility **chip**
opens a 3-option chooser dialog (Private 🔒 / Co-Chefs 👥 / Public 🌐, `VisibilityOption` rows).

**Direct share of a private recipe → Co-Chefs tier** (not Public). `makeSharableAndShareToFollower`
calls `ensureSharableVisibility()` which publishes at `"friends"` (an already-Public recipe is left
Public). The Share-link flow is separate and still requires Public (`showMakePublicForLink` prompt).

**Firestore rule** (`shared_recipes` read): `public` → anyone; author → always; `friends` → only if
an **accepted** mutual-follow doc `{viewer}_{author}` exists (`exists()` + `get()`). Public recipes
+ web viewer unaffected. ⚠️ **Required composite index**: `shared_recipes (authorId ASC, visibility ASC)`
for the profile query — create via the console or the link Firestore emits on first profile open
(not in a managed `firestore.indexes.json`; the existing `follows` indexes were created the same way).

**Chef profile** (`ui/social/ProfileScreen.kt` + `ProfileViewModel`): reached by tapping a row in
`FriendsScreen` **or `UserSearchScreen`** (any chef). The VM resolves access at runtime —
`getFollowStatus(uid) == "accepted"` → `getAuthorRecipes(uid, includeFriendsOnly = true)` (co-chef:
friends + public); else `includeFriendsOnly = false` (**public profile**: public recipes only). The
header badge ("Co-Chef" vs "Chef") + empty-state copy switch on `state.isCoChef`. `getAuthorRecipes`
queries `shared_recipes` `whereEqualTo(authorId)` + `whereIn(visibility, …)` → `ProfileRecipeSummary` cards.

**Review mode + "Add to Shared tab".** Tapping a profile recipe opens the **review screen**
(`ReceivedRecipeScreen`, generalized): a `sealed class ReviewSource { Pointer(shareId) | Direct(recipeId,authorUid,authorName) }`
drives entry — inbox uses `Pointer`, profile uses `Direct`. The bottom button (**"Add to Shared tab"**)
saves a received reference (`saveReceivedReference` + `cacheReceivedRecipe`) to Tab 2; `deleteReceivedPointer`
runs in pointer mode only. Losing co-chef status later drops the saved reference on the next
`syncReceivedRecipes` (mirror read denied → treated as gone).

**Routes**: `profile/{uid}?name=` → `ProfileScreen`; `profileRecipe/{recipeId}?authorUid=&authorName=`
→ `ReceivedRecipeScreen(ReviewSource.Direct)`.

---

### F13 — Discover Tab (Phase 1: Lean MVP) ✅ (Android; iOS pending)

A recommendation feed (`ui/discover/`) replacing the placeholder. **All client-side**, no new cloud
infra beyond one public-recipes fetch.

**Ranking** (`DiscoverRanker`, pure): `score = 2·mealMatch + affinity + sourceBoost − recencyPenalty`.
- **Meal match** — `MealClassifier.mealsFor(tags)` maps tags → meal types (keyword heuristic, no schema
  change); `currentMeal(hour)` picks the slot (breakfast 5–10 · lunch 11–14 · snack 15–16 · dinner
  17–21 · dessert otherwise). Unclassified recipes are mildly eligible any time.
- **Affinity** — `topCuisines(ownTags)`: the user's most-frequent cuisine tags (minus meal words),
  learned implicitly from their own collection.
- **Source boost** — OWN > FRIEND > PUBLIC.
- **Recency penalty** — recipes in `cooked_log` within 48h are pushed down (so the same dish isn't
  re-suggested next meal).

**Feed** (`DiscoverViewModel` assembles, `DiscoverScreen` renders LazyColumn of LazyRow shelves):
`"{Meal} ideas"` (blended, meal-appropriate) · `"From your kitchen"` (local) · `"From your co-chefs"`
(`getAuthorRecipes` per co-chef, cap 10) · `"Fresh from the community"` (`SharedRecipeService.getPublicRecipeSummaries`,
newest 50, excludes self/dupes) · `"Recently cooked"` (from `cooked_log`). Empty shelves hidden.
Top bar: **Surprise me** (random pick from the meal-appropriate pool) + Refresh. Candidates are
`DiscoverRecipe(recipeId, tags, source, authorUid, authorName, isLocal)`.

**Open behaviour (view free, save deliberately):**
- `isLocal` recipe → normal `recipe/{id}` detail (editable).
- remote (friend/public) → `profileRecipe/...` = the F12 read-only `ReceivedRecipeScreen` (Direct mode):
  full detail, **Cook** (Cooking Mode without saving), **Add to Shared tab** (explicit save). Nothing is
  persisted unless the user saves.

**Cooked log + reusable Cooking Mode:** `CookingModeScreen` is now `internal` (shared between detail
+ review). Its "Done ✓" calls `markCooked(recipeId)` → upserts `cooked_log`. The review screen
constructs a transient `RecipeDetailUiState` to host Cooking Mode for unsaved recipes.

#### Phase 2 — Popularity (saves + likes) ✅
- **Counters on the mirror**: `shared_recipes/{id}.saveCount` + `likeCount`, maintained **only** by
  Admin-SDK Cloud Functions (`backend/functions/index.js`): `onReceivedSaved`/`onReceivedRemoved`
  (trigger on `received_recipes/{uid}/items/{recipeId}` — doc-id = recipeId) and `onRecipeLiked`/
  `onRecipeUnliked` (trigger on `shared_recipes/{id}/likes/{uid}`). `bumpCounter` uses
  `FieldValue.increment` wrapped in try/catch (missing mirror → ignored). Re-publish preserves counts
  (`buildDocument` omits them; `set(merge)`).
- **Likes** (`SharedRecipeService`): `setLiked(recipeId, liked)` writes/deletes `likes/{uid}`;
  `likeStateFlow(recipeId)` combines a `likes/{uid}` listener (isLiked) + the recipe doc (counts) →
  `LikeState(isLiked, likeCount, saveCount)`. ❤ toggle + count in the read-only review screen
  (`ReceivedRecipeScreen`); read-only counts on the owner's `RecipeDetailScreen` when published.
  Anonymous users can't like (rule + `canLike`).
- **Ranking**: `DiscoverRecipe` gains `saveCount`/`likeCount`; `DiscoverRanker` adds
  `+ ln(1 + saveCount*2 + likeCount)·0.5` (capped, public only). `getPopularPublicRecipes()` (orderBy
  `saveCount` desc) feeds a **"Popular"** shelf; the VM unions recent ∪ popular (cold-start safe).
  Cards show 🔖/❤ badges when counts > 0.
- **Indexes** (manual): `shared_recipes (visibility, sharedAt desc)` + `(visibility, saveCount desc)`.

#### Phase 3a — Cross-scope search ✅
- A pinned search field in `DiscoverScreen` searches **own > friends > public**, merged in that order
  (dedupe by id). Blank query → shelves; non-blank → results `LazyColumn`.
- **Own + friends**: client-side `contains` (case-insensitive, substring) over the `localCandidates` /
  `friendCandidatesCache` the VM already cached from the feed load. `onSearchChange` debounces ~300ms.
- **Public**: Firestore has no full-text, so each `shared_recipes` mirror carries a **`searchTokens`**
  array (lowercased words from title + tags, `SharedRecipeService.searchTokens`, written in `buildDocument`).
  `searchPublicRecipes(query)` does `whereEqualTo(visibility,public).whereArrayContains(searchTokens, <longest query word>)`
  then client-refines to the full query (word-level match, multi-word narrows). Requires index
  `shared_recipes (visibility ==, searchTokens array-contains)`.
- **Backfill**: existing mirrors get tokens via `backend/firestore/backfill-search-tokens.js`
  (run once with the service-account key) — or naturally on the next re-publish.

#### Phase 3b — Polish ✅
- **Explicit cuisine preferences**: `data/UserPreferences.kt` (SharedPreferences `chefsjournal_prefs`,
  local-only) stores chosen cuisine tags. Account → "Recipe preferences" = `FilterChip`s (curated
  list ∪ the user's own tags, minus meal words). When set, they **override** the implicit affinity in
  `DiscoverViewModel.load` (`explicit.ifEmpty { DiscoverRanker.topCuisines(ownTags) }`). Changing prefs
  reflects on the next Discover refresh.
- **Pull-to-refresh**: `PullToRefreshBox` around the Discover shelves → `vm.refresh()` (`isRefreshing` state).
- **Discover is now the default tab**: `ChefsJournalNavGraph` `startDestination = BottomTab.Discover.route`.

---

