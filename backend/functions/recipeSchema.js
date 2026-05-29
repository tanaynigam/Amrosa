/**
 * Amrosa recipe JSON schema — used in the Gemini prompt so the model
 * returns data that maps directly onto the Firestore / Room data model.
 */

const RECIPE_SCHEMA_DESCRIPTION = `
Return a single JSON object with EXACTLY these fields (no markdown, no explanation, ONLY the JSON):

{
  "title": "string — recipe title",
  "description": "string — 1-2 sentence summary of the dish",
  "sourceUrls": ["the original URL that was imported"],
  "baseServings": number — how many servings/portions this recipe makes,
  "baseServingsMin": number or null — low end of yield range if the recipe gives a range (e.g. "makes 15-20 cookies" → 15), otherwise null,
  "baseServingsMax": number or null — high end of yield range if the recipe gives a range, otherwise null,
  "scaleIngredientId": null,
  "scaleStep": 1.0,
  "prepTimeMinutes": number or null,
  "cookTimeMinutes": number or null,
  "imageUrl": "string or null — the main recipe image URL if found in the page",
  "tags": ["string array — category tags like 'Dinner', 'Italian', 'Baking', 'Vegetarian', etc."],
  "isCustomized": false,

  "sections": [
    {
      "id": "section-import-001",
      "name": "string — section name, e.g. 'Dough', 'Sauce', 'Assembly'. Use a single section if the recipe has no sub-parts",
      "orderIndex": 0
    }
  ],

  "ingredients": [
    {
      "id": "ing-import-001",
      "sectionId": "section-import-001 — must match a section id above",
      "name": "string — ingredient name, e.g. 'All Purpose Flour'",
      "quantityValue": number or null — numeric quantity at base yield (e.g. 2.25 for '2¼ cups'). null for 'to taste', 'a pinch', etc.,
      "quantityUnit": "string or null — unit like 'cup', 'tablespoon', 'teaspoon', 'oz', 'kg', 'g', 'whole', 'clove', etc. null if no unit",
      "quantityDisplay": "string — human-readable display like '2¼ cup', '1 can / 28oz', 'to taste', 'a pinch'",

      "quantityValueMetric": number or null — metric equivalent of quantityValue. Populate for volume (cups→ml, tbsp→ml, tsp→ml) and weight (oz→g, lb→kg). null if already metric, non-convertible count, or 'to taste',
      "quantityUnitMetric": "string or null — metric unit: 'ml', 'L', 'g', 'kg'. null if not convertible",
      "quantityDisplayMetric": "string or null — full metric display like '240ml', '15ml', '227g', '1.1kg'. null if not convertible",

      "quantityValueImperial": null,
      "quantityUnitImperial": null,
      "quantityDisplayImperial": null,

      "groupLabel": "string — logical grouping for display, e.g. 'Wet Ingredients', 'Dry Ingredients', 'Spices', 'Sauce', 'Garnish'",
      "isOptional": boolean — true if the recipe says 'optional',
      "substituteGroupId": null,
      "substituteRatio": 1.0,
      "orderIndex": number — 0-based within each section
    }
  ],

  "steps": [
    {
      "id": "step-import-001",
      "sectionId": "section-import-001 — must match a section id",
      "instruction": "string — the full step instruction text",
      "orderIndex": number — 0-based globally across all sections (section 1 steps 0-3, section 2 steps 4-7, etc.)
    }
  ],

  "stepIngredientRefs": [
    {
      "stepId": "step-import-001 — must match a step id",
      "ingredientId": "ing-import-001 — must match an ingredient id",
      "quantityDisplay": "string — the quantity of this ingredient used in this specific step, e.g. '2 tbsp', '1 cup'"
    }
  ],

  "parseNotes": "string or null — if any important field was genuinely unclear or could not be confidently extracted (e.g. yield not stated, cooking time ambiguous, section structure unclear), include ONE brief sentence describing what was uncertain. Set to null if everything was extracted cleanly."
}

IMPORTANT RULES:
1. IDs must be unique. Use the pattern: section-import-001, section-import-002, etc. for sections; ing-import-001, ing-import-002 for ingredients; step-import-001, step-import-002 for steps.
2. Every ingredient and step MUST have a valid sectionId that matches one of the sections.
3. stepIngredientRefs links which ingredients are used in which step. Include a ref whenever a step explicitly mentions using a specific ingredient. Not every ingredient needs a ref, and not every step needs refs.
4. Parse numeric quantities carefully: "¼" = 0.25, "½" = 0.5, "¾" = 0.75, "⅓" = 0.33, "⅔" = 0.67, "1½" = 1.5, "2¼" = 2.25.
5. If the recipe has distinct sub-recipes or phases (e.g. dough + sauce + assembly), create separate sections. If it's a simple single-phase recipe, use one section.
6. Group ingredients logically by their role (e.g. "Marinade", "Sauce", "Dry Ingredients", "Garnish").
7. Mark ingredients as optional (isOptional: true) only if the recipe explicitly says "optional".
8. For "to taste", "as needed", "for garnish" type quantities, set quantityValue, quantityUnit, and ALL conversion fields to null.
9. Return ONLY the JSON object. No markdown code fences, no explanation, no preamble.

METRIC CONVERSION RULES (quantityValueMetric / quantityUnitMetric / quantityDisplayMetric only):
- ALWAYS leave imperial fields null — they are computed automatically from metric by the server.
- Volume → ml or L:  1 cup = 240 ml, 1 tbsp = 15 ml, 1 tsp = 5 ml, 1 fl oz = 30 ml
- Weight → g or kg:  1 oz = 28.35 g, 1 lb = 453.6 g
- If the original unit is already metric (g, kg, ml, L), copy the value into the metric fields unchanged.
- If the original unit is already imperial (oz, lb, fl oz), convert to metric and populate metric fields.
- Non-convertible quantities (whole items like "2 cloves", "1 can", "3 eggs", "to taste", "a pinch"): set ALL conversion fields to null.
- Round metric values sensibly: 240 ml not 240.00 ml; 28 g not 28.35 g for small amounts.
`;

module.exports = { RECIPE_SCHEMA_DESCRIPTION };
