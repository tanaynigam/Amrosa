import SwiftUI

struct ContentView: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        TabView {
            NavigationStack {
                AllRecipesView()
            }
            .tabItem {
                Label("All", systemImage: "book.open")
            }

            NavigationStack {
                YourRecipesView()
            }
            .tabItem {
                Label("Your Recipes", systemImage: "bookmark")
            }

            NavigationStack {
                SharedRecipesView()
            }
            .tabItem {
                Label("Shared", systemImage: "globe")
            }

            NavigationStack {
                AccountView()
            }
            .tabItem {
                Label("Account", systemImage: "person")
            }
        }
    }
}
