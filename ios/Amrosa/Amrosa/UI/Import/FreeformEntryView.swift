import SwiftUI
import Observation

@Observable
@MainActor
final class FreeformEntryViewModel {
    var recipeText: String = ""
    var isFormatting = false
    var isReformatting = false
    var errorMessage: String? = nil
    var parsedRecipe: ParsedRecipeData? = nil
    var savedRecipeId: String? = nil
    var editRecipeId: String? = nil

    private let cloudFunctions: CloudFunctionsService
    private let repository: RecipeRepository
    private let authRepository: AuthRepository

    init(cloudFunctions: CloudFunctionsService, repository: RecipeRepository, authRepository: AuthRepository) {
        self.cloudFunctions = cloudFunctions
        self.repository = repository
        self.authRepository = authRepository
    }

    func formatWithGemini() {
        guard !recipeText.trimmed.isEmpty else {
            errorMessage = "Please enter some recipe text first."
            return
        }
        isFormatting = true
        errorMessage = nil

        Task {
            defer { isFormatting = false }
            do {
                parsedRecipe = try await cloudFunctions.formatRecipeText(recipeText)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func reformat() {
        isReformatting = true
        errorMessage = nil
        parsedRecipe = nil

        Task {
            defer { isReformatting = false }
            do {
                parsedRecipe = try await cloudFunctions.formatRecipeText(recipeText)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func saveRecipe() {
        guard let parsed = parsedRecipe else { return }
        let recipeId = UUID().uuidString
        let now = Date()
        let uid = authRepository.uid
        let name = authRepository.displayName ?? "Me"

        do {
            _ = try repository.insertFullRecipeFromParsed(
                recipeId: recipeId,
                parsed: parsed,
                authorId: uid,
                authorDisplayName: name,
                isImported: false,
                needsReview: false
            )
            savedRecipeId = recipeId
            parsedRecipe = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func saveAndEdit() {
        guard let parsed = parsedRecipe else { return }
        let recipeId = UUID().uuidString
        let uid = authRepository.uid
        let name = authRepository.displayName ?? "Me"

        do {
            _ = try repository.insertFullRecipeFromParsed(
                recipeId: recipeId,
                parsed: parsed,
                authorId: uid,
                authorDisplayName: name,
                isImported: false,
                needsReview: false
            )
            editRecipeId = recipeId
            parsedRecipe = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func dismissReview() {
        parsedRecipe = nil
    }
}

// MARK: - View

struct FreeformEntryView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: FreeformEntryViewModel?
    @State private var navigateToEditorRecipe: RecipeModel? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                FreeformEntryContent(viewModel: vm)
                    .sheet(isPresented: Binding(
                        get: { vm.parsedRecipe != nil },
                        set: { if !$0 { vm.dismissReview() } }
                    )) {
                        if let parsed = vm.parsedRecipe {
                            RecipeReviewSheet(
                                parsed: parsed,
                                primaryLabel: "Save Recipe",
                                secondaryLabel: "Reformat",
                                isSecondaryLoading: vm.isReformatting,
                                onPrimary: {
                                    vm.saveRecipe()
                                    dismiss()
                                },
                                onSecondary: { vm.reformat() },
                                onDismiss: { vm.dismissReview() },
                                onEdit: {
                                    vm.saveAndEdit()
                                }
                            )
                        }
                    }
                    .onChange(of: vm.editRecipeId) { _, newId in
                        if let id = newId,
                           let recipe = try? container.recipeRepository.fetchRecipe(id: id) {
                            navigateToEditorRecipe = recipe
                        }
                    }
                    .navigationDestination(item: $navigateToEditorRecipe) { recipe in
                        RecipeEditorView(recipe: recipe)
                    }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Type It Out")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if viewModel == nil {
                viewModel = FreeformEntryViewModel(
                    cloudFunctions: container.cloudFunctions,
                    repository: container.recipeRepository,
                    authRepository: container.authRepository
                )
            }
        }
    }
}

private struct FreeformEntryContent: View {
    @Bindable var viewModel: FreeformEntryViewModel

    private let placeholder = "Paste or type your recipe here…\n\nExample:\n2 cups flour\n1 tsp salt\nMix and bake at 350°F for 30 min."

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topLeading) {
                TextEditor(text: $viewModel.recipeText)
                    .font(.body)
                    .padding(8)

                if viewModel.recipeText.isEmpty {
                    Text(placeholder)
                        .font(.body)
                        .foregroundStyle(.tertiary)
                        .padding(14)
                        .allowsHitTesting(false)
                }
            }
            .frame(maxHeight: .infinity)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding()

            HStack {
                Text("\(viewModel.recipeText.count) characters")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal)

            if let error = viewModel.errorMessage {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
                    .padding(.horizontal)
            }

            Button {
                viewModel.formatWithGemini()
            } label: {
                Group {
                    if viewModel.isFormatting {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Label("Format with Gemini", systemImage: "sparkles")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.vertical, 14)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isFormatting || viewModel.recipeText.trimmed.isEmpty)
            .padding()
        }
    }
}
