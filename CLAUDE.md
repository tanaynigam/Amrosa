# Amrosa — Project Specification
### by Aerion

> **Amrosa** is the app name. Upon launch, the user is greeted with the heading **"Amrita & Ambrosia"** — a fusion of the Sanskrit and Greek words for divine, immortal sustenance. This is the guiding identity of the app: recipes that are exquisite, elaborate, and deeply personal.

---

## Project Overview

| Field | Detail |
|---|---|
| **App Name** | Amrosa |
| **Company** | Aerion |
| **Platform** | Android (primary); iOS planned (Swift/SwiftUI/SwiftData, separate codebase) |
| **Language** | Kotlin |
| **Min SDK** | API 26 (Android 8.0+) |
| **Architecture** | MVVM + Repository Pattern |
| **Database (local)** | Room (SQLite) |
| **Database (cloud)** | Firebase Firestore |
| **Cloud Storage** | Firebase Storage (for images) |
| **Auth** | Firebase Authentication (Google Sign-In + email/password + phone OTP) |
| **AI / Recipe Parsing** | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |

---

## Monorepo Structure

```
Amrosa/
├── android/          # Android app (Kotlin + Jetpack Compose) — primary platform
├── ios/              # iOS app (Swift + SwiftUI + SwiftData) — planned
├── backend/
│   ├── functions/    # Firebase Cloud Functions (Node.js 24)
│   │   ├── index.js           # Function entry points
│   │   ├── parseRecipe.js     # URL/file/Sheets/Docs/freeform → Gemini → recipe JSON
│   │   ├── recipeSchema.js    # Amrosa JSON schema for Gemini prompt
│   │   └── package.json       # firebase-functions, axios, @google/generative-ai, xlsx
│   └── firestore/
│       ├── seed-recipes.json  # All seeded recipes as Firestore-ready JSON
│       └── upload-recipes.js  # Node.js upload script (firebase-admin)
└── shared/           # Shared assets, design tokens, documentation
```

---

## Design Philosophy

- **Lightweight first.** No bloat. No unnecessary screens, animations, or dependencies.
- **Offline-capable.** The app must be fully functional without an internet connection. Cloud sync is secondary, not required. Auth is optional — the app works without signing in.
- **Cooking-mode friendly.** Large text, clear layout, minimal taps. Think: hands covered in flour, glancing at the screen.
- **Reliable over flashy.** Smooth, consistent, crash-free. Every interaction should feel instant.
- **Auth is optional at launch.** Anonymous use is fully supported. Signing in unlocks cloud backup, personal recipe sync, and sharing. No forced login.

---

## Navigation: 5 Bottom Tabs

```
Tab 1 — All       (📖)  All recipes: personal + seeded + imported + copied shared
Tab 2 — Personal  (🔖)  Only personal/seeded recipes + "Add New Recipe" FAB
Tab 3 — Imported  (⬇️)  URL/file/Sheets/Docs-imported recipes + import UI
Tab 4 — Account   (👤)  Profile, sign-in/out, sync status, app settings   ← LIVE
Tab 5 — Shared    (🌐)  Community shared recipes; browse, copy, share your own  ← PLANNED (F8)
```

> **Current state:** 4 tabs (All, Personal, Imported, Account). Shared is planned for F8.
> When Shared is added it will become Tab 4 and Account shifts to Tab 5.

---

## Core Features

### F1 — Recipe Storage

Every recipe in Room has:

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val sourceUrls: String,              // JSON List<String>
    val baseServings: Int,
    val baseServingsMin: Int? = null,    // yield range low end (e.g. 15 for cookies)
    val baseServingsMax: Int? = null,    // yield range high end (e.g. 20 for cookies)
    val scaleIngredientId: String? = null, // anchor ingredient for scaling
    val scaleStep: Double = 1.0,         // increment per +/− tap on anchor
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: String,                    // JSON List<String>
    val isCustomized: Boolean = false,
    val isImported: Boolean = false,
    val needsReview: Boolean = false,    // true = imported but not yet confirmed
    val version: Int = 1,
    val changeLog: String = "[]",        // JSON List<RecipeChange>
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long? = null,
    val authorId: String? = null,        // Firebase UID; null for seeded
    val authorDisplayName: String? = null // e.g. "Tanay", "Imported", "Amrosa"
)
```

**Ingredient model** (F6 unit conversions implemented):

```kotlin
@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val name: String,
    val quantityValue: Double?,
    val quantityUnit: String?,
    val quantityDisplay: String?,
    val groupLabel: String?,
    val isOptional: Boolean = false,
    val substituteGroupId: String?,
    val substituteRatio: Float = 1.0f,
    val orderIndex: Int,
    // F6 — placed at END so DatabaseSeeder positional calls stay valid
    val quantityValueMetric: Double? = null,
    val quantityUnitMetric: String? = null,
    val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null,
    val quantityUnitImperial: String? = null,
    val quantityDisplayImperial: String? = null
)
```

#### Scaling modes
- **Servings-based** (default): `baseQuantity × selectedServings / baseServings`
- **Anchor-ingredient-based**: +/− adjusts the anchor by `scaleStep`; all others scale proportionally.

---

### F2 — Personal Recipes & Cloud Sync

- **Pull sync** on app launch: fetch Firestore `recipes` where `updatedAt > lastSyncTimestamp`, upsert into Room.
- **Push sync** on save: `RecipeSyncService.pushPersonalRecipe()` → `personal_recipes/{recipeId}` (fire-and-forget).
- **Version control**: every editor save increments `version` and appends `RecipeChange(version, timestamp, summary)`.
- **Seeder key**: bump `DatabaseSeeder` seed key AND `AmrosaDatabase.DB_VERSION` together on every schema change.

**Current Firestore collections:**

| Collection | Contents |
|---|---|
| `recipes` | Seeded/shared recipes — pull-only |
| `personal_recipes` | Personal recipes — push+pull (will scope to `/{uid}/` with remaining F7 work) |
| `shared_recipes` | Community-shared recipes — planned (F8) |

---

### F3 — Recipe Import (URL, File, Sheets, Docs)

All paths: Cloud Functions → Gemini 2.5 Flash → JSON → review sheet → Room.

#### Cloud Functions

| Function | Input | Description |
|---|---|---|
| `parseRecipeUrl` | `{ url }` | Regular URLs, Google Sheets, Google Docs |
| `parseRecipeContent` | `{ content, type, fileName }` | XLSX, CSV, plain text |
| `formatRecipeText` | `{ text }` | Freeform natural language (F5) |

All functions use `gemini-2.5-flash` with `thinkingBudget: 0`, `application/json` response MIME, `parseNotes` field.

#### Review flow (pending-review model)
1. Parse result → recipe immediately saved to Room with `needsReview = true` (data never lost)
2. `RecipeReviewSheet` (shared bottom sheet) opens
3. Three actions: **Confirm** / **Reimport** / **Edit icon** (pencil in header → navigate to editor)
4. Back gesture → stays in Room with `needsReview = true`
5. Imported tab shows pending recipes first with amber "Needs Review" banner

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
    onEdit: (() -> Unit)? = null // non-null → pencil icon shown in header
)
```

---

### F4 — Recipe Editor

Full inline editor. Entry points: pencil icon on detail screen, **or** Edit button on review sheet.

- **Fork dialog** for seeded recipes (becomes a personal copy)
- Editable: title, description, times, yield, tags, URLs, sections, ingredients, steps
- Every save increments `version`, appends `RecipeChange`, pushes to `personal_recipes`

---

### F5 — Freeform Recipe Entry ✅

FAB on Personal tab → ModalBottomSheet → two options:
- **"Type it out"** → `FreeformEntryScreen`
- **"Import from URL or file"** → switches to Imported tab

`FreeformEntryScreen`: large OutlinedTextField (minLines=8) → "Format with Gemini" → `formatRecipeText` CF → `RecipeReviewSheet` (Save / Reformat / Edit).

**`FreeformViewModel` key methods:**
- `formatWithGemini()` — calls CF, sets `parsedRecipe`
- `saveRecipe()` — saves to Room (`isImported=false, isCustomized=true, needsReview=false`), sets `savedRecipeId` → `onBack()`
- `saveAndEdit()` — saves same way, sets `editRecipeId` → `onEditClick(id)`
- `reformat()` / `dismissReview()` — UI-only state resets

---

### F6 — Ingredient Unit Conversions ✅

#### Storage
6 fields at end of `IngredientEntity`: `quantityValue/Unit/DisplayMetric` and `Imperial`. Gemini populates on import/freeform; seeded recipes have all null.

#### `QuantityScaler` (`ui/util/QuantityScaler.kt`)
```kotlin
enum class UnitMode { ORIGINAL, METRIC, IMPERIAL }

object QuantityScaler {
    fun scale(quantityValue: Double?, quantityUnit: String?, quantityDisplay: String?, scale: Double): String
    fun scale(ingredient: Ingredient, scaleFactor: Double, unitMode: UnitMode): String
}
```

#### Detail screen
Unit toggle (SingleChoiceSegmentedButtonRow) shown only when ≥1 ingredient has conversion data. Session-level — not persisted. Scaling and unit conversion computed together.

#### Gemini conversion rules
1 cup=240ml, 1 tbsp=15ml, 1 tsp=5ml, 1 oz=28.35g, 1 lb=453.6g. Non-convertible → all null.

---

### F7 — Authentication ✅ (core done)

#### Auth providers implemented
- **Google Sign-In** via `play-services-auth`
- **Email / Password** (sign-in + sign-up with display name)
- **Phone OTP** via `PhoneAuthProvider` (60s timeout, auto-verification supported)
- **Anonymous** — silent sign-in on every first launch

All methods call `linkWithCredential` when upgrading an anonymous session (preserves local data).

#### `AuthRepository` (`data/auth/AuthRepository.kt`)
`signInAnonymouslyIfNeeded()` · `signInWithGoogle(idToken)` · `signInWithEmail(email, pw)` · `signUpWithEmail(name, email, pw)` · `signInWithPhoneCredential(credential)` · `signOut()` · `authStateFlow(): Flow<FirebaseUser?>`

#### `AuthScreen` + `AuthViewModel` (`ui/auth/`)
Pushed route (`"auth"`), not a tab. Segmented toggle Sign In | Create Account. Google button, email form, phone flow (number → OTP → verify). `LaunchedEffect(state.authenticatedUserId)` pops back on success.

#### `AccountScreen` + `AccountViewModel` (`ui/account/`)
Profile card · "Sign In / Create Account" button (anonymous) → AuthScreen · "Sign Out" (signed in) · Sync stats · DB info · About.

#### Anonymous auth on launch (`AmrosaApplication`)
```kotlin
container.authRepository.signInAnonymouslyIfNeeded()  // always first
container.seeder.seedIfNeeded()
container.syncService.sync()
```

#### Firebase config
- Project: `amrosa-2ec82`
- Web client ID stored in `res/values/strings.xml` as `google_web_client_id`

#### Remaining F7 work
- Populate `authorId`/`authorDisplayName` when saving freeform/imported recipes
- Scope push sync path to `personal_recipes/{uid}/recipes/{recipeId}`
- Deprecate old `SettingsScreen`

---

### F8 — Shared Recipes — PLANNED

Tab 5 (will shift Account from Tab 4). Browse `shared_recipes` Firestore collection, copy to personal, share own recipes. Full Firestore structure planned under `personal_recipes/{uid}/recipes/` and `shared_recipes/{recipeId}`.

---

## Screen Map

```
── Bottom Tab 1: All ──────────────────────────────────────────────────
All Recipes Screen
  ├── Search bar, category filter chips, recipe card list
  └── Settings gear → navigates to Account tab

── Bottom Tab 2: Personal ─────────────────────────────────────────────
Personal Recipes Screen
  ├── Search + filter, recipe list (isImported = false only)
  └── FAB "Add New Recipe" → ModalBottomSheet
        ├── "Type it out" → FreeformEntryScreen
        └── "Import from URL or file" → Imported tab

FreeformEntryScreen  (pushed)
  ├── OutlinedTextField (minLines=8, example placeholder), char count
  ├── "Format with Gemini" button → formatRecipeText CF
  └── RecipeReviewSheet ("Save Recipe" / "Reformat" / Edit icon)

── Bottom Tab 3: Imported ─────────────────────────────────────────────
ImportScreen
  ├── URL import card, file import card (.xlsx/.csv/.txt), Sheets/Docs hint
  └── Imported recipe list (isImported = true)
        ├── needsReview recipes first — amber "Needs Review" banner
        └── RecipeReviewSheet ("Confirm" / "Reimport" / Edit icon)

── Bottom Tab 4: Account ──────────────────────────────────────────────
AccountScreen
  ├── Profile card (name/email/phone or "Not signed in")
  ├── "Sign In / Create Account" → AuthScreen  OR  "Sign Out"
  ├── Sync & Storage stats, DB version, About

AuthScreen  (pushed route "auth")
  ├── "Amrita & Ambrosia" heading
  ├── Segmented: Sign In | Create Account
  ├── Google button, email+password form (+ name in Create Account)
  └── "Use phone number" → phone number input → OTP input

── Push routes ────────────────────────────────────────────────────────
RecipeDetailScreen
  ├── Title, source URLs (tappable), prep/cook time, yield adjuster
  ├── Section jump chips, unit toggle (Orig/Metric/Imp — shown when conversions exist)
  ├── Substitute selectors, optional toggles, ingredient checklist
  ├── Steps, Notes, Cooking Mode button, Edit button → RecipeEditorScreen

RecipeEditorScreen
  ├── Fork dialog (seeded recipes), full metadata + sections/ingredients/steps edit
  └── Save → Room + Firestore push

CookingModeScreen (fullscreen)
  └── Step-by-step, large text, screen-on lock
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Jetpack Navigation Compose |
| Local DB | Room + SQLite |
| Cloud DB | Firebase Firestore |
| Auth | Firebase Authentication + `play-services-auth` (Google Sign-In) |
| Image Storage | Firebase Storage (planned) |
| Background Sync | On-launch coroutine (AmrosaApplication) |
| Recipe AI | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |
| Image Loading | Coil |
| DI | Manual (AppContainer) |
| State Management | ViewModel + StateFlow |
| JSON | Gson |
| XLSX parsing (backend) | SheetJS (`xlsx` npm package) |

> **Note:** This project uses **Gemini (Google AI)** — NOT the Anthropic Claude API. All AI calls go through the `GEMINI_API_KEY` Firebase secret. Do not add Anthropic dependencies.

---

## Data Model (Room Entities — DB v8, seeder `seeded_v10`)

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val title: String, val description: String?,
    val sourceUrls: String,           // JSON List<String>
    val baseServings: Int,
    val baseServingsMin: Int? = null, val baseServingsMax: Int? = null,
    val scaleIngredientId: String? = null, val scaleStep: Double = 1.0,
    val prepTimeMinutes: Int?, val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: String,                 // JSON List<String>
    val isCustomized: Boolean = false, val isImported: Boolean = false,
    val needsReview: Boolean = false,
    val version: Int = 1, val changeLog: String = "[]",
    val createdAt: Long, val updatedAt: Long,
    val syncedAt: Long? = null,
    val authorId: String? = null,
    val authorDisplayName: String? = null
)

@Entity(tableName = "recipe_sections")
data class RecipeSectionEntity(@PrimaryKey val id: String, val recipeId: String, val name: String, val orderIndex: Int)

@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String, val sectionId: String?,
    val name: String,
    val quantityValue: Double?, val quantityUnit: String?, val quantityDisplay: String?,
    val groupLabel: String?,
    val isOptional: Boolean = false,
    val substituteGroupId: String?, val substituteRatio: Float = 1.0f,
    val orderIndex: Int,
    // F6 fields — at end, all default null
    val quantityValueMetric: Double? = null, val quantityUnitMetric: String? = null, val quantityDisplayMetric: String? = null,
    val quantityValueImperial: Double? = null, val quantityUnitImperial: String? = null, val quantityDisplayImperial: String? = null
)

@Entity(tableName = "steps")
data class StepEntity(@PrimaryKey val id: String, val recipeId: String, val sectionId: String?, val instruction: String, val orderIndex: Int)

@Entity(tableName = "step_ingredient_refs", primaryKeys = ["stepId", "ingredientId"])
data class StepIngredientRefEntity(val stepId: String, val ingredientId: String, val quantityDisplay: String?)

@Entity(tableName = "recipe_notes")
data class RecipeNoteEntity(@PrimaryKey val id: String, val recipeId: String, val content: String, val createdAt: Long, val updatedAt: Long)
```

---

## Current Recipes

| ID | Title | Yield | Scaling | Sections |
|---|---|---|---|---|
| `recipe-001` | Brown Butter Chocolate Chip Cookies | 15–20 | Flour-based, ¼ cup steps | Cookie Dough |
| `recipe-002` | Neapolitan Pizza | 8 | Servings ± 1 | Pizza Dough, Marinara Sauce, Béchamel Sauce, Final Pizza |
| `recipe-003` | Butter Chicken | 6 | Servings ± 1 | Tandoori Chicken, Makhani Gravy Base, Final Cooking |
| `recipe-004` | Malai Kofta | 4–5 | Servings ± 1 | Kofta Balls, Kofta Filling, Gravy |

Source: `Food Recipes.xlsx` in the project root. DatabaseSeeder must match the xlsx exactly.

---

## Current Status

### Done ✅
- Room DB **v8**, seeder `seeded_v10` — 4 seeded recipes
- Recipe detail: yield scaling, ingredient checklist, step-ingredient refs, substitute selectors, optional toggles, section jump chips
- Cooking mode (fullscreen, step-by-step, screen-on lock)
- Notes system (per-recipe, timestamped, editable)
- Firebase Firestore connected (`amrosa-2ec82`); `RecipeSyncService` pull delta + push sync
- Version control (`version` int + `changeLog` JSON)
- Recipe Editor — full edit, fork dialog, cloud push
- Source URLs clickable
- Cloud Functions: `parseRecipeUrl`, `parseRecipeContent`, `formatRecipeText` — all deployed
- **F3** — Full pending-review import flow (needsReview, amber banner, Confirm/Reimport/Edit on sheet)
- **`RecipeReviewSheet`** — extracted shared composable (`ui/import_recipe/RecipeReviewSheet.kt`); `onEdit` callback shows pencil icon in header
- **F5** — Freeform recipe entry: FAB bottom sheet, FreeformEntryScreen, formatRecipeText CF, save/saveAndEdit actions
- **F6** — Unit conversions: 6 fields on IngredientEntity, UnitMode enum, QuantityScaler, unit toggle in RecipeDetailScreen
- **4-tab navigation**: All · Personal · Imported · Account
- **F7 (core)** — AuthRepository (anonymous/Google/email/phone), AuthScreen with all 3 methods + sign-up, AccountScreen, anonymous sign-in on launch, authorId/authorDisplayName on RecipeEntity

### Planned — In Priority Order

| # | Feature | Description |
|---|---|---|
| **F7 remainder** | Auth wire-up | Populate `authorId`/`authorDisplayName` on save; scope push path to `personal_recipes/{uid}/`; deprecate SettingsScreen |
| **F8** | Shared Recipes | Community tab, browse/copy/share; Firestore security rules |
| — | Recipe Images | Firebase Storage integration |
| — | Shopping List | Dedicated shopping list tab |
| — | iOS | Swift/SwiftUI/SwiftData |

---

## UI/UX Guidelines

- **Typography:** Minimum 16sp body, 20sp+ for steps
- **Color palette:** Warm, earthy tones — cream, terracotta, deep green, muted gold
- **Dark mode:** Supported from the start
- **No ads. No onboarding flows. No forced permissions.**
- **Cooking Mode** keeps screen on automatically
- **Unit toggle** (Original / Metric / Imperial) is session-level, not persisted
- **Parse notes banner** in review sheet uses `tertiaryContainer` color, is dismissible
- **Auth is optional** — app is fully functional without signing in

---

## Out of Scope (permanently removed)

- Social sharing other than recipe sharing (F8) — ❌
- Meal planning / calendar integration — ❌
- Nutritional information — ❌
- Voice input / hands-free mode — ❌

---

## Notes for AI Coding Assistants

- **AI model**: Gemini 2.5 Flash (`@google/generative-ai`). Do NOT use Anthropic SDK.
- **Kotlin idioms**: data classes, sealed classes, extension functions, `StateFlow` over `LiveData`
- **Compose only**: all UI in Jetpack Compose — no XML layouts
- **IO dispatcher**: all DB and network on `Dispatchers.IO`
- **Room is source of truth**: UI never reads Firestore directly
- **Minimal dependencies**: no library added without justification
- **Scaling math**: servings-based: `baseQty × selectedServings / baseServings`; anchor-based: `baseQty × anchorQty / baseAnchorQty`; unit conversions scale in parallel
- **DB versioning**: bump `AmrosaDatabase.DB_VERSION` and `DatabaseSeeder` seeder key (`seeded_vN`) together. **Current: DB v8, seeder `seeded_v10`.**
- **IngredientEntity field order**: F6 conversion fields are LAST (after `orderIndex`). Do not insert new fields before `orderIndex` — it will break positional DatabaseSeeder calls.
- **isImported**: `false` → Personal tab; `true` → Imported tab; both → All tab
- **needsReview flow**: recipes saved to Room immediately with `needsReview = true`. Review sheet is a confirmation step, not a save step. `confirmImportedRecipe(recipeId)` clears flag. `dismissReview()` clears UI only. `reimportUrl()`/`reimportFromFile()` delete-and-reinsert same recipeId. `openReviewForRecipe(recipeId)` loads from Room via `getRecipeWithDetails` → `toParsedRecipeData()`.
- **RecipeChange**: `data class RecipeChange(val version: Int, val timestamp: Long, val summary: String)` — auto-generated summary from changed fields
- **Auth upgrade pattern**: all sign-in methods check `auth.currentUser?.isAnonymous == true` → `linkWithCredential` instead of new sign-in (preserves local data)
- **RecipeReviewSheet**: shared composable in `ui/import_recipe/`. Pass `primaryLabel`/`secondaryLabel` to distinguish import vs freeform. Pass `onEdit` to show pencil icon.
