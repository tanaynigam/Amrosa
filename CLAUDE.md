# Amrosa — Project Specification
### by Aerion

> **Amrosa** is the app name. Upon launch, the user is greeted with the heading **"Amrita & Ambrosia"** — a fusion of the Sanskrit and Greek words for divine, immortal sustenance. This is the guiding identity of the app: recipes that are exquisite, elaborate, and deeply personal.

---

## Project Overview

| Field | Detail |
|---|---|
| **App Name** | Amrosa |
| **Company** | Aerion |
| **Platform** | Android (Android Studio) |
| **Language** | Kotlin |
| **Min SDK** | API 26 (Android 8.0+) |
| **Architecture** | MVVM + Repository Pattern |
| **Database (local)** | Room (SQLite) |
| **Database (cloud)** | Firebase Firestore |
| **Cloud Storage** | Firebase Storage (for images) |
| **Recipe Parsing** | Cloud Function or backend service for URL scraping |

---

## Design Philosophy

- **Lightweight first.** No bloat. No unnecessary screens, animations, or dependencies.
- **Offline-capable.** The app must be fully functional without an internet connection. Cloud sync is secondary, not required.
- **Cooking-mode friendly.** Large text, clear layout, minimal taps to get to what you need. Think: hands covered in flour, glancing at the screen.
- **Reliable over flashy.** Smooth, consistent, crash-free. Every interaction should feel instant.
- **No authentication.** The app is personal and device-local by default. Cloud sync is tied to a device ID or anonymous Firebase session — no login required.

---

## Core Features

### 1. Recipe Storage (Primary)
- Store recipes locally using **Room Database**
- Each recipe contains:
  - `id` (UUID)
  - `title` (String)
  - `source_urls` (List<String>) — all source links kept together, not per section
  - `description` (String, nullable)
  - `sections` (List<RecipeSection>) — sub-recipe blocks (e.g. "Dough", "Marinara", "Final Pizza")
  - `ingredients` (List<Ingredient>)
  - `steps` (List<Step>)
  - `tags` (List<String>) — e.g. "Indian", "pasta", "weeknight"; used for category filtering
  - `base_servings` (Int) — reference yield for all scaling math
  - `base_servings_min` (Int, nullable) — low end of yield range (e.g. 15 for cookies)
  - `base_servings_max` (Int, nullable) — high end of yield range (e.g. 20 for cookies)
  - `scale_ingredient_id` (String, nullable) — anchor ingredient for scaling (e.g. flour); null = servings-based scaling
  - `scale_step` (Double, default 1.0) — increment per +/− tap (e.g. 0.25 cup of flour)
  - `prep_time_minutes` (Int, nullable)
  - `cook_time_minutes` (Int, nullable)
  - `image_url` (String, nullable)
  - `is_customized` (Boolean) — flags user-modified recipes
  - `created_at` (Long timestamp)
  - `updated_at` (Long timestamp)
  - `synced_at` (Long timestamp, nullable) — last cloud sync time

#### Scaling Modes
- **Servings-based** (default): all quantities scale proportionally to `selectedServings / baseServings`. Used for most recipes (pizza, butter chicken, malai kofta).
- **Anchor-ingredient-based**: scaling is driven by a specific ingredient's quantity (e.g. All Purpose Flour for cookies). The yield display shows a range (e.g. "15–20") and the +/− buttons adjust the anchor ingredient by `scaleStep` increments. All other ingredients scale proportionally to the anchor's ratio. Used for baking recipes where flour is the natural driver.

#### Ingredient model extensions
Each `Ingredient` carries:
  - `group_label` (String, nullable) — display grouping within checklist (e.g. "Wet Ingredients", "Marinara Specific", "Optional Toppings")
  - `is_optional` (Boolean)
  - `substitute_group_id` (String, nullable) — ingredients sharing a group ID are mutually exclusive alternatives; null means no substitute
  - `substitute_ratio` (Float, default 1.0) — quantity multiplier relative to the base ingredient in the group (e.g. Fresh Yeast = 2.0× Active Dry Yeast)

#### Step-ingredient references
- `StepIngredientRef` join entity: `step_id`, `ingredient_id`, quantity used specifically in that step
- Enables inline quantity display within step text, scaling-aware and substitute-aware

#### Recipe sections
- `RecipeSection`: `id`, `recipe_id`, `name` (String), `order_index` (Int)
- Each `Step` and `Ingredient` belongs to a section via `section_id`
- Section jump chips appear at the top of the detail view for multi-section recipes, allowing users to tap and auto-scroll directly to any section (e.g. "Pizza Dough", "Marinara Sauce", "Final Pizza")

### 2. Cloud Sync (Firestore)
- Use **Firebase Firestore** as the cloud source of truth for "official" recipes
- Sync strategy: **pull-only delta sync**
  - Room DB is always the read source for the UI
  - On app open with connectivity: pull recipes from Firestore where `updatedAt > lastSyncTimestamp`
  - Local seed (DatabaseSeeder) runs as a fallback if the DB is empty
  - Sync timestamp stored in SharedPreferences (`amrosa_sync`)
- Firestore document structure: flat document with inline arrays for sections, ingredients, steps, and stepIngredientRefs
- Document IDs: `recipe-001`, `recipe-002`, etc.
- Future: users will be able to add their own recipes to their own accounts (per-user subcollection)
- `RecipeSyncService.kt` handles parsing Firestore documents into Room entities
- Upload script: `firestore/upload-recipes.js` (Node.js + firebase-admin) reads `firestore/seed-recipes.json` and pushes to the `recipes` collection

### 3. URL Recipe Import

User pastes a recipe URL → Cloud Function fetches the page, sends the HTML to Claude API, receives structured recipe JSON → app displays a review screen → user saves to Room.

#### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Android App                                │
│                                                                    │
│  Import Screen                                                     │
│  ├── Paste URL text field                                          │
│  ├── "Import" button → POST to Cloud Function                     │
│  ├── Loading spinner while waiting                                 │
│  └── Review Screen (pre-filled recipe editor)                      │
│       ├── Title, description, yield, prep/cook time                │
│       ├── Sections with ingredients and steps                      │
│       ├── User can edit anything before saving                     │
│       └── "Save" → insert into Room (isCustomized = true)          │
└──────────────────────┬───────────────────────────────────────────┘
                       │ HTTPS POST { url: "..." }
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│               Firebase Cloud Function (Node.js)                    │
│               `parseRecipeUrl`                                     │
│                                                                    │
│  1. Fetch the URL HTML (axios/node-fetch, follow redirects)        │
│  2. Strip scripts/styles, extract <body> text content              │
│  3. Send cleaned HTML + system prompt to Claude API                │
│     (Messages API, model: claude-sonnet-4-20250514)                   │
│  4. Claude returns structured JSON matching Amrosa schema          │
│  5. Cloud Function validates required fields, returns to app       │
└──────────────────────────────────────────────────────────────────┘
```

#### Claude API prompt strategy

The Cloud Function sends a single message to Claude with:
- **System prompt**: "You are a recipe parser. Given the HTML of a recipe page, extract the recipe into the exact JSON schema provided. Return ONLY valid JSON, no markdown."
- **Schema**: the exact Amrosa recipe structure (sections, ingredients with quantityValue/quantityUnit/quantityDisplay/groupLabel/isOptional, steps with orderIndex, stepIngredientRefs linking steps to ingredients)
- **User message**: the cleaned HTML content

The schema given to Claude must match the Firestore document structure so the app can parse it with the same `RecipeSyncService` logic.

#### Key design decisions

1. **Cloud Function, not on-device** — keeps the Anthropic API key server-side, avoids bundling it in the APK. Also avoids fetching arbitrary URLs from the user's device (CORS, captchas, etc.).
2. **Claude over JSON-LD parsing** — most recipe sites use JSON-LD `Recipe` schema, but the format varies wildly and many sites don't have it. Claude handles arbitrary HTML reliably and can infer sections, group labels, optional ingredients, and step-ingredient references that JSON-LD never provides.
3. **Review before save** — never auto-save parsed recipes. The user always sees what was parsed and can edit title, fix quantities, add/remove sections, adjust yield before committing.
4. **`isCustomized = true`** — all imported recipes are flagged as user-customized. The original URL is stored in `sourceUrls`.
5. **User-local only (for now)** — imported recipes go into Room only, not the shared Firestore `recipes` collection. Future: per-user Firestore subcollection.
6. **No image scraping** — image URL from the page can be stored in `imageUrl` but actual image download/caching is deferred.

#### Cloud Function implementation plan

```
functions/
├── index.js              # Cloud Function entry point
├── parseRecipe.js        # URL fetch + Claude API call + response validation
├── recipeSchema.js       # Amrosa JSON schema definition (for Claude prompt)
├── package.json          # firebase-functions, axios, @anthropic-ai/sdk
└── .env                  # ANTHROPIC_API_KEY (set via firebase functions:secrets)
```

- **Runtime**: Node.js 20 (Firebase Functions v2)
- **Trigger**: HTTPS callable (`onCall`) — Firebase SDK handles auth headers automatically
- **Timeout**: 60 seconds (HTML fetch + Claude API can be slow)
- **Memory**: 512MB (sufficient for HTML processing)
- **Secrets**: `ANTHROPIC_API_KEY` stored via Firebase secrets manager (`firebase functions:secrets:set ANTHROPIC_API_KEY`)

#### App-side implementation plan

- New route: `"import"` in `AmrosaNavGraph`
- `ImportScreen.kt` — URL input + import button + loading state
- `ImportReviewScreen.kt` — pre-filled recipe fields, edit before save
- `ImportViewModel.kt` — calls Cloud Function via Firebase Functions SDK, holds parsed recipe state
- Firebase Functions SDK dependency added to `app/build.gradle.kts`

#### Fallback / error handling

- **Paywall / login-gated pages**: Claude returns what it can; if the HTML has no recipe content, return an error message to the user ("Could not find a recipe on this page")
- **Rate limiting**: Cloud Function can enforce per-device rate limits (e.g. 10 imports/day) using the device's Firebase anonymous auth UID
- **Malformed response**: if Claude's JSON doesn't validate against the schema, retry once with a correction prompt; if still invalid, return error
- **Network errors**: app shows a clear error message with retry option

---

## Current Recipes

| ID | Title | Yield | Scaling | Sections |
|---|---|---|---|---|
| `recipe-001` | Brown Butter Chocolate Chip Cookies | 15–20 (range) | Flour-based, ¼ cup steps | Cookie Dough |
| `recipe-002` | Neopolitan Pizza | 8 | Servings ± 1 | Pizza Dough, Marinara Sauce, Béchamel Sauce, Final Pizza |
| `recipe-003` | Butter Chicken | 6 | Servings ± 1 | Tandoori Chicken, Makhani Gravy Base, Final Cooking |
| `recipe-004` | Malai Kofta | 4–5 | Servings ± 1 | Kofta Balls, Kofta Filling, Gravy |

All recipe data originates from `Food Recipes.xlsx` in the project root. The DatabaseSeeder translates this spreadsheet into Room entities exactly — ingredient names, quantities, step wording, and structure should match the xlsx.

---

## Screen Map

```
Splash / Launch Screen
  └── "Amrita & Ambrosia" heading with Aerion branding

Home Screen
  ├── Search bar (local, instant search across title, tags, ingredients)
  ├── Category filter chips (Main Course, Dessert, Appetizer, etc.)
  └── Recipe card list (title, category, cook time, thumbnail)

Recipe Detail Screen
  ├── Header: title, all source links (tappable, grouped together), prep/cook time
  ├── Yield adjuster (+ / −) — scales all quantities proportionally throughout
  │    └── Reset button appears when yield != default
  ├── Section jump chips (horizontal scrollable row) — taps auto-scroll to section headers
  ├── Substitute selectors — radio/toggle per substitute group (e.g. Dark Chocolate vs Milk Chocolate)
  │    └── Selecting a substitute updates ingredient quantities AND inline step text
  ├── Optional ingredient toggles — switch on/off optional items; greys out in checklist
  │    and removes inline quantity from steps
  ├── Ingredient Checklist — grouped by label, checkboxes, scaled quantities, substitutes applied
  ├── Recipe Sections — e.g. "Pizza Dough", "Marinara Sauce", "Béchamel Sauce", "Final Pizza"
  │    └── Each section shows its steps with inline quantities for that step's ingredients
  ├── Notes & Comments — timestamped user notes, editable in-place
  ├── Cooking Mode button — launches fullscreen step-by-step view; keeps screen on automatically
  └── Edit button → Recipe Editor Screen (future)

Cooking Mode (fullscreen, launched from Recipe Detail)
  ├── One step at a time, very large text
  ├── Section label shown above each step
  ├── Relevant ingredients + quantities for that step shown in a card below the instruction
  ├── Previous / Next buttons to navigate
  ├── Screen stays on automatically (keepScreenOn)
  └── Exit button returns to Recipe Detail

Recipe Editor Screen (future)
  ├── Edit all fields inline (title, times, yield, tags, source links)
  ├── Add/remove/reorder sections, ingredients, and steps
  ├── Mark ingredients as optional, assign substitute groups and ratios
  ├── Link ingredients to steps (for inline quantity display)
  └── Save → writes to Room + queues Firestore sync

Add Recipe Screen (future)
  ├── Option A: Import from URL (paste link → Cloud Function + Claude API → review → save)
  └── Option B: Manual entry (blank editor form)

Settings Screen (gear icon on home screen)
  ├── Cloud Sync — last synced timestamp, local recipe count, force sync button
  ├── Database — DB version, storage info
  └── About — app name, version, Aerion credit
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose |
| Navigation | Jetpack Navigation Component |
| Local DB | Room + SQLite |
| Cloud DB | Firebase Firestore |
| Image Storage | Firebase Storage |
| Background Sync | On-launch coroutine (AmrosaApplication) |
| URL Parsing | Firebase Cloud Function v2 + Claude API (planned) |
| Image Loading | Coil |
| DI | Manual (AppContainer) |
| State Management | ViewModel + StateFlow |
| JSON | Gson |

---

## Data Model (Room Entities)

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val sourceUrls: String,                 // JSON string of List<String>
    val baseServings: Int,
    val baseServingsMin: Int?,              // yield range low end (nullable)
    val baseServingsMax: Int?,              // yield range high end (nullable)
    val scaleIngredientId: String?,         // anchor ingredient for scaling (nullable)
    val scaleStep: Double = 1.0,            // increment per +/− tap
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val imageUrl: String?,
    val tags: String,                       // JSON string of List<String>
    val isCustomized: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "recipe_sections")
data class RecipeSectionEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val name: String,
    val orderIndex: Int
)

@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val name: String,
    val quantityValue: Double?,             // numeric quantity at base yield
    val quantityUnit: String?,
    val quantityDisplay: String?,           // display string (e.g. "2¼ cup")
    val groupLabel: String?,
    val isOptional: Boolean = false,
    val substituteGroupId: String?,
    val substituteRatio: Float = 1.0f,
    val orderIndex: Int
)

@Entity(tableName = "steps")
data class StepEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val instruction: String,
    val orderIndex: Int
)

@Entity(tableName = "step_ingredient_refs", primaryKeys = ["stepId", "ingredientId"])
data class StepIngredientRefEntity(
    val stepId: String,
    val ingredientId: String,
    val quantityDisplay: String?
)

@Entity(tableName = "recipe_notes")
data class RecipeNoteEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)
```

---

## Folder Structure

```
app/
├── data/
│   ├── local/          # Room DB, DAOs, entities, Converters
│   ├── remote/         # RecipeSyncService (Firestore → Room)
│   └── repository/     # RecipeRepository (single source of truth)
├── domain/
│   └── model/          # Clean domain models (Recipe, Ingredient, Step, etc.)
├── ui/
│   ├── home/           # HomeScreen (recipe list, settings gear icon)
│   ├── detail/         # RecipeDetailScreen, CookingModeScreen, RecipeDetailViewModel
│   ├── settings/       # SettingsScreen (sync status, force sync, about)
│   ├── import/         # (planned) ImportScreen, ImportReviewScreen, ImportViewModel
│   └── util/           # QuantityScaler
├── navigation/         # AmrosaNavGraph (routes: home, recipe/{id}, settings)
├── di/                 # AppContainer (manual DI)
└── AmrosaApplication.kt
firestore/
├── seed-recipes.json   # All recipes as Firestore-ready JSON
├── upload-recipes.js   # Node.js upload script (firebase-admin)
└── serviceAccountKey.json  # (gitignored) Firebase service account key
functions/              # (planned) Firebase Cloud Functions
├── index.js            # Cloud Function entry points
├── parseRecipe.js      # URL fetch + Claude API parsing
├── recipeSchema.js     # Amrosa JSON schema for Claude prompt
└── package.json        # firebase-functions, axios, @anthropic-ai/sdk
```

---

## Current Status

### Done
- Room database with 4 seeded recipes (cookies, pizza, butter chicken, malai kofta)
- Recipe detail screen with yield scaling, ingredient checklist, step-ingredient refs, substitute selectors, optional toggles
- Section jump chips for multi-section recipes (auto-scroll to section headers)
- Cooking mode (fullscreen step-by-step with ingredient cards)
- Notes system (add/delete timestamped notes per recipe)
- Firebase project connected (`google-services.json` in `app/`, project: `amrosa-2ec82`)
- Firestore database live with all 4 recipes uploaded (`recipes` collection, test mode rules)
- `RecipeSyncService` with delta sync (pull recipes where `updatedAt > lastSyncTimestamp`)
- Firestore upload toolchain: `firestore/upload-recipes.js` + `seed-recipes.json` + `firebase-admin`
- `seed-recipes.json` fully synced with DatabaseSeeder (all 4 recipes, ingredient counts, step text, refs match exactly)
- `AmrosaApplication` calls `syncService.sync()` on launch after seeding
- Settings screen with sync status, force sync, DB version, about section (gear icon on home screen)
- URL recipe import architecture planned (Cloud Function + Claude API, see section 3 above)

### Pending
- **URL recipe import implementation** — Cloud Function (`parseRecipeUrl`), Claude API integration, import/review screens in app. Requires: Firebase Blaze plan, Anthropic API key, `firebase-functions` SDK
- **Firestore security rules** — currently in test mode (open read/write); need to lock down to read-only for `recipes` collection before production
- **Recipe editor screen** — edit all fields inline, save to Room + queue Firestore sync
- **Recipe images** — Firebase Storage integration for recipe photos (currently all `imageUrl` are null)
- **Search & filtering** — home screen search bar and category filter chips (UI exists but not fully wired)

### Implementation order (suggested)
1. URL recipe import (Cloud Function + app screens)
2. Recipe editor screen (needed for editing imported recipes)
3. Firestore security rules (before any public release)
4. Recipe images
5. Search & filtering polish

---

## UI/UX Guidelines

- **Typography:** Large, legible fonts in recipe detail view — minimum 16sp for body, 20sp+ for steps
- **Color palette:** Warm, earthy tones — cream, terracotta, deep green, muted gold. Reflects the "exquisite but grounded" identity
- **Dark mode:** Supported from the start
- **No ads. No onboarding flows. No permission requests beyond storage.**
- **Cooking Mode** button in recipe detail launches fullscreen step-by-step view and keeps screen on — no separate toggle
- **Ingredient checklist as shopping list** — the grouped checklist view doubles as a shopping mode; checkboxes persist per session
- **Section jumps** — horizontal chip row at top of detail view for quick navigation in multi-section recipes

---

## Out of Scope (for now)

- User accounts / multi-user support — **planned later**: users will eventually add their own recipes to per-user Firestore subcollections
- Social sharing or community features
- Meal planning or calendar integration
- Nutritional information
- Voice input or hands-free mode
- iOS version

---

## Notes for Claude (AI Coding Assistant)

- Always prefer **Kotlin idiomatic code** (data classes, sealed classes, extension functions)
- Use **Jetpack Compose** for all UI — no XML layouts
- All DB operations must run on **IO dispatcher**, never on Main
- Treat Room as the **single source of truth** for UI — Firestore only feeds into Room
- Keep dependencies minimal — no library should be added without clear justification
- **Scaling math**: two modes — servings-based (`baseQuantity × selectedServings / baseServings`) and anchor-based (`baseQuantity × anchorQty / baseAnchorQty`). The recipe's `scaleIngredientId` determines which mode is used
- **Substitute resolution**: when a substitute is selected, use its `substituteRatio` to adjust quantity. Display the substitute ingredient name in step inline text instead of the base ingredient
- **StepIngredientRef quantities** scale the same way as ingredient quantities — always derive from base and multiply, never store scaled values
- **Notes** are append-friendly but also editable; each `RecipeNoteEntity` is independently timestamped
- **Recipe data must match the xlsx** — `Food Recipes.xlsx` is the canonical source for all recipe content. Ingredient names, quantities, step wordings, and structure should be faithfully translated from the spreadsheet
- **Database versioning**: uses `fallbackToDestructiveMigration()`. Bump `AmrosaDatabase.version` and `DatabaseSeeder` seed key when changing recipe data
