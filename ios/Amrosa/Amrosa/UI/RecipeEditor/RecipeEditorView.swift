import SwiftUI

// MARK: - Entry point

struct RecipeEditorView: View {
    let recipe: RecipeModel?
    let forking: Bool
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: RecipeEditorViewModel?
    @State private var showDeleteConfirm = false

    init(recipe: RecipeModel? = nil, forking: Bool = false) {
        self.recipe = recipe
        self.forking = forking
    }

    var body: some View {
        Group {
            if let vm = viewModel {
                RecipeEditorContent(viewModel: vm, dismiss: { dismiss() }, showDelete: $showDeleteConfirm)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(navTitle)
        .navigationBarTitleDisplayMode(.inline)
        .alert("Delete Recipe?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) { viewModel?.deleteRecipe() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\"\(viewModel?.title ?? "")\" will be permanently deleted. This cannot be undone.")
        }
        .onChange(of: viewModel?.deleteComplete) { _, done in if done == true { dismiss() } }
        .onAppear {
            if viewModel == nil {
                if let r = recipe {
                    viewModel = RecipeEditorViewModel(
                        recipe: r,
                        repository: container.recipeRepository,
                        authRepository: container.authRepository,
                        syncService: container.syncService,
                        sharedRecipeService: container.sharedRecipeService,
                        cloudFunctions: container.cloudFunctions,
                        forking: forking
                    )
                } else {
                    viewModel = RecipeEditorViewModel(
                        repository: container.recipeRepository,
                        authRepository: container.authRepository,
                        syncService: container.syncService,
                        sharedRecipeService: container.sharedRecipeService,
                        cloudFunctions: container.cloudFunctions
                    )
                }
            }
        }
    }

    private var navTitle: String {
        if forking { return "Fork Recipe" }
        return recipe == nil ? "New Recipe" : "Edit Recipe"
    }
}

// MARK: - Main content

private struct RecipeEditorContent: View {
    @Bindable var viewModel: RecipeEditorViewModel
    let dismiss: () -> Void
    @Binding var showDelete: Bool

    var body: some View {
        Form {
            // ── Metadata ────────────────────────────────────────────────
            Section("Title") {
                TextField("Recipe title", text: $viewModel.title)
            }

            Section("Description") {
                TextField("Description (optional)", text: $viewModel.description, axis: .vertical)
                    .lineLimit(3...6)
            }

            Section("Servings & Time") {
                HStack {
                    Text("Servings")
                    Spacer()
                    TextField("4", text: $viewModel.baseServings)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                }
                HStack {
                    Text("Yield Min")
                    Spacer()
                    TextField("–", text: $viewModel.baseServingsMin)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                }
                HStack {
                    Text("Yield Max")
                    Spacer()
                    TextField("–", text: $viewModel.baseServingsMax)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                }
                HStack {
                    Text("Prep (min)")
                    Spacer()
                    TextField("–", text: $viewModel.prepTimeMinutes)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                }
                HStack {
                    Text("Cook (min)")
                    Spacer()
                    TextField("–", text: $viewModel.cookTimeMinutes)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 60)
                }
            }

            Section("Author") {
                Picker("Author", selection: $viewModel.isPersonalAuthor) {
                    Text("Imported").tag(false)
                    Text("Personal — \(viewModel.personalAuthorName)").tag(true)
                }
                .pickerStyle(.menu)
            }

            // F10: variation name field — only shown when editing a variation
            if viewModel.isVariant {
                Section("Variation Name") {
                    HStack {
                        TextField("e.g. Spicy, Vegan", text: Binding(
                            get: { viewModel.variantName },
                            set: { viewModel.updateVariantName($0) }
                        ))
                        Text("\(viewModel.variantName.count)/\(RecipeEditorViewModel.maxVariantNameLen)")
                            .font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }

            Section("Tags") {
                TextField("Dinner, Italian, Baking…", text: $viewModel.tagsText)
            }

            Section("Source URLs") {
                TextField("Paste URLs (one per line)", text: $viewModel.sourceUrlsText, axis: .vertical)
                    .lineLimit(2...5)
                    .autocapitalization(.none)
            }

            // ── Update unit conversions (F6) — above the ingredients ─────
            Section {
                Button {
                    viewModel.updateConversions()
                } label: {
                    HStack {
                        if viewModel.isConverting {
                            ProgressView()
                        } else {
                            Label("Update unit conversions", systemImage: "arrow.triangle.2.circlepath")
                        }
                    }
                }
                .disabled(viewModel.isConverting)
                if let msg = viewModel.conversionMessage {
                    Text(msg).font(.caption).foregroundStyle(.secondary)
                }
            } footer: {
                Text("Re-derives Metric/Imperial amounts (weight for dry items, fl oz for liquids). Save to keep them.")
            }

            // ── Sections ────────────────────────────────────────────────
            ForEach($viewModel.sections) { $section in
                SectionEditorBlock(
                    section: $section,
                    viewModel: viewModel
                )
            }
            .onDelete { viewModel.deleteSection(at: $0) }
            .onMove { viewModel.moveSection(from: $0, to: $1) }

            Section {
                Button {
                    viewModel.addSection()
                } label: {
                    Label("Add Section", systemImage: "plus.circle")
                }
            }

            // ── Error ────────────────────────────────────────────────────
            if let error = viewModel.errorMessage {
                Section {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                }
            }

            // Delete — only shown when editing an existing recipe
            if viewModel.existingRecipe != nil {
                Section {
                    Button(role: .destructive) {
                        showDelete = true
                    } label: {
                        if viewModel.isDeleting {
                            ProgressView()
                        } else {
                            Label("Delete Recipe", systemImage: "trash")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .disabled(viewModel.isDeleting)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if viewModel.isSaving {
                    ProgressView()
                } else {
                    Button("Save") {
                        Task {
                            await viewModel.save()
                            if viewModel.savedRecipeId != nil { dismiss() }
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled(viewModel.title.trimmed.isEmpty)
                }
            }
        }
    }
}

// MARK: - Section editor block

private struct SectionEditorBlock: View {
    @Binding var section: EditorSection
    @Bindable var viewModel: RecipeEditorViewModel

    var body: some View {
        Section {
            // Section name
            HStack {
                Image(systemName: "square.grid.3x1.fill.below.line.grid.1x2")
                    .foregroundStyle(.secondary)
                    .font(.caption)
                TextField("Section name", text: $section.name)
                    .font(.headline)
            }

            // Ingredients header + list
            if !section.ingredients.isEmpty {
                ForEach($section.ingredients) { $ing in
                    IngredientEditorRow(ingredient: $ing)
                }
                .onDelete { viewModel.deleteIngredient(from: section.id, at: $0) }
                .onMove { viewModel.moveIngredient(in: section.id, from: $0, to: $1) }
            }
            Button {
                viewModel.addIngredient(to: section.id)
            } label: {
                Label("Add Ingredient", systemImage: "plus")
                    .font(.subheadline)
            }

            // Steps header + list
            if !section.steps.isEmpty {
                ForEach($section.steps) { $step in
                    StepEditorRow(step: $step)
                }
                .onDelete { viewModel.deleteStep(from: section.id, at: $0) }
                .onMove { viewModel.moveStep(in: section.id, from: $0, to: $1) }
            }
            Button {
                viewModel.addStep(to: section.id)
            } label: {
                Label("Add Step", systemImage: "plus")
                    .font(.subheadline)
            }
        } header: {
            Text(section.name)
                .textCase(nil)
        }
    }
}

// MARK: - Ingredient editor row

private struct IngredientEditorRow: View {
    @Binding var ingredient: EditorIngredient
    @State private var showNote = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("Ingredient name", text: $ingredient.name)
                .font(.body)

            HStack(spacing: 8) {
                TextField("Qty", text: Binding(
                    get: { ingredient.quantityDisplay.isEmpty
                           ? (ingredient.quantityValue.map { formatNumber($0) } ?? "")
                           : ingredient.quantityDisplay },
                    set: { ingredient.quantityDisplay = $0
                           ingredient.quantityValue = Double($0) }
                ))
                .frame(width: 60)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
                .font(.subheadline)

                TextField("Unit", text: $ingredient.quantityUnit)
                    .frame(width: 70)
                    .textFieldStyle(.roundedBorder)
                    .font(.subheadline)
                    .autocapitalization(.none)

                Spacer()

                Toggle("", isOn: $ingredient.isOptional)
                    .labelsHidden()
                    .scaleEffect(0.8)
                Text("Optional")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // F11: optional shopping note — unobtrusive, hidden until requested
            if showNote || !ingredient.shoppingNote.isEmpty {
                TextField("Shopping note (brand / comment)", text: $ingredient.shoppingNote, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .font(.caption)
                    .lineLimit(1...2)
            } else {
                Button {
                    showNote = true
                } label: {
                    Label("Shopping note", systemImage: "plus")
                        .font(.caption)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
        }
        .padding(.vertical, 2)
    }

    private func formatNumber(_ n: Double) -> String {
        n.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(n)) : String(n)
    }
}

// MARK: - Step editor row

private struct StepEditorRow: View {
    @Binding var step: EditorStep

    var body: some View {
        TextField("Step instruction", text: $step.instruction, axis: .vertical)
            .font(.body)
            .lineLimit(2...8)
    }
}
