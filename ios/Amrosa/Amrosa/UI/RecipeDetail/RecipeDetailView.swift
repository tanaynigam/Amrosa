import SwiftUI

// MARK: - Entry

struct RecipeDetailView: View {
    let recipe: RecipeModel
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: RecipeDetailViewModel?
    @State private var showCookingMode = false
    @State private var navigateToEditor = false
    @State private var isForkMode = false
    @State private var showShareOptions = false
    @State private var showFollowerPicker = false
    @State private var showSentConfirmation = false
    @State private var showRemoveConfirm = false
    // "Make public to share" prompt for a private recipe
    @State private var pendingShareRecipient: (uid: String, name: String)? = nil
    // Variations (F10)
    @State private var openVariant: RecipeModel? = nil
    @State private var showAddVariant = false
    @State private var newVariantName = ""

    var body: some View {
        Group {
            if let vm = viewModel {
                RecipeDetailContent(
                    viewModel: vm,
                    onOpenVariant: { openVariant = $0 },
                    onAddVariant: { newVariantName = ""; showAddVariant = true }
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle(recipe.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if viewModel?.isReceived == true {
                    // Received (Tab 2) — read-only: Remove + Cooking Mode only
                    Button(role: .destructive) { showRemoveConfirm = true } label: {
                        Image(systemName: "trash")
                    }
                    Button { showCookingMode = true } label: {
                        Image(systemName: "book.closed")
                    }
                } else {
                    // Owned recipe — Share + Edit + Cooking Mode
                    if viewModel?.isOwner == true {
                        Button { viewModel?.loadFollowing(); showShareOptions = true } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                    Button { navigateToEditor = true } label: {
                        Image(systemName: "pencil")
                    }
                    Button { showCookingMode = true } label: {
                        Image(systemName: "book.closed")
                    }
                }
            }
        }
        .navigationDestination(isPresented: $navigateToEditor) {
            RecipeEditorView(recipe: recipe, forking: isForkMode)
        }
        .fullScreenCover(isPresented: $showCookingMode) {
            if let vm = viewModel { CookingModeView(viewModel: vm) }
        }
        .sheet(isPresented: $showShareOptions) {
            if let vm = viewModel {
                ShareOptionsSheet(
                    isPresented: $showShareOptions,
                    onSendToFollower: { showShareOptions = false; showFollowerPicker = true },
                    onShareLink: { showShareOptions = false; vm.handleShareTap() }
                )
            }
        }
        .sheet(isPresented: Binding(get: { viewModel?.showShareSheet ?? false },
                                    set: { viewModel?.showShareSheet = $0 })) {
            if let url = viewModel?.shareURL { ShareSheet(items: [url]) }
        }
        .alert("Share Recipe?", isPresented: Binding(get: { viewModel?.showShareConfirmDialog ?? false },
                                                      set: { viewModel?.showShareConfirmDialog = $0 })) {
            Button("Share") { viewModel?.publishAndShare() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This recipe will be visible to anyone with the link. You can make it private again at any time.")
        }
        .sheet(isPresented: $showFollowerPicker) {
            if let vm = viewModel {
                FollowerPickerSheet(viewModel: vm, isPresented: $showFollowerPicker) { uid, name in
                    // Gate on Public — sharing a private recipe prompts to make it public first
                    if vm.isPublic {
                        vm.shareToFollower(uid: uid, name: name)
                    } else {
                        pendingShareRecipient = (uid, name)
                    }
                }
            }
        }
        .alert("Sent!", isPresented: $showSentConfirmation) {
            Button("OK", role: .cancel) { viewModel?.shareSentToName = nil }
        } message: {
            if let name = viewModel?.shareSentToName { Text("Recipe sent to \(name).") }
        }
        .onChange(of: viewModel?.shareSentToName) { _, newVal in if newVal != nil { showSentConfirmation = true } }
        .alert(
            "Share with \(pendingShareRecipient?.name ?? "")?",
            isPresented: Binding(get: { pendingShareRecipient != nil },
                                 set: { if !$0 { pendingShareRecipient = nil } })
        ) {
            Button("Share") {
                if let r = pendingShareRecipient { viewModel?.makePublicAndShareToFollower(uid: r.uid, name: r.name) }
                pendingShareRecipient = nil
            }
            Button("Cancel", role: .cancel) { pendingShareRecipient = nil }
        } message: {
            Text("Sharing makes this recipe public so they can view it. You can make it private again later.")
        }
        .confirmationDialog("Remove this recipe?", isPresented: $showRemoveConfirm, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { viewModel?.removeReceivedRecipe() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It will be removed from your recipes. The original author keeps their copy.")
        }
        .onChange(of: viewModel?.removed) { _, isRemoved in if isRemoved == true { dismiss() } }
        .alert("New Variation", isPresented: $showAddVariant) {
            TextField("Name (e.g. Spicy, Vegan)", text: $newVariantName)
            Button("Create") { viewModel?.createVariant(name: newVariantName) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Creates an editable copy you can tweak. Max 20 characters.")
        }
        // Variation just created → open its editor
        .navigationDestination(item: Binding(
            get: { viewModel?.createdVariantId.flatMap { viewModel?.recipeModel(id: $0) } },
            set: { _ in viewModel?.createdVariantId = nil }
        )) { newVariant in
            RecipeEditorView(recipe: newVariant)
        }
        // Switch to another family member (variation chip tap)
        .navigationDestination(item: $openVariant) { model in
            RecipeDetailView(recipe: model)
        }
        .onAppear {
            if viewModel == nil {
                viewModel = RecipeDetailViewModel(
                    recipe: recipe,
                    repository: container.recipeRepository,
                    authRepository: container.authRepository,
                    sharedRecipeService: container.sharedRecipeService,
                    socialRepository: container.socialRepository,
                    syncService: container.syncService
                )
                viewModel?.startCommentListener()
                viewModel?.loadVariants()
            } else {
                // Returning from editor / variant switch — refresh + detect deletion
                viewModel?.reload()
            }
        }
        .onChange(of: viewModel?.recipeDeleted) { _, deleted in
            if deleted == true { dismiss() }
        }
    }
}

// MARK: - iOS share sheet wrapper
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uvc: UIActivityViewController, context: Context) {}
}

// MARK: - Main content (matches Android LazyColumn layout)

private struct RecipeDetailContent: View {
    @Bindable var viewModel: RecipeDetailViewModel
    var onOpenVariant: (RecipeModel) -> Void = { _ in }
    var onAddVariant: () -> Void = {}

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {

                // ── Variation chips (F10): Original · <names> · ＋ Variation ──
                if viewModel.variants.count > 1 || viewModel.canAddVariant {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(viewModel.variants) { v in
                                Button {
                                    if !v.isCurrent, let model = viewModel.recipeModel(id: v.id) {
                                        onOpenVariant(model)
                                    }
                                } label: {
                                    Text(v.label)
                                        .font(.caption).fontWeight(.medium)
                                        .padding(.horizontal, 12).padding(.vertical, 6)
                                        .background(v.isCurrent ? Color.accentColor : Color(.secondarySystemBackground))
                                        .foregroundStyle(v.isCurrent ? Color.white : Color.primary)
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                            if viewModel.canAddVariant {
                                Button(action: onAddVariant) {
                                    Label("Variation", systemImage: "plus")
                                        .font(.caption).fontWeight(.medium)
                                        .padding(.horizontal, 12).padding(.vertical, 6)
                                        .overlay(Capsule().stroke(Color.accentColor, lineWidth: 1))
                                        .foregroundStyle(Color.accentColor)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                    }
                }

                // ── Header: description + visibility chip ─────────────
                if let desc = viewModel.recipe.recipeDescription {
                    Text(desc)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                        .padding(.bottom, 4)
                }
                if viewModel.isOwner {
                    VisibilityChip(viewModel: viewModel)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }

                // ── Time + Yield row ──────────────────────────────────
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        if let prep = viewModel.recipe.prepTimeMinutes {
                            Text("Prep  \(prep.timeDisplayString)")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        if let cook = viewModel.recipe.cookTimeMinutes {
                            Text("Cook  \(cook.timeDisplayString)")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    YieldAdjuster(viewModel: viewModel)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                Divider().padding(.horizontal, 16)

                // ── Section jump chips ────────────────────────────────
                let sections = viewModel.sortedSections
                if sections.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(sections) { section in
                                Button(section.name) {
                                    withAnimation { proxy.scrollTo(section.id, anchor: .top) }
                                }
                                .font(.caption).fontWeight(.medium)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(Color(.secondarySystemBackground))
                                .foregroundStyle(.primary)
                                .clipShape(Capsule())
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }

                // ── Source links ──────────────────────────────────────
                if !viewModel.recipe.sourceUrls.isEmpty {
                    SectionHeader("Sources")
                    ForEach(viewModel.recipe.sourceUrls, id: \.self) { url in
                        if let link = URL(string: url) {
                            Link("• \(url)", destination: link)
                                .font(.footnote)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 2)
                        }
                    }
                    Divider().padding(.horizontal, 16).padding(.vertical, 8)
                }

                // ── Substitute selectors ("Options") ──────────────────
                let subGroups = viewModel.substituteGroups
                if !subGroups.isEmpty {
                    SectionHeader("Options")
                    ForEach(subGroups, id: \.groupId) { group in
                        SubstituteSelector(
                            options: group.options,
                            selectedId: viewModel.selectedSubstitutes[group.groupId]
                                ?? group.options.first?.id ?? "",
                            viewModel: viewModel,
                            groupId: group.groupId
                        )
                    }
                    Divider().padding(.horizontal, 16).padding(.vertical, 8)
                }

                // ── Ingredients ───────────────────────────────────────
                HStack {
                    Text("Ingredients")
                        .font(.title2).fontWeight(.semibold)
                    Spacer()
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
                        .frame(width: 180)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 4)

                let allIngredients = viewModel.visibleIngredients
                let groupedIngredients = Dictionary(grouping: allIngredients) { $0.groupLabel ?? "" }
                let groupOrder = allIngredients.compactMap { $0.groupLabel }.uniqued()

                // Ungrouped first
                let ungrouped = allIngredients.filter { $0.groupLabel == nil || $0.groupLabel!.isEmpty }
                ForEach(ungrouped) { ing in
                    IngredientRow(ingredient: ing, viewModel: viewModel)
                }

                // Labelled groups
                ForEach(groupOrder, id: \.self) { label in
                    if let ings = groupedIngredients[label], !ings.isEmpty {
                        Text(label)
                            .font(.footnote).fontWeight(.semibold)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                            .padding(.bottom, 2)
                        ForEach(ings) { ing in
                            IngredientRow(ingredient: ing, viewModel: viewModel)
                        }
                    }
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 8)

                // ── Instructions ──────────────────────────────────────
                SectionHeader("Instructions")

                if sections.isEmpty {
                    // No sections — show all steps flat
                    let steps = viewModel.steps(for: nil).sorted { $0.orderIndex < $1.orderIndex }
                    ForEach(Array(steps.enumerated()), id: \.element.id) { idx, step in
                        StepRow(step: step, index: idx + 1)
                    }
                } else {
                    ForEach(sections) { section in
                        Text(section.name)
                            .font(.title3).fontWeight(.semibold)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 6)
                            .id(section.id)
                        let sectionSteps = viewModel.steps(for: section)
                        ForEach(Array(sectionSteps.enumerated()), id: \.element.id) { idx, step in
                            StepRow(step: step, index: idx + 1)
                        }
                    }
                    // Steps without a section
                    let unsectionedSteps = viewModel.steps(for: nil)
                    ForEach(Array(unsectionedSteps.enumerated()), id: \.element.id) { idx, step in
                        StepRow(step: step, index: idx + 1)
                    }
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 8)

                // ── Notes ─────────────────────────────────────────────
                HStack {
                    Text("Notes").font(.title2).fontWeight(.semibold)
                    Spacer()
                    Button {
                        viewModel.newNoteText = viewModel.newNoteText.isEmpty ? " " : ""
                    } label: {
                        Image(systemName: "plus.bubble")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.accentColor)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 4)

                if !viewModel.newNoteText.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        TextField("Add a note, tweak, or observation…", text: Binding(
                            get: { viewModel.newNoteText.trimmingCharacters(in: .whitespaces) == "" ? "" : viewModel.newNoteText },
                            set: { viewModel.newNoteText = $0 }
                        ), axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(3...6)
                        if !viewModel.newNoteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Button("Save Note") { viewModel.addNote() }
                                .buttonStyle(.borderedProminent).font(.subheadline)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                }

                ForEach(viewModel.sortedNotes) { note in
                    NoteRow(note: note, viewModel: viewModel)
                }
                if viewModel.sortedNotes.isEmpty && viewModel.newNoteText.isEmpty {
                    Text("No notes yet. Tap + to add one.")
                        .font(.body).foregroundStyle(.secondary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                }

                // ── Comments (public recipes only) ────────────────────
                if viewModel.isPublic {
                    CommentsSection(viewModel: viewModel)
                }

                Spacer(minLength: 32)
            }
        }
        } // ScrollViewReader
    }
}

// MARK: - Yield adjuster

private struct YieldAdjuster: View {
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        HStack(spacing: 2) {
            Text("Yield").font(.footnote).foregroundStyle(.secondary)
            Button {
                viewModel.adjustScale(delta: -1)
            } label: {
                Image(systemName: "minus").frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canDecrement)

            Text(viewModel.yieldDisplay)
                .font(.title3).fontWeight(.semibold)
                .frame(minWidth: 40)
                .onLongPressGesture { viewModel.resetScale() }

            Button {
                viewModel.adjustScale(delta: 1)
            } label: {
                Image(systemName: "plus").frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)

            // Reset button if scale changed
            if !viewModel.isDefaultScale {
                Button { viewModel.resetScale() } label: {
                    Image(systemName: "arrow.counterclockwise").frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Section header

private struct SectionHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.title2).fontWeight(.semibold)
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 4)
    }
}

// MARK: - Ingredient row (matches Android IngredientRow with per-ingredient optional toggle)

private struct IngredientRow: View {
    let ingredient: IngredientModel
    @Bindable var viewModel: RecipeDetailViewModel

    var isChecked: Bool { viewModel.checkedIngredientIds.contains(ingredient.id) }
    var isOptionalEnabled: Bool { viewModel.enabledOptionals.contains(ingredient.id) }
    var isDimmed: Bool { ingredient.isOptional && !isOptionalEnabled }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // Checkmark circle
            Button {
                if ingredient.isOptional && !isOptionalEnabled {
                    viewModel.toggleOptional(ingredient.id)
                } else {
                    if isChecked { viewModel.checkedIngredientIds.remove(ingredient.id) }
                    else { viewModel.checkedIngredientIds.insert(ingredient.id) }
                }
            } label: {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isChecked ? Color.accentColor : (isDimmed ? Color.secondary.opacity(0.4) : Color.secondary))
                    .font(.title3)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(viewModel.scaledQuantity(for: ingredient))
                        .fontWeight(.medium)
                    Text(ingredient.name)
                }
                .font(.body)
                .strikethrough(isChecked)
                .foregroundStyle(isDimmed ? Color.secondary : (isChecked ? Color.secondary : Color.primary))

                if ingredient.isOptional {
                    Button(isOptionalEnabled ? "Optional (tap to disable)" : "Optional — tap to add") {
                        viewModel.toggleOptional(ingredient.id)
                    }
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                    .buttonStyle(.plain)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
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

            VStack(alignment: .leading, spacing: 6) {
                Text(step.instruction)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)

                // Ingredient refs — shown as small chips below the step text
                let refs = step.ingredientRefs
                if !refs.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(refs, id: \.ingredient?.id) { ref in
                                if let ing = ref.ingredient {
                                    let display = ref.quantityDisplay ?? ing.quantityDisplay ?? ""
                                    Text("\(display) \(ing.name)".trimmingCharacters(in: .whitespaces))
                                        .font(.caption)
                                        .padding(.horizontal, 8).padding(.vertical, 3)
                                        .background(Color.accentColor.opacity(0.1))
                                        .foregroundStyle(Color.accentColor)
                                        .clipShape(Capsule())
                                }
                            }
                        }
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: - Substitute selector

private struct SubstituteSelector: View {
    let options: [IngredientModel]
    let selectedId: String
    @Bindable var viewModel: RecipeDetailViewModel
    let groupId: String

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options) { option in
                    Button {
                        viewModel.selectSubstitute(groupId, option.id)
                    } label: {
                        Text(option.name)
                            .font(.subheadline)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(option.id == selectedId ? Color.accentColor : Color(.secondarySystemBackground))
                            .foregroundStyle(option.id == selectedId ? Color.white : Color.primary)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }
}

// MARK: - Visibility chip

private struct VisibilityChip: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @State private var showConfirm = false

    var body: some View {
        Button { showConfirm = true } label: {
            Label(
                viewModel.isPublic ? "Public" : "Private",
                systemImage: viewModel.isPublic ? "globe" : "lock"
            )
            .font(.caption).fontWeight(.medium)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(viewModel.isPublic ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground))
            .foregroundStyle(viewModel.isPublic ? Color.accentColor : Color.secondary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .confirmationDialog(
            viewModel.isPublic ? "Make Private?" : "Make Public?",
            isPresented: $showConfirm, titleVisibility: .visible
        ) {
            if viewModel.isPublic {
                Button("Make Private", role: .destructive) { viewModel.setVisibility("private") }
            } else {
                Button("Make Public") { viewModel.setVisibility("public") }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(viewModel.isPublic
                ? "This recipe will no longer be accessible via its link."
                : "This recipe will be visible to anyone with the link.")
        }
    }
}

// MARK: - Notes

private struct NoteRow: View {
    let note: RecipeNoteModel
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text(note.content).font(.body)
                Text(note.updatedAt.relativeString()).font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer()
            Menu {
                Button("Edit") { viewModel.isEditingNote = note; viewModel.editingNoteText = note.content }
                Button("Delete", role: .destructive) { viewModel.deleteNote(note) }
            } label: {
                Image(systemName: "ellipsis").foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .sheet(item: $viewModel.isEditingNote) { _ in
            NavigationStack {
                TextEditor(text: Binding(get: { viewModel.editingNoteText }, set: { viewModel.editingNoteText = $0 }))
                    .padding()
                    .navigationTitle("Edit Note")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { viewModel.isEditingNote = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button("Save") { viewModel.saveEditingNote() } }
                    }
            }
        }
    }
}

// MARK: - Comments

private struct CommentsSection: View {
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "bubble.left.and.bubble.right").foregroundStyle(Color.accentColor)
                Text("Comments").font(.title2).fontWeight(.semibold)
                Spacer()
                Text("\(viewModel.comments.count)").font(.footnote).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Comment input
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Add a comment…", text: Binding(
                    get: { viewModel.newCommentText },
                    set: { viewModel.newCommentText = $0 }
                ), axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...5)

                Button {
                    viewModel.postComment()
                } label: {
                    Image(systemName: "paperplane.fill")
                        .foregroundStyle(viewModel.newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? Color.secondary : Color.accentColor)
                }
                .disabled(viewModel.newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if viewModel.comments.isEmpty {
                Text("No comments yet. Be the first!")
                    .font(.body).foregroundStyle(.secondary)
                    .padding(.horizontal, 16).padding(.vertical, 8)
            } else {
                ForEach(viewModel.comments) { comment in
                    CommentRow(comment: comment, viewModel: viewModel)
                }
            }
        }
    }
}

private struct CommentRow: View {
    let comment: SharedComment
    @Bindable var viewModel: RecipeDetailViewModel

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(comment.authorDisplayName).font(.caption).fontWeight(.semibold)
                    Text(comment.createdAt.relativeString()).font(.caption2).foregroundStyle(.tertiary)
                }
                Text(comment.content).font(.body)
            }
            Spacer()
            if viewModel.canDeleteComment(comment) {
                Button { viewModel.deleteComment(comment) } label: {
                    Image(systemName: "trash").font(.caption).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: - Share options sheet

private struct ShareOptionsSheet: View {
    @Binding var isPresented: Bool
    let onSendToFollower: () -> Void
    let onShareLink: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color(.systemFill))
                .frame(width: 36, height: 4).padding(.top, 12)
            Text("Share Recipe").font(.headline).padding(.vertical, 16)
            Divider()

            Button(action: onSendToFollower) {
                HStack(spacing: 14) {
                    Image(systemName: "paperplane.fill").font(.title3).foregroundStyle(Color.accentColor).frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send to a follower").font(.body).fontWeight(.medium).foregroundStyle(.primary)
                        Text("Share directly — only that person will see it").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
                .background(Color.accentColor.opacity(0.07))
            }
            .buttonStyle(.plain)

            Divider().padding(.leading, 66)

            Button(action: onShareLink) {
                HStack(spacing: 14) {
                    Image(systemName: "link").font(.title3).foregroundStyle(.secondary).frame(width: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Share link").font(.body).foregroundStyle(.primary)
                        Text("Anyone with the link can view this recipe").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
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
    let onSend: (_ uid: String, _ name: String) -> Void

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isFollowingLoading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if viewModel.following.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "person.2.slash").font(.system(size: 40)).foregroundStyle(.secondary)
                        Text("You're not following anyone yet").foregroundStyle(.secondary)
                        Text("Follow people from the Account tab to share recipes with them.")
                            .font(.caption).foregroundStyle(.tertiary).multilineTextAlignment(.center).padding(.horizontal)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(viewModel.following) { person in
                        HStack {
                            ZStack {
                                Circle().fill(Color.accentColor.opacity(0.12)).frame(width: 40, height: 40)
                                Text(String(person.displayName.prefix(1)).uppercased()).font(.headline).foregroundStyle(Color.accentColor)
                            }
                            Text(person.displayName).font(.body)
                            Spacer()
                            Button("Send") {
                                onSend(person.uid, person.displayName)
                                isPresented = false
                            }
                            .font(.subheadline).buttonStyle(.borderedProminent)
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Send Recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { isPresented = false } }
            }
        }
    }
}
