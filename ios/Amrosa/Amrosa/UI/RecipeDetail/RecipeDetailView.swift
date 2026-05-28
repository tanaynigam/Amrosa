import SwiftUI

struct RecipeDetailView: View {
    let recipe: RecipeModel
    @Environment(AppContainer.self) private var container
    @State private var viewModel: RecipeDetailViewModel?
    @State private var showCookingMode = false
    @State private var navigateToEditor = false
    @State private var showForkAlert = false
    @State private var isForkMode = false
    @State private var showShareOptions = false
    @State private var showFollowerPicker = false
    @State private var showSentConfirmation = false

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
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                // Single share button — owners only; opens ShareOptionsSheet
                if viewModel?.isOwner == true {
                    Button {
                        viewModel?.loadFollowing()
                        showShareOptions = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
                // Edit button
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
        .sheet(isPresented: Binding(
            get: { viewModel?.showShareSheet ?? false },
            set: { viewModel?.showShareSheet = $0 }
        )) {
            if let url = viewModel?.shareURL {
                ShareSheet(items: [url])
            }
        }
        .alert("Share Recipe?", isPresented: Binding(
            get: { viewModel?.showShareConfirmDialog ?? false },
            set: { viewModel?.showShareConfirmDialog = $0 }
        )) {
            Button("Share") { viewModel?.publishAndShare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This recipe will be visible to anyone with the link. You can make it private again at any time.")
        }
        .sheet(isPresented: $showShareOptions) {
            if let vm = viewModel {
                ShareOptionsSheet(
                    isPresented: $showShareOptions,
                    onSendToFollower: {
                        showShareOptions = false
                        showFollowerPicker = true
                    },
                    onShareLink: {
                        showShareOptions = false
                        vm.handleShareTap()
                    }
                )
            }
        }
        .sheet(isPresented: $showFollowerPicker) {
            if let vm = viewModel {
                FollowerPickerSheet(viewModel: vm, isPresented: $showFollowerPicker)
            }
        }
        .alert("Sent!", isPresented: $showSentConfirmation) {
            Button("OK", role: .cancel) { viewModel?.shareSentToName = nil }
        } message: {
            if let name = viewModel?.shareSentToName {
                Text("Recipe sent to \(name).")
            }
        }
        .onChange(of: viewModel?.shareSentToName) { _, newVal in
            if newVal != nil { showSentConfirmation = true }
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
                    authRepository: container.authRepository,
                    sharedRecipeService: container.sharedRecipeService,
                    socialRepository: container.socialRepository
                )
                // Start comment listener if recipe is already public
                viewModel?.startCommentListener()
            }
        }
    }
}

// MARK: - iOS share sheet wrapper

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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

                    // Visibility toggle (owner only)
                    if viewModel.isOwner {
                        VisibilityChip(viewModel: viewModel)
                            .padding(.horizontal)
                    }

                    // Notes
                    NotesSection(viewModel: viewModel)

                    // Comments (when public)
                    if viewModel.isPublic {
                        RecipeCommentsSection(viewModel: viewModel)
                    }

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

// MARK: - Visibility chip

private struct VisibilityChip: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @State private var showConfirm = false

    var body: some View {
        Button {
            showConfirm = true
        } label: {
            Label(
                viewModel.isPublic ? "Public" : "Private",
                systemImage: viewModel.isPublic ? "globe" : "lock"
            )
            .font(.subheadline)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(viewModel.isPublic ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground))
            .foregroundStyle(viewModel.isPublic ? Color.accentColor : Color.secondary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .confirmationDialog(
            viewModel.isPublic ? "Make Private?" : "Make Public?",
            isPresented: $showConfirm,
            titleVisibility: .visible
        ) {
            if viewModel.isPublic {
                Button("Make Private", role: .destructive) {
                    viewModel.setVisibility("private")
                }
            } else {
                Button("Make Public") { viewModel.setVisibility("public") }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(viewModel.isPublic
                ? "This recipe will no longer be visible to others."
                : "This recipe will be visible to anyone with the link.")
        }
    }
}

// MARK: - Comments (owner view, public recipes)

private struct RecipeCommentsSection: View {
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Comments")
                .font(.title3).fontWeight(.semibold)
                .padding(.horizontal)

            ForEach(viewModel.comments) { comment in
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

            VStack(alignment: .leading, spacing: 8) {
                TextField("Add a comment…", text: Binding(
                    get: { viewModel.newCommentText },
                    set: { viewModel.newCommentText = $0 }
                ), axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...5)
                if !viewModel.newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Button("Post Comment") { viewModel.postComment() }
                        .font(.subheadline).buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal)
        }
    }
}

// MARK: - Share options sheet

private struct ShareOptionsSheet: View {
    @Binding var isPresented: Bool
    let onSendToFollower: () -> Void
    let onShareLink: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Handle
            Capsule()
                .fill(Color(.systemFill))
                .frame(width: 36, height: 4)
                .padding(.top, 12)

            Text("Share Recipe")
                .font(.headline)
                .padding(.vertical, 16)

            Divider()

            // Option A: Send to follower (highlighted)
            Button(action: onSendToFollower) {
                HStack(spacing: 14) {
                    Image(systemName: "paperplane.fill")
                        .font(.title3)
                        .foregroundStyle(Color.accentColor)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send to a follower")
                            .font(.body)
                            .fontWeight(.medium)
                            .foregroundStyle(.primary)
                        Text("Share directly — only that person will see it")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .background(Color.accentColor.opacity(0.07))
            }
            .buttonStyle(.plain)

            Divider()
                .padding(.leading, 66)

            // Option B: Share link
            Button(action: onShareLink) {
                HStack(spacing: 14) {
                    Image(systemName: "link")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Share link")
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text("Anyone with the link can view this recipe")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
            }
            .buttonStyle(.plain)

            Spacer().frame(minHeight: 20)
        }
        .presentationDetents([.height(240)])
        .presentationDragIndicator(.hidden)
    }
}

// MARK: - Follower picker sheet

private struct FollowerPickerSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @Binding var isPresented: Bool

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isFollowingLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if viewModel.following.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "person.2.slash")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary)
                        Text("You're not following anyone yet")
                            .foregroundStyle(.secondary)
                        Text("Follow people from the Account tab to share recipes with them.")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(viewModel.following) { person in
                        HStack {
                            // Avatar
                            ZStack {
                                Circle()
                                    .fill(Color.accentColor.opacity(0.12))
                                    .frame(width: 40, height: 40)
                                Text(String(person.displayName.prefix(1)).uppercased())
                                    .font(.headline)
                                    .foregroundStyle(Color.accentColor)
                            }
                            Text(person.displayName)
                                .font(.body)
                            Spacer()
                            Button("Send") {
                                viewModel.shareToFollower(uid: person.uid, name: person.displayName)
                                isPresented = false
                            }
                            .font(.subheadline)
                            .buttonStyle(.borderedProminent)
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Send Recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = false }
                }
            }
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
