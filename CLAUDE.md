# Chef's Journal — Project Specification
### by Aerion

> **Chef's Journal** is the app name. The launch screen shows the **Chef's Journal** wordmark. The guiding identity of the app: recipes that are exquisite, elaborate, and deeply personal. (Renamed "Amrosa" → "Chef's List" → "Tablefeed" → **Chef's Journal**; fully rebranded to the `com.aerion.chefsjournal` package + the `chef-s-journal-6a0fd` Firebase project. Identifiers use `ChefsJournal`/`chefsjournal` — no apostrophe.)

> ⚙️ **Split-platform workflow (since 2026-06):** New features are implemented on **Android first, in this session**. The **iOS port is handled by a separate Claude session** on a Mac. This CLAUDE.md is the **hand-off contract** between the two: every Android feature must be documented here (schema, data flow, UI, Firestore shape) so the iOS session can port it, and Android-ahead features are tracked under the iOS "Remaining Gaps" tables. Shared infrastructure (Firestore rules, Cloud Functions, indexes) is owned by the Android session and benefits both.

---

## Project Overview

| Field | Detail |
|---|---|
| **App Name** | Chef's Journal |
| **Company** | Aerion |
| **Platform** | Android (primary, Kotlin); iOS (Swift/SwiftUI/SwiftData — in progress, separate codebase) |
| **Android Language** | Kotlin |
| **iOS Language** | Swift |
| **Min SDK (Android)** | API 26 (Android 8.0+) |
| **Min OS (iOS)** | iOS 17+ (SwiftData) |
| **Architecture** | MVVM + Repository Pattern (both platforms) |
| **Database (local — Android)** | Room (SQLite) — **current: DB v13** (real migrations preserve data; seeder is a no-op) |
| **Database (local — iOS)** | SwiftData (ModelContainer, no manual migrations) |
| **Database (cloud)** | Firebase Firestore (`chef-s-journal-6a0fd`) |
| **Cloud Storage** | Firebase Storage (planned — images) |
| **Auth** | Firebase Authentication — **mandatory** (Google Sign-In + email/password + phone OTP) |
| **AI / Recipe Parsing** | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |

---

## Monorepo Structure

```
ChefsJournal/
├── android/          # Android app (Kotlin + Jetpack Compose) — primary platform
├── ios/              # iOS app (Swift + SwiftUI + SwiftData) — in progress
│   └── ChefsJournal/ChefsJournal/
│       ├── ChefsJournalApp.swift         # Entry point; Firebase init; AppContainer setup
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
│   │   ├── recipeSchema.js    # ChefsJournal JSON schema for Gemini prompt
│   │   └── package.json       # firebase-functions, axios, @google/generative-ai, xlsx
│   └── firestore/
│       └── upload-recipes.js  # Node.js admin upload script (reference only)
├── hosting/          # Firebase Hosting (deployed to chef-s-journal-6a0fd.web.app)
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
- **Auth is mandatory.** `ChefsJournalNavGraph` observes `authStateFlow()`. When `currentUser == null` or anonymous, the full-screen `AuthScreen` is shown (no back button, no bottom bar). The main `Scaffold` + `NavHost` don't render until sign-in.
- **Sign-out deletes all local data.** `AccountViewModel.signOut()` calls `container.clearAllLocalData(context)` (Room `clearAllTables()` + sync prefs cleared) before `authRepository.signOut()`. Auth state change recomposes the nav graph back to the auth gate automatically.
- **Sign-in triggers sync.** `ChefsJournalApplication` observes `authStateFlow()` and calls `syncService.syncPersonalRecipes()` + `syncService.syncReceivedRecipes()` whenever a real (non-anonymous) user is detected. (There is **no seeding** — fresh installs start empty; the old `DatabaseSeeder`/`recipes` collection was removed in the rebrand.)
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

**Notification deep links:** tapping a push routes to the relevant screen — `recipe_shared` → `received/{shareId}` review; `follow_request`/`follow_accepted` → Account tab. `MainActivity` reads the FCM `type`/`shareId` extras (foreground via PendingIntent, background via launch intent) and hands a route to the nav graph through `ChefsJournalApplication.pendingDeepLink`.

---


## Core Features & Screen Map → see `docs/`

The full per-feature specs (F1–F13: storage, sync, import, editing, freeform, conversions, auth,
visibility/sharing, co-chefs, variations, shopping list, tiers, Discover) and the complete screen map
live in separate files to keep this contract small:

- **`docs/FEATURES.md`** — F1–F13 detailed spec. Read before changing a feature.
- **`docs/SCREEN-MAP.md`** — every screen + navigation route.
- **`docs/CHANGELOG.md`** — the shipped-work log (what was built, per platform).
- **`docs/IOS.md`** — iOS status, file structure, conventions, and remaining-gaps tables.

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
| Background Sync | On-launch coroutine (`ChefsJournalApplication`) |
| Recipe AI | Gemini 2.5 Flash via Firebase Cloud Functions v2 (Node.js 24) |
| Image Loading | Coil |
| DI | Manual (`AppContainer` in `ChefsJournalApplication`) |
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
| Hosting | Firebase Hosting (`chef-s-journal-6a0fd.web.app`) |

> **AI note:** This project uses **Gemini (Google AI)** — NOT the Anthropic Claude API. All AI calls go through the `GEMINI_API_KEY` Firebase secret. Do not add Anthropic dependencies.

---

## Current Status


### Done ✅

The full shipped-work log moved to **`docs/CHANGELOG.md`** (it was the single largest block in this file).
Consult it for how/why a feature was implemented; add new shipped entries there, not here.

### Planned — In Priority Order

| # | Feature | Description |
|---|---|---|
| — | Recipe Images | Firebase Storage integration; image picker on editor; Coil (Android) / AsyncImage (iOS) display. **Both platforms.** Largest remaining feature. |
| — | Discover tab | **Fully built on Android (F13):** feed · popularity · search · chef/public profiles · cuisine prefs · pull-to-refresh · default tab. (iOS port pending in the other session.) Future ideas if wanted: ratings, dietary filters, seasonal boosts. |
| — | Gemini brand/substitute suggestions | The deferred half of F11 — AI-suggested top brands + substitutes per ingredient (author notes already ship). |
| — | iOS: Universal Links | ✅ Wired — AASA file + `Associated Domains` entitlement added. Needs `firebase deploy --only hosting` to publish the AASA. |
| — | Shopping list — cross-recipe / standalone | Optional: combine multiple recipes into one shopping trip (current F11 is per-recipe). |
| — | **QOL — Freeform entry: reachable "Format with Gemini"** | On the freeform/paste screen the action button sits **below** the text area, so pasting a long recipe forces a long scroll to reach it. Fix: **pin the action** so it's always reachable without scrolling — e.g. a sticky bottom bar (or `Scaffold` bottomBar / floating action) holding "Format with Gemini", with the text field scrolling under it. Must not fight the keyboard (keep `imePadding()`), and shouldn't cover the last lines while typing. Applies to the **import-by-text** path specifically; users who *are* editing should still be able to scroll the full text freely. **Both platforms** (iOS has the same layout). |

**Tech debt / smaller follow-ups:**
- Verify the iOS sync clean-replace (`replaceSyncedContent`) compiles + behaves in Xcode (written but not built on the Android dev box).
- iOS fresh-pull doesn't set `scaleIngredientId`/`scaleStep` on `insertFullRecipeFromParsed` → anchor-scaling config lost on first multi-device pull. Small, isolated.
- Editor's post-save cloud push runs in `viewModelScope` and can be cancelled mid-pop; launch-time `pushAllPersonalRecipes()` self-heals. Could move to an app-scope coroutine.

---


## iOS Platform Status → see `docs/IOS.md`

iOS implementation status, file structure, iOS-specific conventions, and the authoritative
**Remaining Gaps** tables now live in **`docs/IOS.md`**. The iOS session should read that file in full;
the Android session must record every new Android feature there (or in `docs/CHANGELOG.md`) so iOS can port it.

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
- **Auth gate**: `ChefsJournalNavGraph` collects `authStateFlow()`. `isSignedIn = currentUser?.isAnonymous == false`. When false → renders `AuthScreen()` (root, no back). When true → renders `MainAppScaffold()`.
- **MainAppScaffold** is a separate private composable with its own `rememberNavController()`. Created fresh on each sign-in.
- Tab routes: `"yours_tab"`, `"shared_tab"`, `"discover_tab"`, `"account_tab"` (All tab removed)
- Push routes: `"recipe/{recipeId}?startEdit={bool}"` (inline edit, no separate edit route), `"shared/{recipeId}"`, `"freeform"`, `"import?reviewId={reviewId}"`, `"profile/{uid}?name="`, `"profileRecipe/..."`, `"shopping/..."`, `"user_search"`, `"friends"`, `"received/{shareId}"` (the `"notifications"` route is removed — push is OS-level via FCM)
- The `"auth"` route is **removed** from the nav graph — auth is handled at the outer `ChefsJournalNavGraph` level.
- Import route uses optional query param `reviewId`; default empty string, treated as null in screen.
- `showBottomBar` is true only when `currentDestination?.route` is one of the 4 tab routes.
- Deep link `chefsjournal://shared/{recipeId}` is registered on the `"shared/{recipeId}"` composable via `navDeepLink`.
- `"received/{shareId}"` is a **review screen**: loads via `SocialRepository.getReceivedRecipe()` (returns `ReceivedRecipeData`); "Save Recipe" → Room copy → `popBackStack()` to Shared tab. `onSaved` is `() -> Unit`.
- `"shared_tab"` (`SharedInboxScreen`) shows received recipes from `shared_to/{uid}/recipes/` as recipe cards — NOT a community browse. Same feed surfaced by the My Recipes "Shared" filter chip.
- The My Recipes tab (`"yours_tab"`) also passes `onSharedRecipeClick = { navigate("received/$shareId") }` for its Shared chip.
- `"discover_tab"` is a placeholder screen — no navigation to sub-routes yet.

### Auth Patterns
- Anonymous auth removed. Do not call `signInAnonymouslyIfNeeded()` — it exists in `AuthRepository` for compat but is never called.
- Sign-out flow: always call `container.clearAllLocalData(context)` before `authRepository.signOut()`.
- Push sync: only runs for `!authRepository.isAnonymous` users.
- On sign-in: `ChefsJournalApplication.authStateFlow` observer automatically triggers seed + sync.

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
- **FCM token** — `ChefsJournalMessagingService.onNewToken()` stores token in `users/{uid}.fcmToken`. `refreshFcmToken()` in `ChefsJournalApplication` fetches current token on each sign-in. `updateFcmToken(token)` in `SocialRepository` writes the field.
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
- `clearAllLocalData(context: Context)` — `database.clearAllTables()` + clears `chefsjournal_sync` prefs. Called on sign-out.
- The `database` field is `private` — access it only through `clearAllLocalData()` or the exposed DAOs via `repository`.

### RecipeChange
```kotlin
data class RecipeChange(val version: Int, val timestamp: Long, val summary: String)
```
Appended to `changeLog` on every editor save. Summary auto-generated from changed fields (e.g. "Updated: title, author, recipe content").

---

