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
  "Populate ONLY the metric conversion fields for every ingredient where a sensible conversion exists: " +
  "volumes (cups/tbsp/tsp/fl oz) → ml or L; weights (oz/lb) → g or kg. " +
  "If the original unit is already metric (g, kg, ml, L), copy it into the metric fields as-is. " +
  "Leave ALL imperial fields (quantityValueImperial, quantityUnitImperial, quantityDisplayImperial) as null — " +
  "they are computed automatically from metric. " +
  "Leave ALL conversion fields null for uncountable quantities (e.g. 'to taste', 'a pinch', '2 cloves', '3 eggs'). " +
  "If any important field is genuinely unclear, add a brief note in the parseNotes field. " +
  "Return ONLY valid JSON, no markdown, no explanation.";

const FREEFORM_SYSTEM_INSTRUCTION =
  "You are a recipe formatter for the Amrosa recipe app. " +
  "The user has typed a recipe from memory — it may be incomplete, informal, partial, or unstructured. " +
  "Your job is to extract everything you can and structure it into the exact JSON schema provided. " +
  "Fill in missing fields with sensible defaults: servings = 1 if not stated, times = null if unknown. " +
  "Populate ONLY the metric conversion fields for every ingredient where a sensible conversion exists: " +
  "volumes (cups/tbsp/tsp/fl oz) → ml or L; weights (oz/lb) → g or kg. " +
  "If the original unit is already metric (g, kg, ml, L), copy it into the metric fields as-is. " +
  "Leave ALL imperial fields (quantityValueImperial, quantityUnitImperial, quantityDisplayImperial) as null — " +
  "they are computed automatically from metric. " +
  "Leave ALL conversion fields null for uncountable quantities (e.g. 'to taste', 'a pinch', '2 cloves', '3 eggs'). " +
  "NEVER use cups, tbsp, tsp, or any volume measure in the imperial fields — imperial is weight-first. " +
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
    // Extract JSON-LD BEFORE cleaning — cleanHtml strips <script> tags
    const jsonLd = extractJsonLdRecipe(html);
    content = jsonLd
      ? `Structured recipe data (JSON-LD schema.org/Recipe):\n${jsonLd}`
      : cleanHtml(html);
    sourceHint = url;
  }

  if (content.length < 80) {
    throw new Error(
      "Could not extract meaningful content from this page. " +
      "The page may be paywalled, login-gated, or not contain a recipe."
    );
  }

  const recipe = await callGemini(geminiContent, sourceHint, apiKey, IMPORT_SYSTEM_INSTRUCTION);
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

// ─── JSON-LD extraction ───────────────────────────────────────────────────────

/**
 * Attempts to extract a Recipe JSON-LD block from raw HTML.
 * Most recipe sites embed structured data for Google rich snippets — this is
 * much more reliable than scraping HTML, and works even on sites that otherwise
 * block scrapers because it's part of the initial document response.
 * Returns the JSON object as a formatted string, or null if none found.
 */
function extractJsonLdRecipe(html) {
  const scriptRegex =
    /<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  while ((match = scriptRegex.exec(html)) !== null) {
    try {
      const raw = match[1].trim();
      if (!raw) continue;
      const data = JSON.parse(raw);
      // Some sites wrap everything in a @graph array
      const candidates = Array.isArray(data["@graph"])
        ? data["@graph"]
        : [data];
      for (const item of candidates) {
        const types = Array.isArray(item["@type"])
          ? item["@type"]
          : [item["@type"]];
        if (types.includes("Recipe")) {
          return JSON.stringify(item, null, 2);
        }
      }
    } catch {
      // Malformed JSON-LD — skip and try the next script block
    }
  }
  return null;
}

// ─── HTML fetch + clean ───────────────────────────────────────────────────────

async function fetchPage(url) {
  try {
    const response = await axios.get(url, {
      timeout: 20000,
      maxRedirects: 5,
      decompress: true,
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
          "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br",
        "Cache-Control": "no-cache",
        "Upgrade-Insecure-Requests": "1",
        "sec-ch-ua":
          '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
        "sec-ch-ua-mobile": "?0",
        "sec-ch-ua-platform": '"Windows"',
        "sec-fetch-dest": "document",
        "sec-fetch-mode": "navigate",
        "sec-fetch-site": "none",
        "sec-fetch-user": "?1",
      },
    });
    return response.data;
  } catch (err) {
    if (err.response) {
      const status = err.response.status;
      if (status === 402 || status === 403) {
        throw new Error(
          `BLOCKED:${status}:This website blocks automated access. ` +
          `Try copying the recipe text and using "Type it out" in the Personal tab instead.`
        );
      }
      throw new Error(`Failed to fetch URL (HTTP ${status})`);
    }
    if (err.code === "ECONNREFUSED" || err.code === "ENOTFOUND") {
      throw new Error(`Failed to fetch URL: Could not reach ${new URL(url).hostname}`);
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

// ─── Imperial computation ─────────────────────────────────────────────────────

/**
 * Format a number removing needless trailing zeros.
 * 2.0 → "2",  2.50 → "2.5",  0.25 → "0.25"
 */
function fmtNum(n) {
  return parseFloat(n.toFixed(2)).toString();
}

/**
 * Adaptive rounding for imperial amounts so small values don't collapse to 0:
 *   >= 1   → 1 decimal  (e.g. 4.2 oz)
 *   >= 0.1 → 2 decimals (e.g. 0.18 oz)
 *   < 0.1  → 3 decimals (e.g. 0.035 oz)
 * Returns a clean Number (JS prints it without trailing zeros).
 */
function impRound(value) {
  if (value >= 1)   return Math.round(value * 10) / 10;
  if (value >= 0.1) return Math.round(value * 100) / 100;
  return Math.round(value * 1000) / 1000;
}

/**
 * Compute imperial fields deterministically from metric fields.
 * Rules:
 *   g / kg  → oz  (< 453.6 g)  or lb  (≥ 453.6 g)
 *   ml / L  → fl oz
 *   anything else or null metric → imperial stays null
 *
 * Called after validateRecipe so every metric field is already null-defaulted.
 */
function computeImperialFromMetric(recipe) {
  for (const ing of recipe.ingredients) {
    const val  = ing.quantityValueMetric;
    const unit = (ing.quantityUnitMetric || "").toLowerCase().trim();

    if (val == null || !unit) {
      ing.quantityValueImperial   = null;
      ing.quantityUnitImperial    = null;
      ing.quantityDisplayImperial = null;
      continue;
    }

    if (unit === "g" || unit === "kg") {
      const grams  = unit === "kg" ? val * 1000 : val;
      if (grams >= 453.6) {
        const lb     = impRound(grams / 453.6);
        ing.quantityValueImperial   = lb;
        ing.quantityUnitImperial    = "lb";
        ing.quantityDisplayImperial = `${lb} lb`;
      } else {
        const oz     = impRound(grams / 28.35);
        ing.quantityValueImperial   = oz;
        ing.quantityUnitImperial    = "oz";
        ing.quantityDisplayImperial = `${oz} oz`;
      }
    } else if (unit === "ml" || unit === "l") {
      const ml     = unit === "l" ? val * 1000 : val;
      const floz   = impRound(ml / 29.574);
      ing.quantityValueImperial   = floz;
      ing.quantityUnitImperial    = "fl oz";
      ing.quantityDisplayImperial = `${floz} fl oz`;
    } else {
      // Unknown or non-numeric metric unit (shouldn't happen, but be safe)
      ing.quantityValueImperial   = null;
      ing.quantityUnitImperial    = null;
      ing.quantityDisplayImperial = null;
    }
  }
}

// ─── Standalone ingredient conversion (Update conversions button) ──────────────

// Plausible food density range (g per ml). Outside this, a volume→weight value is
// physically impossible → we reject it and fall back to a volume (fl oz) conversion.
const MIN_DENSITY = 0.1;
const MAX_DENSITY = 2.5;

// Deterministic density table for common ingredients (g/ml). Substring-matched on name.
// Used to override Gemini's weight for the common case so it's not AI-dependent.
const DENSITY_TABLE = [
  { keys: ["all-purpose flour", "plain flour", "maida", "flour", "atta", "besan"], d: 0.55 },
  { keys: ["brown sugar"], d: 0.72 },
  { keys: ["powdered sugar", "icing sugar", "confectioner"], d: 0.56 },
  { keys: ["sugar", "caster"], d: 0.85 },
  { keys: ["butter", "ghee", "margarine"], d: 0.96 },
  { keys: ["cocoa", "cacao"], d: 0.41 },
  { keys: ["rolled oats", "oats"], d: 0.41 },
  { keys: ["rice", "basmati"], d: 0.85 },
  { keys: ["salt"], d: 1.2 },
  { keys: ["honey"], d: 1.42 },
  { keys: ["cornstarch", "corn starch", "cornflour"], d: 0.54 },
  { keys: ["breadcrumbs", "bread crumbs"], d: 0.36 },
];

function densityFor(name) {
  const n = (name || "").toLowerCase();
  for (const row of DENSITY_TABLE) {
    if (row.keys.some((k) => n.includes(k))) return row.d;
  }
  return null;
}

// Normalised key used to look up / accumulate a learned density for an ingredient.
// Lowercased, punctuation stripped, whitespace collapsed → e.g. "Almond Flour!" → "almond flour".
function normalizeKey(name) {
  return (name || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Round + format a metric amount (g or ml), choosing kg/L for large values. */
function metricFields(value, baseUnit) {
  // baseUnit: "g" or "ml"
  if (value == null) return { v: null, u: null, d: null };
  if (baseUnit === "g" && value >= 1000) {
    const kg = impRound(value / 1000);
    return { v: kg, u: "kg", d: `${kg} kg` };
  }
  if (baseUnit === "ml" && value >= 1000) {
    const l = impRound(value / 1000);
    return { v: l, u: "L", d: `${l} L` };
  }
  const r = value >= 10 ? Math.round(value) : impRound(value);
  return { v: r, u: baseUnit, d: `${r} ${baseUnit}` };
}

const CONVERT_SYSTEM_INSTRUCTION =
  "You are a unit-conversion assistant for the Amrosa recipe app. " +
  "For each ingredient, return its metric VOLUME in ml (`ml`) when it was measured by volume " +
  "(cups, tbsp, tsp, fl oz, ml, L) — otherwise null — AND its metric WEIGHT in grams (`grams`) when " +
  "it is a dry/solid ingredient OR was measured by weight (oz, lb, g, kg) — otherwise null. " +
  "For LIQUID ingredients (water, milk, oil, stock, broth, juice, cream, vinegar), give `ml` and leave `grams` null. " +
  "For dry ingredients measured by volume, give BOTH `ml` (the volume) and `grams` (weight via realistic density). " +
  "Conversions: 1 cup = 240 ml, 1 tbsp = 15 ml, 1 tsp = 5 ml, 1 fl oz = 30 ml; 1 oz = 28.35 g, 1 lb = 453.6 g. " +
  "Non-convertible (whole counts like '2 eggs'/'3 cloves', 'to taste', 'a pinch', 'for garnish') → both null. " +
  'Return ONLY a JSON array (no markdown), one object per input id: [{"id":"...","ml":number|null,"grams":number|null}]';

/**
 * Convert ingredients to metric + imperial with validation.
 * Gemini supplies ml and/or grams; the server then:
 *   - overrides grams with a curated density when the ingredient is known (deterministic)
 *   - else uses a *learned* density (from `learnedMap`) for ingredients we've seen recur
 *   - validates the implied density (grams / ml) is physically plausible; if not, drops
 *     the weight and falls back to the volume (ml → fl oz)
 *   - prefers weight (g → oz/lb) when valid, else volume (ml → fl oz)
 * Imperial is always computed deterministically from the chosen metric.
 *
 * @param {object} learnedMap  { normalizedKey: density(g/ml) } promoted learned densities.
 * @returns {{ ingredients: Array, candidates: Array }}
 *   `candidates` = fresh, Gemini-derived volume→weight observations (not from any table)
 *   whose implied density passed the plausibility bounds — the caller persists these so the
 *   density table grows over time for ingredients that recur.
 */
async function convertIngredientsFromList(ingredients, apiKey, learnedMap = {}) {
  if (!Array.isArray(ingredients) || ingredients.length === 0) {
    throw new Error("Please provide ingredients to convert.");
  }

  const genAI = new GoogleGenerativeAI(apiKey);
  const model = genAI.getGenerativeModel({
    model: "gemini-2.5-flash",
    systemInstruction: CONVERT_SYSTEM_INSTRUCTION,
    generationConfig: {
      responseMimeType: "application/json",
      maxOutputTokens: 8192,
      thinkingConfig: { thinkingBudget: 0 },
    },
  });

  const compact = ingredients.map((i) => ({
    id: i.id,
    name: i.name,
    quantity: i.quantityDisplay || i.quantity || "",
  }));
  const prompt = `Convert these ingredients:\n${JSON.stringify(compact, null, 2)}`;

  const result = await model.generateContent(prompt);
  let jsonText = result.response.text().trim();
  if (jsonText.startsWith("```")) {
    jsonText = jsonText.replace(/^```(?:json)?\s*\n?/, "").replace(/\n?```\s*$/, "");
  }

  let parsed;
  try {
    parsed = JSON.parse(jsonText);
  } catch (err) {
    throw new Error(`Gemini returned invalid JSON: ${err.message}`);
  }
  if (!Array.isArray(parsed)) parsed = parsed.ingredients || [];

  // Map results by id so we can match back to the requested ingredients.
  const byId = new Map(parsed.map((p) => [p.id, p]));

  // Fresh volume→weight observations to persist (so the table grows over time).
  const candidates = [];

  const out = ingredients.map((ing) => {
    const p = byId.get(ing.id) || {};
    let ml = typeof p.ml === "number" && p.ml > 0 ? p.ml : null;
    let grams = typeof p.grams === "number" && p.grams > 0 ? p.grams : null;
    // Where the weight came from: "gemini" (raw), "table" (curated), "learned" (accumulated).
    let gramsSource = grams != null ? "gemini" : null;

    // Density override for dry ingredients measured by volume.
    // Priority: curated table (authoritative) → learned density → Gemini's own number.
    if (ml != null) {
      const tableD = densityFor(ing.name);
      if (tableD != null) {
        grams = ml * tableD;
        gramsSource = "table";
      } else {
        const learnedD = learnedMap[normalizeKey(ing.name)];
        if (typeof learnedD === "number" && learnedD > 0) {
          grams = ml * learnedD;
          gramsSource = "learned";
        }
      }
    }

    // Validate volume→weight density; reject implausible weights.
    if (grams != null && ml != null) {
      const density = grams / ml;
      if (density < MIN_DENSITY || density > MAX_DENSITY) {
        grams = null; // fall back to volume
        gramsSource = null;
      }
    }

    // Record a learning candidate only for genuine Gemini-derived volume→weight
    // conversions that survived validation and aren't already in a table.
    if (gramsSource === "gemini" && grams != null && ml != null) {
      const key = normalizeKey(ing.name);
      if (key) candidates.push({ key, name: (ing.name || "").trim(), density: grams / ml });
    }

    // Choose metric: prefer weight when we have a trustworthy one, else volume.
    let metric;
    if (grams != null) metric = metricFields(grams, "g");
    else if (ml != null) metric = metricFields(ml, "ml");
    else metric = { v: null, u: null, d: null };

    return {
      id: ing.id,
      quantityValueMetric: metric.v,
      quantityUnitMetric: metric.u,
      quantityDisplayMetric: metric.d,
      quantityValueImperial: null,
      quantityUnitImperial: null,
      quantityDisplayImperial: null,
    };
  });

  // Imperial is always derived from the chosen metric with exact math.
  computeImperialFromMetric({ ingredients: out });
  return { ingredients: out, candidates };
}

// ─── Validation ───────────────────────────────────────────────────────────────

/**
 * Safety net for collective step references (e.g. "add all the paste ingredients").
 * Any ingredient that no step references gets attached to the first step of its
 * section so it still surfaces in cooking mode. Mirrors the on-device fallback.
 */
function linkOrphanIngredients(recipe) {
  const ingredients = recipe.ingredients || [];
  const steps = recipe.steps || [];
  const refs = recipe.stepIngredientRefs;
  if (ingredients.length === 0 || steps.length === 0) return;

  const referenced = new Set(refs.map((r) => r.ingredientId));

  // Group steps by section, ordered, to find each section's first step.
  const firstStepBySection = new Map();
  [...steps]
    .sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
    .forEach((s) => {
      const key = s.sectionId ?? "__none__";
      if (!firstStepBySection.has(key)) firstStepBySection.set(key, s.id);
    });
  const firstStepOverall = [...steps].sort(
    (a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0)
  )[0].id;

  for (const ing of ingredients) {
    if (referenced.has(ing.id)) continue;
    const key = ing.sectionId ?? "__none__";
    const stepId = firstStepBySection.get(key) || firstStepOverall;
    refs.push({
      stepId,
      ingredientId: ing.id,
      quantityDisplay: ing.quantityDisplay ?? null,
    });
    referenced.add(ing.id);
  }
}

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
  linkOrphanIngredients(recipe);
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

  // Null-default metric fields if Gemini omitted them
  for (const ing of recipe.ingredients) {
    ing.quantityValueMetric   = ing.quantityValueMetric   ?? null;
    ing.quantityUnitMetric    = ing.quantityUnitMetric    ?? null;
    ing.quantityDisplayMetric = ing.quantityDisplayMetric ?? null;
    // Imperial is always null here — computeImperialFromMetric fills them below
    ing.quantityValueImperial   = null;
    ing.quantityUnitImperial    = null;
    ing.quantityDisplayImperial = null;
  }

  // Compute imperial from metric with exact math — never trust Gemini for this
  computeImperialFromMetric(recipe);
}

module.exports = { parseRecipeFromUrl, parseRecipeFromContent, formatRecipeFromText, convertIngredientsFromList };
