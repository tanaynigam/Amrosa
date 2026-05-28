import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class ReceivedRecipeViewModel {
    var recipe: SharedRecipe? = nil
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
        recipe = await socialRepository.getReceivedRecipe(shareId: shareId)
        if let r = recipe {
            selectedServings = r.baseServings
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

    func saveToMyRecipes() {
        guard let r = recipe, !isSaved, !isSaving else { return }
        isSaving = true
        Task {
            let success = await sharedRecipeService.copyToMyRecipes(r)
            isSaved = success
            isSaving = false
        }
    }

    var scaledServingsDisplay: String {
        guard let r = recipe else { return "\(selectedServings)" }
        if let min = r.baseServingsMin, let max = r.baseServingsMax {
            let ratio = Double(selectedServings) / Double(r.baseServings)
            let scaledMin = Int(round(Double(min) * ratio))
            let scaledMax = Int(round(Double(max) * ratio))
            return "\(scaledMin)–\(scaledMax)"
        }
        return "\(selectedServings)"
    }

    var sortedSections: [SharedSection] {
        recipe?.sections.sorted { $0.orderIndex < $1.orderIndex } ?? []
    }

    func ingredients(for section: SharedSection?) -> [SharedIngredient] {
        (recipe?.ingredients ?? [])
            .filter { $0.sectionId == section?.id }
            .sorted { $0.orderIndex < $1.orderIndex }
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
    @State private var viewModel: ReceivedRecipeViewModel?

    var body: some View {
        Group {
            if let vm = viewModel {
                if vm.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let recipe = vm.recipe {
                    ReceivedRecipeContent(viewModel: vm, recipe: recipe)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary)
                        Text("Recipe not found")
                            .foregroundStyle(.secondary)
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

// MARK: - Content

private struct ReceivedRecipeContent: View {
    @Bindable var viewModel: ReceivedRecipeViewModel
    let recipe: SharedRecipe

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Sender attribution
                if let sender = recipe.authorDisplayName {
                    Label("Shared by \(sender)", systemImage: "person.fill")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                }

                // Description
                if let desc = recipe.description {
                    Text(desc)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                }

                // Times
                HStack(spacing: 20) {
                    if let prep = recipe.prepTimeMinutes {
                        LabeledContent("Prep", value: prep.timeDisplayString)
                    }
                    if let cook = recipe.cookTimeMinutes {
                        LabeledContent("Cook", value: cook.timeDisplayString)
                    }
                    Spacer()
                }
                .font(.subheadline)
                .padding(.horizontal)

                // Yield adjuster
                HStack {
                    Text("Yield")
                        .font(.subheadline)
                    Spacer()
                    HStack(spacing: 16) {
                        Button {
                            viewModel.adjustServings(delta: -1)
                        } label: {
                            Image(systemName: "minus.circle.fill")
                                .font(.title2)
                        }
                        .buttonStyle(.plain)
                        .disabled(viewModel.selectedServings <= 1)

                        Text(viewModel.scaledServingsDisplay)
                            .font(.headline)
                            .frame(minWidth: 40)

                        Button {
                            viewModel.adjustServings(delta: 1)
                        } label: {
                            Image(systemName: "plus.circle.fill")
                                .font(.title2)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)

                // Sections
                ForEach(viewModel.sortedSections) { section in
                    ReceivedSectionBlock(section: section, viewModel: viewModel)
                }

                // Save button
                Button {
                    viewModel.saveToMyRecipes()
                } label: {
                    Group {
                        if viewModel.isSaving {
                            ProgressView()
                        } else if viewModel.isSaved {
                            Label("Saved to My Recipes", systemImage: "checkmark")
                        } else {
                            Label("Save to My Recipes", systemImage: "plus")
                        }
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .disabled(viewModel.isSaved || viewModel.isSaving)
                .padding(.horizontal)
                .padding(.bottom, 24)
            }
        }
    }
}

// MARK: - Section block

private struct ReceivedSectionBlock: View {
    let section: SharedSection
    @Bindable var viewModel: ReceivedRecipeViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(section.name)
                .font(.title3)
                .fontWeight(.semibold)
                .padding(.horizontal)
                .padding(.vertical, 8)

            let ings = viewModel.ingredients(for: section)
            if !ings.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Ingredients")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                        .padding(.bottom, 4)

                    ForEach(ings) { ing in
                        HStack(alignment: .top, spacing: 8) {
                            Text(viewModel.scaledQuantity(for: ing))
                                .fontWeight(.medium)
                            Text(ing.name)
                            if ing.isOptional {
                                Text("(optional)")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .font(.body)
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                    }
                }
                .padding(.bottom, 12)
            }

            let sectionSteps = viewModel.steps(for: section)
            if !sectionSteps.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Steps")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)

                    ForEach(Array(sectionSteps.enumerated()), id: \.element.id) { index, step in
                        HStack(alignment: .top, spacing: 14) {
                            ZStack {
                                Circle()
                                    .fill(Color.accentColor.opacity(0.15))
                                    .frame(width: 32, height: 32)
                                Text("\(index + 1)")
                                    .font(.headline)
                                    .foregroundStyle(Color.accentColor)
                            }
                            .padding(.top, 2)

                            Text(step.instruction)
                                .font(.body)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.bottom, 16)
            }
        }
    }
}
