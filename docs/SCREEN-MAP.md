# Screen Map

> Split out of CLAUDE.md. Read when adding/changing screens or navigation.

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

