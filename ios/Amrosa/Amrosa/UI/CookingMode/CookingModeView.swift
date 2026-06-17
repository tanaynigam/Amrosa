import SwiftUI

struct CookingModeView: View {
    @Bindable var viewModel: RecipeDetailViewModel
    /// Optional section to start cooking from ("▶ Cook from here").
    var startSectionId: String? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var currentStepIndex = 0
    @State private var didApplyStart = false
    @State private var finishing = false   // true → play the "All done!" animation, then exit

    private func goNext() {
        if currentStepIndex < allSteps.count - 1 { currentStepIndex += 1 } else { finishing = true }
    }
    private func goPrev() {
        if currentStepIndex > 0 { currentStepIndex -= 1 }
    }

    private var allSteps: [StepModel] {
        viewModel.recipe.steps.sorted { $0.orderIndex < $1.orderIndex }
    }

    /// Sections that actually contain steps, in step order (for the jump menu).
    private var stepSections: [RecipeSectionModel] {
        var seen = Set<String>()
        var result: [RecipeSectionModel] = []
        for step in allSteps {
            if let s = step.section, !seen.contains(s.id) {
                seen.insert(s.id); result.append(s)
            }
        }
        return result
    }

    private func firstStepIndex(ofSection sectionId: String) -> Int? {
        allSteps.firstIndex { $0.section?.id == sectionId }
    }

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()

            VStack(spacing: 0) {
                // Top bar
                HStack {
                    Button("Done") { dismiss() }
                        .font(.headline)

                    Spacer()

                    // Unit cycler chip (matches the detail screen) — only when conversions exist
                    if viewModel.hasConversionData {
                        Button { viewModel.cycleUnitMode() } label: {
                            Label(viewModel.unitMode.shortLabel, systemImage: "arrow.left.arrow.right")
                                .font(.caption).fontWeight(.medium)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(Color(.tertiarySystemBackground))
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }

                    // Section jump menu — only when >1 section has steps
                    if stepSections.count > 1 {
                        Menu {
                            ForEach(stepSections) { section in
                                Button(section.name) {
                                    if let idx = firstStepIndex(ofSection: section.id) { currentStepIndex = idx }
                                }
                            }
                        } label: {
                            Image(systemName: "list.bullet")
                        }
                    }

                    Text("\(currentStepIndex + 1) / \(allSteps.count)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(Color(.secondarySystemBackground))

                // Step content
                if allSteps.isEmpty {
                    Spacer()
                    Text("No steps found.")
                        .foregroundStyle(.secondary)
                    Spacer()
                } else {
                    let step = allSteps[currentStepIndex]

                    ScrollView {
                        VStack(alignment: .leading, spacing: 20) {
                            // Section label
                            if let section = step.section {
                                Text(section.name.uppercased())
                                    .font(.caption)
                                    .fontWeight(.semibold)
                                    .foregroundStyle(.secondary)
                                    .tracking(1)
                            }

                            Text(step.instruction)
                                .font(.title3)
                                .lineSpacing(6)
                                .fixedSize(horizontal: false, vertical: true)

                            // Referenced ingredients (augmented: collectively-referenced
                            // ingredients attach to their section's first step — QOL #3).
                            let stepIngs = viewModel.cookingStepIngredients[step.id] ?? []
                            if !stepIngs.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Ingredients for this step")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    ForEach(stepIngs) { ing in
                                        // Tick off ingredients as they're added (session-only;
                                        // clears when you leave the recipe screen).
                                        let checked = viewModel.checkedIngredientIds.contains(ing.id)
                                        Button {
                                            if checked { viewModel.checkedIngredientIds.remove(ing.id) }
                                            else { viewModel.checkedIngredientIds.insert(ing.id) }
                                        } label: {
                                            HStack {
                                                Image(systemName: checked ? "checkmark.square.fill" : "square")
                                                    .foregroundStyle(checked ? Color.accentColor : Color.secondary)
                                                // Unit-aware + scaled (honours the toggle)
                                                Text(viewModel.scaledQuantity(for: ing))
                                                    .fontWeight(.medium)
                                                Text(ing.name)
                                                Spacer()
                                            }
                                            .font(.subheadline)
                                            .strikethrough(checked)
                                            .foregroundStyle(checked ? Color.secondary : Color.primary)
                                            .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding()
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            }
                        }
                        .padding(24)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        // Tap anywhere (not on a checkbox) advances; swipe ← next / → previous.
                        .contentShape(Rectangle())
                        .onTapGesture { goNext() }
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 24).onEnded { v in
                                guard abs(v.translation.width) > abs(v.translation.height),
                                      abs(v.translation.width) > 50 else { return }
                                if v.translation.width < 0 { goNext() } else { goPrev() }
                            }
                        )
                    }

                    Spacer(minLength: 0)
                }

                // Navigation
                HStack(spacing: 20) {
                    Button(action: goPrev) {
                        Image(systemName: "arrow.left")
                            .font(.title2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                    }
                    .buttonStyle(.bordered)
                    .disabled(currentStepIndex == 0)

                    if currentStepIndex == allSteps.count - 1 {
                        Button { finishing = true } label: {
                            Text("Done!")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Button(action: goNext) {
                            Image(systemName: "arrow.right")
                                .font(.title2)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding()
                .background(Color(.secondarySystemBackground))
            }

            // ── "All done!" page — its own page with a Back button (an accidental tap on the last
            //    step no longer auto-exits) + a heart prompt for recipes you don't own. ──
            if finishing {
                CookDonePage(viewModel: viewModel) { dismiss() }
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            // Start at the requested section, if any ("▶ Cook from here")
            if !didApplyStart, let sid = startSectionId, let idx = firstStepIndex(ofSection: sid) {
                currentStepIndex = idx
            }
            didApplyStart = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
}

// MARK: - "All done!" page (its own page; Back button + heart prompt for non-owned recipes)

private struct CookDonePage: View {
    @Bindable var viewModel: RecipeDetailViewModel
    let onBack: () -> Void
    @State private var shown = false

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 110))
                    .foregroundStyle(Color.accentColor)
                    .scaleEffect(shown ? 1 : 0.3)
                Text("All done!")
                    .font(.largeTitle).fontWeight(.bold)

                // Heart prompt — only for recipes you don't own (F20).
                if viewModel.canLike {
                    VStack(spacing: 8) {
                        Text("Enjoyed this recipe?").font(.subheadline).foregroundStyle(.secondary)
                        Button { viewModel.toggleLike() } label: {
                            Label(viewModel.isLiked ? "Liked" : "Like",
                                  systemImage: viewModel.isLiked ? "heart.fill" : "heart")
                                .font(.subheadline).fontWeight(.medium)
                                .foregroundStyle(viewModel.isLiked ? Color.red : Color.accentColor)
                                .padding(.horizontal, 18).padding(.vertical, 10)
                                .overlay(Capsule().stroke(Color.accentColor.opacity(0.4)))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 8)
                }

                Button("Back to recipe", action: onBack)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 12)
            }
            .opacity(shown ? 1 : 0)
        }
        .onAppear {
            withAnimation(.spring(response: 0.45, dampingFraction: 0.55)) { shown = true }
            viewModel.markCooked()   // record once on reaching the done page
        }
    }
}
