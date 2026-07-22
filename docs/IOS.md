# iOS platform status, structure & hand-off gaps

> Split out of CLAUDE.md. **The iOS session should read this file in full.** It holds the iOS implementation status, file structure, conventions, and the remaining-gaps tables.

> 🚨 **READ FIRST — identifier names in this file are unreliable; trust the repo.**
> The Android side was renamed twice more (Chef's List → Tablefeed → **Chef's Journal**) using bulk
> find/replace that also rewrote **iOS** identifiers in this document. So inline names like
> `ios/ChefsJournal/…`, `ChefsJournalApp`, `com.aerion.chefsjournal` in the prose below are **wrong for
> the current iOS tree**.
>
> **Actual iOS state on disk right now:** the app is on the **Chef's List** identity —
> `ios/ChefsList/`, bundle id `com.aerion.chefslist`, done in commit `14d2c52`
> (*rebrand(ios): … → Chef's Journal*). Its `GoogleService-Info.plist` is still the **old
> `amrosa-2ec82`** file — swapping it (plus REVERSED_CLIENT_ID) is the one remaining user step. See the **🔁 Rebrand** row below.

## iOS Platform Status

The iOS codebase (`ios/ChefsList/`) is a fully-functional port of the Android app. Swift, SwiftUI, SwiftData. All core features including auth gate, shared recipes, comments, and visibility/share are implemented. Notifications are push-only (no in-app notification screen).

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
| **F8 — Share link** | iOS share sheet with `https://chef-s-journal-6a0fd.web.app/shared/{id}`; publish dialog for private recipes |
| **F8 — Comments** | Post/delete in owner view + visitor view; commenter or recipe owner can delete |
| **F8 — SharedRecipeService** | `publish/unpublish`, `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **Deep links** | `chefsjournal://shared/{id}` custom scheme + `https://chef-s-journal-6a0fd.web.app/shared/{id}` via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **Tab restructure** | All tab removed; 4 tabs: **My Recipes** · Shared Recipes · Discover (F13 feed) · Account |
| **My Recipes Shared chip** | `YourRecipesViewModel` has `.shared` filter; loads `receivedRecipesSummaryStream()`; chip switches list to shared `SharedRecipeCard`s, hides FAB, tap → `ReceivedRecipeView`; compact capsule search bar w/ clear button + adaptive placeholder; chips scroll inside the list |
| **Shared Recipes tab** | `SharedInboxView` + `SharedInboxViewModel`; live `shared_to/{uid}/recipes/` stream; full recipe cards (title, times, tags, author + "from sender"); taps → `ReceivedRecipeView` |
| **Discover tab (F13 — full)** | Default + left-most tab. `MealClassifier` + `DiscoverRanker` (meal match · cuisine affinity · source boost · **popularity** · recency penalty). Shelves: "{Meal} ideas", "From your kitchen", "From your co-chefs" (`getAuthorRecipes` per co-chef), **"Popular"** (recent ∪ `getPopularPublicRecipes`, by saves·2+likes), "Fresh from the community" (`getPublicRecipeSummaries`), "Recently cooked" (`cooked_log`). Surprise-me + pull-to-refresh. `CookedLogModel` + `markCooked()` on Cooking Mode "Done!". Local card → editable detail; remote → `ReceivedRecipeView(.direct)`. **Phase 5 (popularity):** `getPopularPublicRecipes`, `setLiked`/`likeStateStream` + ❤ toolbar on `ReceivedRecipeView` (read-only counts; `canLike` = signed-in). **Phase 6 (search):** `.searchable` cross-scope search (own/friends client-side `contains`, public via `searchPublicRecipes` array-contains on `searchTokens` + client refine, 300ms debounce); `buildDocument` now writes `searchTokens`. **Phase 7 (prefs):** `UserPreferences` (UserDefaults) explicit cuisine chips in Account ("Recipe preferences") override implicit affinity on next refresh. |
| **Account tab** | Profile card (tappable → edit name alert), sign-out + data-wipe dialog, recipe count, last sync |
| **Profile name edit** | Tap profile card → alert with text field → `authRepository.updateDisplayName()` + `upsertProfile()`; toast on success |
| **F9 — Follow system** | `SocialRepository`, `UserSearchView`, `ReceivedRecipeView`, `FriendsView`; follow/unfollow/accept/decline, direct recipe sharing, Co-Chefs list. In-app notifications removed (push only). |
| **F9 — Push notifications only** | In-app `NotificationsView` + notification reading removed; Firestore notification writes remain to trigger `sendPushNotification`. `AppDelegate` + `FirebaseMessaging`; APNs bridge; token stored on sign-in; tap routing (follow → Account, recipe shared → Shared). **Token path:** `users/{uid}/private/push.fcmToken` — same doc Android writes and the Cloud Function reads (legacy `users/{uid}.fcmToken` fallback). **Swizzling is OFF** (`FirebaseAppDelegateProxyEnabled = false`), so `AppDelegate` must keep manually forwarding `didRegisterForRemoteNotificationsWithDeviceToken` → `Messaging.messaging().apnsToken`. **⚠️ APNs environment:** `aps-environment` is per-configuration — `ChefsJournalDebug.entitlements` (`development`, APNs sandbox) for Debug, `ChefsJournal.entitlements` (`production`) for Release/TestFlight. A dev-signed build carrying `production` gets `BadDeviceToken` and pushes silently never arrive. One APNs **auth key** (`.p8`, uploaded in Firebase → Cloud Messaging with Key ID + Team ID `7S2FY6WF5V`) serves both environments. |
| **Shared recipe author attribution** | `buildSharedToDocument` stores `authorDisplayName` (original author) separate from `fromDisplayName` (sender); `getReceivedRecipe` returns `ReceivedRecipeData(recipe, fromDisplayName)`; `ReceivedRecipeView` "Shared by [sender]" banner + Sources section + "Save Recipe" → dismiss back |
| **Co-Chef stale-data repair** | `repairFriendships()` on sign-in creates missing reverse follow docs for pre-mutual-friendship data |
| **Recipe detail (section-grouped)** | Ingredients grouped by section (step order) then group label via `ingredientSectionBlocks` — matches Android; steps grouped by section under "Instructions"; section jump chips scroll via `ScrollViewReader`; step-ingredient ref chips shown inline. (Still has per-ingredient check circles — see Remaining Gaps.) |
| **Cooking mode (parity)** | Unit toggle (Orig/Metric/Imp), scrolling content, section jump menu, "▶ Cook from here" start-at-section — matches Android, incl. the session-only step tick-off checklist |
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
| **🔁 Rebrand → Chef's Journal (`com.aerion.chefsjournal` / `chef-s-journal-6a0fd`)** | ✅ **Code/config done in the repo** (compiles with code-signing disabled): bundle id → `com.aerion.chefsjournal`; display name → "Chef's Journal"; scheme `chefslist://` → `chefsjournal://`; deep-link/share host + Associated Domains → `chef-s-journal-6a0fd.web.app`; Xcode project/target/folders renamed `ChefsList` → `ChefsJournal` (`ios/ChefsJournal/ChefsJournal/…`, `ChefsJournalApp`, `ChefsJournal.entitlements`, `ChefsJournalTests`, `project.yml` regenerated); UserDefaults keys → `chefsjournal_*`; notification names → `ChefsJournal*`. **⚠️ Needs the user's accounts:** (a) **Firebase** — add an iOS app (`com.aerion.chefsjournal`) to `chef-s-journal-6a0fd`, replace `ios/ChefsJournal/ChefsJournal/GoogleService-Info.plist` (still the original **amrosa-2ec82** file) and update **REVERSED_CLIENT_ID** in `project.yml` (annotated); (b) **Apple** — register the new App ID with **Associated Domains + Push Notifications** capabilities (new bundle id = new App Store Connect app, as planned), and upload the **APNs auth key** to the `chef-s-journal-6a0fd` Firebase project or push won't deliver on iOS. UIDs are preserved in the migration, so signing in restores all data. |
| **F16 — Edit mode redesign (jiggle + popups)** | ✅ **Ported (true in-place, zero-reflow).** The detail pencil flips `RecipeDetailContent` into edit mode while rendering the **identical body** — same `ScrollView`/`LazyVStack`, same items, same heights, same scaled/unit quantities — so **nothing on screen moves**: editable rows just gain an outline + jiggle + tap (`.editable`, `UI/Util/EditAffordance.swift`, uses `.overlay`+`.rotationEffect` which don't affect layout). Scroll position is preserved exactly. Add/convert/delete actions live both in the **edit toolbar** (`ellipsis.circle` menu: Recipe details / Add ingredient / Add step / Add section / Update unit conversions / Delete recipe) **and** as in-context **ghost "＋ Add" rows** with view-mode height reserved so adds don't shift content (Phase G). Mirrors Android's `toPreviewRecipe`: lightweight **display value types** (`RecipeDisplayModels.swift`: `DisplaySection`/`DisplayIngredient`/`DisplayStep`) that both the real `RecipeModel` (view) and the live draft (edit) map into via `RecipeDetailViewModel+Display.swift` (same scale+unit + same grouping/empty-section hiding in both modes, incl. a single-nameless-section sentinel so no-section recipes match exactly), so `IngredientRow`/`StepRow` are one component for both. Tapping a row/header/description opens its sheet (`EditSheetHost` in `RecipeEditSheets.swift`: Details/Section/Ingredient/Step via `EditTarget`, state seeded in `init`, opens at `.large`; add-Ingredient/Step sheets have a section picker). Edit state + ops live in `RecipeDetailViewModel+Edit.swift` (`enterEdit`/`cancelEdit`/`saveEdit`/`updateConversions`/`deleteRecipe`); save maps the draft via `updateFullRecipe` (also rewrites step→ingredient refs from `EditorStep.ingredientIds`), pushes, and re-publishes if shared. `RecipeEditorView` is retained for the import/freeform & new-variation entry points. **✅ Now fully aligned with Android** — iOS adopted in-context **ghost "＋ Add" rows** (Phase G), and has the later refinements: optional **chip row per section** (+ Account default), inline substitute **swap chips** + "Substitute for" picker (step refs resolve to the selected member), **per-recipe remembered selections**, quantity **ranges** (F15 `quantityValueMax`), and the scroll-stability bits (greyed chips, constant-height yield, reserved add-row height). |
| **F17 — Shared with specific people** | ✅ **Ported** — iOS has `sharedWith` on the recipe model, the `"shared"` tier in the visibility chooser + recipients sheet (`searchUsers`/`getUsers`), `setSharedWith`/add/remove + republish, and the direct-share path adding to `sharedWith`. The `sharedWith` ACL rules are shared infra. |
| **F18 — Notes = one cloud thread on every recipe** | ✅ **Ported.** One **"Notes"** section on every real recipe (`notesVisible = !needsReview`), backed by the top-level **`recipe_notes/{recipeId}`** collection (NOT the mirror) so notes are visibility-independent. `SharedRecipeService` repointed the comment methods to `recipe_notes/{id}/notes` + added `ensureNotesParent`/`setNotesLocked`/`getNotesLocked`. VM `loadNotes()` observes the thread for every recipe (not just shared), records ownership if owner, and loads the lock; `toggleNotesLock()` is owner-only (optimistic). `NotesSection` view: header + count + owner lock toggle; add-note input hidden + "🔒 locked" shown when locked; delete by note author or recipe owner. The old private on-device notes section + the `shared_recipes/{id}/comments` path are dropped (the `RecipeNoteModel`/`NoteRow` linger unused). Rules are shared infra (already in repo; deploy `firestore:rules`). |
| **Universal Links** | ✅ Wired — `Associated Domains` entitlement (`applinks:chef-s-journal-6a0fd.web.app`) + AASA at `hosting/public/.well-known/apple-app-site-association` (served as application/json). Pending `firebase deploy --only hosting`. |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |

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
| Project generation | xcodegen 2.45.4 (`project.yml` in `ios/ChefsJournal/`) |
| Bundle ID | `com.aerion.chefsjournal` |
| Firebase project | chef-s-journal-6a0fd |

### iOS File Structure

```
ios/ChefsJournal/
├── project.yml                         # xcodegen spec (run: xcodegen generate from ios/ChefsJournal/)
├── ChefsJournal.xcodeproj/                   # generated — do not edit manually
└── ChefsJournal/
    ├── ChefsJournalApp.swift                 # @main, Firebase.configure(), AppContainer init
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
| **F8 — Share button** | Top bar icon (owners only); if public → iOS share sheet with `https://chef-s-journal-6a0fd.web.app/shared/{id}`; if private → dialog → publish → share sheet |
| **F8 — SharedRecipeService** | `publish/unpublish`, live `sharedRecipesStream()`, `getSharedRecipeDetail`, `commentsStream`, `addComment`, `deleteComment`, `copyToMyRecipes` |
| **F8 — Shared tab** | `SharedRecipesView` + `SharedRecipesViewModel`; live Firestore stream; search; "Yours" badge; taps route to `SharedRecipeDetailView` |
| **F8 — Shared detail** | `SharedRecipeDetailView`; read-only detail: yield adjuster, unit toggle, ingredients, steps; "Copy to My Recipes" button; comments section |
| **F8 — Comments** | `SharedComment` model; post/delete in both owner view and visitor view; delete allowed for commenter OR recipe owner |
| **Deep links** | `chefsjournal://` custom URL scheme registered in project.yml; `https://chef-s-journal-6a0fd.web.app/shared/{id}` handled via `onOpenURL`; routes to `SharedRecipeDetailView` |
| **Version control** | `version` Int + `changeLog` JSON on every editor save |

#### 🔧 Remaining Gaps (vs Android)

> Full, detailed list is in **"iOS 🔶 Remaining Gaps (planned)"** earlier in this file — that table is authoritative. Recent Android-only additions to port are summarized here.

| Gap | Detail |
|---|---|
| **F12 — Visibility tiers + Co-Chef profiles** | `"friends"` tier in publish; 3-option visibility chooser; co-chef/public `ProfileView`. See the authoritative gaps table above. |
| ~~F15 — Ingredient quantity ranges~~ | ✅ **Ported** — iOS has `quantityValueMax` (+ metric/imperial) on its model, the min–max scaler, sync/import threading, and the editor range max. |
| **Collective step-ingredient cooking-mode fallback** | Mirror `augmentedStepRefs()`: attach any ingredient referenced by no step in its section to that section's first step, so collectively-referenced ingredients show in cooking mode. Import-side server net is shared backend. |
| **Universal Links** | ✅ Wired (entitlement + AASA file). Pending `firebase deploy --only hosting` to publish the AASA. |
| **Recipe images** | Firebase Storage not yet wired up (`imageUrl` field exists in schema) |

### iOS Notes for AI Coding Assistants

- **Swift version**: 5 (not 6). Strict concurrency is OFF — do not add `Sendable` or `actor` isolation unless explicitly needed.
- **@MainActor everywhere**: All ViewModels and Repositories are `@MainActor`. No `DispatchQueue.main.async` needed.
- **SwiftData is source of truth**: UI never reads Firestore directly (except `SharedRecipeDetailView` which reads from `SharedRecipeService` since shared recipes are Firestore-only).
- **xcodegen**: After adding/removing Swift files, run `xcodegen generate` from `ios/ChefsJournal/` to regenerate the Xcode project. Never edit `.xcodeproj` manually.
- **Auth gate**: `ContentView` uses `authStateStream()` AsyncStream. `isSignedIn = user != nil && !user.isAnonymous`. When false → `AuthView()` is root (no NavigationStack wrapper needed — just a plain view). When true → `MainAppView` (TabView).
- **Sign-out**: Always call `container.clearAllLocalData()` before `authRepository.signOut()`. The auth state stream in ContentView will automatically switch back to the auth gate.
- **SharedRecipe types**: `SharedRecipe`, `SharedSection`, `SharedIngredient`, `SharedStep`, `SharedComment` are Firestore-only value types in `Data/DTOs/SharedRecipe.swift`. Never stored in SwiftData.
- **Deep links**: `onOpenURL` in ContentView handles both `chefsjournal://shared/{id}` and `https://chef-s-journal-6a0fd.web.app/shared/{id}`. Sets `deepLinkRecipeId` binding which `SharedRecipesView` uses to navigate to `SharedRecipeDetailView`.
- **Anchor scaling math**: `scaleFactor = scaleAnchorQty / baseAnchorQty`. `adjustScale(delta: Int)` adds `delta × scaleStep` to `scaleAnchorQty` (min = 1 step). Long-press yield → `resetScale()`.
- **EditorSection/EditorIngredient/EditorStep**: Defined in `RecipeEditorViewModel.swift`. Used by both `RecipeEditorViewModel` and `RecipeRepository.updateFullRecipe`.
- **Firebase init crash prevention**: `@State private var appContainer: AppContainer` (type annotation only); initialize in `App.init()` body after `FirebaseApp.configure()` using `_appContainer = State(initialValue: AppContainer())`.
- **needsReview on iOS**: Same flow as Android — save immediately with `needsReview = true`, confirm later. `confirmReview(recipeId:)` clears flag.
- **Timestamp cross-platform sync**: iOS must write timestamps as `Int64` milliseconds to be compatible with Android's `Long` fields. Use `Int64(date.timeIntervalSince1970 * 1000)` when pushing to Firestore.

