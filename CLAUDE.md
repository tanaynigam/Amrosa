# Amrosa — Project Specification
### by Aerion

> **Amrosa** is the app name. Upon launch, the user is greeted with the heading **"Amrita & Ambrosia"** — a fusion of the Sanskrit and Greek words for divine, immortal sustenance. This is the guiding identity of the app: recipes that are exquisite, elaborate, and deeply personal.

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
| **Database (local — Android)** | Room (SQLite) — **current: DB v9, seeder key `seeded_v11`** |
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
│           ├── Shared/             # SharedRecipesView + SharedRecipesViewModel (live Firestore stream)
│           │                       # SharedRecipeDetailView + SharedRecipeDetailViewModel (visitor view)
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
Auth Gate   (🔐)  Full-screen login wall — shown when not signed in; no back button
Tab 1 — All           (📖)  Browse all recipes (personal + imported + shared copies)
Tab 2 — Your Recipes  (🔖)  All your recipes merged (personal + imported); Add Recipe FAB
Tab 3 — Shared        (🌐)  Community shared recipes; browse, copy, share your own
Tab 4 — Account       (👤)  Profile, follow system, notifications, sync, sign-out
```

**Design decisions:**
- **Auth is mandatory.** `AmrosaNavGraph` observes `authStateFlow()`. When `currentUser == null` or anonymous, the full-screen `AuthScreen` is shown (no back button, no bottom bar). The main `Scaffold` + `NavHost` don't render until sign-in.
- **Sign-out deletes all local data.** `AccountViewModel.signOut()` calls `container.clearAllLocalData(context)` (Room `clearAllTables()` + sync prefs cleared) before `authRepository.signOut()`. Auth state change recomposes the nav graph back to the auth gate automatically.
- **Sign-in triggers seed + sync.** `AmrosaApplication` observes `authStateFlow()` and calls `seeder.seedIfNeeded()` + `syncService.sync()` + `syncService.syncPersonalRecipes()` whenever a real (non-anonymous) user is detected.
- The old "Personal" and "Imported" tabs are **merged** into one "Your Recipes" tab. Filter chips (`All | Personal | Imported`) on that tab provide in-tab filtering.
- `isImported` controls **author display when sharing** (`false` = real name, `true` = "Imported"), not which tab a recipe appears in.
- **Import** is a push route (`"import?reviewId=..."`) accessible from the Add Recipe FAB, not a tab.
- Pending-review recipes float to the top of Your Recipes with a "Needs review — tap to confirm" badge; tapping opens the import screen with the review sheet pre-loaded.
- **Tab 4 is "Account"** (route `"account_tab"`, composable `AccountScreen`) — contains both account management and social/follow features. Keeps the tab count at 4 and avoids a dedicated social tab.

---

## Core Features

### F1 — Recipe Storage

**Room DB v9** — all entities below are current.

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
    val isImported: Boolean = false,     // controls author display when shared (see Author Attribution)
    val needsReview: Boolean = false,    // true = imported but not yet confirmed by user
    val version: Int = 1,
    val changeLog: String = "[]",        // JSON List<RecipeChange>
    val createdAt: Long,
    val updatedAt: Long,
    val syncedAt: Long? = null,
    val authorId: String? = null,        // Firebase UID of creator; null for pre-auth recipes
    val authorDisplayName: String? = null, // real display name at creation time
    val visibility: String = "private"   // "private" | "public" (public = mirrored to shared_recipes)
)
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
    val quantityDisplayImperial: String? = null
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
| `shared_recipes/{recipeId}` | Community-shared recipes (visibility = "public") |
| `shared_recipes/{recipeId}/comments/{commentId}` | Comments on shared recipes |
| `users/{uid}` | Public user profile — displayName, photoUrl, updatedAt. Created/merged on each sign-in via `SocialRepository.upsertProfile()`. Used for user search. |
| `follows/{followerId}_{followeeId}` | Follow relationship. Fields: followerId, followerName, followeeId, followeeName, status ("pending"\|"accepted"), createdAt. Composite indexes required: (followeeId, status) and (followerId, status). |
| `notifications/{uid}/items/{notifId}` | Per-user notification inbox. Fields: type, fromUid, fromDisplayName, shareId?, recipeName?, createdAt, read. |
| `shared_to/{recipientUid}/recipes/{shareId}` | Recipes shared directly to a specific user. Full recipe data + fromUid + fromDisplayName + sharedAt. Only the sender can write; only the recipient can read. |

**Sync behaviour:**
- **Pull sync** on app launch (if signed in): fetches `personal_recipes/{uid}/recipes/` where `updatedAt > lastSyncTimestamp`, upserts into Room.
- **Push sync** on personal recipe save: `RecipeSyncService.pushPersonalRecipe(recipeId)` → `personal_recipes/{uid}/recipes/{recipeId}`. Fire-and-forget — push failure does not block local save.
- **Sync on sign-in**: `AmrosaApplication.authStateFlow` observer calls `syncService.sync()` + `syncPersonalRecipes()` whenever a real user is detected.

**Seeder:** `DatabaseSeeder.seedIfNeeded()` is a **complete no-op**. Fresh installs start blank. Bump seed key together with DB version if schema changes, but no seeding logic runs.

**DB versioning:** `fallbackToDestructiveMigration()`. Current: **DB v9, seeder key `seeded_v11`**. Always bump both together.

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

#### Import screen (push route — not a tab)

`ImportScreen` is a **push route** accessed from the Add Recipe FAB in Your Recipes. Route: `"import?reviewId={reviewId}"`. Optional `reviewId` param auto-opens the review sheet for a specific recipe.

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
7. Your Recipes tab shows pending recipes first with "Needs review" badge; tapping re-opens the review sheet

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

### F4 — Recipe Editor

Full inline editor. Entry points: pencil icon on detail screen; Edit button on review sheet.

- **Fork dialog** when editing a seeded or shared-copied recipe (becomes a personal copy)
- Editable fields: title, description, prep/cook times, yield (single or range), tags, source URLs, **author (dropdown)**, sections, ingredients (name, quantity display, unit, group label, optional flag), steps
- Every save increments `version`, appends `RecipeChange`, pushes to `personal_recipes/{uid}/recipes/`

#### Author dropdown (editor)

The author field is an **`ExposedDropdownMenuBox`** with two options:
- **Imported** — saves `isImported = true`, `authorDisplayName = "Imported"`. Default for any recipe opened from an URL/file import.
- **Personal — [User's Name]** — saves `isImported = false`, `authorDisplayName = authRepository.displayName ?: email`. Default for freeform-typed recipes.

Changing the dropdown on save also updates `isImported` in Room, keeping the Your Recipes filter chips in sync.

`RecipeEditorViewModel` exposes:
- `val personalAuthorName: String` — the signed-in user's display name (used for the "Personal" option label)
- `updateIsPersonalAuthor(Boolean)` — updates `EditorUiState.isPersonalAuthor`

**Preserved on save (not exposed in UI):** `originalAuthorId`, `originalVisibility`. When forking a seeded recipe (`authorId == null`), stamps the current user's UID as `authorId`.

---

### F5 — Freeform Recipe Entry ✅

FAB on Your Recipes tab → ModalBottomSheet → two options:
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

#### Gemini conversion constants
1 cup=240ml, 1 tbsp=15ml, 1 tsp=5ml, 1 fl oz=30ml, 1 oz=28.35g, 1 lb=453.6g, 1 kg=2.205lb. Non-convertible (counts, "to taste") → null.

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
Tab 4. Always shows a signed-in user (auth is mandatory). Profile card · "Sign Out" button · Sync stats · DB version · About.

#### Launch sequence (`AmrosaApplication.onCreate()`)
```kotlin
container = AppContainer(this)
appScope.launch {
    // Seed + sync whenever a real user is signed in (on start or after sign-in)
    container.authRepository.authStateFlow().collect { user ->
        if (user != null && !user.isAnonymous) {
            container.seeder.seedIfNeeded()       // no-op currently
            container.syncService.sync()
            container.syncService.syncPersonalRecipes()
        }
    }
}
```

#### Firebase config
Project: `amrosa-2ec82`. Web client ID in `res/values/strings.xml` as `google_web_client_id`. SHA-1 debug fingerprint registered in Firebase Console for Google Sign-In.

---

### F8 — Shared Recipes & Visibility ✅

#### Recipe visibility

Every recipe has `visibility: String = "private"` in Room.
- **`"private"`** (default) — visible only to the owner
- **`"public"`** — mirrored to `shared_recipes/{recipeId}` in Firestore

#### Share button (detail screen top bar)

The **Share icon** appears in the `RecipeDetailScreen` top bar for owners only (`state.isOwner == true`):

- **Recipe is already public** → tap Share → Android system share sheet opens immediately with link `amrosa://shared/{recipeId}` and recipe title as subject
- **Recipe is private** → tap Share → dialog: *"This recipe will be visible to anyone with the link. You can make it private again at any time."* → tap **Share** → `setVisibility("public")` runs; once `state.isPublic` becomes true, share sheet opens automatically via `LaunchedEffect(state.isPublic, pendingShareAfterPublish)`

Making private: the `FilterChip` (lock / globe icon) in the recipe body still toggles visibility for owners — it's the secondary way to make a public recipe private again.

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

**iOS Universal Links** — NOT yet implemented. Requires:
1. `apple-app-site-association` file at `https://amrosa-2ec82.web.app/.well-known/apple-app-site-association`
2. `Associated Domains` entitlement in Xcode project: `applinks:amrosa-2ec82.web.app`

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

#### Shared tab (Tab 3)

`SharedScreen` reads from Firestore `shared_recipes` live (callbackFlow). Shows all public recipes with search.

Card routing:
- Tap own recipe (`recipe.authorId == currentUid`) → `"recipe/$id"` (owner / Room-based detail)
- Tap others' recipe → `"shared/$id"` (visitor / Firestore-based detail)

Cards show a "Yours" badge for the owner's recipes.

#### `SharedRecipeDetailScreen` (visitor view — Firestore-based)

Route: `"shared/{recipeId}"`. Also handles deep links (`amrosa://shared/{recipeId}`). Reads from Firestore, not Room. Features:
- Yield adjuster, unit toggle (Original/Metric/Imperial)
- Read-only ingredients + steps
- **"Copy to My Recipes"** button: saves full copy to Room with new UUIDs, `visibility = "private"`, `isImported = false`, author set to current user
- Comments section (if signed in)

#### Comments

Stored in `shared_recipes/{recipeId}/comments/{commentId}`. Live stream via `callbackFlow`.

- **Post**: any authenticated user; max 1000 chars
- **Delete**: commenter (own comment) or recipe owner (any comment on their recipe)
- Comments are immutable once posted (no edit)
- Comment input shown in both `RecipeDetailScreen` (owner view, when recipe is public) and `SharedRecipeDetailScreen` (visitor view)

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

### F9 — Follow System & Direct In-App Sharing ✅

Two sharing modes exist in parallel:
1. **Deep link sharing** (F8) — share an HTTPS link anyone can open; opens in app or browser
2. **Direct in-app sharing** (F9) — share a recipe directly to a specific follower; they get an in-app notification and can review / save the recipe

The follow system and notifications live inside **Tab 4 (Account)** — no new tab needed.

#### Follow model

**One-directional** (A follows B ≠ B follows A). Not a mutual friendship model.

**`follows/{followerId}_{followeeId}`** — document ID encodes both parties for direct lookup
```
followerId:    String     // UID of the person who sent the request
followerName:  String     // display name snapshot
followeeId:    String     // UID of the person being followed
followeeName:  String
status:        "pending" | "accepted"
createdAt:     Timestamp
```

Required composite Firestore indexes (Firestore logs a creation URL on first query; click it):
- `follows (followeeId ASC, status ASC)` — for pending requests query
- `follows (followerId ASC, status ASC)` — for accepted following list

**`users/{uid}`** — public user profile (written on every sign-in via `SocialRepository.upsertProfile()`)
```
displayName:  String
photoUrl:     String?
updatedAt:    Timestamp
```

**`notifications/{uid}/items/{notifId}`** — per-user notification inbox (client-delivered, no Cloud Function)
```
type:              "follow_request" | "follow_accepted" | "recipe_shared"
fromUid:           String
fromDisplayName:   String
shareId:           String?     // doc ID in shared_to — populated for recipe_shared
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
  ├── Profile card: display name · email
  ├── Notifications bell (top bar) — BadgedBox with unread count; taps → NotificationsScreen
  ├── People section (signed-in only):
  │     PendingRequestCards (one per pending follow request, with Accept ✓ / Decline ✗)
  │     "Following: N" row
  │     "Find People to Follow" button → UserSearchScreen
  ├── Sync & Storage: last synced · recipe count · Force Sync
  ├── About: DB version · Aerion
  └── [Sign Out] → confirmation dialog → clears data + signs out
```

#### UserSearchScreen (push route `"user_search"`)

- `OutlinedTextField` with 300ms debounce + `distinctUntilChanged` on `queryFlow`
- Firestore prefix range query: `displayName >= query` and `displayName <= query + ""`, excludes self, limit 20
- Each result row: avatar initial circle · display name · Follow / Requested / Following button
- Follow status pre-populated for all results on each search

#### NotificationsScreen (push route `"notifications"`)

- Live `getNotificationsFlow()` stream (latest 50, ordered by `createdAt DESC`)
- `NotificationRow`: tinted icon circle (primaryContainer when unread) · bold sender · action label · relative timestamp · unread dot
- Tap `follow_request` or `follow_accepted` → navigates to `account_tab`
- Tap `recipe_shared` → navigates to `received/{shareId}`
- "Mark all read" TextButton in TopAppBar when any unread

#### ReceivedRecipeScreen (push route `"received/{shareId}"`)

- Loads from `shared_to/{uid}/recipes/{shareId}` via `SocialRepository.getReceivedRecipe()`
- "From: [sender name]" banner in `tertiaryContainer`
- Read-only detail: yield adjuster, unit toggle (if conversions exist), sections/ingredients/steps
- "Save to My Recipes" bottom bar: creates fresh Room copy (new UUIDs for recipe + sections + ingredients + steps + refs), `authorId = currentUid`, `isImported = false`, `visibility = "private"`, `needsReview = false`
- On save: `LaunchedEffect(savedRecipeId)` → `onSaved(newRecipeId)` → `navController.navigate("recipe/$newRecipeId")`

#### Direct recipe sharing (from RecipeDetailScreen)

- **Send icon** (AutoMirrored.Send) in top bar (owners only, separate from the public Share icon)
- Tap → `viewModel.loadFollowing()` + opens `FollowerPickerSheet`
- `FollowerPickerSheet` (`ModalBottomSheet`): list of accepted followers; each row has a Send IconButton
- Send → `viewModel.shareToFollower(recipientUid, recipientName)` → `SocialRepository.shareRecipeTo()`:
  1. Writes full recipe JSON to `shared_to/{recipientUid}/recipes/{shareId}`
  2. Delivers `recipe_shared` notification to recipient's inbox
- Snackbar shown: "Recipe sent to [Name]"

#### Push notifications (FCM — planned)

Background/foreground push via Firebase Cloud Messaging. Token stored in `users/{uid}.fcmToken`. Notification types mirror the in-app inbox: `follow_request`, `follow_accepted`, `recipe_shared`.

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

── Bottom Tab 1: All ──────────────────────────────────────────────────
HomeScreen (RecipeFilter.ALL)
  ├── Search bar + category filter chips
  ├── Recipe card list (all local recipes)
  │     Each card: title · time · tags · Author row (person icon + name) · Shared pill (if public)
  │     needsReview badge on pending cards
  ├── needsReview cards → "import?reviewId=$id" (review sheet)
  └── Settings gear icon → Profile tab

── Bottom Tab 2: Your Recipes ─────────────────────────────────────────
HomeScreen (RecipeFilter.YOURS — all user recipes: personal + imported)
  ├── Search bar
  ├── Author filter chips: [ All ] [ Personal ] [ Imported ]
  │     Personal = isImported:false; Imported = isImported:true
  ├── Category filter chips
  ├── Recipe list sorted: needsReview DESC → updatedAt DESC
  │     Each card: same as All tab (author row + shared pill)
  ├── needsReview cards → ImportScreen (review sheet)
  └── FAB "Add Recipe" → ModalBottomSheet
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

── Bottom Tab 3: Shared ───────────────────────────────────────────────
SharedScreen  (live Firestore stream)
  ├── Search bar
  ├── Shared recipe cards (title, author, time chips, tags, "Yours" badge)
  └── Tap own recipe → RecipeDetailScreen (owner view)
      Tap others' recipe → SharedRecipeDetailScreen (visitor view)

SharedRecipeDetailScreen  (pushed route "shared/{recipeId}")
  Deep links: https://amrosa-2ec82.web.app/shared/{recipeId}  |  amrosa://shared/{recipeId}
  ├── Recipe detail (read-only): yield adjuster, unit toggle
  ├── Ingredients grouped by section
  ├── Steps
  ├── "Copy to My Recipes" button (disabled if already copied or not signed in)
  └── Comments section (read + add/delete)

── Bottom Tab 4: Account ──────────────────────────────────────────────
AccountScreen  (route "account_tab")
  ├── TopAppBar: "Account" title · Bell icon (BadgedBox with unread count) → NotificationsScreen
  ├── Profile card: display name · email
  ├── People section (signed-in only):
  │     PendingRequestCard per pending follow request
  │       avatar initial · "X wants to follow you" · Accept ✓ / Decline ✗ buttons
  │       CircularProgressIndicator while action is in-flight
  │     "Following: N" stat row
  │     PersonSearch icon + "Find People to Follow" TextButton → UserSearchScreen
  ├── Sync & Storage: last synced · recipe count · Force Sync button
  ├── About: DB version · Aerion
  └── [Sign Out] → dialog: "Recipes removed from device..." → clears data + signs out

NotificationsScreen  (pushed route "notifications")
  ├── "Mark all read" TextButton in TopAppBar (when any unread)
  ├── Full notification history (read + unread), latest first
  ├── follow_request → tap → navigates to account_tab
  ├── follow_accepted → tap → navigates to account_tab
  └── recipe_shared → tap → navigates to received/{shareId}

UserSearchScreen  (pushed route "user_search")
  ├── OutlinedTextField (300ms debounce, prefix search on displayName)
  └── Results: avatar initial · display name · [Follow] / "Requested" / "Following" button

── Push routes (from any tab) ─────────────────────────────────────────
RecipeDetailScreen  (pushed route "recipe/{recipeId}")
  ├── Title, source URLs (tappable), prep/cook time
  ├── Yield adjuster (+/−, reset)
  ├── Section jump chips (auto-scroll)
  ├── Unit toggle: Original | Metric | Imperial (shown when conversions exist)
  ├── Substitute selectors, optional toggles, ingredient checklist
  ├── Recipe steps with inline ingredient refs
  ├── Notes (timestamped, add/edit/delete)
  ├── Cooking Mode button → CookingModeScreen
  ├── Top bar actions (owners only):
  │     [Send icon (AutoMirrored.Send)] → FollowerPickerSheet (direct in-app share)
  │     [Share icon] → if public: Android share sheet with HTTPS URL
  │                    if private: publish dialog → setVisibility("public") → share sheet
  │     [Edit pencil] → RecipeEditorScreen
  │     [Cooking Mode book]
  ├── Visibility FilterChip in body (owner only): 🔒 Private | 🌐 Public
  │     Confirms before toggling; public → comments section shown
  └── Comments section (when recipe is public)

FollowerPickerSheet  (ModalBottomSheet — from Send icon)
  ├── Accepted followers list: avatar initial circle · display name · Send IconButton per row
  ├── Empty state: "You're not following anyone yet"
  └── Tap Send → shareToFollower() → writes to shared_to/ + delivers recipe_shared notif
        Snackbar: "Recipe sent to [Name]"

ReceivedRecipeScreen  (pushed route "received/{shareId}")
  ├── "From: [sender name]" banner (tertiaryContainer)
  ├── Read-only recipe detail: yield adjuster, unit toggle (if conversions exist)
  ├── Sections / ingredients / steps
  └── Bottom bar: "Save to My Recipes" Button (BookmarkAdd icon)
        → new Room copy (new UUIDs) with authorId=currentUid, isImported=false, visibility=private
        → navigates to "recipe/{newRecipeId}"

RecipeEditorScreen  (pushed route "recipe/edit/{recipeId}")
  ├── Fork dialog (seeded/shared-copied recipes)
  ├── Metadata: title, description, times, yield (single/range), tags, URLs
  ├── Author dropdown (ExposedDropdownMenuBox):
  │     [Imported]  — isImported=true, authorDisplayName="Imported"
  │     [Personal — Name]  — isImported=false, authorDisplayName=user's name
  │     Default: Imported for imported recipes; Personal for freeform recipes
  ├── Sections with ingredients + steps (add/reorder/delete)
  └── Save → Room (updates isImported + authorDisplayName) + push to personal_recipes Firestore

CookingModeScreen  (pushed from RecipeDetailScreen)
  ├── Fullscreen, one step at a time, large text
  ├── Section label, ingredient card (scaled + unit-converted)
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
| **Room DB v9** | All entities: recipes, sections, ingredients, steps, step_ingredient_refs, recipe_notes |
| **Recipe detail** | Yield scaling (servings + anchor-based), ingredient checklist, step-ingredient refs, substitute selectors, optional toggles, section jump chips |
| **Cooking mode** | Fullscreen step-by-step, screen-on lock |
| **Notes system** | Per-recipe, timestamped, add/edit/delete |
| **Firebase Firestore** | Connected (`amrosa-2ec82`), security rules deployed |
| **RecipeSyncService** | Pull delta sync + `pushPersonalRecipe()` push to `personal_recipes/{uid}/recipes/` |
| **Version control** | `version` int + `changeLog` JSON array of `RecipeChange` |
| **Recipe Editor (F4)** | Full edit; fork dialog; author dropdown (Imported/Personal); cloud push; visibility preserved on save |
| **Source URLs** | Clickable, open in browser |
| **F3 — Import** | `parseRecipeUrl` + `parseRecipeContent` CF deployed; pending-review flow; Confirm/Reimport/Edit; Google Sheets/Docs auto-detect; file import (XLSX/CSV/TXT, 5 MB max) |
| **RecipeReviewSheet** | Shared composable; parse notes banner (tertiaryContainer, dismissible); onEdit pencil icon; author toggle for imports |
| **F5 — Freeform** | FreeformEntryScreen, FAB bottom sheet, `formatRecipeText` CF, save/saveAndEdit flow |
| **F6 — Unit conversions** | 6 IngredientEntity fields, `QuantityScaler`, `UnitMode`, unit toggle on detail screen |
| **4-tab navigation** | All · Your Recipes · Shared · Account (Imported tab removed; Import is push route) |
| **Your Recipes filter chips** | `[All] [Personal] [Imported]` chips filter by `isImported` within the tab |
| **Recipe cards** | Author row (person icon + name) + Shared pill on all cards |
| **F7 — Mandatory auth gate** | Auth required at launch; `AuthScreen` shown as root when not signed in (no back button); main app only renders after real sign-in |
| **F7 — Sign-out clears data** | `clearAllLocalData()` in `AppContainer` wipes Room + sync prefs; called before `signOut()` |
| **F7 — Auth** | Google + email/password + phone OTP; `linkWithCredential` upgrade; sync on sign-in; `AccountScreen`; `AuthScreen` |
| **F7 — Author attribution** | `authorId` + `authorDisplayName` stamped at creation; editor author dropdown; personal = real name; imported = "Imported" override at publish time |
| **F8 — Visibility** | `visibility` field on RecipeEntity; private/public; share button in detail top bar |
| **F8 — Share button** | Top bar icon (owners only); if public → Android share sheet with `amrosa://shared/{id}`; if private → dialog → publish → share sheet |
| **F8 — Deep links + App Links** | HTTPS App Links (`https://amrosa-2ec82.web.app/shared/{id}`) + `amrosa://` fallback; `assetlinks.json` in Firebase Hosting; `navDeepLink` for both patterns in NavGraph |
| **F8 — Firebase Hosting** | `shared.html` recipe viewer (browser fallback); `index.html` landing page; deployed at `amrosa-2ec82.web.app` |
| **F8 — Shared tab** | `SharedScreen` + `SharedViewModel`; live Firestore stream; search; "Yours" badge; routing to owner vs visitor view |
| **F8 — Shared detail** | `SharedRecipeDetailScreen`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; deep link entry point |
| **F8 — Comments** | `Comment` domain model; post/delete; Firestore subcollection; delete by commenter or recipe owner |
| **F8 — Security rules** | Full Firestore rules deployed; per-UID personal access; `shared_recipes` public read; comment create/delete moderation |
| **Seeder disabled** | `seedIfNeeded()` is a no-op; fresh installs start blank |
| **F9 — Follow system** | Request-and-accept following; `users/`, `follows/`, `notifications/`, `shared_to/` Firestore collections; `SocialRepository.kt`; `UserProfile` + `SocialNotification` domain models |
| **F9 — User search** | `UserSearchScreen` + `UserSearchViewModel`; prefix search by display name; Follow/Unfollow/Requested states per user |
| **F9 — Account tab social** | Pending follow requests inline with Accept/Decline; Following count; "Find People" link; Notification bell with unread badge |
| **F9 — Notifications** | `NotificationsScreen` + `NotificationsViewModel`; live unread count stream; mark-as-read / mark-all-read; relative timestamp display |
| **F9 — Direct recipe sharing** | "Send to follower" icon in Recipe Detail top bar (owners only); `FollowerPickerSheet` bottom sheet; stores full recipe data in `shared_to/{recipientUid}/recipes/{shareId}`; delivers `recipe_shared` notification to recipient |
| **F9 — Received recipe** | `ReceivedRecipeScreen` + `ReceivedRecipeViewModel`; loads from `shared_to/{uid}/recipes/{shareId}`; shows sender name; read-only detail with scaling; "Save to My Recipes" → Room copy → navigate to saved recipe |
| **F9 — Notification nav** | `follow_request` / `follow_accepted` → Account tab; `recipe_shared` → `received/{shareId}` route |

### Planned — In Priority Order

| # | Feature | Description |
|---|---|---|
| — | FCM push notifications | Background push for `follow_request` / `recipe_shared` via Cloud Function triggered on Firestore write |
| — | Recipe Images | Firebase Storage integration; image picker on editor; Coil display |
| — | Shopping List | Dedicated screen; add ingredients from recipe detail |
| — | iOS gaps (see below) | Auth gate, sign-out data clear, Shared tab, share/visibility, comments, Universal Links |

---

## iOS Platform Status

The iOS codebase (`ios/Amrosa/`) is a fully-functional port of the Android app. Swift, SwiftUI, SwiftData. All core features including auth gate, shared recipes, comments, and visibility/share are implemented.

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
| **F8 — Shared tab** | `SharedRecipesView` + `SharedRecipesViewModel`; live Firestore stream; search; "Yours" badge |
| **F8 — Shared detail** | `SharedRecipeDetailView`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; comments |
| **F8 — Visibility chip** | Owners see Private/Public toggle chip in detail body; confirms before toggling |
| **F8 — Share button** | Top bar (owners only); iOS share sheet with `https://amrosa-2ec82.web.app/shared/{id}`; publish dialog for private recipes |
| **F8 — Comments** | Post/delete in owner view + visitor view; commenter or recipe owner can delete |
| **F8 — SharedRecipeService** | `publish/unpublish`, live `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **Deep links** | `amrosa://shared/{id}` custom scheme + `https://amrosa-2ec82.web.app/shared/{id}` via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **YourRecipesView** | All local recipes shown (seeded included); filter chips (All/Personal/Imported); search; FAB |
| **Account tab** | Profile (name/email/phone), sign-out + data-wipe dialog, recipe count, last sync |

### iOS 🔶 Remaining Gaps (planned)

| Gap | Detail |
|---|---|
| **Universal Links** | iOS handles `https://amrosa-2ec82.web.app/shared/` via `onOpenURL` already, but requires `Associated Domains` entitlement + `apple-app-site-association` file on the hosting server for iOS to intercept those URLs before Safari opens them |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |
| **F9 — Friends & notifications** | Not yet implemented on iOS (Android feature, planned) |
| **Your Recipes filter chips** | iOS uses a segmented picker; Android uses `[All] [Personal] [Imported]` filter chips — minor UX difference |

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

- Mutual "friendship" model (two-way) — the follow system is one-directional ✅ in scope; bidirectional friending is ❌
- Meal planning / calendar integration — ❌
- Nutritional information — ❌
- Voice input / hands-free mode — ❌
- Social profiles / public profile pages for followers — ❌ (follow exists for direct sharing only)

---

## Notes for AI Coding Assistants

### General
- **AI model**: Gemini 2.5 Flash (`@google/generative-ai` npm). Do NOT use Anthropic SDK.
- **Kotlin idioms**: data classes, sealed classes, extension functions, `StateFlow` over `LiveData`
- **Compose only**: all UI in Jetpack Compose — no XML layouts
- **IO dispatcher**: all DB and network on `Dispatchers.IO`, never on Main
- **Room is source of truth**: UI never reads Firestore directly (except `SharedRecipeDetailScreen` which loads from Firestore since the recipe is not in Room)


### Database
- **DB versioning**: `fallbackToDestructiveMigration()`. Always bump `AmrosaDatabase.DB_VERSION` and `DatabaseSeeder` seeder key (`seeded_vN`) together. **Current: DB v9, seeder `seeded_v11`**.
- **Seeder**: `seedIfNeeded()` is a no-op. Do not add seeding logic. Bump key only when schema changes.
- **IngredientEntity field order**: F6 conversion fields are **last** (after `orderIndex`). Do not insert new fields before them — it breaks positional `DatabaseSeeder` calls.

### Recipe Logic
- **Scaling math**: servings-based: `baseQty × selectedServings / baseServings`; anchor-based: `baseQty × anchorQty / baseAnchorQty`; unit conversion quantities scale in parallel using the same ratio.
- **Substitute resolution**: when substitute selected, apply `substituteRatio` to quantity; show substitute name in step inline text.
- **StepIngredientRef quantities**: always derived from base value × ratio — never store pre-scaled values.
- **Version tracking**: every editor save must increment `version` and append to `changeLog`.

### Author Attribution
- `authorId` + `authorDisplayName` are stamped at creation time (freeform, import, editor fork of seeded recipe).
- `isImported` controls two things: (1) which author name appears when shared, (2) which filter chip it appears under in Your Recipes.
  - `isImported = false` → `authorDisplayName` (real user name) shown on shared recipe; appears under "Personal" chip
  - `isImported = true` → always "Imported" shown (override applied in `SharedRecipeService.buildDocument()`); appears under "Imported" chip
- `authorId` is always the real UID regardless of `isImported` — required for Firestore security rules.
- The editor **author dropdown** changes both `isImported` and `authorDisplayName` on save. `authorId` is never changed.
- `isOwner` in `RecipeDetailViewModel`: `true` when `authorId == currentUid` OR when `authorId == null` (pre-attribution recipes).

### Visibility & Sharing
- Share button in detail top bar (owners only):
  - Already public → opens Android share sheet immediately with `https://amrosa-2ec82.web.app/shared/{recipeId}`
  - Private → dialog → confirm → `setVisibility("public")` + set `pendingShareAfterPublish = true` → `LaunchedEffect` fires share sheet once `state.isPublic` becomes true
- `setVisibility("public")` → updates Room + publishes to `shared_recipes` + starts comment observer.
- `setVisibility("private")` → updates Room + unpublishes + stops comment observer.
- `SharedRecipeService.buildDocument()` applies `authorDisplayName = "Imported"` when `recipe.isImported == true`.
- Comments are Firestore-only; never stored in Room.

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
- Tab routes: `"all_tab"`, `"yours_tab"`, `"shared_tab"`, `"account_tab"`
- Push routes: `"recipe/{recipeId}"`, `"recipe/edit/{recipeId}"`, `"shared/{recipeId}"`, `"freeform"`, `"import?reviewId={reviewId}"`, `"notifications"`, `"user_search"`, `"received/{shareId}"`
- The `"auth"` route is **removed** from the nav graph — auth is handled at the outer `AmrosaNavGraph` level.
- Import route uses optional query param `reviewId`; default empty string, treated as null in screen.
- `showBottomBar` is true only when `currentDestination?.route` is one of the 4 tab routes.
- Deep link `amrosa://shared/{recipeId}` is registered on the `"shared/{recipeId}"` composable via `navDeepLink`.
- `"received/{shareId}"` loads from `shared_to/{uid}/recipes/{shareId}` via `SocialRepository.getReceivedRecipe()`.
- `"notifications"` tapping a follow notification navigates to `account_tab`; recipe_shared navigates to `received/{shareId}`.

### Auth Patterns
- Anonymous auth removed. Do not call `signInAnonymouslyIfNeeded()` — it exists in `AuthRepository` for compat but is never called.
- Sign-out flow: always call `container.clearAllLocalData(context)` before `authRepository.signOut()`.
- Push sync: only runs for `!authRepository.isAnonymous` users.
- On sign-in: `AmrosaApplication.authStateFlow` observer automatically triggers seed + sync.

### Social / Follow System (F9)
- **`SocialRepository`** in `data/remote/SocialRepository.kt` — all Firestore ops for users, follows, notifications, shared_to.
- **`UserProfile`** domain model — uid, displayName, photoUrl, createdAt.
- **`SocialNotification`** domain model — id, type, fromUid, fromDisplayName, shareId?, recipeName?, createdAt, read.
- **`upsertProfile()`** is called in `AmrosaApplication.authStateFlow` on every real sign-in.
- **Follow doc ID** = `{followerId}_{followeeId}`. Composite Firestore indexes needed:
  - `follows (followeeId ASC, status ASC)` — for pending requests query
  - `follows (followerId ASC, status ASC)` — for following list query
  Firestore will log the index creation URL on first query; click it.
- **Notification delivery** = write to `notifications/{toUid}/items/{uuid}`. Done client-side; no Cloud Function required.
- **Direct share** stores full recipe JSON in `shared_to/{recipientUid}/recipes/{shareId}` and delivers a `recipe_shared` notification. The recipient opens `ReceivedRecipeScreen` which loads from that path.
- **`ReceivedRecipeViewModel.saveToMyRecipes()`** creates a fresh Room copy (new UUIDs) with `authorId = currentUid`, `isImported = false`, `visibility = "private"`.
- **Notification bell badge** on Account tab top bar uses `SocialRepository.getUnreadCountFlow()`.
- **Pending follow requests** shown inline in AccountScreen as cards with Accept/Decline buttons.

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

| Gap | Detail |
|---|---|
| **Universal Links** | iOS handles `https://amrosa-2ec82.web.app/shared/` via `onOpenURL` but requires `Associated Domains` entitlement + `apple-app-site-association` on the hosting server for the OS to intercept them before Safari |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |
| **F9 — Friends & notifications** | Not yet implemented on iOS (Android feature, planned) |
| **Your Recipes filter chips** | iOS uses a segmented picker; Android has `[All] [Personal] [Imported]` chips — minor UX difference |

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
