import SwiftData
import Foundation
import UserNotifications
import UIKit
import FirebaseMessaging

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
            RecipeNoteModel.self,
            ShoppingCheckModel.self
        ])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
        let container = try! ModelContainer(for: schema, configurations: [config])
        self.modelContainer = container

        let context = ModelContext(container)
        let repo = RecipeRepository(context: context)
        let auth = AuthRepository()
        let functions = CloudFunctionsService()
        let shared = SharedRecipeService(authRepository: auth, repository: repo)
        let sync = RecipeSyncService(repository: repo, authRepository: auth, sharedRecipeService: shared)
        let social = SocialRepository(authRepository: auth)

        self.recipeRepository = repo
        self.authRepository = auth
        self.cloudFunctions = functions
        self.syncService = sync
        self.sharedRecipeService = shared
        self.socialRepository = social
    }

    /// Called after Firebase is configured and user is signed in.
    /// Upserts the user profile, syncs recipes, and registers for push notifications.
    func onSignIn() async {
        await socialRepository.upsertProfile()
        await syncService.sync()
        await requestPushPermission()
        await refreshFcmToken()
        await socialRepository.repairFriendships()
    }

    /// Fetch the current FCM token and write it to Firestore immediately on sign-in,
    /// in case onNewToken was never called since the last install or token rotation.
    private func refreshFcmToken() async {
        do {
            let token = try await Messaging.messaging().token()
            await socialRepository.storeFcmToken(token)
        } catch {
            // Non-critical — the AppDelegate's MessagingDelegate will handle the next rotation
        }
    }

    /// Ask the user for notification permission and register with APNs.
    /// Safe to call multiple times — iOS only prompts once.
    private func requestPushPermission() async {
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        if granted {
            await MainActor.run {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
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
