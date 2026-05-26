import SwiftUI

struct CookingModeView: View {
    @Bindable var viewModel: RecipeDetailViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var currentStepIndex = 0

    private var allSteps: [StepModel] {
        viewModel.recipe.steps.sorted { $0.orderIndex < $1.orderIndex }
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

                            // Referenced ingredients
                            let refs = step.ingredientRefs
                            if !refs.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Ingredients for this step")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    ForEach(refs) { ref in
                                        if let ing = ref.ingredient {
                                            HStack {
                                                Text(ref.quantityDisplay ?? viewModel.scaledQuantity(for: ing))
                                                    .fontWeight(.medium)
                                                Text(ing.name)
                                            }
                                            .font(.subheadline)
                                        }
                                    }
                                }
                                .padding()
                                .background(Color(.secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            }
                        }
                        .padding(24)
                    }

                    Spacer(minLength: 0)
                }

                // Navigation
                HStack(spacing: 20) {
                    Button {
                        if currentStepIndex > 0 { currentStepIndex -= 1 }
                    } label: {
                        Image(systemName: "arrow.left")
                            .font(.title2)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                    }
                    .buttonStyle(.bordered)
                    .disabled(currentStepIndex == 0)

                    if currentStepIndex == allSteps.count - 1 {
                        Button {
                            dismiss()
                        } label: {
                            Text("Done!")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Button {
                            if currentStepIndex < allSteps.count - 1 { currentStepIndex += 1 }
                        } label: {
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
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
}
