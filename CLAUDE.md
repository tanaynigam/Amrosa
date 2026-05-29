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
Auth Gate       (🔐)  Full-screen login wall — shown when not signed in; no back button
Tab 1 — Your Recipes   (🔖)  All your recipes (personal + imported); Add Recipe FAB
Tab 2 — Shared         (📩)  Recipes directly shared with you by followers
Tab 3 — Discover       (✨)  Recommendations — planned; placeholder screen for now
Tab 4 — Account        (👤)  Profile, co-chef system, sync, sign-out
```

**Design decisions:**
- **Auth is mandatory.** `AmrosaNavGraph` observes `authStateFlow()`. When `currentUser == null` or anonymous, the full-screen `AuthScreen` is shown (no back button, no bottom bar). The main `Scaffold` + `NavHost` don't render until sign-in.
- **Sign-out deletes all local data.** `AccountViewModel.signOut()` calls `container.clearAllLocalData(context)` (Room `clearAllTables()` + sync prefs cleared) before `authRepository.signOut()`. Auth state change recomposes the nav graph back to the auth gate automatically.
- **Sign-in triggers seed + sync.** `AmrosaApplication` observes `authStateFlow()` and calls `seeder.seedIfNeeded()` + `syncService.sync()` + `syncService.syncPersonalRecipes()` whenever a real (non-anonymous) user is detected.
- **"All" tab is removed.** Your Recipes is the primary tab. Filter chips (`All | Personal | Imported`) provide in-tab filtering.
- **In-app notification screen removed.** Replaced by Android push notifications via FCM. `NotificationsScreen` and `SocialNotification` model are deleted. Notification bell and unread badge are removed from Account tab.
- `isImported` controls **author display when sharing** (`false` = real name, `true` = "Imported"), not which filter chip it appears under.
- **Import** is a push route (`"import?reviewId=..."`) accessible from the Add Recipe FAB, not a tab.
- Pending-review recipes float to the top of Your Recipes with a "Needs review — tap to confirm" badge; tapping opens the import screen with the review sheet pre-loaded.
- **"Shared" tab (Tab 2) = "Shared with me"** — shows only recipes other users have directly sent to you (from `shared_to/{uid}/recipes/`). Does NOT show all public recipes or a community browse feed.
- **"Discover" tab (Tab 3) = Recommendations placeholder** — renders a simple "Coming soon" screen. Implementation deferred.
- **Public recipes are NOT auto-listed anywhere.** `visibility = "public"` only means the recipe is mirrored to `shared_recipes` in Firestore so it can be accessed via a shareable link or a future profile view. It does not appear in any in-app browse tab.
- **Tab 4 is "Account"** (route `"account_tab"`, composable `AccountScreen`) — contains both account management and social/follow features.

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
| `users/{uid}` | Public user profile — displayName, photoUrl, email, updatedAt, **fcmToken**. Created/merged on each sign-in via `SocialRepository.upsertProfile()`. Used for user search and FCM push delivery. |
| `follows/{followerId}_{followeeId}` | Co-Chef relationship. Fields: followerId, followerName, followeeId, followeeName, status ("pending"\|"accepted"), createdAt. Composite indexes required: (followeeId, status) and (followerId, status). |
| `notifications/{uid}/items/{notifId}` | Notification inbox — written client-side, read by `sendPushNotification` Cloud Function to dispatch FCM push. Fields: type, fromUid, fromDisplayName, shareId?, recipeName?, createdAt, read. |
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

#### Gemini conversion — metric only; imperial computed in code

**Gemini only produces metric fields.** Imperial is never computed by Gemini — it is computed deterministically by `computeImperialFromMetric()` in `parseRecipe.js` after Gemini returns.

**Gemini metric rules:**
- Volume → ml or L: 1 cup = 240 ml, 1 tbsp = 15 ml, 1 tsp = 5 ml, 1 fl oz = 30 ml
- Weight → g or kg: 1 oz = 28.35 g, 1 lb = 453.6 g
- If original is already metric (g, kg, ml, L) → copy to metric fields as-is
- Non-convertible (whole eggs, cloves, "to taste", counts) → all conversion fields null

**`computeImperialFromMetric(recipe)` logic (runs after Gemini):**
- `g` or `kg` → grams first; if `< 453.6g` → oz (`grams / 28.35`, 1dp); if `≥ 453.6g` → lb (`grams / 453.6`, 2dp)
- `ml` or `L` → ml first; → fl oz (`ml / 29.574`, 1dp)
- Unknown or null metric unit → imperial = null
- This guarantees imperial is never in cups/tbsp/tsp — only oz, lb, or fl oz

Gemini's imperial fields are **zeroed in `validateRecipe()`** before `computeImperialFromMetric()` runs, so Gemini output is completely ignored for imperial.

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

#### Recipe visibility model

Every recipe has `visibility: String = "private"` in Room.
- **`"private"`** (default) — visible only to the owner; no Firestore mirror
- **`"public"`** — mirrored to `shared_recipes/{recipeId}` in Firestore, making it accessible via a shareable link and (future) the owner's public profile. **Does NOT make it visible in any in-app browse tab.**

**Public ≠ community-visible.** Public recipes do not appear in the Shared tab or any feed. The Shared tab (Tab 2) shows only recipes *directly sent* to the current user via `shared_to/`. The only ways for someone else to see a public recipe are:
1. They have the direct HTTPS share link
2. The owner directly sends it to them (via "Send to follower")
3. They visit the owner's profile page (future feature — not yet implemented)

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

#### Shared tab (Tab 2) — "Shared with me"

Shows recipes that other users have directly sent to the current user via `shared_to/{uid}/recipes/`. This is a **personal inbox**, not a community browse.

- Loaded from `shared_to/{uid}/recipes/` via `SocialRepository` (live stream or paginated fetch)
- Each card: recipe title · sender name · "Sent X ago" timestamp
- Tap → `ReceivedRecipeScreen` (read-only, with "Save to My Recipes")
- Empty state: "No recipes shared with you yet"

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

#### ReceivedRecipeScreen (push route `"received/{shareId}"`)

- Loads from `shared_to/{uid}/recipes/{shareId}` via `SocialRepository.getReceivedRecipe()`
- “From: [sender name]” banner in `tertiaryContainer`
- Read-only detail: yield adjuster, unit toggle (if conversions exist), sections/ingredients/steps
- “Save to My Recipes” bottom bar: fresh Room copy (new UUIDs), `authorId = currentUid`, `isImported = false`, `visibility = "private"`, `needsReview = false`
- On save → navigates to `"recipe/{newRecipeId}"`

#### Direct recipe sharing (from RecipeDetailScreen)

- Single **Share icon** in top bar (owners only) → `ShareOptionsSheet` (ModalBottomSheet)
- “Send to co-chef” → `FollowerPickerSheet`: list of accepted co-chefs with Send icon per row
- Send → `SocialRepository.shareRecipeTo()`:
  1. Writes full recipe JSON to `shared_to/{recipientUid}/recipes/{shareId}`
  2. Delivers `recipe_shared` notification → triggers FCM push
- Snackbar: “Recipe sent to [Name]”

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

── Bottom Tab 1: Your Recipes ─────────────────────────────────────────
HomeScreen (RecipeFilter.YOURS — all user recipes: personal + imported)
  ├── Search bar
  ├── Author filter chips: [ All ] [ Personal ] [ Imported ]
  │     Personal = isImported:false; Imported = isImported:true
  ├── Category filter chips
  ├── Recipe list sorted: needsReview DESC → updatedAt DESC
  │     Each card: title · time · tags · author row · Shared pill (if public)
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

── Bottom Tab 2: Shared ──────────────────────────────────────────────
SharedScreen  (route "shared_tab" — "Shared with me")
  ├── List of recipes directly sent to you by followers
  │     Each card: recipe title · "From: [sender]" · "Sent X ago"
  ├── Empty state: "No recipes shared with you yet"
  └── Tap → ReceivedRecipeScreen

── Bottom Tab 3: Discover ────────────────────────────────────────────
DiscoverScreen  (route "discover_tab" — placeholder)
  └── "Coming soon" centered text + icon (implementation deferred)

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
  ├── Yield adjuster (+/−, reset)
  ├── Section jump chips (auto-scroll)
  ├── Unit toggle: Original | Metric | Imperial (shown when conversions exist)
  ├── Substitute selectors, optional toggles, ingredient checklist
  ├── Recipe steps with inline ingredient refs
  ├── Notes (timestamped, add/edit/delete)
  ├── Cooking Mode button → CookingModeScreen
  ├── Top bar actions (owners only):
  │     [Share icon] → ShareOptionsSheet (ModalBottomSheet)
  │           Option A (default): "Send to follower" → FollowerPickerSheet
  │           Option B: "Share link" → if public: Android share sheet with HTTPS URL
  │                                    if private: publish dialog → setVisibility("public") → share sheet
  │     [Edit pencil] → RecipeEditorScreen
  │     [Cooking Mode book]
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
  ├── Save → Room (updates isImported + authorDisplayName) + push to personal_recipes Firestore
  └── "Delete Recipe" button (red outlined, bottom) → confirmation dialog → deleteFullRecipe() → navigate back

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
| **4-tab navigation** | Your Recipes · Shared (with me) · Discover (placeholder) · Account (All tab removed) |
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
| **F8 — Shared tab (reworked)** | Tab 2 now shows received recipes from `shared_to/{uid}/` — not community browse. Community browse removed. |
| **F8 — Shared detail** | `SharedRecipeDetailScreen`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; deep link entry point only |
| **F8 — Comments** | `Comment` domain model; post/delete; Firestore subcollection; delete by commenter or recipe owner |
| **F8 — Security rules** | Full Firestore rules deployed; per-UID personal access; `shared_recipes` public read; comment create/delete moderation |
| **Seeder disabled** | `seedIfNeeded()` is a no-op; fresh installs start blank |
| **F9 — Co-Chefs (mutual friends)** | Bidirectional friendship: `acceptFollowRequest()` batch-creates reverse doc so both see count = 1; `unfriend()` batch-deletes both docs; `getFriendsFlow()` queries `followerId == uid AND status == accepted` |
| **F9 — User search** | `UserSearchScreen`; email lookup (`@` in query → exact match) or displayName prefix; shows name + email; "Add Co-Chef" / "Co-Chef ✓" / "Requested" states |
| **F9 — Account tab social** | Co-Chefs section: pending requests inline with Accept/Decline; "Co-Chefs: N" tappable row → FriendsScreen; "Find Co-Chefs" link |
| **F9 — FriendsScreen** | Lists accepted co-chefs; Remove button → confirmation → `unfriend()` batch delete |
| **F9 — Direct recipe sharing** | Single Share icon → `ShareOptionsSheet`; "Send to co-chef" → `FollowerPickerSheet`; stores full recipe in `shared_to/{recipientUid}/recipes/{shareId}`; delivers notification → FCM push |
| **F9 — FCM push notifications** | `AmrosaMessagingService` (`onNewToken` stores token, `onMessageReceived` shows foreground notification); `sendPushNotification` Cloud Function triggered on `notifications/{uid}/items` creation; channel `"amrosa_social"` created on app start; Co-Chef / recipe share push text |
| **F9 — Received recipe** | `ReceivedRecipeScreen` + `ReceivedRecipeViewModel`; loads from `shared_to/{uid}/recipes/{shareId}`; sender banner; read-only detail with scaling; "Save to My Recipes" → Room copy → navigate to saved recipe |
| **Delete recipe** | Red "Delete Recipe" button at bottom of editor; confirmation dialog; calls `repository.deleteFullRecipe(recipeId)` then navigates back |
| **URL import reliability** | `extractJsonLdRecipe()` extracts `schema.org/Recipe` JSON-LD from raw HTML (before cleanHtml strips scripts) — major recipe sites include this for SEO; realistic browser headers; 402/403 → actionable error message suggesting freeform entry |
| **Imperial unit fix** | Gemini only populates metric fields; `computeImperialFromMetric()` in Cloud Function computes imperial from metric with exact math: g/kg → oz (< 453.6g) or lb; ml/L → fl oz (1 fl oz = 29.574 ml) |

### Planned — In Priority Order

| # | Feature | Description |
|---|---|---|
| — | Discover / Recommendations tab | Personalised recipe suggestions (Gemini-powered); replaces placeholder |
| — | Public profile view | View another user's public recipes at `"profile/{uid}"` |
| — | Recipe Images | Firebase Storage integration; image picker on editor; Coil display |
| — | Shopping List | Dedicated screen; add ingredients from recipe detail |
| — | iOS: Universal Links | `apple-app-site-association` file + `Associated Domains` entitlement |

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
| **F8 — Shared detail** | `SharedRecipeDetailView`; read-only; yield adjuster; unit toggle; "Copy to My Recipes"; comments |
| **F8 — Visibility chip** | Owners see Private/Public toggle chip in detail body; confirms before toggling |
| **F8 — Merged share button** | Single Share icon → `ShareOptionsSheet` ("Send to a follower" / "Share link"); owners only |
| **F8 — Share link** | iOS share sheet with `https://amrosa-2ec82.web.app/shared/{id}`; publish dialog for private recipes |
| **F8 — Comments** | Post/delete in owner view + visitor view; commenter or recipe owner can delete |
| **F8 — SharedRecipeService** | `publish/unpublish`, `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **Deep links** | `amrosa://shared/{id}` custom scheme + `https://amrosa-2ec82.web.app/shared/{id}` via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **Tab restructure** | All tab removed; 4 tabs: Your Recipes · Shared with Me · Discover (placeholder) · Account |
| **Shared with Me tab** | `SharedInboxView` + `SharedInboxViewModel`; live `shared_to/{uid}/recipes/` stream; taps → `ReceivedRecipeView` |
| **Discover tab** | Placeholder screen |
| **YourRecipesView** | All local recipes; filter chips (All/Personal/Imported); search; FAB |
| **Account tab** | Profile card (tappable → edit name alert), sign-out + data-wipe dialog, recipe count, last sync |
| **Profile name edit** | Tap profile card → alert with text field → `authRepository.updateDisplayName()` + `upsertProfile()`; toast on success |
| **F9 — Follow system** | `SocialRepository`, `UserSearchView`, `NotificationsView`, `ReceivedRecipeView`; follow/unfollow/accept/decline, notification stream, direct recipe sharing |

### iOS 🔶 Remaining Gaps (planned)

| Gap | Detail |
|---|---|
| **Universal Links** | iOS handles `https://amrosa-2ec82.web.app/shared/` via `onOpenURL` already, but requires `Associated Domains` entitlement + `apple-app-site-association` file on the hosting server for iOS to intercept those URLs before Safari opens them |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |
| **FCM push notifications** | Android uses FCM (AmrosaMessagingService + Cloud Function). iOS keeps in-app notification screen (NotificationsView). APNs/FCM for iOS requires separate certificate setup. |
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
- Tab routes: `"yours_tab"`, `"shared_tab"`, `"discover_tab"`, `"account_tab"` (All tab removed)
- Push routes: `"recipe/{recipeId}"`, `"recipe/edit/{recipeId}"`, `"shared/{recipeId}"`, `"freeform"`, `"import?reviewId={reviewId}"`, `"notifications"`, `"user_search"`, `"received/{shareId}"`
- The `"auth"` route is **removed** from the nav graph — auth is handled at the outer `AmrosaNavGraph` level.
- Import route uses optional query param `reviewId`; default empty string, treated as null in screen.
- `showBottomBar` is true only when `currentDestination?.route` is one of the 4 tab routes.
- Deep link `amrosa://shared/{recipeId}` is registered on the `"shared/{recipeId}"` composable via `navDeepLink`.
- `"received/{shareId}"` loads from `shared_to/{uid}/recipes/{shareId}` via `SocialRepository.getReceivedRecipe()`.
- `"notifications"` tapping a follow notification navigates to `account_tab`; recipe_shared navigates to `received/{shareId}`.
- `"shared_tab"` shows received recipes from `shared_to/{uid}/recipes/` — NOT a community browse.
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
- **Direct share** stores full recipe JSON in `shared_to/{recipientUid}/recipes/{shareId}` + delivers `recipe_shared` notification → FCM push.
- **`ReceivedRecipeViewModel.saveToMyRecipes()`** creates a fresh Room copy (new UUIDs) with `authorId = currentUid`, `isImported = false`, `visibility = "private"`.
- **Pending co-chef requests** shown inline in AccountScreen as cards with Accept/Decline buttons; text "wants to be co-chefs".
- **User search** — `searchUsers(query)`: if `query.contains('@')` → exact `whereEqualTo("email", ...)` lookup; else displayName prefix range query with `` sentinel. Results show name + email. Buttons: "Add Co-Chef" / "Requested" / "Co-Chef ✓".
- **Edit display name**: `AccountViewModel.updateDisplayName(name)` calls `authRepository.updateDisplayName(name)` then `socialRepository.upsertProfile()`. Snackbar "Name updated".
- **Merged share button**: single Share icon → `ShareOptionsSheet` with "Send to co-chef" and "Share link".
- **Shared tab (Tab 2)** = inbox of received recipes. Load from `shared_to/{uid}/recipes/` — NOT `shared_recipes` community collection.
- **Delete recipe**: `RecipeEditorViewModel.deleteRecipe()` calls `repository.deleteFullRecipe(recipeId)` on IO dispatcher, sets `deleteComplete = true` → screen navigates back.

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
| **F9 — Friends & notifications** | ✅ Implemented: `SocialRepository`, `UserSearchView`, `NotificationsView`, `ReceivedRecipeView`; follow/unfollow/accept/decline, notification stream, direct recipe sharing |
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
