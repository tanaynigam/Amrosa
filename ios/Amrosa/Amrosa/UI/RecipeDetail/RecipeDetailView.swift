import SwiftUI

struct RecipeDetailView: View {
    let recipe: RecipeModel
    @Environment(AppContainer.self) private var container
    @State private var viewModel: RecipeDetailViewModel?
    @State private var showCookingMode = false
    @State private var navigateToEditor = false
    @State private var showForkAlert = false
    @State private var isForkMode = false

    var body: some View {
        Group {
            if let vm = viewModel {
                RecipeDetailContent(
                    viewModel: vm,
                    showCookingMode: $showCookingMode,
                    navigateToEditor: $navigateToEditor
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle(recipe.title)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    if recipe.authorId == nil && !recipe.isCustomized {
                        showForkAlert = true
                    } else {
                        navigateToEditor = true
                    }
                } label: {
                    Image(systemName: "pencil")
                }
            }
        }
        .navigationDestination(isPresented: $navigateToEditor) {
            RecipeEditorView(recipe: recipe, forking: isForkMode)
        }
        .fullScreenCover(isPresented: $showCookingMode) {
            if let vm = viewModel {
                CookingModeView(viewModel: vm)
            }
        }
        .alert("Fork Recipe?", isPresented: $showForkAlert) {
            Button("Fork") { isForkMode = true; navigateToEditor = true }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will create a personal copy of the recipe that you can edit.")
        }
        .onAppear {
            if viewModel == nil {
                viewModel = RecipeDetailViewModel(
                    recipe: recipe,
                    repository: container.recipeRepository,
                    authRepository: container.authRepository
                )
            }
        }
    }
}

// MARK: - Content

private struct RecipeDetailContent: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @Binding var showCookingMode: Bool
    @Binding var navigateToEditor: Bool

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Meta info
                    RecipeMetaBar(recipe: viewModel.recipe, viewModel: viewModel)

                    // Source URLs
                    if !viewModel.recipe.sourceUrls.isEmpty {
                        SourceURLsSection(urls: viewModel.recipe.sourceUrls)
                    }

                    // Unit toggle
                    if viewModel.hasConversionData {
                        Picker("Units", selection: Binding(
                            get: { viewModel.unitMode },
                            set: { viewModel.unitMode = $0 }
                        )) {
                            ForEach(UnitMode.allCases, id: \.self) { mode in
                                Text(mode.rawValue).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                    }

                    // Section jump chips
                    if viewModel.sortedSections.count > 1 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(viewModel.sortedSections) { section in
                                    Button(section.name) {
                                        withAnimation { proxy.scrollTo(section.id, anchor: .top) }
                                    }
                                    .font(.subheadline)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(Color(.secondarySystemBackground))
                                    .clipShape(Capsule())
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }

                    // Optional toggle
                    Toggle("Show optional ingredients", isOn: Binding(
                        get: { viewModel.showOptional },
                        set: { viewModel.showOptional = $0 }
                    ))
                    .padding(.horizontal)
                    .font(.subheadline)

                    // Sections
                    ForEach(viewModel.sortedSections) { section in
                        RecipeSectionBlock(section: section, viewModel: viewModel)
                            .id(section.id)
                    }

                    // Notes
                    NotesSection(viewModel: viewModel)

                    // Cooking mode button
                    Button {
                        showCookingMode = true
                    } label: {
                        Label("Start Cooking Mode", systemImage: "flame.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
        }
    }
}

// MARK: - Meta bar

private struct RecipeMetaBar: View {
    let recipe: RecipeModel
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let desc = recipe.recipeDescription {
                Text(desc)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal)
            }

            // Time row
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
                        viewModel.adjustScale(delta: -1)
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .font(.title2)
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canDecrement)

                    Text(viewModel.yieldDisplay)
                        .font(.headline)
                        .frame(minWidth: 40)
                        .onLongPressGesture { viewModel.resetScale() }

                    Button {
                        viewModel.adjustScale(delta: 1)
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.title2)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }
}

// MARK: - Source URLs

private struct SourceURLsSection: View {
    let urls: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Sources")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
            ForEach(urls, id: \.self) { url in
                if url.isValidURL, let link = URL(string: url) {
                    Link(url, destination: link)
                        .font(.caption)
                        .lineLimit(1)
                        .padding(.horizontal)
                } else {
                    Text(url)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .padding(.horizontal)
                }
            }
        }
    }
}

// MARK: - Section block

private struct RecipeSectionBlock: View {
    let section: RecipeSectionModel
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(section.name)
                .font(.title3)
                .fontWeight(.semibold)
                .padding(.horizontal)
                .padding(.vertical, 8)

            // Ingredients
            let visible = viewModel.visibleIngredients(for: section)
            if !visible.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Ingredients")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)
                        .padding(.bottom, 4)

                    ForEach(groupedIngredients(visible), id: \.0) { groupLabel, ings in
                        if let label = groupLabel {
                            Text(label)
                                .font(.caption)
                                .fontWeight(.medium)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal)
                                .padding(.top, 8)
                        }
                        ForEach(ings) { ingredient in
                            IngredientRow(ingredient: ingredient, viewModel: viewModel)
                        }
                    }
                }
                .padding(.bottom, 12)
            }

            // Steps
            let sectionSteps = viewModel.steps(for: section)
            if !sectionSteps.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Steps")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal)

                    ForEach(Array(sectionSteps.enumerated()), id: \.element.id) { index, step in
                        StepRow(step: step, index: index + 1)
                    }
                }
                .padding(.bottom, 16)
            }
        }
    }

    private func groupedIngredients(_ ings: [IngredientModel]) -> [(String?, [IngredientModel])] {
        var groups: [(String?, [IngredientModel])] = []
        var current: (String?, [IngredientModel])? = nil

        for ing in ings {
            if current?.0 == ing.groupLabel {
                current?.1.append(ing)
            } else {
                if let c = current { groups.append(c) }
                current = (ing.groupLabel, [ing])
            }
        }
        if let c = current { groups.append(c) }
        return groups
    }
}

// MARK: - Ingredient row

private struct IngredientRow: View {
    let ingredient: IngredientModel
    @Bindable var viewModel: RecipeDetailViewModel

    var isChecked: Bool { viewModel.checkedIngredientIds.contains(ingredient.id) }

    var body: some View {
        Button {
            if isChecked {
                viewModel.checkedIngredientIds.remove(ingredient.id)
            } else {
                viewModel.checkedIngredientIds.insert(ingredient.id)
            }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isChecked ? .green : .secondary)
                    .font(.title3)

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(viewModel.scaledQuantity(for: ingredient))
                            .fontWeight(.medium)
                        Text(ingredient.name)
                    }
                    .font(.body)
                    .strikethrough(isChecked)
                    .foregroundStyle(isChecked ? .secondary : .primary)

                    if ingredient.isOptional {
                        Text("Optional")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Step row

private struct StepRow: View {
    let step: StepModel
    let index: Int

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 32, height: 32)
                Text("\(index)")
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

// MARK: - Notes

private struct NotesSection: View {
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Notes")
                .font(.title3)
                .fontWeight(.semibold)
                .padding(.horizontal)

            ForEach(viewModel.sortedNotes) { note in
                NoteRow(note: note, viewModel: viewModel)
            }

            // Add note
            VStack(alignment: .leading, spacing: 8) {
                TextField("Add a note…", text: Binding(
                    get: { viewModel.newNoteText },
                    set: { viewModel.newNoteText = $0 }
                ), axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(3...6)

                if !viewModel.newNoteText.isEmpty {
                    Button("Save Note") { viewModel.addNote() }
                        .font(.subheadline)
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal)
        }
    }
}

private struct NoteRow: View {
    let note: RecipeNoteModel
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(note.content)
                    .font(.body)
                Text(note.updatedAt.relativeString())
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            Spacer()
            Menu {
                Button("Edit") {
                    viewModel.isEditingNote = note
                    viewModel.editingNoteText = note.content
                }
                Button("Delete", role: .destructive) {
                    viewModel.deleteNote(note)
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal)
        .sheet(item: $viewModel.isEditingNote) { _ in
            NavigationStack {
                TextEditor(text: Binding(
                    get: { viewModel.editingNoteText },
                    set: { viewModel.editingNoteText = $0 }
                ))
                .padding()
                .navigationTitle("Edit Note")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { viewModel.isEditingNote = nil }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { viewModel.saveEditingNote() }
                    }
                }
            }
        }
    }
}
