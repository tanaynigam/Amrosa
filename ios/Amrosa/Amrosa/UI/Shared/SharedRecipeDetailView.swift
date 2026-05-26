import SwiftUI

// MARK: - ViewModel

@Observable
@MainActor
final class SharedRecipeDetailViewModel {
    var recipe: SharedRecipe? = nil
    var isLoading = true
    var selectedServings: Int = 1
    var unitMode: UnitMode = .original
    var comments: [SharedComment] = []
    var newCommentText = ""
    var isCopying = false
    var isCopied = false
    var errorMessage: String? = nil

    private let recipeId: String
    private let service: SharedRecipeService
    private let authRepository: AuthRepository

    var currentUid: String? { authRepository.uid }

    var scaleFactor: Double {
        guard let recipe = recipe, recipe.baseServings > 0 else { return 1.0 }
        return Double(selectedServings) / Double(recipe.baseServings)
    }

    var yieldDisplay: String {
        guard let recipe = recipe else { return "\(selectedServings)" }
        if let min = recipe.baseServingsMin, let max = recipe.baseServingsMax {
            let ratio = Double(selectedServings) / Double(recipe.baseServings)
            return "\(Int(round(Double(min) * ratio)))–\(Int(round(Double(max) * ratio)))"
        }
        return "\(selectedServings)"
    }

    var hasConversionData: Bool {
        recipe?.ingredients.contains { $0.hasConversionData } ?? false
    }

    init(recipeId: String, service: SharedRecipeService, authRepository: AuthRepository) {
        self.recipeId = recipeId
        self.service = service
        self.authRepository = authRepository
    }

    func load() async {
        isLoading = true
        recipe = await service.getSharedRecipeDetail(recipeId: recipeId)
        selectedServings = recipe?.baseServings ?? 1
        isLoading = false

        // Start comment listener
        Task {
            for await batch in service.commentsStream(recipeId: recipeId) {
                comments = batch
            }
        }
    }

    func adjustServings(delta: Int) {
        selectedServings = max(1, selectedServings + delta)
    }

    func scaledQty(for ingredient: SharedIngredient) -> String {
        switch unitMode {
        case .metric where ingredient.quantityValueMetric != nil:
            return SharedQuantityScaler.format(ingredient.quantityValueMetric! * scaleFactor, unit: ingredient.quantityUnitMetric)
        case .imperial where ingredient.quantityValueImperial != nil:
            return SharedQuantityScaler.format(ingredient.quantityValueImperial! * scaleFactor, unit: ingredient.quantityUnitImperial)
        default:
            if let v = ingredient.quantityValue {
                return SharedQuantityScaler.format(v * scaleFactor, unit: ingredient.quantityUnit)
            }
            return ingredient.quantityDisplay ?? ""
        }
    }

    func postComment() {
        guard !newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let text = newCommentText
        newCommentText = ""
        Task { await service.addComment(recipeId: recipeId, content: text) }
    }

    func deleteComment(_ comment: SharedComment) {
        Task { await service.deleteComment(recipeId: recipeId, commentId: comment.id) }
    }

    func canDeleteComment(_ comment: SharedComment) -> Bool {
        guard let uid = currentUid else { return false }
        return comment.authorId == uid || recipe?.authorId == uid
    }

    func copyToMyRecipes() {
        guard let recipe = recipe else { return }
        isCopying = true
        Task {
            isCopied = await service.copyToMyRecipes(recipe)
            isCopying = false
        }
    }
}

// MARK: - Helper for scaling shared ingredients (no IngredientModel available)

private enum SharedQuantityScaler {
    static func format(_ value: Double, unit: String?) -> String {
        let formatted = formatValue(value)
        if let unit = unit, !unit.isEmpty { return "\(formatted) \(unit)" }
        return formatted
    }

    private static func formatValue(_ value: Double) -> String {
        if value == 0 { return "0" }
        let fractions: [(Double, String)] = [
            (1.0/8,"⅛"),(1.0/4,"¼"),(1.0/3,"⅓"),(3.0/8,"⅜"),
            (1.0/2,"½"),(5.0/8,"⅝"),(2.0/3,"⅔"),(3.0/4,"¾"),(7.0/8,"⅞")
        ]
        let whole = Int(value)
        let remainder = value - Double(whole)
        if remainder < 0.01 { return "\(whole)" }
        for (fraction, symbol) in fractions {
            if abs(remainder - fraction) < 0.04 { return whole > 0 ? "\(whole)\(symbol)" : symbol }
        }
        let str = String(format: "%.2f", value)
        return str.replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
    }
}

// MARK: - View

struct SharedRecipeDetailView: View {
    let recipeId: String
    @Environment(AppContainer.self) private var container
    @State private var viewModel: SharedRecipeDetailViewModel?

    var body: some View {
        Group {
            if let vm = viewModel {
                if vm.isLoading {
                    ProgressView()
                } else if let recipe = vm.recipe {
                    SharedDetailContent(recipe: recipe, viewModel: vm)
                } else {
                    ContentUnavailableView("Recipe not found", systemImage: "doc.questionmark")
                }
            } else {
                ProgressView()
            }
        }
        .navigationBarTitleDisplayMode(.large)
        .onAppear {
            if viewModel == nil {
                viewModel = SharedRecipeDetailViewModel(
                    recipeId: recipeId,
                    service: container.sharedRecipeService,
                    authRepository: container.authRepository
                )
                Task { await viewModel?.load() }
            }
        }
    }
}

// MARK: - Detail content

private struct SharedDetailContent: View {
    let recipe: SharedRecipe
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Meta bar
                SharedMetaBar(recipe: recipe, viewModel: viewModel)

                // Unit toggle
                if viewModel.hasConversionData {
                    Picker("Units", selection: $viewModel.unitMode) {
                        ForEach(UnitMode.allCases, id: \.self) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                }

                // Sections
                let sortedSections = recipe.sections
                ForEach(sortedSections) { section in
                    SharedSectionBlock(section: section, recipe: recipe, viewModel: viewModel)
                }

                // Copy button
                CopyToMyRecipesButton(viewModel: viewModel)
                    .padding(.horizontal)

                // Comments
                CommentsSection(viewModel: viewModel)
            }
        }
        .navigationTitle(recipe.title)
    }
}

// MARK: - Meta bar

private struct SharedMetaBar: View {
    let recipe: SharedRecipe
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let author = recipe.authorDisplayName {
                Label(author, systemImage: "person")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }

            if let desc = recipe.description {
                Text(desc).font(.body).foregroundStyle(.secondary).padding(.horizontal)
            }

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
                Text("Yield").font(.subheadline)
                Spacer()
                HStack(spacing: 16) {
                    Button { viewModel.adjustServings(delta: -1) } label: {
                        Image(systemName: "minus.circle.fill").font(.title2)
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.selectedServings <= 1)

                    Text(viewModel.yieldDisplay)
                        .font(.headline)
                        .frame(minWidth: 40)

                    Button { viewModel.adjustServings(delta: 1) } label: {
                        Image(systemName: "plus.circle.fill").font(.title2)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }
}

// MARK: - Section block

private struct SharedSectionBlock: View {
    let section: SharedSection
    let recipe: SharedRecipe
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var sectionIngredients: [SharedIngredient] {
        recipe.ingredients.filter { $0.sectionId == section.id }
    }
    var sectionSteps: [SharedStep] {
        recipe.steps.filter { $0.sectionId == section.id }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(section.name)
                .font(.title3).fontWeight(.semibold)
                .padding(.horizontal).padding(.vertical, 8)

            if !sectionIngredients.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Ingredients")
                        .font(.caption).foregroundStyle(.secondary)
                        .padding(.horizontal).padding(.bottom, 4)
                    ForEach(sectionIngredients) { ing in
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "circle").foregroundStyle(.secondary).font(.title3)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text(viewModel.scaledQty(for: ing)).fontWeight(.medium)
                                    Text(ing.name)
                                }
                                if ing.isOptional {
                                    Text("Optional").font(.caption).foregroundStyle(.tertiary)
                                }
                            }
                            Spacer()
                        }
                        .padding(.horizontal).padding(.vertical, 8)
                    }
                }
                .padding(.bottom, 12)
            }

            if !sectionSteps.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Steps")
                        .font(.caption).foregroundStyle(.secondary).padding(.horizontal)
                    ForEach(Array(sectionSteps.enumerated()), id: \.element.id) { idx, step in
                        HStack(alignment: .top, spacing: 14) {
                            ZStack {
                                Circle().fill(Color.accentColor.opacity(0.15)).frame(width: 32, height: 32)
                                Text("\(idx + 1)").font(.headline).foregroundStyle(Color.accentColor)
                            }
                            .padding(.top, 2)
                            Text(step.instruction).font(.body).fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.bottom, 16)
            }
        }
    }
}

// MARK: - Copy button

private struct CopyToMyRecipesButton: View {
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var body: some View {
        Button {
            viewModel.copyToMyRecipes()
        } label: {
            if viewModel.isCopying {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            } else if viewModel.isCopied {
                Label("Copied to Your Recipes", systemImage: "checkmark")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            } else {
                Label("Copy to My Recipes", systemImage: "plus.circle")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
            }
        }
        .buttonStyle(.borderedProminent)
        .disabled(viewModel.isCopied || viewModel.isCopying)
        .padding(.bottom, 4)
    }
}

// MARK: - Comments

private struct CommentsSection: View {
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Comments")
                .font(.title3).fontWeight(.semibold)
                .padding(.horizontal)

            ForEach(viewModel.comments) { comment in
                CommentRow(comment: comment, viewModel: viewModel)
            }

            // New comment input
            VStack(alignment: .leading, spacing: 8) {
                TextField("Add a comment…", text: $viewModel.newCommentText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(2...5)
                if !viewModel.newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Button("Post Comment") { viewModel.postComment() }
                        .font(.subheadline)
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 24)
        }
    }
}

private struct CommentRow: View {
    let comment: SharedComment
    @Bindable var viewModel: SharedRecipeDetailViewModel

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(comment.authorDisplayName).font(.caption).fontWeight(.semibold)
                    Text(comment.createdAt.relativeString()).font(.caption2).foregroundStyle(.tertiary)
                }
                Text(comment.content).font(.body)
            }
            Spacer()
            if viewModel.canDeleteComment(comment) {
                Button {
                    viewModel.deleteComment(comment)
                } label: {
                    Image(systemName: "trash").font(.caption).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
    }
}
