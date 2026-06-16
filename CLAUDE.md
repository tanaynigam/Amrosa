# Amrosa — Project Specification
### by Aerion

> **Amrosa** is the app name. Upon launch, the user is greeted with the heading **"Amrita & Ambrosia"** — a fusion of the Sanskrit and Greek words for divine, immortal sustenance. This is the guiding identity of the app: recipes that are exquisite, elaborate, and deeply personal.

> ⚙️ **Split-platform workflow (since 2026-06):** New features are implemented on **Android first, in this session**. The **iOS port is handled by a separate Claude session** on a Mac. This CLAUDE.md is the **hand-off contract** between the two: every Android feature must be documented here (schema, data flow, UI, Firestore shape) so the iOS session can port it, and Android-ahead features are tracked under the iOS "Remaining Gaps" tables. Shared infrastructure (Firestore rules, Cloud Functions, indexes) is owned by the Android session and benefits both.

---

## Project Overview

| Field | Detail |
|---|---|
| **App Name** | Amrosa |
| **Company** | Aerion |
| **Platform** | Android (primary, Kotlin); iOS (Swift/SwiftUI/SwiftData — in progress, separate codebase) |
| **Android Language** | Kotlin |
| **iOS Language** | Swift |
| **Min SDK (Android)** | API 26 (Android 8.0+) |
| **Min OS (iOS)** | iOS 17+ (SwiftData) |
| **Architecture** | MVVM + Repository Pattern (both platforms) |
| **Database (local — Android)** | Room (SQLite) — **current: DB v13** (real migrations preserve data; seeder is a no-op) |
| **Database (local — iOS)** | SwiftData (ModelContainer, no manual migrations) |
| **Database (cloud)** | Firebase Firestore (`amrosa-2ec82`) |
| **Cloud Storage** | Firebase Storage (planned — images) |
| **Auth** | Firebase Authentication — **mandatory** (Google Sign-In + email/password + phone OTP) |
| **AI / Recipe Parsing** | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |

---

## Monorepo Structure

```
Amrosa/
├── android/          # Android app (Kotlin + Jetpack Compose) — primary platform
├── ios/              # iOS app (Swift + SwiftUI + SwiftData) — in progress
│   └── Amrosa/Amrosa/
│       ├── AmrosaApp.swift         # Entry point; Firebase init; AppContainer setup
│       ├── AppContainer.swift      # DI root; ModelContainer; repositories; onLaunch()
│       ├── Data/
│       │   ├── Models/             # SwiftData models (RecipeModel, IngredientModel, etc.)
│       │   ├── DTOs/               # ParsedRecipeData, RecipeChange
│       │   ├── Repositories/       # AuthRepository, RecipeRepository
│       │   └── Services/           # CloudFunctionsService, RecipeSyncService
│       └── UI/
│           ├── ContentView.swift   # Auth gate: AuthView (root) when not signed in; MainAppView (4 tabs) when signed in
│           ├── AllRecipes/         # AllRecipesView + AllRecipesViewModel
│           ├── YourRecipes/        # YourRecipesView + YourRecipesViewModel
│           ├── Shared/             # SharedInboxView (received-recipes feed, full cards)
│           │                       # SharedRecipeDetailView (deep-link visitor view)
│           ├── Account/            # AccountView + AccountViewModel
│           ├── RecipeDetail/       # RecipeDetailView + RecipeDetailViewModel
│           ├── CookingMode/        # CookingModeView
│           ├── RecipeEditor/       # RecipeEditorView + RecipeEditorViewModel
│           ├── Import/             # ImportView, ImportViewModel, FreeformEntryView, RecipeReviewSheet
│           ├── Auth/               # AuthView + AuthViewModel
│           ├── Components/         # RecipeCard, FilterChip, TagChip, etc.
│           └── Util/               # Extensions, QuantityScaler
├── backend/
│   ├── functions/    # Firebase Cloud Functions (Node.js 24)
│   │   ├── index.js           # Function entry points
│   │   ├── parseRecipe.js     # URL/file/Sheets/Docs → Gemini → recipe JSON
│   │   ├── recipeSchema.js    # Amrosa JSON schema for Gemini prompt
│   │   └── package.json       # firebase-functions, axios, @google/generative-ai, xlsx
│   └── firestore/
│       └── upload-recipes.js  # Node.js admin upload script (reference only)
├── hosting/          # Firebase Hosting (deployed to amrosa-2ec82.web.app)
│   └── public/
│       ├── index.html          # Landing page
│       ├── shared.html         # Recipe viewer (browser fallback for shared links)
│       └── .well-known/
│           └── assetlinks.json # Android App Links domain verification
└── shared/           # Shared assets, design tokens, documentation
```

---

## Design Philosophy

- **Lightweight first.** No bloat. No unnecessary screens, animations, or dependencies.
- **Account required.** All recipes are locked behind an account. No anonymous use.
- **Offline-capable.** Once signed in, fully functional without internet. Cloud sync is secondary.
- **Cooking-mode friendly.** Large text, clear layout, minimal taps.
- **Reliable over flashy.** Smooth, consistent, crash-free. Every interaction feels instant.

---

## Navigation: 4 Bottom Tabs + Auth Gate

```
Auth Gate       (🔐)  Full-screen login wall — shown when not signed in; no back button
Tab 1 — My Recipes     (🔖)  Your recipes (personal + imported) + Shared filter; Add Recipe FAB
Tab 2 — Shared         (📩)  Recipes directly shared with you by co-chefs (recipe cards)
Tab 3 — Discover       (✨)  Recommendation feed (F13) — time-of-day meal shelves, own/co-chef/public
Tab 4 — Account        (👤)  Profile, co-chef system, sync, sign-out
```

**Design decisions:**
- **Auth is mandatory.** `AmrosaNavGraph` observes `authStateFlow()`. When `currentUser == null` or anonymous, the full-screen `AuthScreen` is shown (no back button, no bottom bar). The main `Scaffold` + `NavHost` don't render until sign-in.
- **Sign-out deletes all local data.** `AccountViewModel.signOut()` calls `container.clearAllLocalData(context)` (Room `clearAllTables()` + sync prefs cleared) before `authRepository.signOut()`. Auth state change recomposes the nav graph back to the auth gate automatically.
- **Sign-in triggers seed + sync.** `AmrosaApplication` observes `authStateFlow()` and calls `seeder.seedIfNeeded()` + `syncService.sync()` + `syncService.syncPersonalRecipes()` whenever a real (non-anonymous) user is detected.
- **"All" tab is removed.** My Recipes (route `"yours_tab"`, title "My Recipes") is the primary tab. Filter chips (`All | Personal | Imported | Shared`) provide in-tab filtering.
- **Filter chips scroll with the list.** On My Recipes, the chips are the first item *inside* the `LazyColumn` (not a fixed header row), so they scroll away as you browse — maximising vertical space for recipe cards. Author/source chips and category chips share a single horizontally-scrollable row separated by a thin vertical divider. The search bar above is a compact rounded field with a clear (✕) button.
- **"Shared" filter chip (My Recipes tab):** selecting it switches the list to show recipes shared *with* you (live `shared_to/{uid}/recipes/` feed) using the same card design. Category chips and the Add Recipe FAB hide in Shared mode. Tapping a shared card → `received/{shareId}` review screen.
- **Search scoping:** in `All/Personal/Imported` mode search filters local recipes; in `Shared` mode search filters the shared feed by title/tags. Switching the chip resets the selected category.
- **In-app notification screen removed.** Replaced by Android push notifications via FCM. `NotificationsScreen` and `SocialNotification` model are deleted. Notification bell and unread badge are removed from Account tab.
- `isImported` controls **author display when sharing** (`false` = real name, `true` = "Imported"), not which filter chip it appears under.
- **Import** is a push route (`"import?reviewId=..."`) accessible from the Add Recipe FAB, not a tab.
- Pending-review recipes float to the top of My Recipes with a "Needs review — tap to confirm" badge; tapping opens the import screen with the review sheet pre-loaded.
- **"Shared" tab (Tab 2) = "Shared Recipes"** — recipes other users shared with you, **saved as references** to the author's canonical instance (see Recipe Ownership Model below). Same card/detail/cooking-mode UI as Tab 1, but **read-only**: no edit/delete/share, only "Remove from my recipes". No Add Recipe FAB. Pending un-saved shares appear at the top as "In review".
- **"Discover" tab (Tab 3) = Recommendation feed (F13)** — time-of-day meal shelves blending own/co-chef/public recipes, ranked by meal match + cuisine affinity + source + recency. (Later: popularity, cross-scope search, default-tab promotion.)
- **Tab 4 is "Account"** (route `"account_tab"`, composable `AccountScreen`) — contains both account management and social/co-chef features.
- **No "official" recipes.** The seeded `recipes` collection concept is retired — every recipe has a real author. (The original 4 seeded recipes are re-imported under the owner's account as normal personal recipes.)

---

## Recipe Ownership Model (v2 — authoritative)

This is the source-of-truth model for how recipes are owned, shared, and displayed. It supersedes any older "official/seeded" or "copy-on-save" language elsewhere in this doc.

### Three origins, one canonical instance

Every recipe has exactly **one canonical instance**, owned and editable only by its author, living at `personal_recipes/{authorUid}/recipes/{recipeId}`.

| Origin | Where it shows | Editable? | `isReceived` |
|---|---|---|---|
| **Mine** (typed or imported by me) | Tab 1 | ✅ edit / delete / share | `false` |
| **Received** (saved from another user's share) | Tab 2 | ❌ read-only; "Remove" only | `true` |
| ~~Official / seeded~~ | — removed — | — | — |

A **received** recipe is NOT a copy. It is a **reference** to the author's canonical instance, cached locally in Room for offline use. The author is the only one who can edit it; the receiver only views/cooks it.

### Visibility is the share gate

- A recipe is shareable only when its `visibility = "public"`. Going public publishes/refreshes the canonical mirror at `shared_recipes/{recipeId}`; going private **deletes** that mirror.
- Tab 2 references read from `shared_recipes/{recipeId}`.
- If the author flips a recipe **Public → Private** (or deletes it), the mirror disappears → every receiver's next sync removes it from their Tab 2 automatically.

### Sharing & saving flow

1. User A shares a recipe to User B. The recipe must be Public (the share action publishes it if needed). A **pointer** is delivered to B's inbox.
2. B sees the pointer at the top of Tab 2 as **"In review"**.
3. B taps → review screen → **Save Recipe** → the pending pointer is **consumed**, a reference `received_recipes/{B}/items/{recipeId}` = `{authorUid, authorName, recipeId, savedAt}` is written, and the recipe is cached into Room with `isReceived = true` and the **original author preserved**.
4. B opens the saved recipe → the normal `RecipeDetailScreen` (read-only) → cooking mode etc.
5. **Propagation:** on sync, each reference re-reads `shared_recipes/{recipeId}`. Author edits refresh B's cache; author unpublish/delete removes it from B's Tab 2.
6. **Remove** (B): deletes B's reference + local cache only. A's instance is untouched and can be re-shared.

Received recipes are **never** pushed to B's `personal_recipes` (they aren't B's), and are excluded from B's push sync.

### Author label rule

Author name + `isImported` flag are **always** persisted (we never overwrite the name with the literal "Imported"). The label is computed at display time:

```
name  = if (authorId == currentUid) "me" else authorDisplayName
label = if (isImported) "Imported by $name" else name
```

| Recipe | `isImported` | Label |
|---|---|---|
| Tab 1, authored by me | false | **me** |
| Tab 1, imported by me | true | **Imported by me** |
| Tab 2, authored by B | false | **B** |
| Tab 2, imported by B | true | **Imported by B** |

### Cloud collections (recipe ownership)

| Path | Contents |
|---|---|
| `personal_recipes/{authorUid}/recipes/{recipeId}` | Canonical recipe, author-editable. Tab 1 source. |
| `shared_recipes/{recipeId}` | Public mirror; exists only while `visibility = "public"`; re-published on every edit. What receivers read. |
| `received_recipes/{uid}/items/{recipeId}` | A user's saved references `{authorUid, authorName, recipeId, savedAt}`. Drives Tab 2. |
| Direct-share pointer (inbox) | Notifies B of a new share; resolves to `shared_recipes/{recipeId}`; shown as "In review" until saved. |

### Implementation status

✅ **Implemented & verified end-to-end (iOS ↔ Android)** across 5 steps:
1. Schema `isReceived` + Room migration (9→10); removed official/seeded concept, fork dialog, `authorId==null` owner case.
2. Sync rework: dropped seeded `recipes` pull; received refs excluded from push; `syncReceivedRecipes()` refreshes Tab 2 from `shared_recipes` mirrors (prunes when the author unpublishes/deletes). Delete also removes the cloud copy + unpublishes if public.
3. Share requires Public (confirm prompt); share doc carries `recipeId` + `authorUid`; re-publish on edit.
4. Tab 2 = Room-backed references; pending "In review" pointers on top (dismissible ✕); Save consumes the pointer, caches `isReceived=true` with original author.
5. Tab 1 drops the "Shared" chip; `RecipeDetailScreen` is read-only for received recipes (no edit/delete/share — "Remove from my recipes" instead); author labels "me / Imported by me / B / Imported by B".

**Author name resolution:** the real author name is resolved from `users/{authorUid}.displayName` during the received refresh (and the sender's name at save), so legacy/iOS recipes that stored the literal "Imported" still display "Imported by {real name}".

**Firestore rule:** `shared_to/{uid}/recipes` allows the recipient to delete their own pointers (consume-on-save + dismiss).

**Notification deep links:** tapping a push routes to the relevant screen — `recipe_shared` → `received/{shareId}` review; `follow_request`/`follow_accepted` → Account tab. `MainActivity` reads the FCM `type`/`shareId` extras (foreground via PendingIntent, background via launch intent) and hands a route to the nav graph through `AmrosaApplication.pendingDeepLink`.

---

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
- **Sync on sign-in**: `AmrosaApplication.authStateFlow` observer calls `syncPersonalRecipes()` + `syncReceivedRecipes()` whenever a real user is detected.
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
6. **Adds are in-context.** Android appends faint **ghost "＋ Add" rows** right where the item belongs
   ("add an ingredient to *this* section, here") + a top-bar ＋ as a shortcut; view mode reserves the row's
   height so adding doesn't shift. *Why:* you should add an item where it will land, not via a detached
   menu. **iOS currently uses an ellipsis toolbar menu with no in-context add rows — that diverges from this
   intent** (it preserves zero-reflow, which is good, but loses in-context placement). Prefer the ghost-row
   pattern unless there's a strong platform reason.

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

The app **requires** a signed-in account. `AmrosaNavGraph` observes `authStateFlow()` at the outermost level:
- `currentUser == null` or anonymous → renders full-screen `AuthScreen()` with no back button and no bottom bar (the main Scaffold/NavHost do not exist)
- Real signed-in user → renders `MainAppScaffold()` with all 4 tabs

Anonymous auth has been **removed** — `signInAnonymouslyIfNeeded()` is no longer called on launch.

#### Sign-out clears local data

`AccountViewModel.signOut()`:
1. Calls `app.container.clearAllLocalData(context)` — runs Room `database.clearAllTables()` + clears `amrosa_sync` SharedPreferences
2. Calls `authRepository.signOut()`
3. `authStateFlow` emits null → `AmrosaNavGraph` recomposes to auth gate automatically

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

#### Launch sequence (`AmrosaApplication.onCreate()`)
```kotlin
container = AppContainer(this)
createNotificationChannel()   // creates "amrosa_social" NotificationChannel (Android 8+)
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
Project: `amrosa-2ec82`. Web client ID in `res/values/strings.xml` as `google_web_client_id`. SHA-1 debug fingerprint registered in Firebase Console for Google Sign-In.

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
- **Recipe is already public** → Android system share sheet opens with `https://amrosa-2ec82.web.app/shared/{recipeId}`
- **Recipe is private** → confirm dialog: *"This recipe will be visible to anyone with the link."* → `setVisibility("public")` → once `state.isPublic` becomes true, share sheet opens via `LaunchedEffect`
- Making private again: the `FilterChip` (lock / globe icon) in the recipe body toggles visibility for owners

`RecipeDetailViewModel.setVisibility(visibility)`:
1. Updates Room via `repository.setVisibility(recipeId, visibility)`
2. Updates in-memory state
3. `"public"` → `sharedRecipeService.publish(recipe)` + starts comment observer
4. `"private"` → `sharedRecipeService.unpublish(recipeId)` + stops comment observer

#### Deep links & sharing URL

Share URL format: **`https://amrosa-2ec82.web.app/shared/{recipeId}`**

**Android App Links** — `AndroidManifest.xml` has two intent filters on `MainActivity`:
1. `android:autoVerify="true"` HTTPS filter for `amrosa-2ec82.web.app/shared/**` — verified via `assetlinks.json` in Firebase Hosting. When verified, tapping the link opens the app directly with no chooser dialog.
2. `amrosa://shared` custom scheme — legacy fallback; works immediately without domain verification.

NavGraph composable for `"shared/{recipeId}"` handles both:
```kotlin
deepLinks = listOf(
    navDeepLink { uriPattern = "https://amrosa-2ec82.web.app/shared/{recipeId}" },
    navDeepLink { uriPattern = "amrosa://shared/{recipeId}" }
)
```

**Firebase Hosting** (`amrosa-2ec82.web.app`) — deployed:
- `/.well-known/assetlinks.json` — Android App Links domain verification (SHA-256 fingerprint of debug keystore)
- `/shared/{recipeId}` → `shared.html` — browser fallback page; fetches recipe from Firestore REST API, renders full recipe. Has "Open in Amrosa" button (tries `amrosa://` scheme). Works for anyone — app not required.
- `/` → `index.html` — minimal landing page.

Firestore rules updated: `shared_recipes` allows `read: if true` (unauthenticated reads for the web page).

**iOS Universal Links** — ✅ wired (pending hosting deploy):
1. `apple-app-site-association` at `hosting/public/.well-known/apple-app-site-association` (`appID 7S2FY6WF5V.com.aerion.amrosa`, paths `/shared/*`); served as `application/json` via a `firebase.json` header. **Requires `firebase deploy --only hosting` to go live.**
2. `Associated Domains` entitlement `applinks:amrosa-2ec82.web.app` in `Amrosa.entitlements`.
3. `ContentView.onOpenURL` already routes `https://amrosa-2ec82.web.app/shared/{id}` → Shared tab (so no app code change was needed beyond the entitlement).

Test Android App Links via ADB:
```
adb shell am start -W -a android.intent.action.VIEW -d "https://amrosa-2ec82.web.app/shared/RECIPE_ID" com.aerion.amrosa
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

`amrosa://shared/{recipeId}` or `https://amrosa-2ec82.web.app/shared/{recipeId}` → opens this screen. Reads from Firestore `shared_recipes`, not Room. Features:
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

**`AmrosaMessagingService`** (`service/AmrosaMessagingService.kt`) — extends `FirebaseMessagingService`:
- `onNewToken(token)` — saves token to `users/{uid}.fcmToken` in Firestore
- `onMessageReceived(message)` — shows system notification when app is foreground (background handled automatically by OS)
- Notification channel ID: `"amrosa_social"`, name: `"Amrosa"`, importance: DEFAULT
- Registered in `AndroidManifest.xml` with `com.google.firebase.MESSAGING_EVENT` intent filter
- `POST_NOTIFICATIONS` permission declared (required Android 13+)

**`sendPushNotification` Cloud Function** — Firestore `onDocumentCreated` trigger on `notifications/{uid}/items/{notifId}`:
1. Reads `users/{uid}.fcmToken`; skips silently if missing
2. Sends FCM via `admin.messaging().send()` with `android.notification.channelId = "amrosa_social"`

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
- **Explicit cuisine preferences**: `data/UserPreferences.kt` (SharedPreferences `amrosa_prefs`,
  local-only) stores chosen cuisine tags. Account → "Recipe preferences" = `FilterChip`s (curated
  list ∪ the user's own tags, minus meal words). When set, they **override** the implicit affinity in
  `DiscoverViewModel.load` (`explicit.ifEmpty { DiscoverRanker.topCuisines(ownTags) }`). Changing prefs
  reflects on the next Discover refresh.
- **Pull-to-refresh**: `PullToRefreshBox` around the Discover shelves → `vm.refresh()` (`isRefreshing` state).
- **Discover is now the default tab**: `AmrosaNavGraph` `startDestination = BottomTab.Discover.route`.

---

## Screen Map

```
── Auth Gate (shown when not signed in) ───────────────────────────────
AuthScreen  (full-screen, no back button, no bottom bar)
  ├── "Amrita & Ambrosia" heading
  ├── Segmented: Sign In | Create Account
  ├── Google button
  ├── Email + password form (+ name in Create Account mode)
  └── "Use phone number" → phone entry → OTP entry → verify

── Bottom Tab 1: My Recipes ───────────────────────────────────────────
HomeScreen (RecipeFilter.YOURS — title "My Recipes")
  ├── Compact rounded search bar (clear ✕ button); placeholder adapts to mode
  ├── Filter chips (FIRST item INSIDE LazyColumn — scroll away with content):
  │     [ All ] [ Personal ] [ Imported ] [ Shared ]  | (divider) | [All categories] [tags…]
  │     Personal = isImported:false; Imported = isImported:true
  │     Shared = live shared_to/ feed; category chips + FAB hidden in Shared mode
  ├── Recipe list sorted: needsReview DESC → updatedAt DESC
  │     Each card: title · time · tags · author row · Shared pill (if public)
  │     Shared mode: SharedInboxCard (title · time · tags · author · "from sender" · timestamp)
  │       → tap → received/{shareId}
  ├── Search: filters local recipes (All/Personal/Imported) OR shared feed (Shared)
  ├── needsReview cards → ImportScreen (review sheet)
  └── FAB "Add Recipe" (hidden in Shared mode) → ModalBottomSheet
        ├── "Type it out"            → FreeformEntryScreen (pushed)
        └── "Import from URL or file" → ImportScreen (pushed)

FreeformEntryScreen  (pushed route "freeform")
  ├── Large unstructured text area (minLines=8)
  ├── "Format with Gemini" button → formatRecipeText CF
  └── RecipeReviewSheet ("Save Recipe" / "Reformat" / Edit icon)
        └── No author toggle (freeform = always personal recipe, isImported=false)

ImportScreen  (pushed route "import?reviewId=...")
  ├── Back button in TopAppBar
  ├── URL import card + "Import Recipe" button
  ├── File import card (.xlsx / .csv / .txt, max 5 MB) + "Choose File" button
  ├── Google Sheets & Docs hint card
  └── RecipeReviewSheet ("Confirm" / "Reimport" / Edit icon)
        ├── Parse notes banner (tertiaryContainer, dismissible) if parseNotes != null
        └── Author toggle: [Imported] | [My Recipe]
              on confirm → updateIsImported() in Room

── Bottom Tab 2: Shared ──────────────────────────────────────────────
SharedInboxScreen  (route "shared_tab" — "Shared Recipes")
  ├── Full recipe cards (same visual as My Recipes), live shared_to/ feed
  │     Each card: title · prep/cook time · tag chips · author row
  │       (original authorDisplayName · "from sender" if differs) · timestamp
  ├── Empty state: "No recipes shared with you yet"
  └── Tap → ReceivedRecipeScreen (review → "Save Recipe")

── Bottom Tab 3: Discover ────────────────────────────────────────────
DiscoverScreen  (route "discover_tab" — F13 recommendation feed)
  ├── Top bar: "Surprise me" (random meal-appropriate pick) + Refresh
  ├── Vertical shelves (LazyColumn of LazyRow cards): "{Meal} ideas", "From your kitchen",
  │     "From your co-chefs", "Fresh from the community", "Recently cooked" (empties hidden)
  └── Card tap → local recipe = recipe/{id} detail; remote = profileRecipe/... read-only review
        (full detail · Cook without saving · Add to Shared tab)

── Bottom Tab 4: Account ──────────────────────────────────────────────
AccountScreen  (route "account_tab")
  ├── TopAppBar: "Account" title (no notification bell — push notifications are OS-level)
  ├── Profile card: display name · email  [tap to edit name → AlertDialog with OutlinedTextField]
  ├── Co-Chefs section (signed-in only):
  │     PendingRequestCard per pending request
  │       avatar initial · "X wants to be co-chefs" · Accept ✓ / Decline ✗ buttons
  │       CircularProgressIndicator while action is in-flight
  │     "Co-Chefs: N" stat row (tappable) → FriendsScreen
  │     PersonSearch icon + "Find Co-Chefs" TextButton → UserSearchScreen
  ├── Sync & Storage: last synced · recipe count
  ├── About: DB version · Aerion
  └── [Sign Out] → dialog: "Recipes removed from device..." → clears data + signs out

FriendsScreen  (pushed route "friends")
  ├── List of accepted co-chefs: avatar initial · display name · [Remove] button
  ├── Remove → confirmation dialog ("Remove Co-Chef?") → unfriend() batch delete
  └── Empty state: "No co-chefs yet"

UserSearchScreen  (pushed route "user_search")
  ├── OutlinedTextField (300ms debounce, "Find co-chefs by name or email…")
  └── Results: avatar initial · display name · email (secondary) · [Add Co-Chef] / "Requested" / "Co-Chef ✓" button

── Push routes (from any tab) ─────────────────────────────────────────
RecipeDetailScreen  (pushed route "recipe/{recipeId}")
  ├── Title, source URLs (tappable), prep/cook time
  ├── Variation chips: Original · <variation names> · ＋ Variation (F10; owner, ≤4)
  ├── Yield adjuster (+/−, reset)
  ├── Section jump chips (auto-scroll)
  ├── Unit toggle: Original | Metric | Imperial (shown when conversions exist)
  ├── Inline substitute swap chips, optional include/exclude checkboxes
  ├── Ingredient list grouped by SECTION (step order) then group label — DISPLAY ONLY (no checkboxes)
  ├── Recipe steps with inline ingredient refs; each section header has "▶ Cook" (start cooking mode there)
  ├── Notes (timestamped, add/edit/delete)
  ├── Auto-refreshes via Room Flow on the recipe row (edits reflect immediately, no reopening)
  ├── Top bar actions:
  │     [Cart icon] → ShoppingListScreen (combined checklist; passes current scale)
  │     [Cooking Mode book] → CookingModeScreen
  │     (owners only):
  │     [Share icon] → ShareOptionsSheet (ModalBottomSheet)
  │           Option A (default): "Send to follower" → FollowerPickerSheet
  │           Option B: "Share link" → if public: Android share sheet with HTTPS URL
  │                                    if private: publish dialog → setVisibility("public") → share sheet
  │     [Edit pencil] → flips THIS screen into inline edit mode (no nav; scroll preserved).
  │         Top bar becomes Save ✓ + Cancel ✕; items render editable variants (F4/F14).
  ├── Visibility FilterChip in body (owner only): 🔒 Private | 🌐 Public
  │     Confirms before toggling; public → comments section shown
  └── Comments section (when recipe is public)

ShareOptionsSheet  (ModalBottomSheet — from Share icon)
  ├── "Send to follower" ListItem (default — highlighted) → dismisses sheet, opens FollowerPickerSheet
  └── "Share link" ListItem → dismisses sheet, triggers share link flow

FollowerPickerSheet  (ModalBottomSheet — from "Send to co-chef")
  ├── Accepted co-chefs list: avatar initial circle · display name · Send IconButton per row
  ├── Empty state: "You have no co-chefs yet"
  └── Tap Send → shareToFollower() → writes to shared_to/ + delivers recipe_shared notif → FCM push
        Snackbar: "Recipe sent to [Name]"

ReceivedRecipeScreen  (pushed route "received/{shareId}") — review screen
  ├── "Shared by [sender]" banner (tertiaryContainer; uses fromDisplayName)
  ├── Read-only recipe detail: yield adjuster, unit toggle (if conversions exist)
  ├── Sections / ingredients / steps
  ├── Sources section (clickable underlined URLs)
  └── Bottom bar: "Save Recipe" Button (BookmarkAdd icon)
        → new Room copy (new UUIDs) with authorId=currentUid, isImported=false, visibility=private
        → popBackStack() to Shared tab (card stays in feed; copy now in My Recipes)

Inline edit mode (NO separate route — F4/F14, ui/detail/RecipeEditContent.kt)
  ├── Entered via the detail pencil, or arriving with ?startEdit=true (import/freeform/new variation)
  ├── Same LazyColumn/scroll; editable items: metadata card (title/desc/author dropdown/variation
  │     name/times/yield range/tags/URLs/"Update unit conversions"), per-section ingredient rows
  │     (qty/unit/group/optional/shopping-note + add/delete/move), per-step rows (instruction +
  │     add/delete/move + "Uses ingredients" chip picker = F4 step refs), add-section
  ├── Save ✓ (top bar) → updateFullRecipe (version++, push, re-publish if shared) → back to view
  ├── Cancel ✕ (nav) → discard draft
  └── "Delete recipe" (bottom) → confirm (cascades variations) → leaves the recipe

ShoppingListScreen  (pushed route "shopping/{recipeId}?servings=&anchor=")
  ├── Combined checklist: ingredients merged by name, quantities summed (ShoppingAggregator)
  ├── Checkbox + strike-through per line; checks PERSIST (shopping_checks); Reset action
  ├── Author note shown under a line (💡 …) when present
  └── Own yield +/− adjuster + unit toggle (starts from the detail screen's scale)

CookingModeScreen  (pushed from RecipeDetailScreen)
  ├── Fullscreen, one step at a time, large text — content SCROLLS (long steps no longer clipped)
  ├── Section label + step ingredient card: each ingredient is a CHECKBOX row (tick as you add;
  │     session-only, clears when you leave the recipe), scaled + unit-converted
  ├── Unit toggle (Orig | Metric | Imp) in-screen when conversions exist — shared with detail
  ├── Section jump menu (☰ in top bar) when >1 section; can also open AT a section via "▶ Cook"
  ├── Prev / Next navigation
  └── Screen-on lock (keepScreenOn flag)
```

---

## Tech Stack

**Android:**

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Jetpack Navigation Compose |
| Local DB | Room + SQLite |
| Cloud DB | Firebase Firestore |
| Auth | Firebase Authentication + `play-services-auth` |
| Image Storage | Firebase Storage (planned) |
| Background Sync | On-launch coroutine (`AmrosaApplication`) |
| Recipe AI | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |
| Image Loading | Coil |
| DI | Manual (`AppContainer` in `AmrosaApplication`) |
| State Management | ViewModel + StateFlow |
| JSON | Gson |

**iOS:**

| Layer | Technology |
|---|---|
| UI | SwiftUI |
| Navigation | NavigationStack + NavigationDestination |
| Local DB | SwiftData (ModelContainer) |
| Cloud DB | Firebase Firestore (FirebaseFirestore SDK) |
| Auth | Firebase Authentication + GoogleSignIn-iOS |
| Background Sync | `.task` modifier on app entry; `RecipeSyncService` |
| Recipe AI | Same Cloud Functions as Android (`CloudFunctionsService`) |
| DI | Manual (`AppContainer` — `@Observable @MainActor`) |
| State Management | `@Observable` ViewModels + SwiftUI bindings |
| JSON | Swift Codable (JSONEncoder/Decoder) |

**Backend / Shared:**

| Layer | Technology |
|---|---|
| Cloud Functions | Node.js 24, Firebase Functions v2 |
| Recipe parsing | `@google/generative-ai` (Gemini 2.5 Flash) |
| XLSX parsing | SheetJS (`xlsx` npm package) |
| Hosting | Firebase Hosting (`amrosa-2ec82.web.app`) |

> **AI note:** This project uses **Gemini (Google AI)** — NOT the Anthropic Claude API. All AI calls go through the `GEMINI_API_KEY` Firebase secret. Do not add Anthropic dependencies.

---

## Current Status

### Done ✅

| Area | Detail |
|---|---|
| **Room DB v13** | Entities: recipes, sections, ingredients, steps, step_ingredient_refs, recipe_notes, shopping_checks, cooked_log. Real migrations (`MIGRATION_9_10` → `12_13`) preserve user data |
| **Recipe detail** | Yield scaling (servings + anchor-based), ingredient checklist, step-ingredient refs, inline substitute swap chips, inline optional include checkboxes, section jump chips |
| **Cooking mode** | Fullscreen step-by-step, screen-on lock |
| **F18 — Notes = one cloud thread on every recipe (Comments merged in)** | A single **"Notes"** section on **every** recipe (`notesVisible = recipe != null && !needsReview`), stored in a **dedicated top-level `recipe_notes/{recipeId}`** collection (NOT the mirror) so notes are **visibility-independent** — a note added while the recipe is private persists and **becomes visible to everyone once it's shared/public**. `recipe_notes/{id}` = `{recipeAuthorId, locked}`; `recipe_notes/{id}/notes/{noteId}` = entries (reuses the `Comment` model + `getCommentsFlow`/`addComment`/`deleteComment`, repointed in `SharedRecipeService`). **Any signed-in user may read/add** (the recipe is the in-app gate; per product decision rules don't re-check recipe access); **delete by note author or recipe owner**. Owner **Lock** = `toggleNotesLock` → `recipe_notes/{id}.locked` (rule blocks new notes when locked); the owner's load calls `ensureNotesParent` to record `recipeAuthorId` (enables owner-delete). The old **`shared_recipes/{id}/comments`** subcollection + the private `RecipeNoteEntity` section are retired (entity/`addNote`/`deleteNote` linger unused). **Deploy `firestore:rules`.** **Android only** (iOS pending). |
| **Firebase Firestore** | Connected (`amrosa-2ec82`), security rules deployed |
| **RecipeSyncService** | Pull delta sync + `pushPersonalRecipe()` push to `personal_recipes/{uid}/recipes/` |
| **Version control** | `version` int + `changeLog` JSON array of `RecipeChange` |
| **Recipe Editor (F4)** | Full edit; fork dialog; author dropdown (Imported/Personal); cloud push; visibility preserved on save |
| **Source URLs** | Clickable, open in browser |
| **F3 — Import** | `parseRecipeUrl` + `parseRecipeContent` CF deployed; pending-review flow; Confirm/Reimport/Edit; Google Sheets/Docs auto-detect; file import (XLSX/CSV/TXT, 5 MB max) |
| **RecipeReviewSheet** | Shared composable; parse notes banner (tertiaryContainer, dismissible); onEdit pencil icon; author toggle for imports |
| **F5 — Freeform** | FreeformEntryScreen, FAB bottom sheet, `formatRecipeText` CF, save/saveAndEdit flow |
| **F6 — Unit conversions** | 6 IngredientEntity fields, `QuantityScaler`, `UnitMode`, unit toggle on detail screen |
| **4-tab navigation** | My Recipes · Shared · Discover (F13 feed) · Account (All tab removed) |
| **My Recipes filter chips** | `[All] [Personal] [Imported] [Shared]` chips; scroll inside LazyColumn (first item) so they free up vertical space; author + category chips share one scrollable row; compact search bar with clear button |
| **My Recipes "Shared" chip** | Shows live `shared_to/` feed in-tab (same cards); search filters shared feed; FAB + category chips hidden in Shared mode; tap → `received/{shareId}` |
| **Recipe cards** | Author row (person icon + name) + Shared pill on all cards |
| **F7 — Mandatory auth gate** | Auth required at launch; `AuthScreen` shown as root when not signed in (no back button); main app only renders after real sign-in |
| **F7 — Sign-out clears data** | `clearAllLocalData()` in `AppContainer` wipes Room + sync prefs; called before `signOut()` |
| **F7 — Auth** | Google + email/password + phone OTP; `linkWithCredential` upgrade; sync on sign-in; `AccountScreen`; `AuthScreen` |
| **F7 — Author attribution** | `authorId` + `authorDisplayName` stamped at creation; editor author dropdown; personal = real name; imported = "Imported" override at publish time |
| **F8 — Visibility** | `visibility` field on RecipeEntity; share button in detail top bar |
| **F12 — Visibility tiers + Co-Chef profiles** | 3 tiers (private/friends/public, no migration); friends-gated `shared_recipes` read rule; `ProfileScreen` from FriendsScreen shows a co-chef's friends+public recipes; `getAuthorRecipes`; review via generalized `ReceivedRecipeScreen` (`ReviewSource.Pointer|Direct`) → "Add to Shared tab"; private direct-share → Co-Chefs tier. **Android only** (iOS pending). Needs composite index `shared_recipes (authorId, visibility)`. |
| **F13 — Discover tab (Phase 1)** | Recommendation feed: time-of-day meal shelves, implicit cuisine affinity, source boost (own>friend>public), recency penalty via new `cooked_log` (DB v13). Reuses F12 review screen for view-free/cook/save; `CookingModeScreen` now `internal` + "Done"→`markCooked`. **Android only** (iOS pending). |
| **F13 — Discover Phase 2 (popularity)** | `saveCount`/`likeCount` on `shared_recipes` via 4 Admin-SDK Cloud Functions (received-save & like triggers); likes (`setLiked`/`likeStateFlow`) with ❤ on the review screen + read-only counts on the owner's detail; popularity term in the ranker + a "Popular" shelf (recent ∪ popular). **Android only** (iOS pending). Needs index `shared_recipes (visibility, saveCount desc)`. |
| **F13 — Discover Phase 3a (search)** | Cross-scope search bar (own>friends>public, deduped): own/friends client-side `contains`; public via `searchTokens` array on `shared_recipes` + `searchPublicRecipes` (array-contains + client refine), debounced. Backfill script for existing mirrors. **Android only** (iOS pending). Needs index `shared_recipes (visibility, searchTokens)`. |
| **F13 — Discover people search** | The Discover search also matches **users** by name/email (`socialRepository.searchUsers`) — a "People" section of cards above recipe results, each with a follow/request button (`sendFollowRequest`, status-aware) and tap → their `ProfileScreen` (public/co-chef recipes). `DiscoverViewModel.userResults`/`followStatuses`; `DiscoverScreen.onProfileClick` → `profile/{uid}`. **Android only**. |
| **F13 — Discover Phase 3b (polish)** | Public chef profiles via user search (`ProfileViewModel` resolves co-chef vs public via `getFollowStatus`); explicit cuisine prefs (`UserPreferences`) overriding affinity, edited in Account; `CompactSearchField` (shorter); pull-to-refresh; **Discover promoted to the default tab**. **Android only** (iOS pending). |
| **F14 — Inline recipe editing** | Replaced the separate `RecipeEditorScreen` with a **global Edit toggle on the detail screen** that edits in place. All editing logic ported into `RecipeDetailViewModel` (`EditDraft`). Adds **step-ingredient editing** (refs rewritten by `updateFullRecipe`) and retires the import→edit→save redirect bug. `?startEdit` nav arg; `recipe/edit` route + editor screen deleted. **Superseded by F16** (the inline-form `RecipeEditContent.kt` is gone). **Android only** (iOS keeps its editor). |
| **F16 — Edit mode: jiggle + tap-to-edit popups** | Edit mode now keeps the detail screen **visually identical** to the read-only view: the body renders the live draft as a `Recipe` (`EditDraft.toPreviewRecipe`), every editable element **jiggles** + outlines (`Modifier.editable`, `ui/util/EditAffordance.kt`), and a tap opens a `ModalBottomSheet` for that item (`ui/detail/RecipeEditSheets.kt`: Ingredient/Step/Section/Details, switched by sealed `EditTarget`). Adds via ghost "＋ Add" rows + top-bar ＋ menu; empty sections show add-rows in edit, hidden headers in view. Scaler/unit-toggle/options/notes hidden while editing. **Android only** (iOS keeps its own editor — port pending). |
| **F15 — Ingredient quantity ranges** | Ingredients can store a range ("4–6 cloves"): 3 nullable max columns on `IngredientEntity` (`quantityValueMax` + metric + imperial), **Room v14** (`MIGRATION_13_14`). `QuantityScaler` renders/scale both ends; unit/display shared with the min. Gemini sets low+high (`quantityValueMax`), `computeMaxConversions()` scales the metric/imperial maxes by the max/min ratio. Threaded through every mapper/sync. **Backend redeploy required.** **Android only** (iOS pending). |
| **Collective step-ingredient linking (import QOL)** | Recipes whose step says "add all the paste ingredients" without naming them now link every section/group ingredient: schema prompt rule + `linkOrphanIngredients()` server net (attaches unreferenced ingredients to their section's first step) + Android render-time fallback `augmentedStepRefs()` in cooking mode for already-imported recipes. **Backend redeploy required** for layers 1–2; the on-device fallback is **Android only** (iOS needs the same cooking-mode fallback). |
| **Discover = leftmost tab** | `bottomTabs` reordered to `Discover · My Recipes · Shared · Account`; Discover is also the start destination. **Android only** (iOS should mirror the order). |
| **Privacy: no private recipes in Discovery/profiles** | Fixed stale `shared_recipes` mirrors leaking into Discovery/profile. Rules reject `shared_recipes` create/update unless `visibility ∈ {shared, friends, public}`. Client reconciles the mirror: delete unpublishes any shared tier, `saveEdit` unpublishes when private. One-off `backend/firestore/cleanup-private-mirrors.js` purges orphans (`SHARED_TIERS = {shared, friends, public}`). **Deploy `firestore:rules` + run cleanup script.** |
| **F17 — Shared with specific people (per-recipient ACL)** | New `"shared"` visibility tier + `recipes.sharedWith` (UID list; Room **v15**, `MIGRATION_14_15`). A `shared` recipe is mirrored to `shared_recipes` with a `sharedWith` array; the read rule grants `request.auth.uid in resource.data.get('sharedWith', [])` — readable only by author + listed users, never in Discovery/profile. One source of truth → author **edits propagate** (mirror republished on save). UI: "Specific people" in the visibility chooser → `RecipientsSheet` (name/email `searchUsers` to add, removable list); `RecipeDetailViewModel.setSharedRecipients`/`addSharedRecipient`/`removeSharedRecipient`. **Direct "Send to co-chef" now adds to the share-list** (`ensureSharedWith`) instead of bumping to Co-Chefs. `SocialRepository.getUsers(uids)` resolves recipient names. **Deploy `firestore:rules`.** **Android only** (iOS pending). |
| **F8 — Share button** | Top bar icon (owners only); if public → Android share sheet with `amrosa://shared/{id}`; if private → dialog → publish → share sheet |
| **F8 — Deep links + App Links** | HTTPS App Links (`https://amrosa-2ec82.web.app/shared/{id}`) + `amrosa://` fallback; `assetlinks.json` in Firebase Hosting; `navDeepLink` for both patterns in NavGraph |
| **F8 — Firebase Hosting** | `shared.html` recipe viewer (browser fallback); `index.html` landing page; deployed at `amrosa-2ec82.web.app` |
| **F8 — Shared tab (reworked)** | Tab 2 (`SharedInboxScreen`, "Shared Recipes") shows received recipes from `shared_to/{uid}/` as full recipe cards (title/times/tags/author). Not community browse. Same feed as My Recipes "Shared" chip. |
| **Shared recipe author fix** | `buildSharedToDocument()` stores `authorDisplayName` (original author) separately from `fromDisplayName` (sender); recipient sees the original author, not the sender. Old docs fall back to sender. |
| **F8 — Shared detail** | `SharedRecipeDetailScreen`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; deep link entry point only |
| **F8 — Comments** | `Comment` domain model; post/delete; Firestore subcollection; delete by commenter or recipe owner. **Surfaced as "Notes" (F18)** — the separate Comments section was merged into the Notes thread. |
| **F8 — Security rules** | Full Firestore rules deployed; per-UID personal access; `shared_recipes` public read; comment create/delete moderation |
| **Seeder disabled** | `seedIfNeeded()` is a no-op; fresh installs start blank |
| **F9 — Co-Chefs (mutual friends)** | Bidirectional friendship: `acceptFollowRequest()` batch-creates reverse doc so both see count = 1; `unfriend()` batch-deletes both docs; `getFriendsFlow()` queries `followerId == uid AND status == accepted` |
| **F9 — User search** | `UserSearchScreen`; email lookup (`@` in query → exact match) or displayName prefix; shows name + email; "Add Co-Chef" / "Co-Chef ✓" / "Requested" states |
| **F9 — Account tab social** | Co-Chefs section: pending requests inline with Accept/Decline; "Co-Chefs: N" tappable row → FriendsScreen; "Find Co-Chefs" link |
| **F9 — FriendsScreen** | Lists accepted co-chefs; Remove button → confirmation → `unfriend()` batch delete |
| **F9 — Direct recipe sharing** | Single Share icon → `ShareOptionsSheet`; "Send to co-chef" → `FollowerPickerSheet`; stores full recipe in `shared_to/{recipientUid}/recipes/{shareId}`; delivers notification → FCM push |
| **F9 — FCM push notifications** | `AmrosaMessagingService` (`onNewToken` stores token, `onMessageReceived` shows foreground notification); `sendPushNotification` Cloud Function triggered on `notifications/{uid}/items` creation; channel `"amrosa_social"` created on app start; Co-Chef / recipe share push text |
| **F9 — Received recipe (review)** | `ReceivedRecipeScreen` + `ReceivedRecipeViewModel`; `getReceivedRecipe()` returns `ReceivedRecipeData(recipe, fromDisplayName)`; "Shared by [sender]" banner; read-only detail with scaling + Sources section; **"Save Recipe"** → Room copy → `popBackStack()` to Shared tab (card stays in feed) |
| **Delete recipe** | Red "Delete Recipe" button at bottom of editor; confirmation dialog; calls `repository.deleteFullRecipe(recipeId)` then navigates back |
| **URL import reliability** | `extractJsonLdRecipe()` extracts `schema.org/Recipe` JSON-LD from raw HTML (before cleanHtml strips scripts) — major recipe sites include this for SEO; realistic browser headers; 402/403 → actionable error message suggesting freeform entry |
| **Imperial unit fix** | Gemini only populates metric fields; `computeImperialFromMetric()` in Cloud Function computes imperial from metric with exact math: g/kg → oz (< 453.6g) or lb; ml/L → fl oz (1 fl oz = 29.574 ml) |
| **Recipe Ownership Model v2** | ✅ End-to-end (iOS↔Android). No official recipes; Tab 1 = mine (editable), Tab 2 = received references (read-only, "Remove"); reference-based shares via `shared_recipes` mirror gated by Public; auto-removal on unpublish/delete; author labels "me / Imported by X". See "Recipe Ownership Model (v2)". |
| **Notification deep links** | Tapping a push routes to the target screen: `recipe_shared` → `received/{shareId}`; follow notifications → Account tab. `MainActivity` reads FCM extras → `AmrosaApplication.pendingDeepLink` → nav graph navigates. |
| **Update unit conversions (existing recipes)** | `convertIngredients` Cloud Function (Gemini metric w/ density for dry → weight, liquids → volume) + deterministic imperial; editor "Update unit conversions" button above ingredients; `EditorIngredient` now preserves the 6 conversion fields on save (fixes edit wiping conversions); adaptive `impRound()` so tiny amounts don't show `0 oz`. |
| **Convert validation + learned densities** | Curated `DENSITY_TABLE` is authoritative; bounded density check (0.1–2.5 g/ml) falls back to fl oz; self-growing `ingredient_densities` Firestore table (Admin-SDK-only) learns volume→weight densities and serves them after 3+ sightings. |
| **F10 — Recipe Variations** | Up to 4 linked editable copies per recipe (`parentRecipeId` + `variantName`, 20-char cap); hidden from lists; "Original / <name> / ＋ Variation" chips on detail; `duplicateAsVariant()` deep-copy with full id remap; cascade delete; sync-aware. |
| **Cooking mode upgrades** | Honours the unit toggle (Orig/Metric/Imp, switchable mid-cook); content scrolls (long steps no longer clipped); section jump menu + "▶ Cook from here" on detail section headers. |
| **Ingredient ordering** | Detail checklist grouped by SECTION (in step order) then by group label; trailing "Other" bucket for section-less ingredients. |
| **Detail refresh / delete UX** | Detail **auto-refreshes via a Room `Flow`** on the recipe row (`RecipeDao.observeRecipe`) — editor saves reflect immediately, no reopening (replaced the unreliable lifecycle/resume reload). Deleting from the editor returns to the recipe list (not the stale detail). |
| **F11 — Shopping List** | Cart icon → `ShoppingListScreen`; `ShoppingAggregator` combines ingredients by name with summed totals (unit-aware); persisted checks (`shopping_checks` table, Reset action); author `shoppingNote` per ingredient (editor only, travels with recipe, absent from import/freeform); old detail checkbox/strike removed. |
| **Cooking-mode step checklist** | Each ingredient in a step card is a checkbox row (tick as you add); session-only, scoped to the recipe screen so it clears on exit. |

### Planned — In Priority Order

| # | Feature | Description |
|---|---|---|
| — | Recipe Images | Firebase Storage integration; image picker on editor; Coil (Android) / AsyncImage (iOS) display. **Both platforms.** Largest remaining feature. |
| — | Discover tab | **Fully built on Android (F13):** feed · popularity · search · chef/public profiles · cuisine prefs · pull-to-refresh · default tab. (iOS port pending in the other session.) Future ideas if wanted: ratings, dietary filters, seasonal boosts. |
| — | Gemini brand/substitute suggestions | The deferred half of F11 — AI-suggested top brands + substitutes per ingredient (author notes already ship). |
| — | iOS: Universal Links | ✅ Wired — AASA file + `Associated Domains` entitlement added. Needs `firebase deploy --only hosting` to publish the AASA. |
| — | Shopping list — cross-recipe / standalone | Optional: combine multiple recipes into one shopping trip (current F11 is per-recipe). |

**Tech debt / smaller follow-ups:**
- Verify the iOS sync clean-replace (`replaceSyncedContent`) compiles + behaves in Xcode (written but not built on the Android dev box).
- iOS fresh-pull doesn't set `scaleIngredientId`/`scaleStep` on `insertFullRecipeFromParsed` → anchor-scaling config lost on first multi-device pull. Small, isolated.
- Editor's post-save cloud push runs in `viewModelScope` and can be cancelled mid-pop; launch-time `pushAllPersonalRecipes()` self-heals. Could move to an app-scope coroutine.

---

## iOS Platform Status

The iOS codebase (`ios/Amrosa/`) is a fully-functional port of the Android app. Swift, SwiftUI, SwiftData. All core features including auth gate, shared recipes, comments, and visibility/share are implemented. Notifications are push-only (no in-app notification screen).

**Recipe Ownership Model v2 — ported (matches the authoritative model above):**
- `RecipeModel.isReceived` added (SwiftData auto-migrates the additive field). Tab 1 = `isReceived=false`; Tab 2 = `isReceived=true`.
- `RecipeSyncService`: seeded `recipes` pull removed; `sync()` = pull personal + `syncReceivedRecipes()`. Received refresh re-reads each `shared_recipes/{recipeId}` mirror via `SharedRecipeService.getSharedRecipeDetail` → present: re-cache (`cacheReceivedRecipe`, original author preserved); absent: drop reference + local copy. `pushPersonalRecipe` skips received. `deletePersonalRecipe(id)` removes the cloud doc on delete.
- `SocialRepository`: `getReceivedPointer` / `saveReceivedReference` / `deleteReceivedPointer` / `removeReceivedReference`; `buildSharedToDocument` stores `recipeId` + `authorUid`. `received_recipes/{uid}/items` references drive Tab 2.
- `RecipeRepository`: `fetchMyRecipes` (Tab 1, excludes received), `fetchReceivedRecipes` / `fetchReceivedRecipeIds` (Tab 2), `cacheReceivedRecipe` (canonical ids preserved, `isReceived=true`), `removeReceivedRecipe`.
- `ReceivedRecipeView`: reads pointer → loads live mirror; **Save Recipe** writes a reference + caches locally + consumes the pointer (no copy).
- `SharedInboxView` (Tab 2): pending pointers ("In review — tap to save", dismiss ✕) on top + saved received cards below; saved → read-only `RecipeDetailView`.
- `RecipeDetailView`: when `isReceived`, top bar shows only **Remove** + Cooking Mode; owner recipes keep Share + Edit. Share gates on Public — sharing a private recipe prompts "make public to share". `isOwner = authorId == uid && !isReceived`.
- `RecipeEditorView`: delete also removes the cloud copy + unpublishes the mirror if public; save re-publishes the mirror if public. Fork dialog removed.
- Author label rule: "me" / "Imported by me" (Tab 1) · "B" / "Imported by B" (Tab 2). `SharedRecipeService.buildDocument` no longer overwrites the author name with "Imported".
- **Author-name resolution ("Imported by Imported" fix):** at save, when the mirror name is blank/"Imported" the sender IS the author → use `fromDisplayName`. On refresh, `resolveAuthorName()` reads `users/{authorUid}.displayName` (authoritative) so legacy/iOS recipes stored as literal "Imported" display the real name.
- **Notification deep links:** `recipe_shared` tap → Shared tab → `SharedInboxView` consumes the shareId and opens the `ReceivedRecipeView` review screen; `follow_*` → Account tab.

**F10 Recipe Variations + QOL — ported:**
- `RecipeModel.parentRecipeId` + `variantName` (SwiftData auto-migrates). Base lists (`fetchMyRecipes`/`fetchReceivedRecipes`) filter `parentRecipeId == nil`; `getVariants(parentId:)`; `duplicateAsVariant()` deep-copies with full id remap (sections/ingredients/steps/refs/`scaleIngredientId`/`substituteGroupId`).
- `RecipeDetailView`: Original / `<name>` / ＋ Variation chips; chip tap navigates to the family member; ＋ → name alert → `createVariant()` → opens editor (and pushes to cloud). Reloads on resume; dismisses to the list when the recipe was deleted.
- `RecipeEditorView`: variation-name field (n/20) for variations; cascade-deletes variations when a base is deleted; preserves `parentRecipeId`.
- Sync: push/pull carry `parentRecipeId`/`variantName`; received recipes forced standalone (`parentRecipeId = nil`).
- **Cooking mode:** in-screen unit toggle (shared with detail), section jump menu, "▶ Cook from here" on detail section headers (start-at-section), unit-aware ingredient amounts. Already scrolled.
- **Ingredient ordering:** detail checklist grouped by SECTION (step order) then group label, trailing "Other" bucket (`ingredientSectionBlocks`).
- **"Update unit conversions" button** in the editor → `convertIngredients` CF → merges metric/imperial into `EditorIngredient` (now carries the 6 conversion fields); Save persists. `updateFullRecipe` writes the conversion fields (no more wiping).
- **fcmToken privacy:** stored in `users/{uid}/private/push` (owner-only), not the world-readable profile doc.

**F11 Shopping List + step checklist — ported:**
- `IngredientModel.shoppingNote` + `ShoppingCheckModel` (`@Attribute(.unique) key = "recipeId|itemKey"`; SwiftData auto-migrates). Registered in the AppContainer schema; wiped on sign-out; checks cascade-deleted with the recipe. Local only — never synced.
- `ShoppingAggregator.swift` mirrors Android: `normalizeKey` + `build(ingredients:scaleFactor:unitMode:)` → `[ShoppingLine]` (per-unit summing, " + " joins, non-numeric pass-through, one member per substitute group, de-duplicated notes).
- `ShoppingListView` (cart icon in detail top bar → push, carries the detail's servings/anchor): own yield ± / unit toggle, persisted checks with strike-through, Reset confirm, 💡 note under merged lines.
- `shoppingNote` carried through every mapper: push/pull sync, `shared_recipes` mirror + `shared_to` docs (both directions), `cacheReceivedRecipe`, `duplicateAsVariant`, `copyToMyRecipes`, editor save. `ParsedIngredient`/`SharedIngredient` gained a defaulted `shoppingNote` field — import/freeform never set it (matches Android: editor-only authoring via the "＋ Shopping note" field in `IngredientEditorRow`).
- **Detail ingredient list is display-only** (bullet rows; optional toggle kept) — the checklist lives on the Shopping List.
- **Cooking-mode step checklist:** step ingredient rows are checkbox rows backed by the detail VM's `checkedIngredientIds` — persists across steps/re-entry, clears when leaving the recipe (session-only).
- **Auto-refresh note:** Android needed a Room-Flow observer; iOS gets this for free — SwiftData `@Model`s are reference types observed by SwiftUI, so editor saves mutate the same instance the detail renders. The `reload()`-on-appear covers variant-family and deletion changes.

**F15 Ingredient quantity ranges — ported:**
- `IngredientModel` gains `quantityValueMax` / `quantityValueMaxMetric` / `quantityValueMaxImperial` (`Double?`, SwiftData auto-migrates). `ParsedIngredient` + `SharedIngredient` gain the same (defaulted).
- `QuantityScaler` is range-aware: a 5-arg `scale(value, valueMax, unit, display, scale)` renders "min–max unit" scaling **both** ends when `max > min`; `scale(ingredient:…unitMode:)` + `scaleShared` route through it picking the right min/max pair per unit system.
- Threaded through every mapper (push/pull, `shared_recipes` mirror, `shared_to` docs, `cacheReceivedRecipe`, `duplicateAsVariant`, `copyToMyRecipes`, `firestoreToParsedRecipeData`, `replaceSyncedContent`, editor save). **No client conversion math** — `updateConversions()` merges the backend's `quantityValueMaxMetric`/`quantityValueMaxImperial`.
- Editor: a small "to [max]" numeric field beside the quantity (`EditorIngredient.quantityValueMax`).

**Collective step-ingredient linking (cooking-mode fallback) — ported:**
- `RecipeDetailViewModel.cookingStepIngredients` mirrors `augmentedStepRefs()`: any ingredient referenced by no step in its section attaches to that section's first step (fallback: the recipe's first step). `CookingModeView` renders this augmented list so collectively-referenced ingredients still surface.

**F12 Visibility tiers + Co-Chef profiles — ported:**
- 3 tiers: `setVisibility("private"|"friends"|"public")` publishes the mirror for friends/public (with the real tier via `buildDocument`, no longer hard-coded "public") and unpublishes for private. `isPublished = visibility != "private"` gates comments + the direct-share path.
- `VisibilityChip` opens a 3-option chooser (🔒 Private / 👥 Co-Chefs / 🌐 Public).
- Direct-share of a private recipe → `makeSharableAndShareToFollower` publishes at `"friends"` (already-public left public); the prompt says "visible to your co-chefs". The Share-link flow still requires Public (`handleShareTap`).
- `SocialRepository.getAuthorRecipes(authorUid:includeFriendsOnly:)` queries `shared_recipes` by author + tier (`whereField visibility in […]`) → `ProfileRecipeSummary`. Needs the deployed composite index `shared_recipes (authorId, visibility)`.
- `ProfileView` + `ProfileViewModel`: reached by tapping a row in `FriendsView` **and** `UserSearchView`; resolves co-chef vs public via `getFollowStatus`; header badge "Co-Chef"/"Chef".
- `ReceivedRecipeView` generalized via `ReviewSource { .pointer(shareId) | .direct(recipeId, authorUid, authorName) }`. Profile recipes open in `.direct` mode with an **"Add to Shared tab"** button (saves a received reference; no pointer to consume). Inbox keeps `.pointer`.

### iOS ✅ Implemented & Matching Android

| Feature | Notes |
|---|---|
| **SwiftData schema** | Matches Android Room exactly — all fields including F6 unit conversion fields |
| **Mandatory auth gate** | `ContentView` observes `authStateStream()`; shows `AuthView()` as root wall when not signed in; no anonymous sessions |
| **Sign-out clears local data** | `AppContainer.clearAllLocalData()` wipes all SwiftData records + sync prefs; called before `signOut()` with warning dialog |
| **Sign-in triggers sync** | `ContentView.task` detects real user sign-in and calls `container.onSignIn()` |
| **Recipe detail** | Scaling (servings + anchor-based), ingredient checklist, optional toggle, substitute selection, section jump chips, notes, unit mode picker |
| **Anchor-based scaling** | `scaleAnchorQty`, `adjustScale(delta:)`, `resetScale()` (long-press), `yieldDisplay` |
| **Cooking mode** | Full-screen step-by-step; section labels; prev/next nav |
| **Recipe editor** | Full section/ingredient/step CRUD via `EditorSection`/`EditorIngredient`/`EditorStep`; delta-save via `updateFullRecipe`; fork dialog |
| **Import flow** | URL, file (XLSX/CSV/TXT), freeform; `CloudFunctionsService`; `RecipeReviewSheet` with parse notes banner, Confirm/Reimport, author toggle |
| **Pending review model** | `needsReview = true` on save; float to top; tap re-opens review |
| **Auth providers** | Google Sign-In, Email/Password, Phone OTP; anonymous→named `linkOrSignIn` upgrade |
| **Pull sync** | Pulls seeded (`recipes/`) + personal (`personal_recipes/{uid}/recipes/`) from Firestore |
| **Push sync** | `pushPersonalRecipe()` — full payload: sections, ingredients (incl. F6 + `substituteRatio`), steps, `stepIngredientRefs`, `scaleStep`; `pushAllPersonalRecipes()` |
| **Timestamps** | Written as `Int64` milliseconds (Android-compatible `Long`) |
| **F8 — Shared detail** | `SharedRecipeDetailView`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; comments |
| **F8 — Visibility chip** | Owners see Private/Public toggle chip in detail body; confirms before toggling |
| **F8 — Merged share button** | Single Share icon → `ShareOptionsSheet` ("Send to a follower" / "Share link"); owners only |
| **F8 — Share link** | iOS share sheet with `https://amrosa-2ec82.web.app/shared/{id}`; publish dialog for private recipes |
| **F8 — Comments** | Post/delete in owner view + visitor view; commenter or recipe owner can delete |
| **F8 — SharedRecipeService** | `publish/unpublish`, `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **Deep links** | `amrosa://shared/{id}` custom scheme + `https://amrosa-2ec82.web.app/shared/{id}` via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **Tab restructure** | All tab removed; 4 tabs: **My Recipes** · Shared Recipes · Discover (F13 feed) · Account |
| **My Recipes Shared chip** | `YourRecipesViewModel` has `.shared` filter; loads `receivedRecipesSummaryStream()`; chip switches list to shared `SharedRecipeCard`s, hides FAB, tap → `ReceivedRecipeView`; compact capsule search bar w/ clear button + adaptive placeholder; chips scroll inside the list |
| **Shared Recipes tab** | `SharedInboxView` + `SharedInboxViewModel`; live `shared_to/{uid}/recipes/` stream; full recipe cards (title, times, tags, author + "from sender"); taps → `ReceivedRecipeView` |
| **Discover tab (F13 — full)** | Default + left-most tab. `MealClassifier` + `DiscoverRanker` (meal match · cuisine affinity · source boost · **popularity** · recency penalty). Shelves: "{Meal} ideas", "From your kitchen", "From your co-chefs" (`getAuthorRecipes` per co-chef), **"Popular"** (recent ∪ `getPopularPublicRecipes`, by saves·2+likes), "Fresh from the community" (`getPublicRecipeSummaries`), "Recently cooked" (`cooked_log`). Surprise-me + pull-to-refresh. `CookedLogModel` + `markCooked()` on Cooking Mode "Done!". Local card → editable detail; remote → `ReceivedRecipeView(.direct)`. **Phase 5 (popularity):** `getPopularPublicRecipes`, `setLiked`/`likeStateStream` + ❤ toolbar on `ReceivedRecipeView` (read-only counts; `canLike` = signed-in). **Phase 6 (search):** `.searchable` cross-scope search (own/friends client-side `contains`, public via `searchPublicRecipes` array-contains on `searchTokens` + client refine, 300ms debounce); `buildDocument` now writes `searchTokens`. **Phase 7 (prefs):** `UserPreferences` (UserDefaults) explicit cuisine chips in Account ("Recipe preferences") override implicit affinity on next refresh. |
| **Account tab** | Profile card (tappable → edit name alert), sign-out + data-wipe dialog, recipe count, last sync |
| **Profile name edit** | Tap profile card → alert with text field → `authRepository.updateDisplayName()` + `upsertProfile()`; toast on success |
| **F9 — Follow system** | `SocialRepository`, `UserSearchView`, `ReceivedRecipeView`, `FriendsView`; follow/unfollow/accept/decline, direct recipe sharing, Co-Chefs list. In-app notifications removed (push only). |
| **F9 — Push notifications only** | In-app `NotificationsView` + notification reading removed; Firestore notification writes remain to trigger `sendPushNotification`. `AppDelegate` + `FirebaseMessaging`; APNs bridge; token stored on sign-in; tap routing (follow → Account, recipe shared → Shared) |
| **Shared recipe author attribution** | `buildSharedToDocument` stores `authorDisplayName` (original author) separate from `fromDisplayName` (sender); `getReceivedRecipe` returns `ReceivedRecipeData(recipe, fromDisplayName)`; `ReceivedRecipeView` "Shared by [sender]" banner + Sources section + "Save Recipe" → dismiss back |
| **Co-Chef stale-data repair** | `repairFriendships()` on sign-in creates missing reverse follow docs for pre-mutual-friendship data |
| **Recipe detail (section-grouped)** | Ingredients grouped by section (step order) then group label via `ingredientSectionBlocks` — matches Android; steps grouped by section under "Instructions"; section jump chips scroll via `ScrollViewReader`; step-ingredient ref chips shown inline. (Still has per-ingredient check circles — see Remaining Gaps.) |
| **Cooking mode (parity)** | Unit toggle (Orig/Metric/Imp), scrolling content, section jump menu, "▶ Cook from here" start-at-section — matches Android (step tick-off checklist NOT yet ported) |
| **isOwner for pre-auth recipes** | `authorId == nil` → `isOwner = true` (seeded/pre-auth recipes editable by anyone) |
| **Author dropdown in editor** | `RecipeEditorView` has Picker("Author") — "Imported" or "Personal — [Name]"; backed by `isPersonalAuthor: Bool` in VM; applied on save |
| **FAB bottom sheet** | `AddRecipeSheet` (presentationDetent) with descriptive options, matching Android ModalBottomSheet |
| **setVisibility in-memory** | `recipe.visibility` updated immediately in VM after Firestore write so `isPublic` reflects without view recreation |
| **confirmImport author toggle** | `confirmImport()` calls `updateIsImported(!isOwnRecipe)` so the RecipeReviewSheet toggle is actually persisted |
| **reimport duplicate fix** | `reimport()` deletes the pending recipe before clearing state, preventing duplicate SwiftData records |
| **Co-Chefs: N tappable** | AccountView "Co-Chefs: N" is a `NavigationLink` → `FriendsView` (list with Remove + confirmation) |

### iOS 🔶 Remaining Gaps (planned)

| Gap | Detail |
|---|---|
| **F16 — Edit mode redesign (jiggle + popups)** | ✅ **Ported (true in-place, zero-reflow).** The detail pencil flips `RecipeDetailContent` into edit mode while rendering the **identical body** — same `ScrollView`/`LazyVStack`, same items, same heights, same scaled/unit quantities — so **nothing on screen moves**: editable rows just gain an outline + jiggle + tap (`.editable`, `UI/Util/EditAffordance.swift`, uses `.overlay`+`.rotationEffect` which don't affect layout). Scroll position is preserved exactly. All add/convert/delete actions live in the **edit toolbar** (`ellipsis.circle` menu: Recipe details / Add ingredient / Add step / Add section / Update unit conversions / Delete recipe) — **no ghost rows or body chrome that would shift content**. Mirrors Android's `toPreviewRecipe`: lightweight **display value types** (`RecipeDisplayModels.swift`: `DisplaySection`/`DisplayIngredient`/`DisplayStep`) that both the real `RecipeModel` (view) and the live draft (edit) map into via `RecipeDetailViewModel+Display.swift` (same scale+unit + same grouping/empty-section hiding in both modes, incl. a single-nameless-section sentinel so no-section recipes match exactly), so `IngredientRow`/`StepRow` are one component for both. Tapping a row/header/description opens its sheet (`EditSheetHost` in `RecipeEditSheets.swift`: Details/Section/Ingredient/Step via `EditTarget`, state seeded in `init`, opens at `.large`; add-Ingredient/Step sheets have a section picker). Edit state + ops live in `RecipeDetailViewModel+Edit.swift` (`enterEdit`/`cancelEdit`/`saveEdit`/`updateConversions`/`deleteRecipe`); save maps the draft via `updateFullRecipe` (also rewrites step→ingredient refs from `EditorStep.ingredientIds`), pushes, and re-publishes if shared. `RecipeEditorView` is retained for the import/freeform & new-variation entry points. **⚠️ Read F4's "Design intent & rationale" first — it explains the *why* behind these rules.** Still to align with Android's intent: **(a) in-context adds** — Android uses faint ghost "＋ Add ingredient/step" rows *where the item lands*, with view-mode space reserved so adding doesn't shift; the iOS toolbar-menu loses that placement (zero-reflow is preserved, but adopt the ghost-row pattern). **(b) Recent refinements that post-date this port and must be carried over:** optional ingredients render as a **one-line chip row per section** (selecting drops the ingredient in; default-included via an Account toggle) — not inline checkboxes; substitutes show **inline swap chips under the ingredient** (no separate "Options" section) + a **"Substitute for" picker** in the ingredient sheet, and a step that uses a group member **resolves to the selected member** (never blank); **per-recipe remembered selections** (substitute + optional choices persist); quantity **ranges** (F15: `quantityValueMax`) editable in the ingredient sheet; and the scroll-stability refinements (visibility/variation chips kept **greyed** not removed, constant-height yield, reserved add-row height). |
| **F17 — Shared with specific people** | Android-only. iOS needs: a `sharedWith: [String]` on the SwiftData recipe; the mirror build/read to include `sharedWith` + allow the `"shared"` visibility tier; a "Specific people" option in the visibility chooser opening a recipients sheet (reuse `searchUsers` + a `getUsers(uids)` batch read for names); `setSharedWith`/add/remove + republish on change; and the direct-share path adding the recipient to `sharedWith` (visibility `"shared"`) instead of bumping to Co-Chefs. Firestore rules (the `sharedWith` ACL) are shared infra — already deployed. |
| **F18 — Notes = one cloud thread on every recipe** | Android-only. iOS should merge its **Comments** + private **Notes** into one **"Notes"** section shown on **every** recipe, stored in the **top-level `recipe_notes/{recipeId}`** collection (NOT the mirror) so notes survive private↔shared changes. `recipe_notes/{id}` = `{recipeAuthorId, locked}`, `…/notes/{noteId}` = entries. Any signed-in user reads/adds; note-author or recipe-owner deletes; owner **Lock** sets `recipe_notes/{id}.locked` (hide input + rule blocks creates), and the owner records `recipeAuthorId` on load (`ensureNotesParent`). Drop the iOS private on-device notes + the `shared_recipes/{id}/comments` path. Rules are shared infra. |
| **Universal Links** | ✅ Wired — `Associated Domains` entitlement (`applinks:amrosa-2ec82.web.app`) + AASA at `hosting/public/.well-known/apple-app-site-association` (served as application/json). Pending `firebase deploy --only hosting`. |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |

---

## UI/UX Guidelines

- **Typography:** Minimum 16sp body text; 20sp+ for recipe steps
- **Color palette:** Warm, earthy tones — cream, terracotta, deep green, muted gold
- **Dark mode:** Supported from the start
- **No ads. No onboarding flows. No forced permissions.**
- **Cooking Mode** keeps screen on automatically — no separate toggle
- **Unit toggle** (Original / Metric / Imperial) is session-level, not persisted to preferences
- **Parse notes banner** uses `tertiaryContainer` color, is dismissible
- **Auth is mandatory** — `AuthScreen` is the root wall; no anonymous access; sign-out deletes all local data
- **needsReview badge** on cards uses `tertiaryContainer` (matches parse notes, consistent language)

---

## Out of Scope (permanently removed)

- Meal planning / calendar integration — ❌
- Nutritional information — ❌
- Voice input / hands-free mode — ❌
- Social profiles / public profile pages — planned (view other users' public recipes at `"profile/{uid}"`), not yet implemented

---

## Notes for AI Coding Assistants

### General
- **AI model**: Gemini 2.5 Flash (`@google/generative-ai` npm). Do NOT use Anthropic SDK.
- **Kotlin idioms**: data classes, sealed classes, extension functions, `StateFlow` over `LiveData`
- **Compose only**: all UI in Jetpack Compose — no XML layouts
- **IO dispatcher**: all DB and network on `Dispatchers.IO`, never on Main
- **Room is source of truth**: UI never reads Firestore directly (except `SharedRecipeDetailScreen` which loads from Firestore since the recipe is not in Room)


### Database
- **DB versioning**: **Current DB v13.** Real migrations are registered in `AppContainer.addMigrations(...)` and **must be preferred** now that users have data — add a `MIGRATION_(n)_(n+1)` (ALTER TABLE / CREATE TABLE) for every schema change. `fallbackToDestructiveMigration()` stays only as a last-resort safety net (it WIPES local data — avoid relying on it).
- **Detail auto-refresh**: the detail VM observes `RecipeDao.observeRecipe(id)` (a Room `Flow`) and reloads on `updatedAt`/`version` change. Do NOT rely on Compose lifecycle/`ON_RESUME` for cross-screen refresh — `LocalLifecycleOwner` may resolve to the Activity and miss intra-NavHost navigation. Room Flow is the source of truth.
- **Seeder**: `seedIfNeeded()` is a no-op. Do not add seeding logic.
- **IngredientEntity field order**: F6 conversion fields are **last** (after `orderIndex`). Do not insert new fields before them — it breaks positional `DatabaseSeeder` calls.

### Recipe Logic
- **Scaling math**: servings-based: `baseQty × selectedServings / baseServings`; anchor-based: `baseQty × anchorQty / baseAnchorQty`; unit conversion quantities scale in parallel using the same ratio.
- **Substitute resolution**: when substitute selected, apply `substituteRatio` to quantity; show substitute name in step inline text.
- **StepIngredientRef quantities**: always derived from base value × ratio — never store pre-scaled values.
- **Version tracking**: every editor save must increment `version` and append to `changeLog`.
- **Variations (F10)**: a variation is a full recipe with `parentRecipeId` set. List queries filter `parentRecipeId IS NULL`. Create via `RecipeRepository.duplicateAsVariant()` — **regenerate every id and remap all references** (sections, ingredients, steps, step refs, `scaleIngredientId`, `substituteGroupId`); never reuse the source's ids. Base label is the fixed string "Original"; variation names cap at `MAX_VARIANT_NAME_LEN` (20). Deleting a base must cascade-delete its variations. Received recipes are forced standalone (`parentRecipeId = null`).
- **Ingredient checklist order (detail)**: group by section (in `recipe.sections` order) then by `groupLabel`; the precompute for jump-chip indices and the render loop both consume the same `ingredientBlocks` list — keep them in sync if you change the layout.
- **Cooking mode**: scale step ingredient amounts with `QuantityScaler.scale(ingredient, scaleFactor, selectedUnit)` (unit-aware), not the legacy display-only overload; the unit toggle and start-section are passed in from `RecipeDetailScreen`. The step ingredient card is a **session-only checklist** (`mutableStateListOf` of ingredient ids held at the `RecipeDetailScreen` scope so it clears on recipe exit).
- **Shopping List (F11)**: `ShoppingAggregator.build()` merges ingredients by normalized name and **sums per-unit in the active `UnitMode`** — never store these totals, recompute from base × `scaleFactor`. The `itemKey` (normalized name) keys both the merge and the persisted `shopping_checks`. `shoppingNote` lives on the ingredient (travels with the recipe); it's edited **only in the editor**, never in the import/freeform review flow. Checks are local — never include `shopping_checks` in sync/share.

### Author Attribution
- `authorId` + `authorDisplayName` are stamped at creation time (freeform, import, editor fork of seeded recipe).
- `isImported` controls two things: (1) which author name appears when shared, (2) which filter chip it appears under in My Recipes.
  - `isImported = false` → `authorDisplayName` (real user name) shown on shared recipe; appears under "Personal" chip
  - `isImported = true` → always "Imported" shown (override applied in `SharedRecipeService.buildDocument()`); appears under "Imported" chip
- `authorId` is always the real UID regardless of `isImported` — required for Firestore security rules.
- The editor **author dropdown** changes both `isImported` and `authorDisplayName` on save. `authorId` is never changed.
- `isOwner` in `RecipeDetailViewModel`: `true` when `authorId == currentUid` OR when `authorId == null` (pre-attribution recipes).

### Visibility & Sharing (3 tiers — F12)
- `visibility` ∈ `private` / `friends` (Co-Chefs only) / `public`. `isPublished = visibility != "private"`.
- `setVisibility(tier)` → Room update + (`friends`/`public` → publish mirror with that tier + start comments) / (`private` → unpublish + stop comments). `SharedRecipeService.buildDocument` writes the real `visibility` (NOT hardcoded "public").
- Visibility chip → 3-option chooser dialog. **Share-link** flow is separate from the chip and still requires Public (`showMakePublicForLink`).
- **Direct co-chef share** of a private recipe publishes at `"friends"` via `ensureSharableVisibility()` (don't downgrade an already-Public recipe).
- Firestore `shared_recipes` read rule gates `friends` recipes on an accepted mutual-follow doc. **Composite index `shared_recipes (authorId, visibility)`** required for the profile query.
- `SharedRecipeService.buildDocument()` applies `authorDisplayName = "Imported"` when `recipe.isImported == true`.
- Comments are Firestore-only; never stored in Room. Shown when `isPublished` (friends or public).

### Co-Chef Profiles (F12)
- `ProfileScreen`/`ProfileViewModel` ← `SocialRepository.getAuthorRecipes(authorUid, includeFriendsOnly)`. Entry: tap a row in `FriendsScreen`.
- Profile recipe → review via `ReceivedRecipeScreen(ReviewSource.Direct(recipeId, authorUid, authorName))`; **"Add to Shared tab"** saves a received reference (Tab 2). The inbox uses `ReviewSource.Pointer(shareId)`. Both share one screen/VM.

### Import / Review Flow
- Recipes are saved to Room **immediately** with `needsReview = true` before the review sheet opens — data is never lost.
- `confirmRecipe()` calls both `confirmImportedRecipe()` (clears needsReview) and `updateIsImported(!isOwnRecipe)`.
- `dismissReview()` clears only UI state — Room record untouched.
- `reimportUrl()` / `reimportFromFile()` delete-and-reinsert same `recipeId` so the list card updates in place.
- `openReviewForRecipe(recipeId)` reloads from Room via `getRecipeWithDetails` → `toParsedRecipeData()`.
- `isOwnRecipe` in `ImportUiState` defaults to `false` (Imported); resets to `!recipe.isImported` when re-opening a pending recipe.

### Navigation
- **Auth gate**: `AmrosaNavGraph` collects `authStateFlow()`. `isSignedIn = currentUser?.isAnonymous == false`. When false → renders `AuthScreen()` (root, no back). When true → renders `MainAppScaffold()`.
- **MainAppScaffold** is a separate private composable with its own `rememberNavController()`. Created fresh on each sign-in.
- Tab routes: `"yours_tab"`, `"shared_tab"`, `"discover_tab"`, `"account_tab"` (All tab removed)
- Push routes: `"recipe/{recipeId}?startEdit={bool}"` (inline edit, no separate edit route), `"shared/{recipeId}"`, `"freeform"`, `"import?reviewId={reviewId}"`, `"profile/{uid}?name="`, `"profileRecipe/..."`, `"shopping/..."`, `"user_search"`, `"friends"`, `"received/{shareId}"` (the `"notifications"` route is removed — push is OS-level via FCM)
- The `"auth"` route is **removed** from the nav graph — auth is handled at the outer `AmrosaNavGraph` level.
- Import route uses optional query param `reviewId`; default empty string, treated as null in screen.
- `showBottomBar` is true only when `currentDestination?.route` is one of the 4 tab routes.
- Deep link `amrosa://shared/{recipeId}` is registered on the `"shared/{recipeId}"` composable via `navDeepLink`.
- `"received/{shareId}"` is a **review screen**: loads via `SocialRepository.getReceivedRecipe()` (returns `ReceivedRecipeData`); "Save Recipe" → Room copy → `popBackStack()` to Shared tab. `onSaved` is `() -> Unit`.
- `"shared_tab"` (`SharedInboxScreen`) shows received recipes from `shared_to/{uid}/recipes/` as recipe cards — NOT a community browse. Same feed surfaced by the My Recipes "Shared" filter chip.
- The My Recipes tab (`"yours_tab"`) also passes `onSharedRecipeClick = { navigate("received/$shareId") }` for its Shared chip.
- `"discover_tab"` is a placeholder screen — no navigation to sub-routes yet.

### Auth Patterns
- Anonymous auth removed. Do not call `signInAnonymouslyIfNeeded()` — it exists in `AuthRepository` for compat but is never called.
- Sign-out flow: always call `container.clearAllLocalData(context)` before `authRepository.signOut()`.
- Push sync: only runs for `!authRepository.isAnonymous` users.
- On sign-in: `AmrosaApplication.authStateFlow` observer automatically triggers seed + sync.

### Social / Co-Chefs System (F9)
- **`SocialRepository`** in `data/remote/SocialRepository.kt` — all Firestore ops for users, follows, notifications, shared_to.
- **`UserProfile`** domain model — uid, displayName, email?, photoUrl?, createdAt.
- **`SocialNotification` model is deleted** — in-app notification screen removed. Notifications write to Firestore to trigger FCM push only.
- **`upsertProfile()`** writes `displayName`, `photoUrl`, `email`, `fcmToken` (partial via `updateFcmToken`), and `updatedAt` to `users/{uid}` on every real sign-in.
- **Co-chef doc ID** = `{followerId}_{followeeId}`. Composite Firestore indexes needed:
  - `follows (followeeId ASC, status ASC)` — for pending requests query
  - `follows (followerId ASC, status ASC)` — for co-chef list query
- **`acceptFollowRequest(fromUid)`** — batch-commits: (1) updates existing `follows/{fromUid_uid}` to accepted; (2) creates reverse `follows/{uid_fromUid}` as accepted. Both sides now see count = 1.
- **`unfriend(targetUid)`** — batch-deletes both `follows/{uid_target}` and `follows/{target_uid}`. No-op for non-existent doc. Also handles cancelling pending outgoing requests.
- **`getFriendsFlow()`** — queries `followerId == uid AND status == accepted`. Returns correct friend list because acceptance creates both direction docs.
- **Notification delivery** = write to `notifications/{toUid}/items/{uuid}`. Client-side. The `sendPushNotification` Cloud Function trigger reads this and sends FCM push.
- **FCM token** — `AmrosaMessagingService.onNewToken()` stores token in `users/{uid}.fcmToken`. `refreshFcmToken()` in `AmrosaApplication` fetches current token on each sign-in. `updateFcmToken(token)` in `SocialRepository` writes the field.
- **No in-app notification screen** — `NotificationsScreen`, `NotificationsViewModel`, `getNotificationsFlow()`, `getUnreadCountFlow()`, `markNotificationRead()`, `markAllNotificationsRead()` are all deleted.
- **Direct share** stores full recipe JSON in `shared_to/{recipientUid}/recipes/{shareId}` + delivers `recipe_shared` notification → FCM push. `buildSharedToDocument()` writes BOTH `fromDisplayName` (sender) and `authorDisplayName` (original recipe author) so the recipient sees the correct author.
- **`getReceivedRecipe(shareId)`** returns `ReceivedRecipeData(recipe, fromDisplayName)`. `parseSharedRecipe()` reads `authorDisplayName` for the recipe's author (falls back to `fromDisplayName` for old docs missing it).
- **`getReceivedRecipesSummaryFlow()`** returns `ReceivedRecipeSummary` with `authorDisplayName`, `fromDisplayName`, prep/cook times, and tags — enough to render full recipe cards.
- **`ReceivedRecipeViewModel.saveToMyRecipes()`** creates a fresh Room copy (new UUIDs) with `authorId = currentUid`, `isImported = false`, `visibility = "private"`. After save → `popBackStack()` to Shared tab (the shared card stays in the feed). This is the review-screen pattern, mirroring imports.
- **Pending co-chef requests** shown inline in AccountScreen as cards with Accept/Decline buttons; text "wants to be co-chefs".
- **User search** — `searchUsers(query)`: if `query.contains('@')` → exact `whereEqualTo("email", ...)` lookup; else displayName prefix range query with `` sentinel. Results show name + email. Buttons: "Add Co-Chef" / "Requested" / "Co-Chef ✓".
- **Edit display name**: `AccountViewModel.updateDisplayName(name)` calls `authRepository.updateDisplayName(name)` then `socialRepository.upsertProfile()`. Snackbar "Name updated".
- **Merged share button**: single Share icon → `ShareOptionsSheet` with "Send to co-chef" and "Share link".
- **Shared tab (Tab 2, `SharedInboxScreen`)** = inbox of received recipes as full recipe cards. Load from `shared_to/{uid}/recipes/` — NOT `shared_recipes` community collection. The same feed is reachable via the My Recipes "Shared" filter chip (`HomeViewModel` loads it via `getReceivedRecipesSummaryFlow()` only when `filter == YOURS`).
- **`HomeViewModel`** — `YourRecipesFilter` enum now `{ ALL, PERSONAL, IMPORTED, SHARED }`. `filteredRecipes` returns empty in SHARED mode; `filteredSharedRecipes` returns the shared feed (search-filtered) only in SHARED mode. Switching chip resets `selectedCategory`.
- **Delete recipe**: `RecipeDetailViewModel.deleteRecipe()` (inline edit mode) cascades variations, calls `repository.deleteFullRecipe`, removes the cloud copy + unpublishes mirror, sets `deleteComplete = true` → screen pops back.

### AppContainer
- `clearAllLocalData(context: Context)` — `database.clearAllTables()` + clears `amrosa_sync` prefs. Called on sign-out.
- The `database` field is `private` — access it only through `clearAllLocalData()` or the exposed DAOs via `repository`.

### RecipeChange
```kotlin
data class RecipeChange(val version: Int, val timestamp: Long, val summary: String)
```
Appended to `changeLog` on every editor save. Summary auto-generated from changed fields (e.g. "Updated: title, author, recipe content").

---

## iOS Implementation

> iOS app is a feature-for-feature port of the Android app. Swift 5, iOS 17+, SwiftUI, SwiftData, MVVM + @Observable.

### iOS Tech Stack

| Layer | Technology |
|---|---|
| UI | SwiftUI + NavigationStack |
| Local DB | SwiftData (@Model) |
| Cloud DB | Firebase Firestore (iOS SDK 11.x) |
| Auth | FirebaseAuth + GoogleSignIn-iOS 8.x |
| AI / Import | Firebase Cloud Functions (same backend as Android) |
| Image Loading | Kingfisher 8.x |
| DI | Manual (AppContainer — @Observable @MainActor) |
| State Management | @Observable + @State (no ViewModel protocol) |
| Project generation | xcodegen 2.45.4 (`project.yml` in `ios/Amrosa/`) |
| Bundle ID | `com.aerion.amrosa` |
| Firebase project | amrosa-2ec82 |

### iOS File Structure

```
ios/Amrosa/
├── project.yml                         # xcodegen spec (run: xcodegen generate from ios/Amrosa/)
├── Amrosa.xcodeproj/                   # generated — do not edit manually
└── Amrosa/
    ├── AmrosaApp.swift                 # @main, Firebase.configure(), AppContainer init
    ├── AppContainer.swift              # DI: ModelContainer + all repos/services + clearAllLocalData()
    ├── Data/
    │   ├── DTOs/
    │   │   ├── ParsedRecipeData.swift  # ParsedRecipeData, ParsedSection, ParsedIngredient, ParsedStep, ParsedStepRef
    │   │   ├── RecipeChange.swift      # RecipeChange(version:timestamp:summary:)
    │   │   └── SharedRecipe.swift      # SharedRecipe, SharedIngredient, SharedStep, SharedComment (Firestore-only)
    │   ├── Models/                     # SwiftData @Model classes (6 models)
    │   │   ├── RecipeModel.swift       # Full field parity with Android RecipeEntity incl. visibility
    │   │   ├── RecipeSectionModel.swift
    │   │   ├── IngredientModel.swift   # Incl. all F6 metric/imperial fields
    │   │   ├── StepModel.swift
    │   │   ├── StepIngredientRefModel.swift
    │   │   └── RecipeNoteModel.swift
    │   ├── Repositories/
    │   │   ├── RecipeRepository.swift  # Full CRUD: fetchAll/Yours/Personal, insertFullRecipe,
    │   │   │                           #   insertFullRecipeFromParsed, updateFullRecipe (delta),
    │   │   │                           #   upsertFromFirestore, confirmReview, updateIsImported,
    │   │   │                           #   updateVisibility, deleteAllRecipes, notes CRUD
    │   │   └── AuthRepository.swift    # anonymous/Google/email/phone; link-or-sign-in pattern; authStateStream()
    │   └── Services/
    │       ├── CloudFunctionsService.swift  # parseRecipeUrl, parseRecipeContent, formatRecipeText
    │       ├── RecipeSyncService.swift      # pullSeededRecipes, pullPersonalRecipes, pushPersonalRecipe,
    │       │                                #   pushAllPersonalRecipes, sync()
    │       └── SharedRecipeService.swift    # publish/unpublish, sharedRecipesStream, getSharedRecipeDetail,
    │                                        #   commentsStream, addComment, deleteComment, copyToMyRecipes
    ├── UI/
    │   ├── ContentView.swift           # Auth gate: shows AuthView (root) or MainAppView (4 tabs); handles deep links
    │   ├── AllRecipes/                 # AllRecipesView + AllRecipesViewModel
    │   ├── YourRecipes/                # YourRecipesView + YourRecipesViewModel (needsReview banner)
    │   ├── Shared/                     # SharedRecipesView + SharedRecipesViewModel (live Firestore stream)
    │   │                               #   SharedRecipeDetailView + SharedRecipeDetailViewModel (visitor view)
    │   ├── Account/                    # AccountView + AccountViewModel (profile, stats, sign-out + data wipe)
    │   ├── Auth/                       # AuthView + AuthViewModel (Google/email/phone + sign-up)
    │   │                               #   AuthView works as root wall (no NavigationStack needed)
    │   ├── RecipeDetail/               # RecipeDetailView + RecipeDetailViewModel
    │   │                               #   Includes: share button, visibility chip, comments section
    │   ├── RecipeEditor/               # RecipeEditorView + RecipeEditorViewModel
    │   │                               #   EditorSection/EditorIngredient/EditorStep structs
    │   ├── Import/                     # ImportView + ImportViewModel + RecipeReviewSheet
    │   │                               #   FreeformEntryView + FreeformEntryViewModel
    │   ├── CookingMode/               # CookingModeView (fullscreen step-by-step)
    │   └── Components/                # RecipeCard
    └── Util/
        ├── QuantityScaler.swift        # UnitMode enum + scale() functions + fraction formatting
        └── Extensions.swift            # String.trimmed, Date.relativeString, Int.timeDisplayString
```

### iOS Implementation Status

#### ✅ Implemented

| Feature | Notes |
|---|---|
| **SwiftData schema** | 6 @Model classes, full field parity with Android. Cascade delete rules. |
| **AppContainer DI** | @Observable @MainActor. ModelContainer, RecipeRepository, AuthRepository, CloudFunctionsService, RecipeSyncService, SharedRecipeService. `clearAllLocalData()` wipes SwiftData + sync prefs. |
| **Mandatory auth gate** | `ContentView` observes `authStateStream()`. Shows `AuthView()` (root wall, no TabBar) when not signed in. Shows `MainAppView` (4 tabs) when real user is signed in. |
| **Sign-in triggers sync** | Auth state observer in `ContentView.task` calls `container.onSignIn()` whenever a real user sign-in is detected. |
| **Sign-out clears local data** | `AccountViewModel.signOut()` calls `container.clearAllLocalData()` before `auth.signOut()`. Warning dialog shown first. |
| **Firebase init ordering** | `FirebaseApp.configure()` in App.init() before `AppContainer` creation (avoids crash) |
| **GoogleService-Info.plist** | Declared in project.yml `resources:` block so xcodegen bundles it |
| **4-tab navigation** | All · Your Recipes · Shared · Account |
| **Recipe list (All)** | Search + filter, recipe cards |
| **Your Recipes tab** | needsReview sorted first with banner; FAB → Import or Freeform sheet |
| **Recipe detail** | Yield adjuster (servings + anchor-based), unit toggle, section jump chips, ingredient checklist, substitute selector, optional toggle, step-ingredient refs, notes, cooking mode, fork/edit |
| **Anchor-based scaling** | `scaleAnchorQty` tracks current anchor amount; `adjustScale(delta:)`, `resetScale()` (long-press), `yieldDisplay` |
| **Unit conversions (F6)** | Original/Metric/Imperial toggle; only shown when conversion data exists |
| **Cooking mode** | Fullscreen step-by-step (CookingModeView) |
| **Recipe editor** | Full CRUD: metadata, sections, ingredients, steps; delta-save via `updateFullRecipe`; fork dialog; push to Firestore |
| **Recipe import (F3)** | URL import, file import (.txt/.csv), `RecipeReviewSheet` with Confirm/Reimport/Edit, author toggle, needsReview pending flow |
| **Freeform entry (F5)** | FreeformEntryScreen → formatRecipeText CF → RecipeReviewSheet (Save/Reformat/Edit) |
| **Notes** | Per-recipe timestamped notes; add/edit/delete |
| **Auth (F7 core)** | Google Sign-In, email/password (sign-in + sign-up), phone OTP; link-or-sign-in upgrades anonymous session |
| **Account screen** | Profile card, sign-out + data wipe dialog, recipe count, last sync date |
| **Cloud sync pull** | `pullSeededRecipes` (delta by updatedAt), `pullPersonalRecipes(uid:)` |
| **Cloud sync push** | `pushPersonalRecipe` — full payload: sections, ingredients (incl. F6 fields), steps; `pushAllPersonalRecipes()` |
| **Firestore deserialization** | `upsertFromFirestore` + `firestoreToParsedRecipeData` — full sections/ingredients/steps/refs |
| **isOwner flag** | `RecipeDetailViewModel.isOwner` — compares recipe.authorId to auth.uid |
| **Visibility field** | `RecipeModel.visibility: String` (default "private"); `updateVisibility()` in repository |
| **F8 — Visibility chip** | Owners see Private/Public toggle chip in detail body; confirms before toggling |
| **F8 — Share button** | Top bar icon (owners only); if public → iOS share sheet with `https://amrosa-2ec82.web.app/shared/{id}`; if private → dialog → publish → share sheet |
| **F8 — SharedRecipeService** | `publish/unpublish`, live `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **F8 — Shared tab** | `SharedRecipesView` + `SharedRecipesViewModel`; live Firestore stream; search; "Yours" badge; taps route to `SharedRecipeDetailView` |
| **F8 — Shared detail** | `SharedRecipeDetailView`; read-only detail: yield adjuster, unit toggle, ingredients, steps; "Copy to My Recipes" button; comments section |
| **F8 — Comments** | `SharedComment` model; post/delete in both owner view and visitor view; delete allowed for commenter OR recipe owner |
| **Deep links** | `amrosa://` custom URL scheme registered in project.yml; `https://amrosa-2ec82.web.app/shared/{id}` handled via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **Version control** | `version` Int + `changeLog` JSON on every editor save |

#### 🔧 Remaining Gaps (vs Android)

> Full, detailed list is in **"iOS 🔶 Remaining Gaps (planned)"** earlier in this file — that table is authoritative. Recent Android-only additions to port are summarized here.

| Gap | Detail |
|---|---|
| **F12 — Visibility tiers + Co-Chef profiles** | `"friends"` tier in publish; 3-option visibility chooser; co-chef/public `ProfileView`. See the authoritative gaps table above. |
| **F15 — Ingredient quantity ranges** | Add `quantityValueMax` (+ `…Metric`/`…Imperial`, all `Double?`) to the SwiftData ingredient model; scaler renders "min–max unit" scaling both ends; thread through push/pull + parsed-import maps; editor sets the range max. Backend already returns the maxes — no client conversion math. |
| **Collective step-ingredient cooking-mode fallback** | Mirror `augmentedStepRefs()`: attach any ingredient referenced by no step in its section to that section's first step, so collectively-referenced ingredients show in cooking mode. Import-side server net is shared backend. |
| **Universal Links** | ✅ Wired (entitlement + AASA file). Pending `firebase deploy --only hosting` to publish the AASA. |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |

### iOS Notes for AI Coding Assistants

- **Swift version**: 5 (not 6). Strict concurrency is OFF — do not add `Sendable` or `actor` isolation unless explicitly needed.
- **@MainActor everywhere**: All ViewModels and Repositories are `@MainActor`. No `DispatchQueue.main.async` needed.
- **SwiftData is source of truth**: UI never reads Firestore directly (except `SharedRecipeDetailView` which reads from `SharedRecipeService` since shared recipes are Firestore-only).
- **xcodegen**: After adding/removing Swift files, run `xcodegen generate` from `ios/Amrosa/` to regenerate the Xcode project. Never edit `.xcodeproj` manually.
- **Auth gate**: `ContentView` uses `authStateStream()` AsyncStream. `isSignedIn = user != nil && !user.isAnonymous`. When false → `AuthView()` is root (no NavigationStack wrapper needed — just a plain view). When true → `MainAppView` (TabView).
- **Sign-out**: Always call `container.clearAllLocalData()` before `authRepository.signOut()`. The auth state stream in ContentView will automatically switch back to the auth gate.
- **SharedRecipe types**: `SharedRecipe`, `SharedSection`, `SharedIngredient`, `SharedStep`, `SharedComment` are Firestore-only value types in `Data/DTOs/SharedRecipe.swift`. Never stored in SwiftData.
- **Deep links**: `onOpenURL` in ContentView handles both `amrosa://shared/{id}` and `https://amrosa-2ec82.web.app/shared/{id}`. Sets `deepLinkRecipeId` binding which `SharedRecipesView` uses to navigate to `SharedRecipeDetailView`.
- **Anchor scaling math**: `scaleFactor = scaleAnchorQty / baseAnchorQty`. `adjustScale(delta: Int)` adds `delta × scaleStep` to `scaleAnchorQty` (min = 1 step). Long-press yield → `resetScale()`.
- **EditorSection/EditorIngredient/EditorStep**: Defined in `RecipeEditorViewModel.swift`. Used by both `RecipeEditorViewModel` and `RecipeRepository.updateFullRecipe`.
- **Firebase init crash prevention**: `@State private var appContainer: AppContainer` (type annotation only); initialize in `App.init()` body after `FirebaseApp.configure()` using `_appContainer = State(initialValue: AppContainer())`.
- **needsReview on iOS**: Same flow as Android — save immediately with `needsReview = true`, confirm later. `confirmReview(recipeId:)` clears flag.
- **Timestamp cross-platform sync**: iOS must write timestamps as `Int64` milliseconds to be compatible with Android's `Long` fields. Use `Int64(date.timeIntervalSince1970 * 1000)` when pushing to Firestore.
