import XCTest
import SwiftData
@testable import Amrosa

final class AmrosaTests: XCTestCase {

    // MARK: - QuantityScaler

    func testScaleWholeNumber() {
        let result = QuantityScaler.scale(quantityValue: 2.0, quantityUnit: "cup", quantityDisplay: "2 cups", scale: 1.5)
        XCTAssertEqual(result, "3 cup")
    }

    func testScaleFraction() {
        let result = QuantityScaler.scale(quantityValue: 0.5, quantityUnit: "cup", quantityDisplay: "½ cup", scale: 2.0)
        XCTAssertEqual(result, "1 cup")
    }

    func testScaleNoUnit() {
        let result = QuantityScaler.scale(quantityValue: 3.0, quantityUnit: nil, quantityDisplay: "3 eggs", scale: 2.0)
        XCTAssertEqual(result, "6")
    }

    func testScaleNilValue() {
        let result = QuantityScaler.scale(quantityValue: nil, quantityUnit: nil, quantityDisplay: "to taste", scale: 2.0)
        XCTAssertEqual(result, "to taste")
    }

    func testScaleHalfFraction() {
        let result = QuantityScaler.scale(quantityValue: 0.25, quantityUnit: "tsp", quantityDisplay: "¼ tsp", scale: 2.0)
        XCTAssertEqual(result, "½ tsp")
    }

    // MARK: - RecipeModel

    func testRecipeModelJSONRoundtrip() throws {
        let schema = Schema([RecipeModel.self, RecipeSectionModel.self, IngredientModel.self,
                             StepModel.self, StepIngredientRefModel.self, RecipeNoteModel.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        let recipe = RecipeModel(
            id: "test-001",
            title: "Test Recipe",
            sourceUrls: ["https://example.com"],
            baseServings: 4,
            tags: ["Dinner", "Test"]
        )
        context.insert(recipe)
        try context.save()

        let descriptor = FetchDescriptor<RecipeModel>(predicate: #Predicate { $0.id == "test-001" })
        let fetched = try context.fetch(descriptor)
        XCTAssertEqual(fetched.count, 1)
        XCTAssertEqual(fetched[0].title, "Test Recipe")
        XCTAssertEqual(fetched[0].sourceUrls, ["https://example.com"])
        XCTAssertEqual(fetched[0].tags, ["Dinner", "Test"])
    }

    // MARK: - RecipeRepository

    func testInsertAndFetchRecipe() async throws {
        let schema = Schema([RecipeModel.self, RecipeSectionModel.self, IngredientModel.self,
                             StepModel.self, StepIngredientRefModel.self, RecipeNoteModel.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)

        let repo = await MainActor.run { RecipeRepository(context: context) }

        let recipe = RecipeModel(id: "repo-test-001", title: "Repo Test", baseServings: 2)
        try await MainActor.run {
            try repo.insertFullRecipe(recipe, sections: [], ingredients: [], steps: [], refs: [])
        }

        let fetched = try await MainActor.run { try repo.fetchRecipe(id: "repo-test-001") }
        XCTAssertNotNil(fetched)
        XCTAssertEqual(fetched?.title, "Repo Test")
    }

    func testConfirmReview() async throws {
        let schema = Schema([RecipeModel.self, RecipeSectionModel.self, IngredientModel.self,
                             StepModel.self, StepIngredientRefModel.self, RecipeNoteModel.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)
        let repo = await MainActor.run { RecipeRepository(context: context) }

        let recipe = RecipeModel(id: "review-001", title: "Review Test", baseServings: 1, needsReview: true)
        try await MainActor.run { try repo.insertFullRecipe(recipe, sections: [], ingredients: [], steps: [], refs: []) }

        try await MainActor.run { try repo.confirmReview(recipeId: "review-001") }

        let fetched = try await MainActor.run { try repo.fetchRecipe(id: "review-001") }
        XCTAssertEqual(fetched?.needsReview, false)
    }
}
