import SwiftUI

struct ContentView: View {
    @Environment(AppContainer.self) private var container
    @State private var isSignedIn: Bool = false
    @State private var deepLinkRecipeId: String? = nil

    var body: some View {
        Group {
            if isSignedIn {
                MainAppView(deepLinkRecipeId: $deepLinkRecipeId)
            } else {
                // Auth gate — full screen, no tab bar, no back button
                AuthView()
            }
        }
        .onOpenURL { url in
            handleDeepLink(url)
        }
        .task {
            // Observe auth state — reacts to sign-in and sign-out
            for await user in container.authRepository.authStateStream() {
                let signedIn = user != nil && user?.isAnonymous == false
                let wasSignedIn = isSignedIn
                isSignedIn = signedIn

                // Trigger sync whenever a real user signs in
                if signedIn && !wasSignedIn {
                    await container.onSignIn()
                }
            }
        }
    }

    private func handleDeepLink(_ url: URL) {
        // Handle amrosa://shared/{recipeId}
        if url.scheme == "amrosa" && url.host == "shared" {
            let recipeId = url.pathComponents.last
            deepLinkRecipeId = recipeId
        }
        // Handle https://amrosa-2ec82.web.app/shared/{recipeId}
        else if url.host == "amrosa-2ec82.web.app", url.pathComponents.count >= 3,
                url.pathComponents[1] == "shared" {
            deepLinkRecipeId = url.pathComponents[2]
        }
    }
}

// MARK: - Main app (shown when signed in)

private struct MainAppView: View {
    @Binding var deepLinkRecipeId: String?
    @State private var navigateToSharedRecipe = false

    var body: some View {
        TabView {
            NavigationStack {
                AllRecipesView()
            }
            .tabItem { Label("All", systemImage: "book.open") }

            NavigationStack {
                YourRecipesView()
            }
            .tabItem { Label("Your Recipes", systemImage: "bookmark") }

            NavigationStack {
                SharedRecipesView(deepLinkRecipeId: $deepLinkRecipeId)
            }
            .tabItem { Label("Shared", systemImage: "globe") }

            NavigationStack {
                AccountView()
            }
            .tabItem { Label("Account", systemImage: "person") }
        }
    }
}
