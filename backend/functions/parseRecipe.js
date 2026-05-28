const { GoogleGenerativeAI } = require("@google/generative-ai");
const axios = require("axios");
const XLSX = require("xlsx");
const { RECIPE_SCHEMA_DESCRIPTION } = require("./recipeSchema");

// ─── System instructions ──────────────────────────────────────────────────────

const IMPORT_SYSTEM_INSTRUCTION =
  "You are a recipe parser for the Amrosa recipe app. " +
  "Given text content from a recipe source (web page, spreadsheet, document, or plain text), " +
  "extract the recipe into the exact JSON schema provided. Be thorough — capture every " +
  "ingredient, every step, and link ingredients to steps where mentioned. " +
  "Populate the metric and imperial conversion fields for every ingredient where a sensible " +
  "conversion exists. " +
  "METRIC: convert volumes (cups/tbsp/tsp/fl oz) → ml or L; weights (oz/lb) → g or kg. " +
  "IMPERIAL: dry or weight ingredients → oz (if under 1 lb) or lbs; liquid ingredients → fl oz. " +
  "NEVER use cups, tbsp, tsp, or any volume measure in the imperial fields — imperial is weight-first. " +
  "Leave conversion fields null for uncountable quantities (e.g. 'to taste', 'a pinch', counts like '2 cloves'). " +
  "If any important field is genuinely unclear, add a brief note in the parseNotes field. " +
  "Return ONLY valid JSON, no markdown, no explanation.";

const FREEFORM_SYSTEM_INSTRUCTION =
  "You are a recipe formatter for the Amrosa recipe app. " +
  "The user has typed a recipe from memory — it may be incomplete, informal, partial, or unstructured. " +
  "Your job is to extract everything you can and structure it into the exact JSON schema provided. " +
  "Fill in missing fields with sensible defaults: servings = 1 if not stated, times = null if unknown. " +
  "Populate the metric and imperial conversion fields for every ingredient where a sensible " +
  "conversion exists. " +
  "METRIC: convert volumes (cups/tbsp/tsp/fl oz) → ml or L; weights (oz/lb) → g or kg. " +
  "IMPERIAL: dry or weight ingredients → oz (if under 1 lb) or lbs; liquid ingredients → fl oz. " +
  "NEVER use cups, tbsp, tsp, or any volume measure in the imperial fields — imperial is weight-first. " +
  "Leave conversion fields null for uncountable quantities (e.g. 'to taste', 'a pinch', counts like '2 cloves'). " +
  "Use parseNotes to flag anything you had to infer, guess, or fill in with a default. " +
  "Return ONLY valid JSON, no markdown, no explanation.";

// ─── Public entry points ──────────────────────────────────────────────────────

/**
 * Parse a recipe from a URL.
 * Transparently handles:
 *   - Regular recipe pages (HTML → cleaned text → Gemini)
 *   - Google Sheets URLs (export as CSV → Gemini)
 *   - Google Docs URLs  (export as plain text → Gemini)
 */
async function parseRecipeFromUrl(url, apiKey) {
  let content, sourceHint;

  const sheetsId = extractGoogleSheetsId(url);
  const docsId   = extractGoogleDocsId(url);

  if (sheetsId) {
    content = await fetchGoogleSheet(sheetsId);
    sourceHint = `Google Sheets: ${url}`;
  } else if (docsId) {
    content = await fetchGoogleDoc(docsId);
    sourceHint = `Google Docs: ${url}`;
  } else {
    const html = await fetchPage(url);
    content = cleanHtml(html);
    sourceHint = url;
  }

  if (content.length < 80) {
    throw new Error(
      "Could not extract meaningful content from this page. " +
      "The page may be paywalled, login-gated, or not contain a recipe."
    );
  }

  const recipe = await callGemini(content, sourceHint, apiKey, IMPORT_SYSTEM_INSTRUCTION);
  validateRecipe(recipe);

  // Ensure the original URL is captured in sourceUrls
  if (!recipe.sourceUrls.includes(url)) {
    recipe.sourceUrls = [url, ...recipe.sourceUrls.filter(Boolean)];
  }

  return recipe;
}

/**
 * Parse a recipe from file content sent directly from the app.
 *
 * @param {string} content  — plain text / CSV string, OR base64-encoded XLSX bytes
 * @param {string} type     — "text" | "csv" | "xlsx"
 * @param {string} fileName — original file name (e.g. "Butter Chicken.xlsx")
 * @param {string} apiKey   — Gemini API key
 */
async function parseRecipeFromContent(content, type, fileName, apiKey) {
  let textContent;

  if (type === "xlsx") {
    textContent = parseXlsxToText(content);
  } else {
    textContent = content;
  }

  if (!textContent || textContent.trim().length < 50) {
    throw new Error(
      "Could not extract meaningful content from this file. " +
      "Make sure the file contains a recipe with ingredients and steps."
    );
  }

  if (textContent.length > 30000) {
    textContent = textContent.substring(0, 30000);
  }

  const sourceHint = `File: ${fileName}`;
  const recipe = await callGemini(textContent, sourceHint, apiKey, IMPORT_SYSTEM_INSTRUCTION);
  validateRecipe(recipe);

  recipe.sourceUrls = [];
  return recipe;
}

/**
 * Format a freeform recipe typed by the user into structured Amrosa schema.
 *
 * @param {string} text    — raw user-typed recipe text
 * @param {string} apiKey  — Gemini API key
 */
async function formatRecipeFromText(text, apiKey) {
  const trimmed = text.trim();
  if (trimmed.length < 20) {
    throw new Error("Please enter some recipe text to format.");
  }

  const capped = trimmed.length > 20000 ? trimmed.substring(0, 20000) : trimmed;

  const recipe = await callGemini(
    capped,
    "User-typed freeform recipe text",
    apiKey,
    FREEFORM_SYSTEM_INSTRUCTION
  );
  validateRecipe(recipe);

  recipe.sourceUrls = [];
  return recipe;
}

// ─── Google Sheets / Docs ─────────────────────────────────────────────────────

function extractGoogleSheetsId(url) {
  const match = url.match(
    /docs\.google\.com\/spreadsheets\/d\/([a-zA-Z0-9_-]+)/
  );
  return match ? match[1] : null;
}

function extractGoogleDocsId(url) {
  const match = url.match(
    /docs\.google\.com\/document\/d\/([a-zA-Z0-9_-]+)/
  );
  return match ? match[1] : null;
}

async function fetchGoogleSheet(sheetId) {
  const exportUrl = `https://docs.google.com/spreadsheets/d/${sheetId}/export?format=csv`;
  try {
    const response = await axios.get(exportUrl, {
      timeout: 15000,
      headers: { "User-Agent": "Mozilla/5.0 (compatible; Amrosa-RecipeParser/1.0)" },
      maxRedirects: 5,
    });
    return typeof response.data === "string"
      ? response.data
      : JSON.stringify(response.data);
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      throw new Error(
        "Failed to fetch URL: Google Sheet is private. " +
        "Change sharing to 'Anyone with the link can view' and try again."
      );
    }
    throw new Error(`Failed to fetch URL: ${err.message}`);
  }
}

async function fetchGoogleDoc(docId) {
  const exportUrl = `https://docs.google.com/document/d/${docId}/export?format=txt`;
  try {
    const response = await axios.get(exportUrl, {
      timeout: 15000,
      headers: { "User-Agent": "Mozilla/5.0 (compatible; Amrosa-RecipeParser/1.0)" },
      maxRedirects: 5,
    });
    return typeof response.data === "string"
      ? response.data
      : JSON.stringify(response.data);
  } catch (err) {
    if (err.response?.status === 403 || err.response?.status === 401) {
      throw new Error(
        "Failed to fetch URL: Google Doc is private. " +
        "Change sharing to 'Anyone with the link can view' and try again."
      );
    }
    throw new Error(`Failed to fetch URL: ${err.message}`);
  }
}

// ─── XLSX parsing ─────────────────────────────────────────────────────────────

function parseXlsxToText(base64String) {
  const buffer = Buffer.from(base64String, "base64");
  const workbook = XLSX.read(buffer, { type: "buffer" });

  const sheetTexts = [];
  for (const sheetName of workbook.SheetNames) {
    const worksheet = workbook.Sheets[sheetName];
    const csv = XLSX.utils.sheet_to_csv(worksheet, { blankrows: false });
    const trimmed = csv.trim();
    if (trimmed.length > 0) {
      sheetTexts.push(`=== Sheet: ${sheetName} ===\n${trimmed}`);
    }
  }

  return sheetTexts.join("\n\n");
}

// ─── HTML fetch + clean ───────────────────────────────────────────────────────

async function fetchPage(url) {
  try {
    const response = await axios.get(url, {
      timeout: 15000,
      maxRedirects: 5,
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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

function cleanHtml(html) {
  let text = html;

  text = text.replace(/<script[\s\S]*?<\/script>/gi, "");
  text = text.replace(/<style[\s\S]*?<\/style>/gi, "");
  text = text.replace(/<svg[\s\S]*?<\/svg>/gi, "");
  text = text.replace(/<noscript[\s\S]*?<\/noscript>/gi, "");
  text = text.replace(/<nav[\s\S]*?<\/nav>/gi, "");
  text = text.replace(/<footer[\s\S]*?<\/footer>/gi, "");
  text = text.replace(/<aside[\s\S]*?<\/aside>/gi, "");
  text = text.replace(/<!--[\s\S]*?-->/g, "");
  text = text.replace(/<[^>]+>/g, " ");

  const entities = {
    "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#39;": "'",
    "&nbsp;": " ", "&#x27;": "'", "&#x2F;": "/", "&rsquo;": "'",
    "&lsquo;": "'", "&rdquo;": '"', "&ldquo;": '"', "&mdash;": "—",
    "&ndash;": "–", "&frac12;": "½", "&frac14;": "¼", "&frac34;": "¾",
  };
  for (const [entity, char] of Object.entries(entities)) {
    text = text.replaceAll(entity, char);
  }

  text = text.replace(/\s+/g, " ").trim();

  if (text.length > 20000) {
    text = text.substring(0, 20000);
  }

  return text;
}

// ─── Gemini API ───────────────────────────────────────────────────────────────

async function callGemini(content, sourceHint, apiKey, systemInstruction) {
  const genAI = new GoogleGenerativeAI(apiKey);

  const model = genAI.getGenerativeModel({
    model: "gemini-2.5-flash",
    systemInstruction,
    generationConfig: {
      responseMimeType: "application/json",
      maxOutputTokens: 65536,
      thinkingConfig: { thinkingBudget: 0 },
    },
  });

  const prompt =
    `Parse the following recipe content into Amrosa's recipe JSON format.\n\n` +
    `Source: ${sourceHint}\n\n` +
    `SCHEMA:\n${RECIPE_SCHEMA_DESCRIPTION}\n\n` +
    `CONTENT:\n${content}`;

  const result = await model.generateContent(prompt);
  const response = result.response;
  const finishReason = response.candidates?.[0]?.finishReason;
  console.log("Gemini finish reason:", finishReason);

  let jsonText = response.text().trim();
  console.log("Gemini response length:", jsonText.length);
  console.log("Gemini response preview:", jsonText.substring(0, 500));

  if (jsonText.startsWith("```")) {
    jsonText = jsonText
      .replace(/^```(?:json)?\s*\n?/, "")
      .replace(/\n?```\s*$/, "");
  }

  try {
    return JSON.parse(jsonText);
  } catch (err) {
    console.error("JSON parse failed. Full response:", jsonText.substring(0, 2000));
    throw new Error(`Gemini returned invalid JSON: ${err.message}`);
  }
}

// ─── Validation ───────────────────────────────────────────────────────────────

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

  if (!Array.isArray(recipe.stepIngredientRefs)) recipe.stepIngredientRefs = [];
  if (!Array.isArray(recipe.sourceUrls)) recipe.sourceUrls = [];
  if (!Array.isArray(recipe.tags)) recipe.tags = [];

  recipe.scaleIngredientId = recipe.scaleIngredientId || null;
  recipe.scaleStep         = recipe.scaleStep || 1.0;
  recipe.isCustomized      = false;
  recipe.baseServingsMin   = recipe.baseServingsMin || null;
  recipe.baseServingsMax   = recipe.baseServingsMax || null;
  recipe.prepTimeMinutes   = recipe.prepTimeMinutes || null;
  recipe.cookTimeMinutes   = recipe.cookTimeMinutes || null;
  recipe.imageUrl          = recipe.imageUrl || null;
  recipe.description       = recipe.description || null;
  recipe.parseNotes        = recipe.parseNotes || null;

  // Ensure conversion fields default to null if omitted by Gemini
  for (const ing of recipe.ingredients) {
    ing.quantityValueMetric    = ing.quantityValueMetric    ?? null;
    ing.quantityUnitMetric     = ing.quantityUnitMetric     ?? null;
    ing.quantityDisplayMetric  = ing.quantityDisplayMetric  ?? null;
    ing.quantityValueImperial  = ing.quantityValueImperial  ?? null;
    ing.quantityUnitImperial   = ing.quantityUnitImperial   ?? null;
    ing.quantityDisplayImperial = ing.quantityDisplayImperial ?? null;
  }
}

module.exports = { parseRecipeFromUrl, parseRecipeFromContent, formatRecipeFromText };
