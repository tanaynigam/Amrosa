import FirebaseFunctions
import Foundation

final class CloudFunctionsService {
    private let functions = Functions.functions(region: "us-central1")

    // MARK: - Parse recipe from URL / Sheets / Docs

    func parseRecipeUrl(_ url: String) async throws -> ParsedRecipeData {
        let result = try await functions.httpsCallable("parseRecipeUrl").call(["url": url])
        guard let data = result.data as? [String: Any] else {
            throw CloudFunctionError.invalidResponse
        }
        return try mapToParsedRecipeData(data)
    }

    // MARK: - Parse recipe from file content

    func parseRecipeContent(content: String, type: String, fileName: String) async throws -> ParsedRecipeData {
        let payload: [String: Any] = ["content": content, "type": type, "fileName": fileName]
        let result = try await functions.httpsCallable("parseRecipeContent").call(payload)
        guard let data = result.data as? [String: Any] else {
            throw CloudFunctionError.invalidResponse
        }
        return try mapToParsedRecipeData(data)
    }

    // MARK: - Format freeform text

    func formatRecipeText(_ text: String) async throws -> ParsedRecipeData {
        let result = try await functions.httpsCallable("formatRecipeText").call(["text": text])
        guard let data = result.data as? [String: Any] else {
            throw CloudFunctionError.invalidResponse
        }
        return try mapToParsedRecipeData(data)
    }

    // MARK: - Mapping

    private func mapToParsedRecipeData(_ data: [String: Any]) throws -> ParsedRecipeData {
        guard let title = data["title"] as? String else {
            throw CloudFunctionError.missingField("title")
        }

        let sectionsRaw = data["sections"] as? [[String: Any]] ?? []
        let sections = sectionsRaw.compactMap { s -> ParsedSection? in
            guard let id = s["id"] as? String, let name = s["name"] as? String else { return nil }
            return ParsedSection(id: id, name: name, orderIndex: s["orderIndex"] as? Int ?? 0)
        }

        let ingredientsRaw = data["ingredients"] as? [[String: Any]] ?? []
        let ingredients = ingredientsRaw.compactMap { i -> ParsedIngredient? in
            guard let id = i["id"] as? String, let name = i["name"] as? String else { return nil }
            return ParsedIngredient(
                id: id,
                sectionId: i["sectionId"] as? String,
                name: name,
                quantityValue: i["quantityValue"] as? Double,
                quantityUnit: i["quantityUnit"] as? String,
                quantityDisplay: i["quantityDisplay"] as? String,
                groupLabel: i["groupLabel"] as? String,
                isOptional: i["isOptional"] as? Bool ?? false,
                orderIndex: i["orderIndex"] as? Int ?? 0,
                quantityValueMetric: i["quantityValueMetric"] as? Double,
                quantityUnitMetric: i["quantityUnitMetric"] as? String,
                quantityDisplayMetric: i["quantityDisplayMetric"] as? String,
                quantityValueImperial: i["quantityValueImperial"] as? Double,
                quantityUnitImperial: i["quantityUnitImperial"] as? String,
                quantityDisplayImperial: i["quantityDisplayImperial"] as? String
            )
        }

        let stepsRaw = data["steps"] as? [[String: Any]] ?? []
        let steps = stepsRaw.compactMap { s -> ParsedStep? in
            guard let id = s["id"] as? String, let instruction = s["instruction"] as? String else { return nil }
            return ParsedStep(id: id, sectionId: s["sectionId"] as? String, instruction: instruction, orderIndex: s["orderIndex"] as? Int ?? 0)
        }

        let refsRaw = data["stepIngredientRefs"] as? [[String: Any]] ?? []
        let refs = refsRaw.compactMap { r -> ParsedStepRef? in
            guard let stepId = r["stepId"] as? String, let ingredientId = r["ingredientId"] as? String else { return nil }
            return ParsedStepRef(stepId: stepId, ingredientId: ingredientId, quantityDisplay: r["quantityDisplay"] as? String)
        }

        let sourceUrls: [String]
        if let urls = data["sourceUrls"] as? [String] {
            sourceUrls = urls
        } else if let url = data["sourceUrl"] as? String {
            sourceUrls = [url]
        } else {
            sourceUrls = []
        }

        return ParsedRecipeData(
            title: title,
            description: data["description"] as? String,
            sourceUrls: sourceUrls,
            baseServings: data["baseServings"] as? Int ?? 1,
            baseServingsMin: data["baseServingsMin"] as? Int,
            baseServingsMax: data["baseServingsMax"] as? Int,
            prepTimeMinutes: data["prepTimeMinutes"] as? Int,
            cookTimeMinutes: data["cookTimeMinutes"] as? Int,
            imageUrl: data["imageUrl"] as? String,
            tags: data["tags"] as? [String] ?? [],
            sections: sections,
            ingredients: ingredients,
            steps: steps,
            stepIngredientRefs: refs,
            parseNotes: data["parseNotes"] as? String
        )
    }
}

enum CloudFunctionError: LocalizedError {
    case invalidResponse
    case missingField(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "Received an unexpected response from the server."
        case .missingField(let f): return "Server response was missing required field: \(f)."
        }
    }
}
