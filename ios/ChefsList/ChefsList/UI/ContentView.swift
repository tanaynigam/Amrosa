import SwiftUI

struct ContentView: View {
    @Environment(AppContainer.self) private var container
    @State private var isSignedIn: Bool = false
    @State private var deepLinkRecipeId: String? = nil
    // Push notification tap navigation
    @State private var pushShareId: String? = nil
    @State private var pushGoToAccount = false

    var body: some View {
        Group {
            if isSignedIn {
                MainAppView(
                    deepLinkRecipeId: $deepLinkRecipeId,
                    pushShareId: $pushShareId,
                    pushGoToAccount: $pushGoToAccount
                )
            } else {
                // Auth gate — full screen, no tab bar, no back button
                AuthView()
            }
        }
        .onOpenURL { url in
            handleDeepLink(url)
        }
        .onReceive(NotificationCenter.default.publisher(for: .pushNotificationTapped)) { note in
            guard let info = note.userInfo else { return }
            let type    = info["type"]    as? String ?? ""
            let shareId = info["shareId"] as? String ?? ""
            switch type {
            case "follow_request", "follow_accepted":
                pushGoToAccount = true
            case "recipe_shared" where !shareId.isEmpty:
                pushShareId = shareId
            default: break
            }
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
        // Handle chefslist://shared/{recipeId}
        if url.scheme == "chefslist" && url.host == "shared" {
            let recipeId = url.pathComponents.last
            deepLinkRecipeId = recipeId
        }
        // Handle https://chef-s-list.web.app/shared/{recipeId}
        else if url.host == "chef-s-list.web.app", url.pathComponents.count >= 3,
                url.pathComponents[1] == "shared" {
            deepLinkRecipeId = url.pathComponents[2]
        }
    }
}

// MARK: - Main app (shown when signed in)

private struct MainAppView: View {
    @Binding var deepLinkRecipeId: String?
    @Binding var pushShareId: String?
    @Binding var pushGoToAccount: Bool
    // F13: Discover is the default + left-most tab.
    @State private var selectedTab = 0

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                DiscoverView()
            }
            .tabItem { Label("Discover", systemImage: "sparkles") }
            .tag(0)

            NavigationStack {
                YourRecipesView()
            }
            .tabItem { Label("My Recipes", systemImage: "bookmark") }
            .tag(1)

            NavigationStack {
                SharedInboxView(openShareId: $pushShareId)
            }
            .tabItem { Label("Shared", systemImage: "tray") }
            .tag(2)

            NavigationStack {
                AccountView()
            }
            .tabItem { Label("Account", systemImage: "person") }
            .tag(3)
        }
        // Deep link (HTTPS / custom scheme) → Shared tab (now tag 2)
        .onChange(of: deepLinkRecipeId) { _, id in if id != nil { selectedTab = 2 } }
        // Push: recipe shared → Shared tab; SharedInboxView consumes pushShareId → opens review
        .onChange(of: pushShareId) { _, id in if id != nil { selectedTab = 2 } }
        // Push: follow request/accepted → Account tab
        .onChange(of: pushGoToAccount) { _, go in
            if go { selectedTab = 3; pushGoToAccount = false }
        }
    }
}
