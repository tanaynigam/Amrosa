import SwiftData
import Foundation

@Observable
@MainActor
final class AppContainer {
    let modelContainer: ModelContainer
    let recipeRepository: RecipeRepository
    let authRepository: AuthRepository
    let syncService: RecipeSyncService
    let cloudFunctions: CloudFunctionsService
    let sharedRecipeService: SharedRecipeService
    let socialRepository: SocialRepository

    init() {
        let schema = Schema([
            RecipeModel.self,
            RecipeSectionModel.self,
            IngredientModel.self,
            StepModel.self,
            StepIngredientRefModel.self,
            RecipeNoteModel.self
        ])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
        let container = try! ModelContainer(for: schema, configurations: [config])
        self.modelContainer = container

        let context = ModelContext(container)
        let repo = RecipeRepository(context: context)
        let auth = AuthRepository()
        let functions = CloudFunctionsService()
        let sync = RecipeSyncService(repository: repo, authRepository: auth)
        let shared = SharedRecipeService(authRepository: auth, repository: repo)
        let social = SocialRepository(authRepository: auth)

        self.recipeRepository = repo
        self.authRepository = auth
        self.cloudFunctions = functions
        self.syncService = sync
        self.sharedRecipeService = shared
        self.socialRepository = social
    }

    /// Called after Firebase is configured and user is signed in.
    /// Upserts the user profile and syncs personal recipes from Firestore.
    func onSignIn() async {
        await socialRepository.upsertProfile()
        await syncService.sync()
    }

    /// Wipe all local SwiftData records and clear sync preferences.
    /// Called before sign-out so the next user starts clean.
    func clearAllLocalData() {
        // Delete all SwiftData records
        try? recipeRepository.deleteAllRecipes()
        // Clear sync timestamp
        UserDefaults.standard.removeObject(forKey: "amrosa_last_sync")
    }
}
