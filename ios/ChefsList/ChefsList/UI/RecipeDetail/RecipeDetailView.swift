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
    // Cooking mode start-at-section ("▶ Cook from here")
    @State private var cookFromSectionId: String? = nil
    // Shopping list (F11)
    @State private var showShoppingList = false
    // F16 edit-mode delete confirm (triggered from the edit toolbar menu)
    @State private var showDeleteConfirm = false

    var body: some View {
        navAndSheets
            .confirmationDialog("Delete this recipe?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { viewModel?.deleteRecipe() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This permanently deletes the recipe\(viewModel?.recipe.parentRecipeId == nil ? " and its variations" : "").")
            }
            .alert("Unit conversions", isPresented: Binding(
                get: { viewModel?.conversionMessage != nil },
                set: { if !$0 { viewModel?.conversionMessage = nil } })
            ) {
                Button("OK", role: .cancel) { viewModel?.conversionMessage = nil }
            } message: {
                Text(viewModel?.conversionMessage ?? "")
            }
            .onAppear { onAppearSetup() }
            .onChange(of: viewModel?.recipeDeleted) { _, deleted in
                if deleted == true { dismiss() }
            }
    }

    // Split into helper builders so SwiftUI's type-checker handles each chunk independently.

    @ViewBuilder
    private var coreView: some View {
        Group {
            if let vm = viewModel {
                RecipeDetailContent(
                    viewModel: vm,
                    onOpenVariant: { openVariant = $0 },
                    onAddVariant: { newVariantName = ""; showAddVariant = true },
                    onCookFromSection: { cookFromSectionId = $0; showCookingMode = true },
                    onShare: { vm.loadFollowing(); showShareOptions = true },
                    onShoppingList: { showShoppingList = true }
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle(viewModel?.isEditMode == true ? "Edit recipe" : recipe.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel?.isEditMode == true)
        .toolbar { toolbarContent }
        .sheet(item: Binding(get: { viewModel?.editTarget }, set: { viewModel?.editTarget = $0 })) { target in
            if let vm = viewModel { EditSheetHost(target: target, viewModel: vm) }
        }
        .alert("Couldn't save", isPresented: Binding(
            get: { viewModel?.editError != nil },
            set: { if !$0 { viewModel?.editError = nil } })
        ) {
            Button("OK", role: .cancel) { viewModel?.editError = nil }
        } message: {
            Text(viewModel?.editError ?? "")
        }
    }

    @ViewBuilder
    private var navAndSheets: some View {
        shareDialogs
            .navigationDestination(isPresented: $navigateToEditor) {
                RecipeEditorView(recipe: recipe, forking: isForkMode)
            }
            .fullScreenCover(isPresented: $showCookingMode) {
                if let vm = viewModel { CookingModeView(viewModel: vm, startSectionId: cookFromSectionId) }
            }
            .alert("New Variation", isPresented: $showAddVariant) {
                TextField("Name (e.g. Spicy, Vegan)", text: $newVariantName)
                Button("Create") { viewModel?.createVariant(name: newVariantName) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Creates an editable copy you can tweak. Max 20 characters.")
            }
            .navigationDestination(item: createdVariantBinding) { newVariant in
                RecipeEditorView(recipe: newVariant)
            }
            .navigationDestination(item: $openVariant) { model in
                RecipeDetailView(recipe: model)
            }
            // F11: shopping list at the detail screen's current scale
            .navigationDestination(isPresented: $showShoppingList) {
                ShoppingListView(
                    recipeId: recipe.id,
                    initialServings: viewModel?.selectedServings,
                    initialAnchorQty: (viewModel?.isAnchorBased == true) ? viewModel?.scaleAnchorQty : nil
                )
            }
    }

    @ViewBuilder
    private var shareDialogs: some View {
        coreView
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
                        // Published (Co-Chefs or Public) → share directly; private → prompt to publish at Co-Chefs.
                        if vm.isPublished { vm.shareToFollower(uid: uid, name: name) }
                        else { pendingShareRecipient = (uid, name) }
                    }
                }
            }
            .modifier(SentAndRemoveDialogs(
                showSentConfirmation: $showSentConfirmation,
                showRemoveConfirm: $showRemoveConfirm,
                pendingShareRecipient: $pendingShareRecipient,
                viewModel: viewModel
            ))
    }

    private func onAppearSetup() {
        if viewModel == nil {
            viewModel = RecipeDetailViewModel(
                recipe: recipe,
                repository: container.recipeRepository,
                authRepository: container.authRepository,
                sharedRecipeService: container.sharedRecipeService,
                socialRepository: container.socialRepository,
                syncService: container.syncService,
                cloudFunctions: container.cloudFunctions,
                userPreferences: container.userPreferences,
                saveTracker: container.saveTracker
            )
            viewModel?.loadNotes()
            viewModel?.loadLikes()
            viewModel?.loadVariants()
        } else {
            viewModel?.reload()
        }
    }

    /// Navigates to a just-created variation's editor (extracted to ease the type-checker).
    private var createdVariantBinding: Binding<RecipeModel?> {
        Binding(
            get: {
                guard let id = viewModel?.createdVariantId else { return nil }
                return viewModel?.recipeModel(id: id)
            },
            set: { _ in viewModel?.createdVariantId = nil }
        )
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if viewModel?.isEditMode == true {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("Cancel") { viewModel?.cancelEdit() }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                // All edit actions live in the toolbar so the body never reflows (scroll preserved).
                Menu {
                    Button { viewModel?.editTarget = .details } label: { Label("Recipe details", systemImage: "info.circle") }
                    let firstSection = viewModel?.editSections.first?.id
                    if let sid = firstSection {
                        Button { viewModel?.editTarget = .ingredient(sectionId: sid, ingredientId: nil) } label: { Label("Add ingredient", systemImage: "leaf") }
                        Button { viewModel?.editTarget = .step(sectionId: sid, stepId: nil) } label: { Label("Add step", systemImage: "list.number") }
                    }
                    Button { viewModel?.editTarget = .section(sectionId: nil) } label: { Label("Add section", systemImage: "square.stack") }
                    Divider()
                    Button { viewModel?.autoArrangeFromSteps() } label: { Label("Auto-arrange from steps", systemImage: "wand.and.stars") }
                    Button { viewModel?.updateConversions() } label: { Label("Update unit conversions", systemImage: "arrow.triangle.2.circlepath") }
                    Button(role: .destructive) { showDeleteConfirm = true } label: { Label("Delete recipe", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                if viewModel?.isSavingEdit == true || viewModel?.isConverting == true {
                    ProgressView()
                } else {
                    Button { viewModel?.saveEdit() } label: { Image(systemName: "checkmark") }
                }
            }
        } else {
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            // Slimmed down to free up title space: Share moved to the visibility row, cart to the
            // Ingredients header. Smaller icons leave more room for the recipe title.
            if viewModel?.isReceived == true {
                Button(role: .destructive) { showRemoveConfirm = true } label: {
                    Image(systemName: "trash").imageScale(.small)
                }
                Button { cookFromSectionId = nil; showCookingMode = true } label: {
                    Image(systemName: "book.closed").imageScale(.small)
                }
            } else {
                // F19: small spinner while a background save for this recipe is in flight.
                if container.saveTracker.isSaving(recipe.id) {
                    ProgressView()
                }
                if viewModel?.isOwner == true {
                    Button { viewModel?.enterEdit() } label: {
                        Image(systemName: "pencil").imageScale(.small)
                    }
                }
                Button { cookFromSectionId = nil; showCookingMode = true } label: {
                    Image(systemName: "book.closed").imageScale(.small)
                }
            }
        }
        }
    }
}

// MARK: - "Sent" + "Remove" + "make public to share" dialogs (extracted to ease the type-checker)

private struct SentAndRemoveDialogs: ViewModifier {
    @Binding var showSentConfirmation: Bool
    @Binding var showRemoveConfirm: Bool
    @Binding var pendingShareRecipient: (uid: String, name: String)?
    let viewModel: RecipeDetailViewModel?

    func body(content: Content) -> some View {
        content
            .alert("Sent!", isPresented: $showSentConfirmation) {
                Button("OK", role: .cancel) { viewModel?.shareSentToName = nil }
            } message: {
                if let name = viewModel?.shareSentToName { Text("Recipe sent to \(name).") }
            }
            .onChange(of: viewModel?.shareSentToName) { _, newVal in
                if newVal != nil { showSentConfirmation = true }
            }
            .alert(
                "Share with \(pendingShareRecipient?.name ?? "")?",
                isPresented: Binding(get: { pendingShareRecipient != nil },
                                     set: { if !$0 { pendingShareRecipient = nil } })
            ) {
                Button("Share") {
                    if let r = pendingShareRecipient {
                        viewModel?.makeSharableAndShareToFollower(uid: r.uid, name: r.name)
                    }
                    pendingShareRecipient = nil
                }
                Button("Cancel", role: .cancel) { pendingShareRecipient = nil }
            } message: {
                Text("Sharing adds this person to the recipe so they can view it. Only people you pick can see it. You can change this any time.")
            }
            .confirmationDialog("Remove this recipe?", isPresented: $showRemoveConfirm, titleVisibility: .visible) {
                Button("Remove", role: .destructive) { viewModel?.removeReceivedRecipe() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("It will be removed from your recipes. The original author keeps their copy.")
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
    var onCookFromSection: (String) -> Void = { _ in }
    var onShare: () -> Void = {}
    var onShoppingList: () -> Void = {}

    var body: some View {
        // F16: edit mode renders the IDENTICAL body — only `.editable` (outline + jiggle + tap)
        // is flipped on editable rows, and quantities use the same scale/unit — so toggling Edit
        // changes nothing on screen and the scroll position is preserved. Add / convert / delete
        // actions live in the toolbar (no body chrome that would reflow the layout).
        let editing = viewModel.isEditMode
        ScrollViewReader { proxy in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {

                // ── Recipe title (prominent; the nav-bar title is small). Editable in edit mode. ──
                Text(viewModel.displayTitle.isEmpty ? "Untitled recipe" : viewModel.displayTitle)
                    .font(.title).fontWeight(.bold)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.top, 12)
                    .editable(active: editing, phase: 0) { viewModel.editTarget = .details }

                // ── Variation chips (F10) ──
                if viewModel.variants.count > 1 || viewModel.canAddVariant {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(viewModel.variants) { v in
                                Button {
                                    if !editing, !v.isCurrent, let model = viewModel.recipeModel(id: v.id) { onOpenVariant(model) }
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
                            if viewModel.canAddVariant && !editing {
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
                        .padding(.horizontal, 16).padding(.top, 10)
                    }
                }

                // ── Header: description (tap → Details while editing) ──
                if let desc = viewModel.displayDescription {
                    Text(desc)
                        .font(.body).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                        .editable(active: editing, phase: 0) { viewModel.editTarget = .details }
                }
                if viewModel.isOwner {
                    // Kept in both modes (not an editable field) so the layout never reflows.
                    // Share lives here now (moved off the crowded title bar); hidden while editing.
                    HStack {
                        VisibilityChip(viewModel: viewModel)
                        Spacer()
                        if !editing {
                            Button(action: onShare) {
                                Image(systemName: "square.and.arrow.up")
                            }
                            .buttonStyle(.plain).foregroundStyle(Color.accentColor)
                        }
                    }
                    .padding(.horizontal, 16).padding(.top, 8)
                } else if viewModel.canLike {
                    // F20: heart + count for a recipe you don't own (e.g. received).
                    Button { viewModel.toggleLike() } label: {
                        HStack(spacing: 6) {
                            Image(systemName: viewModel.isLiked ? "heart.fill" : "heart")
                                .foregroundStyle(viewModel.isLiked ? Color.red : Color.secondary)
                            if viewModel.likeCount > 0 {
                                Text(viewModel.likeCount.compactCount).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16).padding(.top, 8)
                }

                // ── Time + Yield row (unchanged in both modes) ──
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        if let p = viewModel.displayPrepText { Text(p).font(.footnote).foregroundStyle(.secondary) }
                        if let c = viewModel.displayCookText { Text(c).font(.footnote).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    YieldAdjuster(viewModel: viewModel)
                }
                .padding(.horizontal, 16).padding(.vertical, 10)

                Divider().padding(.horizontal, 16)

                // ── Section jump chips ──
                if viewModel.displaySections.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(viewModel.displaySections) { section in
                                Button(section.name) { withAnimation { proxy.scrollTo(section.id, anchor: .top) } }
                                    .font(.caption).fontWeight(.medium)
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(Color(.secondarySystemBackground))
                                    .foregroundStyle(.primary)
                                    .clipShape(Capsule())
                                    .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    }
                }

                // ── Sources ──
                if !viewModel.recipe.sourceUrls.isEmpty {
                    SectionHeader("Sources")
                    ForEach(viewModel.recipe.sourceUrls, id: \.self) { url in
                        if let link = URL(string: url) {
                            Link("• \(url)", destination: link)
                                .font(.footnote).padding(.horizontal, 16).padding(.vertical, 2)
                        }
                    }
                    Divider().padding(.horizontal, 16).padding(.vertical, 8)
                }

                // (Substitutes are shown inline as swap chips under the ingredient — no "Options" section.)

                // ── Ingredients ──
                HStack(spacing: 12) {
                    Text("Ingredients").font(.title2).fontWeight(.semibold)
                    Spacer()
                    // Compact unit cycler — one chip that rotates Original → Metric → Imperial
                    // (replaces the wide 3-segment toggle so this row isn't crowded).
                    if viewModel.hasConversionData {
                        Button { viewModel.cycleUnitMode() } label: {
                            Label(viewModel.unitMode.shortLabel, systemImage: "arrow.left.arrow.right")
                                .font(.caption).fontWeight(.medium)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(Color(.secondarySystemBackground))
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                    // Shopping list lives here now (moved off the title bar); hidden while editing.
                    if !editing {
                        Button(action: onShoppingList) {
                            Image(systemName: "cart")
                        }
                        .buttonStyle(.plain).foregroundStyle(Color.accentColor)
                    }
                }
                .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)

                ForEach(viewModel.displayIngredientBlocks) { block in
                    if let title = block.title {
                        Text(title.uppercased())
                            .font(.caption).fontWeight(.semibold).foregroundStyle(.secondary)
                            .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 2)
                            .editable(active: editing, phase: 0) {
                                if let sid = block.sectionId { viewModel.editTarget = .section(sectionId: sid) }
                            }
                    }
                    // Optional ingredients: one-line chip row per section (view mode). Selecting a
                    // chip drops the ingredient into the list; unselecting hides it.
                    if !editing, !block.optionalChips.isEmpty {
                        OptionalChipsRow(chips: block.optionalChips) { viewModel.toggleOptional($0) }
                    }
                    ForEach(block.groups) { group in
                        if !group.label.isEmpty {
                            Text(group.label)
                                .font(.footnote).fontWeight(.semibold).foregroundStyle(.secondary)
                                .padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 2)
                        }
                        ForEach(Array(group.items.enumerated()), id: \.element.id) { idx, ing in
                            IngredientRow(ingredient: ing, editing: editing)
                                .editable(active: editing, phase: idx) {
                                    viewModel.editTarget = .ingredient(sectionId: block.sectionId ?? "", ingredientId: ing.id)
                                }
                                .dragReorder(active: editing, id: ing.id) { viewModel.reorderIngredient($0, onto: ing.id) }
                            // Substitute swap chips inline under the selected member (view mode).
                            if !editing, let gid = ing.substituteGroupId, !ing.substituteOptions.isEmpty {
                                SubstituteChipsRow(options: ing.substituteOptions) { viewModel.selectSubstitute(gid, $0) }
                            }
                        }
                    }
                    // In-context "＋ Add ingredient" (edit); view reserves the same height so the
                    // toggle never shifts (F16 intent: in-context adds without reflow).
                    if let sid = block.sectionId {
                        AddRowSlot(editing: editing, title: "Add ingredient",
                                   onDropReorder: { viewModel.moveIngredientToSection($0, to: sid) }) {
                            viewModel.editTarget = .ingredient(sectionId: sid, ingredientId: nil)
                        }
                    }
                }

                Divider().padding(.horizontal, 16).padding(.vertical, 8)

                // ── Instructions ──
                SectionHeader("Instructions")

                ForEach(viewModel.displaySections) { section in
                    let steps = viewModel.displaySteps(forSectionId: section.id)
                    if !steps.isEmpty {
                        HStack {
                            Text(section.name)
                                .font(.title3).fontWeight(.semibold)
                            Spacer()
                            if !editing {
                                Button { onCookFromSection(section.id) } label: {
                                    Label("Cook", systemImage: "play.fill").font(.caption).fontWeight(.medium)
                                }
                                .buttonStyle(.plain).foregroundStyle(Color.accentColor)
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 6)
                        .editable(active: editing, phase: 0) { viewModel.editTarget = .section(sectionId: section.id) }
                        .id(section.id)
                    }
                    ForEach(steps) { step in
                        StepRow(step: step, editing: editing)
                            .editable(active: editing, phase: step.number) {
                                viewModel.editTarget = .step(sectionId: section.id, stepId: step.id)
                            }
                            .dragReorder(active: editing, id: step.id) { viewModel.reorderStep($0, onto: step.id) }
                    }
                    if !steps.isEmpty {
                        AddRowSlot(editing: editing, title: "Add step",
                                   onDropReorder: { viewModel.moveStepToSection($0, to: section.id) }) {
                            viewModel.editTarget = .step(sectionId: section.id, stepId: nil)
                        }
                    }
                }

                // Flat (no-section) steps — both modes
                let flatSteps = viewModel.displaySteps(forSectionId: nil)
                ForEach(flatSteps) { step in
                    StepRow(step: step, editing: editing)
                        .editable(active: editing, phase: step.number) {
                            viewModel.editTarget = .step(sectionId: viewModel.flatStepsSectionId ?? "", stepId: step.id)
                        }
                        .dragReorder(active: editing, id: step.id) { viewModel.reorderStep($0, onto: step.id) }
                }
                if !flatSteps.isEmpty, let sid = viewModel.flatStepsSectionId {
                    AddRowSlot(editing: editing, title: "Add step") {
                        viewModel.editTarget = .step(sectionId: sid, stepId: nil)
                    }
                }

                // In-context "＋ Add section" footer (edit); reserved in view.
                AddRowSlot(editing: editing, title: "Add section") {
                    viewModel.editTarget = .section(sectionId: nil)
                }

                // ── Notes (F18) — one cloud thread on EVERY recipe; visibility-independent.
                // Hidden while editing (below the fold, so hiding doesn't shift content above).
                if !editing && viewModel.notesVisible {
                    Divider().padding(.horizontal, 16).padding(.vertical, 8)
                    NotesSection(viewModel: viewModel)
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

// MARK: - Add-row slot (F16) — ghost "＋ Add" row in edit; equal reserved height in view

private let kAddRowHeight: CGFloat = 44

private struct AddRowSlot: View {
    let editing: Bool
    let title: String
    /// F19: dropping a dragged row's id here moves that item into this section (empty/other section).
    var onDropReorder: ((String) -> Void)? = nil
    let action: () -> Void

    var body: some View {
        Group {
            if editing {
                Button(action: action) {
                    Label(title, systemImage: "plus")
                        .font(.subheadline).foregroundStyle(Color.accentColor)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .frame(height: kAddRowHeight - 8)
                        .overlay(RoundedRectangle(cornerRadius: 8)
                            .strokeBorder(Color.accentColor.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4])))
                        .padding(.horizontal, 16)
                }
                .buttonStyle(.plain)
                .dropDestination(for: String.self) { items, _ in
                    if let d = items.first, let h = onDropReorder { h(d); return true }
                    return false
                }
            } else {
                // Reserve identical height so toggling Edit never shifts the layout.
                Color.clear
            }
        }
        .frame(height: kAddRowHeight)
    }
}

// MARK: - Optional chip row (view mode) — opt-in optional ingredients per section

private struct OptionalChipsRow: View {
    let chips: [DisplayOptionalChip]
    let onToggle: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips) { chip in
                    Button { onToggle(chip.id) } label: {
                        HStack(spacing: 4) {
                            Image(systemName: chip.isEnabled ? "checkmark.circle.fill" : "plus.circle")
                                .font(.caption2)
                            Text(chip.name).font(.caption).fontWeight(.medium)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(chip.isEnabled ? Color.accentColor.opacity(0.15) : Color(.secondarySystemBackground))
                        .foregroundStyle(chip.isEnabled ? Color.accentColor : Color.secondary)
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 4)
        }
    }
}

// MARK: - Ingredient row

private struct IngredientRow: View {
    let ingredient: DisplayIngredient
    var editing: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if editing {
                Image(systemName: "line.3.horizontal")
                    .font(.caption).foregroundStyle(.tertiary).padding(.top, 4)
            }
            Image(systemName: "circle.fill")
                .foregroundStyle(Color.accentColor.opacity(0.5))
                .font(.system(size: 8))
                .padding(.top, 7)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    if !ingredient.scaledQuantity.isEmpty {
                        Text(ingredient.scaledQuantity).fontWeight(.medium)
                    }
                    Text(ingredient.name.isEmpty ? "Tap to edit" : ingredient.name)
                    if ingredient.isOptional {
                        Text("· optional").font(.caption).foregroundStyle(.tertiary)
                    }
                }
                .font(.body)

                if editing, let note = ingredient.shoppingNote {
                    Label(note, systemImage: "lightbulb").font(.caption).foregroundStyle(.tertiary)
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
    let step: DisplayStep
    var editing: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            if editing {
                Image(systemName: "line.3.horizontal")
                    .font(.caption).foregroundStyle(.tertiary).padding(.top, 8)
            }
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 32, height: 32)
                Text("\(step.number)")
                    .font(.headline)
                    .foregroundStyle(Color.accentColor)
            }
            .padding(.top, 2)

            VStack(alignment: .leading, spacing: 6) {
                Text(step.instruction.isEmpty ? "Tap to edit" : step.instruction)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)

                // Ingredient refs — small chips below the step text
                if !step.refs.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(step.refs) { ref in
                                Text(ref.text)
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
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: - Substitute swap chips (inline under the selected ingredient)

private struct SubstituteChipsRow: View {
    let options: [DisplaySubstituteChip]
    let onSelect: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.left.arrow.right").font(.caption2).foregroundStyle(.tertiary)
                ForEach(options) { option in
                    Button { onSelect(option.id) } label: {
                        Text(option.name)
                            .font(.caption).fontWeight(.medium)
                            .padding(.horizontal, 12).padding(.vertical, 5)
                            .background(option.isSelected ? Color.accentColor : Color(.secondarySystemBackground))
                            .foregroundStyle(option.isSelected ? Color.white : Color.primary)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.leading, 38).padding(.trailing, 16).padding(.bottom, 6)
        }
    }
}

// MARK: - Visibility chip

private struct VisibilityChip: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @State private var showChooser = false
    @State private var showRecipients = false

    private var tier: String { viewModel.recipe.visibility }
    private var label: String {
        switch tier {
        case "public": return "Public"
        case "friends": return "Co-Chefs"
        case "shared": return "Specific people"
        default: return "Private"
        }
    }
    private var icon: String {
        switch tier {
        case "public": return "globe"
        case "friends": return "person.2"
        case "shared": return "person.crop.circle.badge.checkmark"
        default: return "lock"
        }
    }
    private var isShared: Bool { tier != "private" }

    var body: some View {
        Button { showChooser = true } label: {
            Label(label, systemImage: icon)
                .font(.caption).fontWeight(.medium)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(isShared ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground))
                .foregroundStyle(isShared ? Color.accentColor : Color.secondary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .confirmationDialog("Who can see this recipe?", isPresented: $showChooser, titleVisibility: .visible) {
            Button("🔒 Private") { viewModel.setVisibility("private") }
            Button("👤 Specific people") { viewModel.loadSharedRecipients(); showRecipients = true }
            Button("👥 Co-Chefs only") { viewModel.setVisibility("friends") }
            Button("🌐 Public (anyone with the link)") { viewModel.setVisibility("public") }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Private: only you. Specific people: only the people you pick. Co-Chefs: people you follow each other with. Public: anyone with the link.")
        }
        .sheet(isPresented: $showRecipients) {
            RecipientsSheet(viewModel: viewModel)
        }
    }
}

// MARK: - Recipients sheet (F17 — share with specific people)

private struct RecipientsSheet: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [UserProfile] = []
    @State private var searchTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
                Section("Shared with") {
                    if viewModel.sharedRecipients.isEmpty {
                        Text("No one yet — search below to add people.")
                            .font(.caption).foregroundStyle(.secondary)
                    } else {
                        ForEach(viewModel.sharedRecipients) { person in
                            HStack {
                                Text(person.displayName)
                                Spacer()
                                Button(role: .destructive) { viewModel.removeSharedRecipient(person.uid) } label: {
                                    Image(systemName: "minus.circle.fill").foregroundStyle(.red)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                if !results.isEmpty {
                    Section("Results") {
                        ForEach(results.filter { r in !viewModel.sharedRecipients.contains { $0.uid == r.uid } }) { person in
                            Button { viewModel.addSharedRecipient(person); query = ""; results = [] } label: {
                                HStack {
                                    Text(person.displayName).foregroundStyle(.primary)
                                    Spacer()
                                    Image(systemName: "plus.circle.fill").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Add by name or email")
            .onChange(of: query) { _, q in
                searchTask?.cancel()
                let trimmed = q.trimmingCharacters(in: .whitespaces)
                guard trimmed.count >= 2 else { results = []; return }
                searchTask = Task {
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    if Task.isCancelled { return }
                    results = await viewModel.searchUsers(trimmed)
                }
            }
            .navigationTitle("Specific people")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onAppear { viewModel.loadSharedRecipients() }
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

private struct NotesSection: View {
    @Bindable var viewModel: RecipeDetailViewModel

    private var canPost: Bool {
        !viewModel.newCommentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Image(systemName: "bubble.left.and.bubble.right").foregroundStyle(Color.accentColor)
                Text("Notes").font(.title2).fontWeight(.semibold)
                Text("\(viewModel.comments.count)").font(.footnote).foregroundStyle(.secondary)
                Spacer()
                // Owner-only lock: freeze / unfreeze new notes (existing notes are kept).
                if viewModel.isOwner {
                    Button { viewModel.toggleNotesLock() } label: {
                        Image(systemName: viewModel.notesLocked ? "lock.fill" : "lock.open")
                            .foregroundStyle(viewModel.notesLocked ? Color.accentColor : Color.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            if viewModel.notesLocked {
                Text("🔒 Notes are locked by the author.")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .padding(.horizontal, 16).padding(.bottom, 8)
            } else {
                HStack(alignment: .bottom, spacing: 8) {
                    TextField("Add a note…", text: Binding(
                        get: { viewModel.newCommentText },
                        set: { viewModel.newCommentText = $0 }
                    ), axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(2...5)

                    Button { viewModel.postComment() } label: {
                        Image(systemName: "paperplane.fill")
                            .foregroundStyle(canPost ? Color.accentColor : Color.secondary)
                    }
                    .disabled(!canPost)
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }

            if viewModel.comments.isEmpty {
                Text("No notes yet. Be the first!")
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
