import SwiftUI
import UniformTypeIdentifiers

struct ImportView: View {
    var reviewRecipeId: String? = nil
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: ImportViewModel?
    @State private var showFilePicker = false
    @State private var navigateToEditor: String? = nil
    @State private var navigateToEditorRecipe: RecipeModel? = nil

    var body: some View {
        Group {
            if let vm = viewModel {
                ImportContent(
                    viewModel: vm,
                    showFilePicker: $showFilePicker,
                    onEditorRequested: { recipeId in
                        if let r = try? container.recipeRepository.fetchRecipe(id: recipeId) {
                            navigateToEditorRecipe = r
                        }
                    }
                )
                .sheet(isPresented: Binding(
                    get: { vm.parsedRecipe != nil },
                    set: { if !$0 { vm.dismissReview() } }
                )) {
                    if let parsed = vm.parsedRecipe {
                        RecipeReviewSheet(
                            parsed: parsed,
                            primaryLabel: "Confirm",
                            secondaryLabel: "Reimport",
                            isSecondaryLoading: vm.isReimporting,
                            onPrimary: { vm.confirmImport() },
                            onSecondary: { vm.reimport() },
                            onDismiss: { vm.dismissReview() },
                            onEdit: {
                                if let id = vm.reviewingRecipeId,
                                   let r = try? container.recipeRepository.fetchRecipe(id: id) {
                                    vm.dismissReview()
                                    navigateToEditorRecipe = r
                                }
                            },
                            isOwnRecipe: vm.isOwnRecipe,
                            onIsOwnRecipeChange: { vm.isOwnRecipe = $0 }
                        )
                    }
                }
                .navigationDestination(item: $navigateToEditorRecipe) { recipe in
                    RecipeEditorView(recipe: recipe)
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Import Recipe")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.spreadsheet, .commaSeparatedText, .plainText],
            allowsMultipleSelection: false
        ) { result in
            handleFilePick(result: result)
        }
        .onAppear {
            if viewModel == nil {
                let vm = ImportViewModel(
                    repository: container.recipeRepository,
                    cloudFunctions: container.cloudFunctions,
                    authRepository: container.authRepository
                )
                viewModel = vm
                if let id = reviewRecipeId {
                    vm.loadPendingReview(recipeId: id)
                }
            }
        }
    }

    private func handleFilePick(result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }

        let ext = url.pathExtension.lowercased()
        let type: String
        switch ext {
        case "xlsx", "xls": type = "xlsx"
        case "csv": type = "csv"
        default: type = "text"
        }

        guard let content = try? String(contentsOf: url, encoding: .utf8) else { return }
        viewModel?.importFromFileContent(content: content, type: type, fileName: url.lastPathComponent)
    }
}

private struct ImportContent: View {
    @Bindable var viewModel: ImportViewModel
    @Binding var showFilePicker: Bool
    let onEditorRequested: (String) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // URL import card
                VStack(alignment: .leading, spacing: 12) {
                    Text("Import from URL")
                        .font(.headline)

                    TextField("https://…", text: $viewModel.urlText)
                        .textFieldStyle(.roundedBorder)
                        .autocapitalization(.none)
                        .keyboardType(.URL)

                    Text("Supports recipe URLs, Google Sheets, and Google Docs.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Button {
                        viewModel.importFromURL()
                    } label: {
                        Group {
                            if viewModel.isImporting {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                            } else {
                                Text("Import")
                                    .fontWeight(.semibold)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isImporting || viewModel.urlText.isEmpty)
                }
                .padding()
                .cardStyle()

                // File import card
                VStack(alignment: .leading, spacing: 12) {
                    Text("Import from File")
                        .font(.headline)

                    Text("Upload a .xlsx, .csv, or .txt file with your recipe.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Button {
                        showFilePicker = true
                    } label: {
                        Group {
                            if viewModel.isImportingFile {
                                ProgressView()
                                    .frame(maxWidth: .infinity)
                            } else {
                                Label("Choose File", systemImage: "doc.badge.plus")
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                    .disabled(viewModel.isImportingFile)
                }
                .padding()
                .cardStyle()

                if let error = viewModel.errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            }
            .padding()
        }
    }
}
