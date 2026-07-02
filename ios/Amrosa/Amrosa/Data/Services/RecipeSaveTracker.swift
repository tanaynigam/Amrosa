import Observation
import Foundation

/// App-scoped tracker for in-flight recipe saves (F19). The editor exits edit mode immediately on
/// Save; the actual write runs here so it **survives leaving the recipe**. Screens observe `saving`
/// to show a small spinner (My-Recipes card, detail top bar) while a recipe is persisting.
@Observable
@MainActor
final class RecipeSaveTracker {
    private(set) var saving: Set<String> = []

    func isSaving(_ recipeId: String) -> Bool { saving.contains(recipeId) }

    /// Run `work` to completion regardless of which screen is showing. The closure typically holds a
    /// strong reference to the editor VM, keeping it alive until the save finishes.
    func run(_ recipeId: String, _ work: @escaping () async -> Void) {
        saving.insert(recipeId)
        Task {
            await work()
            saving.remove(recipeId)
        }
    }
}
