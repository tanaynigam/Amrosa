const { GoogleGenerativeAI } = require("@google/generative-ai");
const axios = require("axios");
const { RECIPE_SCHEMA_DESCRIPTION } = require("./recipeSchema");

/**
 * Fetch a recipe URL, extract its content, and parse it into
 * Amrosa's recipe schema using the Gemini API.
 *
 * @param {string} url — the recipe page URL
 * @param {string} apiKey — Gemini API key (from Firebase secrets)
 * @returns {object} — parsed recipe JSON matching Amrosa schema
 */
async function parseRecipeFromUrl(url, apiKey) {
  // 1. Fetch the page HTML
  const html = await fetchPage(url);

  // 2. Clean the HTML — strip scripts, styles, nav, ads, keep body text
  const cleaned = cleanHtml(html);

  if (cleaned.length < 100) {
    throw new Error(
      "Could not extract meaningful content from this page. " +
      "The page may be paywalled, login-gated, or not contain a recipe."
    );
  }

  // 3. Send to Gemini API
  const recipe = await callGemini(url, cleaned, apiKey);

  // 4. Validate and return
  validateRecipe(recipe);
  return recipe;
}

/**
 * Fetch page HTML with a browser-like User-Agent.
 */
async function fetchPage(url) {
  try {
    const response = await axios.get(url, {
      timeout: 15000,
      maxRedirects: 5,
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
      },
    });
    return response.data;
  } catch (err) {
    if (err.response) {
      throw new Error(`Failed to fetch URL (HTTP ${err.response.status})`);
    }
    throw new Error(`Failed to fetch URL: ${err.message}`);
  }
}

/**
 * Strip scripts, styles, SVGs, nav, footer, ads from HTML.
 * Return plain-ish text content, trimmed to ~60k chars to stay
 * well within Gemini's context window.
 */
function cleanHtml(html) {
  let text = html;

  // Remove script, style, svg, noscript blocks
  text = text.replace(/<script[\s\S]*?<\/script>/gi, "");
  text = text.replace(/<style[\s\S]*?<\/style>/gi, "");
  text = text.replace(/<svg[\s\S]*?<\/svg>/gi, "");
  text = text.replace(/<noscript[\s\S]*?<\/noscript>/gi, "");

  // Remove nav, header, footer, aside elements (common non-recipe content)
  text = text.replace(/<nav[\s\S]*?<\/nav>/gi, "");
  text = text.replace(/<footer[\s\S]*?<\/footer>/gi, "");
  text = text.replace(/<aside[\s\S]*?<\/aside>/gi, "");

  // Remove HTML comments
  text = text.replace(/<!--[\s\S]*?-->/g, "");

  // Remove remaining HTML tags but keep text content
  text = text.replace(/<[^>]+>/g, " ");

  // Decode common HTML entities
  text = text.replace(/&amp;/g, "&");
  text = text.replace(/&lt;/g, "<");
  text = text.replace(/&gt;/g, ">");
  text = text.replace(/&quot;/g, '"');
  text = text.replace(/&#39;/g, "'");
  text = text.replace(/&nbsp;/g, " ");
  text = text.replace(/&#x27;/g, "'");
  text = text.replace(/&#x2F;/g, "/");
  text = text.replace(/&rsquo;/g, "'");
  text = text.replace(/&lsquo;/g, "'");
  text = text.replace(/&rdquo;/g, '"');
  text = text.replace(/&ldquo;/g, '"');
  text = text.replace(/&mdash;/g, "—");
  text = text.replace(/&ndash;/g, "–");
  text = text.replace(/&frac12;/g, "½");
  text = text.replace(/&frac14;/g, "¼");
  text = text.replace(/&frac34;/g, "¾");

  // Collapse whitespace
  text = text.replace(/\s+/g, " ").trim();

  // Trim to ~20k chars — keeps us well within free-tier token limits
  if (text.length > 20000) {
    text = text.substring(0, 20000);
  }

  return text;
}

/**
 * Call Gemini API to parse the cleaned page content into a recipe.
 */
async function callGemini(url, cleanedContent, apiKey) {
  const genAI = new GoogleGenerativeAI(apiKey);

  const model = genAI.getGenerativeModel({
    model: "gemini-2.5-flash",
    systemInstruction:
      "You are a recipe parser for the Amrosa recipe app. " +
      "Given the text content of a recipe web page, extract the recipe " +
      "into the exact JSON schema provided. Be thorough — capture every " +
      "ingredient, every step, and link ingredients to steps where mentioned. " +
      "Return ONLY valid JSON, no markdown, no explanation.",
    generationConfig: {
      responseMimeType: "application/json",
      maxOutputTokens: 8192,
    },
  });

  const result = await model.generateContent(
    `Parse the following recipe page into Amrosa's recipe JSON format.\n\n` +
    `Source URL: ${url}\n\n` +
    `SCHEMA:\n${RECIPE_SCHEMA_DESCRIPTION}\n\n` +
    `PAGE CONTENT:\n${cleanedContent}`
  );

  const response = result.response;
  const finishReason = response.candidates?.[0]?.finishReason;
  console.log("Gemini finish reason:", finishReason);

  let jsonText = response.text().trim();
  console.log("Gemini response length:", jsonText.length);
  console.log("Gemini response preview:", jsonText.substring(0, 500));

  // Strip markdown code fences if present despite instructions
  if (jsonText.startsWith("```")) {
    jsonText = jsonText.replace(/^```(?:json)?\s*\n?/, "").replace(/\n?```\s*$/, "");
  }

  try {
    return JSON.parse(jsonText);
  } catch (err) {
    console.error("JSON parse failed. Full response:", jsonText.substring(0, 2000));
    throw new Error(`Gemini returned invalid JSON: ${err.message}`);
  }
}

/**
 * Basic validation — ensure required top-level fields and at least
 * one section, ingredient, and step exist.
 */
function validateRecipe(recipe) {
  if (!recipe.title || typeof recipe.title !== "string") {
    throw new Error("Parsed recipe is missing a title");
  }
  if (!Array.isArray(recipe.sections) || recipe.sections.length === 0) {
    throw new Error("Parsed recipe has no sections");
  }
  if (!Array.isArray(recipe.ingredients) || recipe.ingredients.length === 0) {
    throw new Error("Parsed recipe has no ingredients");
  }
  if (!Array.isArray(recipe.steps) || recipe.steps.length === 0) {
    throw new Error("Parsed recipe has no steps");
  }
  if (typeof recipe.baseServings !== "number" || recipe.baseServings < 1) {
    recipe.baseServings = 1;
  }

  // Ensure arrays that might be missing are present
  if (!Array.isArray(recipe.stepIngredientRefs)) {
    recipe.stepIngredientRefs = [];
  }
  if (!Array.isArray(recipe.sourceUrls)) {
    recipe.sourceUrls = [];
  }
  if (!Array.isArray(recipe.tags)) {
    recipe.tags = [];
  }

  // Set defaults for missing optional fields
  recipe.scaleIngredientId = recipe.scaleIngredientId || null;
  recipe.scaleStep = recipe.scaleStep || 1.0;
  recipe.isCustomized = false;
  recipe.baseServingsMin = recipe.baseServingsMin || null;
  recipe.baseServingsMax = recipe.baseServingsMax || null;
  recipe.prepTimeMinutes = recipe.prepTimeMinutes || null;
  recipe.cookTimeMinutes = recipe.cookTimeMinutes || null;
  recipe.imageUrl = recipe.imageUrl || null;
  recipe.description = recipe.description || null;
}

module.exports = { parseRecipeFromUrl };
