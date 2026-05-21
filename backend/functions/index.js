const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { setGlobalOptions } = require("firebase-functions/v2");
const { parseRecipeFromUrl } = require("./parseRecipe");

// Global options for cost control
setGlobalOptions({ maxInstances: 5 });

// Secret reference — value is injected at runtime, never in code
const geminiKey = defineSecret("GEMINI_API_KEY");

/**
 * parseRecipeUrl — HTTPS callable Cloud Function
 *
 * Called from the Android app via Firebase Functions SDK.
 * Accepts: { url: "https://..." }
 * Returns: parsed recipe JSON matching Amrosa schema
 */
exports.parseRecipeUrl = onCall(
  {
    secrets: [geminiKey],
    timeoutSeconds: 120,
    memory: "512MiB",
    region: "us-central1",
  },
  async (request) => {
    const url = request.data?.url;

    // Validate input
    if (!url || typeof url !== "string") {
      throw new HttpsError("invalid-argument", "A recipe URL is required.");
    }

    // Basic URL validation
    try {
      const parsed = new URL(url);
      if (!["http:", "https:"].includes(parsed.protocol)) {
        throw new Error("bad protocol");
      }
    } catch {
      throw new HttpsError("invalid-argument", "Invalid URL format.");
    }

    // Get the API key from secrets
    const apiKey = geminiKey.value();
    if (!apiKey) {
      throw new HttpsError(
        "internal",
        "Gemini API key is not configured. Run: firebase functions:secrets:set GEMINI_API_KEY"
      );
    }

    // Parse the recipe
    try {
      const recipe = await parseRecipeFromUrl(url, apiKey);

      // Add timestamps
      const now = Date.now();
      recipe.createdAt = now;
      recipe.updatedAt = now;

      return { recipe };
    } catch (err) {
      // Surface user-friendly errors
      if (err.message.includes("Failed to fetch URL")) {
        throw new HttpsError("not-found", err.message);
      }
      if (err.message.includes("Could not extract")) {
        throw new HttpsError("failed-precondition", err.message);
      }
      if (err.message.includes("invalid JSON")) {
        throw new HttpsError(
          "internal",
          `Failed to parse: ${err.message}`
        );
      }
      if (err.message.includes("missing a title") ||
          err.message.includes("has no sections") ||
          err.message.includes("has no ingredients") ||
          err.message.includes("has no steps")) {
        throw new HttpsError("failed-precondition", err.message);
      }

      throw new HttpsError("internal", `Recipe import failed: ${err.message}`);
    }
  }
);
