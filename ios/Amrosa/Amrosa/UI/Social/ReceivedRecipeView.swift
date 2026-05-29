import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class ReceivedRecipeViewModel {
    var recipe: SharedRecipe? = nil
    var fromDisplayName: String = "Someone"   // who SENT it (distinct from recipe author)
    var isLoading: Bool = true
    var isSaved: Bool = false
    var isSaving: Bool = false
    var selectedServings: Int = 1
    var scaleFactor: Double = 1.0

    private let shareId: String
    private let socialRepository: SocialRepository
    private let sharedRecipeService: SharedRecipeService

    init(shareId: String, socialRepository: SocialRepository, sharedRecipeService: SharedRecipeService) {
        self.shareId = shareId
        self.socialRepository = socialRepository
        self.sharedRecipeService = sharedRecipeService
    }

    func load() async {
        isLoading = true
        if let data = await socialRepository.getReceivedRecipe(shareId: shareId) {
            recipe = data.recipe
            fromDisplayName = data.fromDisplayName
            selectedServings = data.recipe.baseServings
        }
        isLoading = false
    }

    func adjustServings(delta: Int) {
        guard let r = recipe else { return }
        let newServings = selectedServings + delta
        if newServings >= 1 {
            selectedServings = newServings
            scaleFactor = Double(newServings) / Double(r.baseServings)
        }
    }

    /// Saves a copy to local recipes. `onSaved` fires after a successful save so the
    /// caller can pop back to the Shared tab (mirrors the import-review flow).
    func saveRecipe(onSaved: @escaping () -> Void) {
        guard let r = recipe, !isSaved, !isSaving else { return }
        isSaving = true
        Task {
            let success = await sharedRecipeService.copyToMyRecipes(r)
            isSaved = success
            isSaving = false
            if success { onSaved() }
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
    let shareId: String
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ReceivedRecipeViewModel?

    var body: some View {
        Group {
            if let vm = viewModel {
                if vm.isLoading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let recipe = vm.recipe {
                    ReceivedRecipeContent(viewModel: vm, recipe: recipe, onSaved: { dismiss() })
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 40)).foregroundStyle(.secondary)
                        Text("Recipe not found").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(viewModel?.recipe?.title ?? "Recipe")
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if viewModel == nil {
                viewModel = ReceivedRecipeViewModel(
                    shareId: shareId,
                    socialRepository: container.socialRepository,
                    sharedRecipeService: container.sharedRecipeService
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

                // Save button — "Save Recipe", pops back to Shared tab after save
                Button {
                    viewModel.saveRecipe(onSaved: onSaved)
                } label: {
                    Group {
                        if viewModel.isSaving {
                            ProgressView()
                        } else {
                            Label("Save Recipe", systemImage: "plus")
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
