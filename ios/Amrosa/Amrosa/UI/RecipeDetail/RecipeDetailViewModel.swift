import SwiftData
import Observation
import Foundation

/// One member of a recipe's variation family (the base or a variation).
struct VariantRef: Identifiable {
    let id: String
    let label: String
    let isCurrent: Bool
    let isBase: Bool
}

@Observable
@MainActor
final class RecipeDetailViewModel {
    var recipe: RecipeModel
    var selectedServings: Int
    var scaleAnchorQty: Double          // tracks anchor-based scale; same as base when not anchor-mode
    var unitMode: UnitMode = .original
    var checkedIngredientIds: Set<String> = []
    var selectedSubstitutes: [String: String] = [:]  // substituteGroupId → chosen ingredientId
    var enabledOptionals: Set<String> = []            // per-ingredient optional toggle (like Android)
    var newNoteText = ""
    var isEditingNote: RecipeNoteModel? = nil
    var editingNoteText = ""

    // Sharing / visibility
    var showShareSheet = false
    var shareURL: URL? = nil
    var showShareConfirmDialog = false
    var isPublishing = false

    // Direct sharing to followers
    var following: [UserProfile] = []
    var isFollowingLoading = false
    var shareSentToName: String? = nil
    private var followingTask: Task<Void, Never>? = nil

    // Comments (shown when recipe is public)
    var comments: [SharedComment] = []
    var newCommentText = ""
    var commentListenerTask: Task<Void, Never>? = nil

    // Variations (F10)
    static let maxVariants = 4
    static let maxVariantNameLen = 20
    var variants: [VariantRef] = []
    var canAddVariant = false
    /// Set to a freshly created variation's id so the view can open its editor.
    var createdVariantId: String? = nil

    // ── Inline edit mode (F16) ──────────────────────────────────────────────
    var isEditMode = false
    var editTarget: EditTarget? = nil
    var editError: String? = nil
    var isSavingEdit = false
    var isConverting = false
    var conversionMessage: String? = nil
    var isDeleting = false
    // Live draft (mirrors the read-only layout while editing)
    var editTitle = ""
    var editDescription = ""
    var editPrepTime = ""
    var editCookTime = ""
    var editBaseServings = "1"
    var editIsRangeYield = false
    var editServingsMin = ""
    var editServingsMax = ""
    var editTagsText = ""           // comma-separated
    var editSourceUrlsText = ""     // newline-separated
    var editIsPersonalAuthor = false
    var editIsVariant = false
    var editVariantName = ""
    var editSections: [EditorSection] = []
    var deletedSectionIds: Set<String> = []
    var deletedIngredientIds: Set<String> = []
    var deletedStepIds: Set<String> = []

    let repository: RecipeRepository
    let authRepository: AuthRepository
    let sharedRecipeService: SharedRecipeService
    private let socialRepository: SocialRepository
    let syncService: RecipeSyncService?
    let cloudFunctions: CloudFunctionsService?
    let userPreferences: UserPreferences?

    init(recipe: RecipeModel, repository: RecipeRepository, authRepository: AuthRepository, sharedRecipeService: SharedRecipeService, socialRepository: SocialRepository, syncService: RecipeSyncService? = nil, cloudFunctions: CloudFunctionsService? = nil, userPreferences: UserPreferences? = nil) {
        self.recipe = recipe
        self.repository = repository
        self.authRepository = authRepository
        self.sharedRecipeService = sharedRecipeService
        self.socialRepository = socialRepository
        self.syncService = syncService
        self.cloudFunctions = cloudFunctions
        self.userPreferences = userPreferences
        self.selectedServings = recipe.baseServings
        // Seed anchor quantity from the anchor ingredient's base value
        if let anchorId = recipe.scaleIngredientId,
           let anchor = recipe.ingredients.first(where: { $0.id == anchorId }),
           let baseValue = anchor.quantityValue {
            self.scaleAnchorQty = baseValue
        } else {
            self.scaleAnchorQty = 0
        }
        seedSelections()
    }

    /// Restore remembered substitute + optional choices for this recipe (validated against the
    /// current recipe so stale ids are ignored); falls back to the "include optionals" default.
    private func seedSelections() {
        let optionalIds = Set(recipe.ingredients.filter { $0.isOptional }.map { $0.id })

        // Substitutes: keep only groupId→ingredientId pairs whose ingredient still exists in that group.
        if let prefs = userPreferences {
            let saved = prefs.recipeSubstitutes(recipe.id)
            var valid: [String: String] = [:]
            for (gid, ingId) in saved where recipe.ingredients.contains(where: { $0.id == ingId && $0.substituteGroupId == gid }) {
                valid[gid] = ingId
            }
            selectedSubstitutes = valid

            if let savedOpts = prefs.recipeEnabledOptionals(recipe.id) {
                enabledOptionals = savedOpts.intersection(optionalIds)
            } else {
                enabledOptionals = prefs.includeOptionalsByDefault() ? optionalIds : []
            }
        } else {
            enabledOptionals = optionalIds
        }
    }

    // MARK: - Scaling

    var isAnchorBased: Bool {
        recipe.scaleIngredientId != nil
    }

    private var baseAnchorQty: Double? {
        guard let anchorId = recipe.scaleIngredientId,
              let anchor = recipe.ingredients.first(where: { $0.id == anchorId }) else { return nil }
        return anchor.quantityValue
    }

    var scaleFactor: Double {
        if let base = baseAnchorQty, base > 0 {
            return scaleAnchorQty / base
        }
        return Double(selectedServings) / Double(recipe.baseServings)
    }

    /// Display string for the current yield (accounting for anchor-based scaling).
    var yieldDisplay: String {
        if isAnchorBased,
           let anchorId = recipe.scaleIngredientId,
           let anchor = recipe.ingredients.first(where: { $0.id == anchorId }) {
            let scaled = QuantityScaler.scale(
                quantityValue: scaleAnchorQty,
                quantityUnit: anchor.quantityUnit,
                quantityDisplay: nil,
                scale: 1.0
            )
            return "\(scaled) \(anchor.name)"
        }
        // Servings range
        if let min = recipe.baseServingsMin, let max = recipe.baseServingsMax {
            let ratio = Double(selectedServings) / Double(recipe.baseServings)
            let scaledMin = Int(round(Double(min) * ratio))
            let scaledMax = Int(round(Double(max) * ratio))
            return "\(scaledMin)–\(scaledMax)"
        }
        return "\(selectedServings)"
    }

    /// True when the scale is at the recipe's base value (no adjustment made).
    var isDefaultScale: Bool {
        if let base = baseAnchorQty { return scaleAnchorQty == base }
        return selectedServings == recipe.baseServings
    }

    var canDecrement: Bool {
        if isAnchorBased {
            return scaleAnchorQty > recipe.scaleStep
        }
        return selectedServings > 1
    }

    func adjustScale(delta: Int) {
        if baseAnchorQty != nil {
            let step = recipe.scaleStep
            let newQty = scaleAnchorQty + Double(delta) * step
            scaleAnchorQty = max(step, newQty)
        } else {
            let newServings = selectedServings + delta
            if newServings >= 1 { selectedServings = newServings }
        }
    }

    func resetScale() {
        if let base = baseAnchorQty {
            scaleAnchorQty = base
        } else {
            selectedServings = recipe.baseServings
        }
    }

    var hasConversionData: Bool {
        recipe.ingredients.contains { $0.hasConversionData }
    }

    func scaledQuantity(for ingredient: IngredientModel) -> String {
        QuantityScaler.scale(ingredient: ingredient, scaleFactor: scaleFactor, unitMode: unitMode)
    }

    // MARK: - Ownership

    /// True when this is a received recipe (Tab 2) — read-only, "Remove" instead of edit/delete/share.
    var isReceived: Bool { recipe.isReceived }

    /// Owner = authored by me AND not a received reference (Recipe Ownership Model v2).
    var isOwner: Bool {
        guard let uid = authRepository.uid else { return false }
        if recipe.isReceived { return false }
        // Pre-auth recipes have no authorId — treat as owned by the signed-in user
        guard let authorId = recipe.authorId else { return true }
        return authorId == uid
    }

    /// Set true after a received recipe is removed → view navigates back.
    var removed: Bool = false

    /// Author label per v2 rule: "me" / "Imported by me" / "B" / "Imported by B".
    var authorLabel: String {
        let name: String
        if recipe.authorId == authRepository.uid {
            name = "me"
        } else {
            name = recipe.authorDisplayName ?? "Unknown"
        }
        return recipe.isImported ? "Imported by \(name)" : name
    }

    // MARK: - Sections / ingredients

    var sortedSections: [RecipeSectionModel] {
        recipe.sections.sorted { $0.orderIndex < $1.orderIndex }
    }

    func steps(for section: RecipeSectionModel?) -> [StepModel] {
        recipe.steps.filter { $0.section?.id == section?.id }.sorted { $0.orderIndex < $1.orderIndex }
    }

    /// Ingredients to show per step in cooking mode, with collectively-referenced
    /// ("add all the paste ingredients") orphans attached to their section's first step.
    /// Mirrors Android `augmentedStepRefs()`: any ingredient referenced by NO step in its
    /// section is attached to that section's first step (fallback: the recipe's first step),
    /// so it still surfaces in cooking mode. A no-op when refs are already complete.
    var cookingStepIngredients: [String: [IngredientModel]] {
        let steps = recipe.steps
        var result: [String: [IngredientModel]] = [:]
        for step in steps {
            result[step.id] = step.ingredientRefs.compactMap { $0.ingredient }
        }
        let referenced = Set(steps.flatMap { $0.ingredientRefs.compactMap { $0.ingredient?.id } })

        // Distinct section keys (section ids + ingredient sections, including nil).
        var seenKeys = Set<String>()
        var sectionKeys: [String?] = []
        for key in recipe.sections.map({ $0.id as String? }) + recipe.ingredients.map({ $0.section?.id }) {
            let token = key ?? "__nil__"
            if seenKeys.insert(token).inserted { sectionKeys.append(key) }
        }

        let globalFirstStepId = steps.min(by: { $0.orderIndex < $1.orderIndex })?.id
        for key in sectionKeys {
            let firstStepId = steps.filter { $0.section?.id == key }
                .min(by: { $0.orderIndex < $1.orderIndex })?.id ?? globalFirstStepId
            guard let firstStepId else { continue }
            let orphans = recipe.ingredients.filter { $0.section?.id == key && !referenced.contains($0.id) }
            if orphans.isEmpty { continue }
            var list = result[firstStepId] ?? []
            for ing in orphans where !list.contains(where: { $0.id == ing.id }) {
                list.append(ing)
            }
            result[firstStepId] = list
        }
        return result
    }

    /// Substitute groups — used to render "Options" selectors above ingredients.
    var substituteGroups: [(groupId: String, options: [IngredientModel])] {
        let grouped = Dictionary(grouping: recipe.ingredients.filter { $0.substituteGroupId != nil },
                                 by: { $0.substituteGroupId! })
        return grouped.map { (groupId: $0.key, options: $0.value.sorted { $0.orderIndex < $1.orderIndex }) }
    }

    /// Visible ingredients as a flat list — collapses substitute groups to the selected option and
    /// drops optionals the user hasn't included (they're added back via the per-section chip row).
    var visibleIngredients: [IngredientModel] {
        recipe.ingredients
            .sorted { $0.orderIndex < $1.orderIndex }
            .filter { ing in
                // Optional ingredients are opt-in: only show when included via the chip row.
                if ing.isOptional && !enabledOptionals.contains(ing.id) { return false }
                // Substitute group: show only the selected option
                if let gid = ing.substituteGroupId {
                    let groupMembers = recipe.ingredients.filter { $0.substituteGroupId == gid }
                    let selected = selectedSubstitutes[gid]
                    if let s = selected { return ing.id == s }
                    return groupMembers.sorted { $0.orderIndex < $1.orderIndex }.first?.id == ing.id
                }
                return true
            }
    }

    /// Optional ingredients per section id (incl. nil → "__other__"), for the chip rows. These show
    /// regardless of whether the optional is currently included, so you can always toggle them.
    func optionalChips(forSectionId sectionId: String?) -> [IngredientModel] {
        recipe.ingredients
            .filter { $0.isOptional && ($0.section?.id ?? "__other__") == (sectionId ?? "__other__") }
            .sorted { $0.orderIndex < $1.orderIndex }
    }

    struct IngredientGroup: Identifiable {
        let id = UUID()
        let label: String           // "" = ungrouped
        let ingredients: [IngredientModel]
    }
    struct IngredientSectionBlock: Identifiable {
        let id: String              // section id, or "__other__"
        let title: String?          // nil when only one section / no section headers needed
        let groups: [IngredientGroup]
    }

    /// Ingredient checklist grouped by SECTION (in step/section order), then by group label.
    /// Section-less ingredients fall into a trailing "Other" block. Matches Android's ordering.
    var ingredientSectionBlocks: [IngredientSectionBlock] {
        let visible = visibleIngredients
        let multiSection = sortedSections.count > 1
        let hasSectionless = visible.contains { $0.section == nil }

        func groups(_ ings: [IngredientModel]) -> [IngredientGroup] {
            guard !ings.isEmpty else { return [] }
            var result: [IngredientGroup] = []
            let ungrouped = ings.filter { ($0.groupLabel ?? "").isEmpty }
            if !ungrouped.isEmpty { result.append(IngredientGroup(label: "", ingredients: ungrouped)) }
            var seen = Set<String>()
            for ing in ings {
                guard let label = ing.groupLabel, !label.isEmpty, !seen.contains(label) else { continue }
                seen.insert(label)
                result.append(IngredientGroup(label: label, ingredients: ings.filter { $0.groupLabel == label }))
            }
            return result
        }

        var blocks: [IngredientSectionBlock] = []
        for section in sortedSections {
            let g = groups(visible.filter { $0.section?.id == section.id })
            if !g.isEmpty {
                blocks.append(IngredientSectionBlock(id: section.id,
                    title: (multiSection || hasSectionless) ? section.name : nil, groups: g))
            }
        }
        let otherGroups = groups(visible.filter { $0.section == nil })
        if !otherGroups.isEmpty {
            blocks.append(IngredientSectionBlock(id: "__other__",
                title: blocks.isEmpty ? nil : "Other", groups: otherGroups))
        }
        return blocks
    }

    func toggleOptional(_ ingredientId: String) {
        if enabledOptionals.contains(ingredientId) {
            enabledOptionals.remove(ingredientId)
        } else {
            enabledOptionals.insert(ingredientId)
        }
        userPreferences?.setRecipeEnabledOptionals(recipe.id, enabledOptionals)
    }

    func selectSubstitute(_ groupId: String, _ ingredientId: String) {
        selectedSubstitutes[groupId] = ingredientId
        userPreferences?.setRecipeSubstitutes(recipe.id, selectedSubstitutes)
    }

    // MARK: - Notes

    func addNote() {
        guard !newNoteText.isEmpty else { return }
        try? repository.addNote(to: recipe.id, content: newNoteText)
        newNoteText = ""
    }

    func saveEditingNote() {
        guard let note = isEditingNote else { return }
        try? repository.updateNote(note, content: editingNoteText)
        isEditingNote = nil
        editingNoteText = ""
    }

    func deleteNote(_ note: RecipeNoteModel) {
        try? repository.deleteNote(note)
    }

    var sortedNotes: [RecipeNoteModel] {
        recipe.notes.sorted { $0.createdAt < $1.createdAt }
    }

    // MARK: - Visibility & Sharing

    var isPublic: Bool { recipe.visibility == "public" }
    /// Published = visible to others (Co-Chefs or Public). Drives comments + direct-share gate.
    var isPublished: Bool { recipe.visibility != "private" }

    func handleShareTap() {
        if isPublic {
            openShareSheet()
        } else {
            showShareConfirmDialog = true
        }
    }

    func publishAndShare() {
        isPublishing = true
        Task {
            _ = await sharedRecipeService.publish(recipe)
            try? repository.updateVisibility(recipeId: recipe.id, visibility: "public")
            recipe.visibility = "public"    // update in-memory
            isPublishing = false
            startCommentListener()
            openShareSheet()
        }
    }

    /// F12: three tiers. "friends" and "public" both publish the mirror (with the real tier);
    /// "private" unpublishes it.
    func setVisibility(_ visibility: String) {
        Task {
            try? repository.updateVisibility(recipeId: recipe.id, visibility: visibility)
            recipe.visibility = visibility   // update in-memory so the chip reflects immediately
            if visibility == "private" {
                _ = await sharedRecipeService.unpublish(recipe.id)
                stopCommentListener()
                comments = []
            } else {
                _ = await sharedRecipeService.publish(recipe)
                startCommentListener()
            }
        }
    }

    // MARK: - Direct sharing to followers

    /// Starts (or re-starts) the following stream to populate the follower picker.
    func loadFollowing() {
        guard followingTask == nil else { return }
        isFollowingLoading = true
        followingTask = Task {
            for await profiles in socialRepository.friendsStream() {
                guard !Task.isCancelled else { break }
                following = profiles
                isFollowingLoading = false
            }
        }
    }

    /// Share this (already-public) recipe directly to a co-chef.
    func shareToFollower(uid: String, name: String) {
        Task { await shareToFollowerInternal(uid: uid, name: name) }
    }

    /// Make the recipe Public (publishes the canonical mirror), then share it.
    /// Used when the user confirms the "make public to share" prompt for a private recipe.
    /// F12: direct-share a private recipe → publish at the **Co-Chefs** tier (not Public).
    /// An already-public recipe is left Public. Used by the "share makes this visible to co-chefs" prompt.
    func makeSharableAndShareToFollower(uid: String, name: String) {
        Task {
            if recipe.visibility == "private" {
                try? repository.updateVisibility(recipeId: recipe.id, visibility: "friends")
                recipe.visibility = "friends"
                _ = await sharedRecipeService.publish(recipe)
                startCommentListener()
            }
            await shareToFollowerInternal(uid: uid, name: name)
        }
    }

    private func shareToFollowerInternal(uid: String, name: String) async {
        let success = await socialRepository.shareRecipeTo(recipientUid: uid, recipientName: name, recipe: recipe)
        if success { shareSentToName = name }
    }

    // MARK: - Received recipes (Tab 2)

    /// F13: record that this recipe was cooked (Cooking Mode "Done!").
    func markCooked() {
        try? repository.markCooked(recipeId: recipe.id)
    }

    /// Remove a received recipe: deletes the cloud reference + local cache.
    /// The author's canonical instance is untouched.
    func removeReceivedRecipe() {
        Task {
            await socialRepository.removeReceivedReference(recipeId: recipe.id)
            try? repository.removeReceivedRecipe(id: recipe.id)
            removed = true
        }
    }

    private func openShareSheet() {
        let urlString = "https://amrosa-2ec82.web.app/shared/\(recipe.id)"
        shareURL = URL(string: urlString)
        showShareSheet = true
    }

    // MARK: - Variations (F10)

    /// True once the underlying recipe no longer exists (deleted in the editor) →
    /// the detail screen should pop back to the list.
    var recipeDeleted = false

    /// Re-read on resume (e.g. returning from the editor) so edits show immediately,
    /// and detect deletion so the screen can navigate back to the list.
    func reload() {
        let stillExists = (try? repository.fetchRecipe(id: recipe.id)) ?? nil
        if stillExists == nil {
            recipeDeleted = true
            return
        }
        loadVariants()
    }

    /// Build the variation family (base + its variations) for the selector chips.
    func loadVariants() {
        let baseId = recipe.parentRecipeId ?? recipe.id
        let baseRecipe = recipe.parentRecipeId == nil ? recipe : try? repository.fetchRecipe(id: baseId)
        let variations = (try? repository.getVariants(parentId: baseId)) ?? []
        var refs: [VariantRef] = []
        if let base = baseRecipe {
            // Base chip uses a fixed short label (recipe titles can be long).
            refs.append(VariantRef(id: base.id, label: "Original", isCurrent: base.id == recipe.id, isBase: true))
        }
        for v in variations {
            let name = (v.variantName?.isEmpty == false) ? v.variantName! : "Variation"
            refs.append(VariantRef(id: v.id, label: name, isCurrent: v.id == recipe.id, isBase: false))
        }
        variants = refs
        canAddVariant = isOwner && variations.count < Self.maxVariants
    }

    /// Fetch a recipe model by id (for navigating between variation family members).
    func recipeModel(id: String) -> RecipeModel? {
        try? repository.fetchRecipe(id: id)
    }

    /// Create a new variation of the current recipe, then emit its id so the view opens the editor.
    func createVariant(name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let finalName = trimmed.isEmpty ? "Variation" : String(trimmed.prefix(Self.maxVariantNameLen))
        let newId = try? repository.duplicateAsVariant(
            sourceId: recipe.id,
            variantName: finalName,
            currentUid: authRepository.uid,
            displayName: authRepository.displayName ?? authRepository.email
        )
        if let newId = newId {
            createdVariantId = newId
            // Push the new variation to the cloud (it's a real personal recipe).
            if let model = try? repository.fetchRecipe(id: newId) {
                Task { await syncService?.pushPersonalRecipe(model) }
            }
        }
    }

    // MARK: - Comments

    func startCommentListener() {
        guard isPublic else { return }
        commentListenerTask?.cancel()
        commentListenerTask = Task {
            for await batch in sharedRecipeService.commentsStream(recipeId: recipe.id) {
                guard !Task.isCancelled else { break }
                comments = batch
            }
        }
    }

    func stopCommentListener() {
        commentListenerTask?.cancel()
        commentListenerTask = nil
    }

    func postComment() {
        guard !newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let text = newCommentText
        newCommentText = ""
        Task { await sharedRecipeService.addComment(recipeId: recipe.id, content: text) }
    }

    func deleteComment(_ comment: SharedComment) {
        Task { await sharedRecipeService.deleteComment(recipeId: recipe.id, commentId: comment.id) }
    }

    func canDeleteComment(_ comment: SharedComment) -> Bool {
        guard let uid = authRepository.uid else { return false }
        return comment.authorId == uid || recipe.authorId == uid
    }
}
