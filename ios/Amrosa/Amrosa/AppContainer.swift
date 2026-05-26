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

        self.recipeRepository = repo
        self.authRepository = auth
        self.cloudFunctions = functions
        self.syncService = sync
    }

    func onLaunch() async {
        await authRepository.signInAnonymouslyIfNeeded()
        await syncService.sync()
    }
}
