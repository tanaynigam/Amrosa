const { GoogleGenerativeAI } = require("@google/generative-ai");

// ─── F22 Surface 1 — natural-language recipe editing ──────────────────────────
//
// Gemini returns a short list of EDIT OPERATIONS against the ids we sent it — never a
// rewritten recipe. That keeps changes precise, cheap and previewable, and the ops map
// 1:1 onto the EditDraft mutators the Android/iOS ViewModels already have.
//
// Nothing here trusts the model: every op is validated against the recipe that was sent,
// unknown ids are DROPPED rather than failing the request (same validate-don't-throw
// principle as the title/section import fixes).

/** Hard ceiling on ops. A prompt wanting more than this is a rewrite, not an edit. */
const MAX_OPS = 25;

const OP_TYPES = new Set([
  "updateIngredient", "addIngredient", "deleteIngredient",
  "updateStep", "addStep", "deleteStep",
  "updateMeta",
]);

const ING_FIELDS = ["name", "quantityDisplay", "quantityUnit", "quantityValue", "isOptional", "shoppingNote"];
const META_FIELDS = ["title", "description", "baseServings", "prepTimeMinutes", "cookTimeMinutes", "tags"];

const EDIT_SYSTEM_INSTRUCTION =
  "You edit recipes on behalf of a home cook who describes the change in plain language. " +
  "You NEVER rewrite the recipe. You return ONLY a JSON object of the exact shape:\n" +
  '{ "operations": [ ... ], "notes": string|null }\n\n' +
  "Allowed operations (use the `id` values EXACTLY as given in the recipe — never invent one):\n" +
  '{ "op":"updateIngredient", "id":"<ingredient id>", "reason":"<short>", "fields":{ "name"?, "quantityDisplay"?, "quantityUnit"?, "quantityValue"?, "isOptional"?, "shoppingNote"? } }\n' +
  '{ "op":"addIngredient", "sectionId":"<section id>", "reason":"<short>", "fields":{ "name", "quantityDisplay"?, "quantityUnit"?, "quantityValue"?, "isOptional"? } }\n' +
  '{ "op":"deleteIngredient", "id":"<ingredient id>", "reason":"<short>" }\n' +
  '{ "op":"updateStep", "id":"<step id>", "instruction":"<full new text>", "reason":"<short>" }\n' +
  '{ "op":"addStep", "sectionId":"<section id>", "instruction":"<text>", "reason":"<short>" }\n' +
  '{ "op":"deleteStep", "id":"<step id>", "reason":"<short>" }\n' +
  '{ "op":"updateMeta", "reason":"<short>", "fields":{ "title"?, "description"?, "baseServings"?, "prepTimeMinutes"?, "cookTimeMinutes"?, "tags"? } }\n\n' +
  "RULES:\n" +
  "- Change ONLY what the user asked for. Touching an unrelated line is a failure.\n" +
  "- Emit the smallest set of operations that satisfies the request. Return an empty " +
  "`operations` array if the request is unclear or already satisfied, and explain in `notes`.\n" +
  "- `reason` is one short phrase shown to the user next to the change, e.g. \"Doubled the garlic\". " +
  "Write it in plain language, never mention ids, JSON or operations.\n" +
  "- Vague quantities are VALID and must be preserved: \"a handful\", \"to taste\", \"a splash\". " +
  "Put them in `quantityDisplay` and set `quantityValue` to null. NEVER force a number onto a " +
  "quantity the user left vague. You may suggest a number in `notes`, but do not apply it.\n" +
  "- `quantityDisplay` is what the cook reads (e.g. \"3 cloves\", \"1 1/2 cups\"). `quantityValue` is " +
  "the same amount as a plain number (3, 1.5) or null when there isn't one. Keep the two consistent.\n" +
  "- To scale a whole recipe, emit one updateIngredient per affected ingredient so every change is " +
  "visible. Never hide a bulk change behind a single operation.\n" +
  "- `updateStep` returns the COMPLETE new instruction text, not a diff.\n" +
  "- If the user asks for something that isn't an edit (a question, a substitution query), return no " +
  "operations and answer briefly in `notes`.\n" +
  "- `notes` is null when there is nothing worth saying. Do not narrate the obvious.";

/**
 * Ask Gemini for edit operations against [slimRecipe].
 *
 * @param slimRecipe { title, description, baseServings, prepTimeMinutes, cookTimeMinutes,
 *                     tags[], sections:[{ id, name, ingredients:[...], steps:[...] }] }
 * @param prompt     the user's natural-language request
 * @returns { operations[], notes, dropped[] }
 */
async function editRecipeWithPromptImpl(slimRecipe, prompt, apiKey) {
  const genAI = new GoogleGenerativeAI(apiKey);
  const model = genAI.getGenerativeModel({
    model: "gemini-2.5-flash",
    systemInstruction: EDIT_SYSTEM_INSTRUCTION,
    generationConfig: {
      responseMimeType: "application/json",
      maxOutputTokens: 8192,
      thinkingConfig: { thinkingBudget: 0 },
    },
  });

  const text =
    `RECIPE (ids are authoritative — reuse them verbatim):\n` +
    `${JSON.stringify(slimRecipe)}\n\n` +
    `USER REQUEST:\n${prompt}`;

  const result = await model.generateContent(text);
  const response = result.response;
  console.log("editRecipe finish reason:", response.candidates?.[0]?.finishReason);

  let jsonText = response.text().trim();
  if (jsonText.startsWith("```")) {
    jsonText = jsonText.replace(/^```(?:json)?\s*\n?/, "").replace(/\n?```\s*$/, "");
  }

  let parsed;
  try {
    parsed = JSON.parse(jsonText);
  } catch (err) {
    console.error("editRecipe JSON parse failed:", jsonText.substring(0, 2000));
    throw new Error(`Gemini returned invalid JSON: ${err.message}`);
  }

  return validateOperations(parsed, slimRecipe);
}

/**
 * Keep only operations that name ids present in the recipe we actually sent.
 * A hallucinated id drops that one op — it never fails the whole request.
 */
function validateOperations(parsed, slimRecipe) {
  const sections = Array.isArray(slimRecipe?.sections) ? slimRecipe.sections : [];
  const sectionIds = new Set(sections.map((s) => s && s.id).filter(Boolean));
  const ingredientIds = new Set();
  const stepIds = new Set();
  for (const s of sections) {
    for (const i of (s && s.ingredients) || []) if (i && i.id) ingredientIds.add(i.id);
    for (const st of (s && s.steps) || []) if (st && st.id) stepIds.add(st.id);
  }
  // A recipe with no sections still has a place to add to; fall back to the first section id.
  const defaultSectionId = sections.length > 0 ? sections[0].id : null;

  const raw = Array.isArray(parsed?.operations) ? parsed.operations : [];
  const operations = [];
  const dropped = [];
  const drop = (op, why) => dropped.push({ op: (op && op.op) || "unknown", id: (op && op.id) || null, why });

  for (const op of raw) {
    if (operations.length >= MAX_OPS) { drop(op, "operation limit reached"); continue; }
    if (!op || typeof op !== "object" || !OP_TYPES.has(op.op)) { drop(op, "unknown operation"); continue; }

    const reason = cleanStr(op.reason) || "Updated by assistant";

    switch (op.op) {
      case "updateIngredient": {
        if (!ingredientIds.has(op.id)) { drop(op, "no such ingredient"); break; }
        const fields = pickFields(op.fields, ING_FIELDS);
        if (Object.keys(fields).length === 0) { drop(op, "no fields to change"); break; }
        operations.push({ op: op.op, id: op.id, reason, fields });
        break;
      }
      case "addIngredient": {
        const sectionId = sectionIds.has(op.sectionId) ? op.sectionId : defaultSectionId;
        if (!sectionId) { drop(op, "no section to add to"); break; }
        const fields = pickFields(op.fields, ING_FIELDS);
        if (!cleanStr(fields.name)) { drop(op, "ingredient needs a name"); break; }
        operations.push({ op: op.op, sectionId, reason, fields });
        break;
      }
      case "deleteIngredient": {
        if (!ingredientIds.has(op.id)) { drop(op, "no such ingredient"); break; }
        operations.push({ op: op.op, id: op.id, reason });
        break;
      }
      case "updateStep": {
        if (!stepIds.has(op.id)) { drop(op, "no such step"); break; }
        const instruction = cleanStr(op.instruction);
        if (!instruction) { drop(op, "step text is empty"); break; }
        operations.push({ op: op.op, id: op.id, reason, instruction });
        break;
      }
      case "addStep": {
        const sectionId = sectionIds.has(op.sectionId) ? op.sectionId : defaultSectionId;
        if (!sectionId) { drop(op, "no section to add to"); break; }
        const instruction = cleanStr(op.instruction);
        if (!instruction) { drop(op, "step text is empty"); break; }
        operations.push({ op: op.op, sectionId, reason, instruction });
        break;
      }
      case "deleteStep": {
        if (!stepIds.has(op.id)) { drop(op, "no such step"); break; }
        operations.push({ op: op.op, id: op.id, reason });
        break;
      }
      case "updateMeta": {
        const fields = pickFields(op.fields, META_FIELDS);
        if (Object.keys(fields).length === 0) { drop(op, "no fields to change"); break; }
        operations.push({ op: op.op, reason, fields });
        break;
      }
    }
  }

  if (dropped.length > 0) console.log("editRecipe dropped ops:", JSON.stringify(dropped));

  return { operations, notes: cleanStr(parsed?.notes) || null, dropped };
}

/** Keep only known keys with a usable value. Explicit nulls survive — they mean "clear this". */
function pickFields(src, allowed) {
  const out = {};
  if (!src || typeof src !== "object") return out;
  for (const key of allowed) {
    if (!(key in src)) continue;
    const v = src[key];
    if (v === undefined) continue;
    if (typeof v === "string") {
      const t = v.trim();
      // An empty string is only meaningful for the fields a cook can legitimately blank out.
      if (t === "" && key !== "description" && key !== "shoppingNote" && key !== "quantityUnit") continue;
      out[key] = t;
    } else if (typeof v === "number" || typeof v === "boolean" || v === null) {
      out[key] = v;
    } else if (Array.isArray(v)) {
      out[key] = v.map((x) => cleanStr(x)).filter(Boolean);
    }
  }
  return out;
}

function cleanStr(v) {
  return typeof v === "string" ? v.trim() : "";
}

module.exports = { editRecipeWithPromptImpl, validateOperations, MAX_OPS };
