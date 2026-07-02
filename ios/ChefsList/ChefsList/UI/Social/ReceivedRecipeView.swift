import SwiftUI

/// How the review screen was entered (F12): a pending inbox share (consumes the pointer on save)
/// or a chef-profile recipe (resolves the mirror directly; nothing to consume).
enum ReviewSource {
    case pointer(shareId: String)
    case direct(recipeId: String, authorUid: String, authorName: String)
}

// MARK: - ViewModel

@Observable
@MainActor
final class ReceivedRecipeViewModel {
    var recipe: SharedRecipe? = nil
    var fromDisplayName: String = "Someone"   // who SENT it / the author (distinct from current user)
    var isLoading: Bool = true
    var isSaved: Bool = false
    var isSaving: Bool = false
    var errorMessage: String? = nil
    var selectedServings: Int = 1
    var scaleFactor: Double = 1.0
    // F13 Phase 2 — likes
    var likeState = SharedRecipeService.LikeState()
    var canLike: Bool = false

    private let source: ReviewSource
    private let socialRepository: SocialRepository
    private let sharedRecipeService: SharedRecipeService
    private let repository: RecipeRepository
    private let authRepository: AuthRepository
    // Resolved during load(): canonical recipeId + author for the save step.
    private var resolvedRecipeId: String?
    private var resolvedAuthorUid: String = ""
    private var likeTask: Task<Void, Never>?

    init(source: ReviewSource, socialRepository: SocialRepository, sharedRecipeService: SharedRecipeService, repository: RecipeRepository, authRepository: AuthRepository) {
        self.source = source
        self.socialRepository = socialRepository
        self.sharedRecipeService = sharedRecipeService
        self.repository = repository
        self.authRepository = authRepository
        self.canLike = authRepository.isSignedIn
    }

    func load() async {
        isLoading = true
        switch source {
        case .pointer(let shareId):
            // Pending inbox share → read pointer, then the live mirror.
            if let p = await socialRepository.getReceivedPointer(shareId: shareId) {
                fromDisplayName = p.fromDisplayName
                resolvedAuthorUid = p.authorUid
                resolvedRecipeId = p.recipeId
                recipe = await sharedRecipeService.getSharedRecipeDetail(recipeId: p.recipeId)
            } else {
                errorMessage = "This share is no longer available."
            }
        case .direct(let recipeId, let authorUid, let authorName):
            // Chef-profile recipe → resolve the mirror directly.
            fromDisplayName = authorName
            resolvedAuthorUid = authorUid
            resolvedRecipeId = recipeId
            recipe = await sharedRecipeService.getSharedRecipeDetail(recipeId: recipeId)
        }
        if recipe == nil && errorMessage == nil {
            errorMessage = "This recipe is no longer shared by its author."
        }
        if let r = recipe { selectedServings = r.baseServings }
        // Observe like/save counts for the resolved mirror.
        if let rid = resolvedRecipeId, likeTask == nil {
            likeTask = Task { [weak self] in
                guard let self else { return }
                for await ls in self.sharedRecipeService.likeStateStream(recipeId: rid) {
                    if Task.isCancelled { break }
                    self.likeState = ls
                }
            }
        }
        isLoading = false
    }

    func toggleLike() {
        guard canLike, let rid = resolvedRecipeId else { return }
        let nowLiked = !likeState.isLiked
        Task { await sharedRecipeService.setLiked(recipeId: rid, liked: nowLiked) }
    }

    func adjustServings(delta: Int) {
        guard let r = recipe else { return }
        let newServings = selectedServings + delta
        if newServings >= 1 {
            selectedServings = newServings
            scaleFactor = Double(newServings) / Double(r.baseServings)
        }
    }

    /// Save as a received reference (v2): write `received_recipes/{uid}/items/{recipeId}`,
    /// cache locally with isReceived=true, and — pointer mode only — consume the pending pointer.
    /// `onSaved` fires after success so the caller can pop back.
    func saveRecipe(onSaved: @escaping () -> Void) {
        guard let r = recipe, let recipeId = resolvedRecipeId, !isSaved, !isSaving else { return }
        isSaving = true
        Task {
            // Real author name: the mirror's name unless it's blank/"Imported" (legacy/iOS),
            // in which case the sender/author IS fromDisplayName.
            let mirrorName = r.authorDisplayName
            let realAuthorName: String
            if let m = mirrorName, !m.trimmingCharacters(in: .whitespaces).isEmpty, m.lowercased() != "imported" {
                realAuthorName = m
            } else {
                realAuthorName = fromDisplayName
            }
            await socialRepository.saveReceivedReference(
                recipeId: recipeId, authorUid: resolvedAuthorUid, authorName: realAuthorName
            )
            do {
                try repository.cacheReceivedRecipe(r, isImported: r.isImported, authorDisplayName: realAuthorName)
                if case .pointer(let shareId) = source {
                    await socialRepository.deleteReceivedPointer(shareId: shareId)
                }
                isSaved = true
                isSaving = false
                onSaved()
            } catch {
                errorMessage = "Couldn't save recipe: \(error.localizedDescription)"
                isSaving = false
            }
        }
    }

    var scaledServingsDisplay: String {
        guard let r = recipe else { return "\(selectedServings)" }
        if let min = r.baseServingsMin, let max = r.baseServingsMax {
            let ratio = Double(selectedServings) / Double(r.baseServings)
            return "\(Int(round(Double(min) * ratio)))–\(Int(round(Double(max) * ratio)))"
        }
        return "\(selectedServings)"
    }

    var sortedSections: [SharedSection] {
        recipe?.sections.sorted { $0.orderIndex < $1.orderIndex } ?? []
    }

    var allIngredients: [SharedIngredient] {
        (recipe?.ingredients ?? []).sorted { $0.orderIndex < $1.orderIndex }
    }

    func steps(for section: SharedSection?) -> [SharedStep] {
        (recipe?.steps ?? [])
            .filter { $0.sectionId == section?.id }
            .sorted { $0.orderIndex < $1.orderIndex }
    }

    func scaledQuantity(for ingredient: SharedIngredient) -> String {
        QuantityScaler.scaleShared(ingredient: ingredient, scaleFactor: scaleFactor, unitMode: .original)
    }
}

// MARK: - View

struct ReceivedRecipeView: View {
    let source: ReviewSource
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ReceivedRecipeViewModel?

    /// Inbox entry — a pending share pointer.
    init(shareId: String) { self.source = .pointer(shareId: shareId) }
    /// Chef-profile entry — resolve the mirror directly.
    init(source: ReviewSource) { self.source = source }

    private var isDirect: Bool { if case .direct = source { return true }; return false }

    var body: some View {
        Group {
            if let vm = viewModel {
                if vm.isLoading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let recipe = vm.recipe {
                    ReceivedRecipeContent(viewModel: vm, recipe: recipe, onSaved: { dismiss() },
                                          saveLabel: isDirect ? "Add to Shared tab" : "Save Recipe")
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 40)).foregroundStyle(.secondary)
                        Text(vm.errorMessage ?? "Recipe not found").foregroundStyle(.secondary)
                            .multilineTextAlignment(.center).padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(viewModel?.recipe?.title ?? "Recipe")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if let vm = viewModel, vm.recipe != nil {
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 4) {
                        if vm.likeState.likeCount > 0 {
                            Text("\(vm.likeState.likeCount)")
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        Button { vm.toggleLike() } label: {
                            Image(systemName: vm.likeState.isLiked ? "heart.fill" : "heart")
                                .foregroundStyle(vm.likeState.isLiked ? Color.red : Color.accentColor)
                        }
                        .disabled(!vm.canLike)
                    }
                }
            }
        }
        .onAppear {
            if viewModel == nil {
                viewModel = ReceivedRecipeViewModel(
                    source: source,
                    socialRepository: container.socialRepository,
                    sharedRecipeService: container.sharedRecipeService,
                    repository: container.recipeRepository,
                    authRepository: container.authRepository
                )
                Task { await viewModel?.load() }
            }
        }
    }
}

// MARK: - Content (flat layout matching RecipeDetailView)

private struct ReceivedRecipeContent: View {
    @Bindable var viewModel: ReceivedRecipeViewModel
    let recipe: SharedRecipe
    let onSaved: () -> Void
    var saveLabel: String = "Save Recipe"

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {

                // Shared-by banner — shows the SENDER (not the original author)
                Label("Shared by \(viewModel.fromDisplayName)", systemImage: "paperplane.fill")
                    .font(.subheadline)
                    .foregroundStyle(Color.accentColor)
                    .padding(.horizontal, 16).padding(.top, 12)

                // Original author (if different from sender)
                if let author = recipe.authorDisplayName, author != viewModel.fromDisplayName {
                    Label(author, systemImage: "person")
                        .font(.caption).foregroundStyle(.secondary)
                        .padding(.horizontal, 16).padding(.top, 4)
                }

                if let desc = recipe.description {
                    Text(desc).font(.body).foregroundStyle(.secondary)
                        .padding(.horizontal, 16).padding(.top, 4)
                }

                // Time + Yield
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        if let prep = recipe.prepTimeMinutes {
                            Text("Prep  \(prep.timeDisplayString)").font(.footnote).foregroundStyle(.secondary)
                        }
                        if let cook = recipe.cookTimeMinutes {
                            Text("Cook  \(cook.timeDisplayString)").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    HStack(spacing: 4) {
                        Text("Yield").font(.footnote).foregroundStyle(.secondary)
                        Button { viewModel.adjustServings(delta: -1) } label: {
                            Image(systemName: "minus").frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain).disabled(viewModel.selectedServings <= 1)
                        Text(viewModel.scaledServingsDisplay).font(.title3).fontWeight(.semibold).frame(minWidth: 40)
                        Button { viewModel.adjustServings(delta: 1) } label: {
                            Image(systemName: "plus").frame(width: 32, height: 32)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
                Divider().padding(.horizontal, 16)

                // Sources
                if !recipe.sourceUrls.isEmpty {
                    Text("Sources").font(.title2).fontWeight(.semibold)
                        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                    ForEach(recipe.sourceUrls, id: \.self) { url in
                        if let link = URL(string: url) {
                            Link("• \(url)", destination: link)
                                .font(.footnote)
                                .padding(.horizontal, 16).padding(.vertical, 2)
                        }
                    }
                    Divider().padding(.horizontal, 16).padding(.vertical, 8)
                }

                // Ingredients (flat)
                Text("Ingredients").font(.title2).fontWeight(.semibold)
                    .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                ForEach(viewModel.allIngredients) { ing in
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "circle").foregroundStyle(.secondary).font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                Text(viewModel.scaledQuantity(for: ing)).fontWeight(.medium)
                                Text(ing.name)
                            }
                            .font(.body)
                            if ing.isOptional {
                                Text("Optional").font(.caption).foregroundStyle(.tertiary)
                            }
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 8)

                // Instructions
                Text("Instructions").font(.title2).fontWeight(.semibold)
                    .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 4)

                let sections = viewModel.sortedSections
                if sections.isEmpty {
                    let steps = viewModel.steps(for: nil)
                    ForEach(Array(steps.enumerated()), id: \.element.id) { idx, step in
                        ReceivedStepRow(instruction: step.instruction, index: idx + 1)
                    }
                } else {
                    ForEach(sections) { section in
                        Text(section.name).font(.title3).fontWeight(.semibold)
                            .padding(.horizontal, 16).padding(.vertical, 6)
                        let steps = viewModel.steps(for: section)
                        ForEach(Array(steps.enumerated()), id: \.element.id) { idx, step in
                            ReceivedStepRow(instruction: step.instruction, index: idx + 1)
                        }
                    }
                    let unsectioned = viewModel.steps(for: nil)
                    ForEach(Array(unsectioned.enumerated()), id: \.element.id) { idx, step in
                        ReceivedStepRow(instruction: step.instruction, index: idx + 1)
                    }
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 8)

                // Save button — saves a received reference, pops back after save
                Button {
                    viewModel.saveRecipe(onSaved: onSaved)
                } label: {
                    Group {
                        if viewModel.isSaving {
                            ProgressView()
                        } else {
                            Label(saveLabel, systemImage: "plus")
                        }
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isSaved || viewModel.isSaving)
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
    }
}

private struct ReceivedStepRow: View {
    let instruction: String
    let index: Int

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 32, height: 32)
                Text("\(index)").font(.headline).foregroundStyle(Color.accentColor)
            }
            .padding(.top, 2)
            Text(instruction).font(.body).fixedSize(horizontal: false, vertical: true)
            Spacer()
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}
