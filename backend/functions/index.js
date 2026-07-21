const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");
const {
  parseRecipeFromUrl,
  parseRecipeFromContent,
  formatRecipeFromText,
  convertIngredientsFromList,
} = require("./parseRecipe");

admin.initializeApp();

// Global options for cost control
setGlobalOptions({ maxInstances: 5 });

// Secret reference — value is injected at runtime, never in code
const geminiKey = defineSecret("GEMINI_API_KEY");

// ─── Shared error handler ─────────────────────────────────────────────────────

function toHttpsError(err) {
  const msg = err.message || "";
  // BLOCKED:4xx: errors come from fetchPage with a user-facing message already attached
  if (msg.startsWith("BLOCKED:")) {
    const userMsg = msg.split(":").slice(2).join(":");
    return new HttpsError("failed-precondition", userMsg.trim());
  }
  if (msg.includes("Failed to fetch URL")) return new HttpsError("not-found", msg);
  if (msg.includes("Could not extract"))   return new HttpsError("failed-precondition", msg);
  if (msg.includes("Please enter"))        return new HttpsError("invalid-argument", msg);
  if (msg.includes("invalid JSON"))        return new HttpsError("internal", `Failed to parse: ${msg}`);
  if (msg.includes("missing a title") ||
      msg.includes("has no sections")  ||
      msg.includes("has no ingredients") ||
      msg.includes("has no steps"))        return new HttpsError("failed-precondition", msg);
  return new HttpsError("internal", `Recipe import failed: ${msg}`);
}

// ─── parseRecipeUrl ───────────────────────────────────────────────────────────

/**
 * Parse a recipe from a URL.
 * Transparently handles regular web pages, Google Sheets, and Google Docs URLs.
 *
 * Input:  { url: "https://..." }
 * Output: { recipe: { ...Tablefeed schema... } }
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

    if (!url || typeof url !== "string") {
      throw new HttpsError("invalid-argument", "A recipe URL is required.");
    }

    try {
      const parsed = new URL(url);
      if (!["http:", "https:"].includes(parsed.protocol)) {
        throw new Error("bad protocol");
      }
    } catch {
      throw new HttpsError("invalid-argument", "Invalid URL format.");
    }

    const apiKey = geminiKey.value();
    if (!apiKey) {
      throw new HttpsError(
        "internal",
        "Gemini API key is not configured. Run: firebase functions:secrets:set GEMINI_API_KEY"
      );
    }

    try {
      const recipe = await parseRecipeFromUrl(url, apiKey);
      const now = Date.now();
      recipe.createdAt = now;
      recipe.updatedAt = now;
      return { recipe };
    } catch (err) {
      throw toHttpsError(err);
    }
  }
);

// ─── parseRecipeContent ───────────────────────────────────────────────────────

/**
 * Parse a recipe from file content uploaded directly from the app.
 * Supports plain text, CSV, and XLSX (Excel) files.
 *
 * Input:  { content: string, type: "text"|"csv"|"xlsx", fileName: string }
 * Output: { recipe: { ...Tablefeed schema... } }
 */
exports.parseRecipeContent = onCall(
  {
    secrets: [geminiKey],
    timeoutSeconds: 120,
    memory: "512MiB",
    region: "us-central1",
  },
  async (request) => {
    const { content, type, fileName } = request.data ?? {};

    if (!content || typeof content !== "string") {
      throw new HttpsError("invalid-argument", "File content is required.");
    }

    const validTypes = ["text", "csv", "xlsx"];
    if (!type || !validTypes.includes(type)) {
      throw new HttpsError(
        "invalid-argument",
        `Invalid file type. Expected one of: ${validTypes.join(", ")}.`
      );
    }

    const apiKey = geminiKey.value();
    if (!apiKey) {
      throw new HttpsError(
        "internal",
        "Gemini API key is not configured. Run: firebase functions:secrets:set GEMINI_API_KEY"
      );
    }

    try {
      const recipe = await parseRecipeFromContent(
        content,
        type,
        fileName || "file",
        apiKey
      );
      const now = Date.now();
      recipe.createdAt = now;
      recipe.updatedAt = now;
      return { recipe };
    } catch (err) {
      throw toHttpsError(err);
    }
  }
);

// ─── formatRecipeText ─────────────────────────────────────────────────────────

/**
 * Format a freeform recipe typed by the user into the structured Tablefeed schema.
 * Used by the "Add New Recipe → Type it out" flow in the Personal tab.
 *
 * Input:  { text: string }
 * Output: { recipe: { ...Tablefeed schema... } }
 */
exports.formatRecipeText = onCall(
  {
    secrets: [geminiKey],
    timeoutSeconds: 120,
    memory: "512MiB",
    region: "us-central1",
  },
  async (request) => {
    const { text } = request.data ?? {};

    if (!text || typeof text !== "string" || text.trim().length < 10) {
      throw new HttpsError(
        "invalid-argument",
        "Recipe text is required (minimum 10 characters)."
      );
    }

    const apiKey = geminiKey.value();
    if (!apiKey) {
      throw new HttpsError(
        "internal",
        "Gemini API key is not configured. Run: firebase functions:secrets:set GEMINI_API_KEY"
      );
    }

    try {
      const recipe = await formatRecipeFromText(text, apiKey);
      const now = Date.now();
      recipe.createdAt = now;
      recipe.updatedAt = now;
      return { recipe };
    } catch (err) {
      throw toHttpsError(err);
    }
  }
);

// ─── convertIngredients ─────────────────────────────────────────────────────
/**
 * Compute metric + imperial conversions for a list of ingredients.
 * Used by the recipe editor's "Update conversions" button on existing recipes.
 *
 * Input:  { ingredients: [{ id, name, quantityDisplay }] }
 * Output: { ingredients: [{ id, quantityValueMetric, quantityUnitMetric, quantityDisplayMetric,
 *                           quantityValueImperial, quantityUnitImperial, quantityDisplayImperial }] }
 */
// Number of independent observations before a learned density is trusted enough
// to be SERVED back as a deterministic override. Below this it only accumulates.
const LEARN_PROMOTE_COUNT = 3;

exports.convertIngredients = onCall(
  {
    secrets: [geminiKey],
    timeoutSeconds: 120,
    memory: "512MiB",
    region: "us-central1",
  },
  async (request) => {
    const ingredients = request.data?.ingredients;
    if (!Array.isArray(ingredients) || ingredients.length === 0) {
      throw new HttpsError("invalid-argument", "Ingredients are required.");
    }

    const apiKey = geminiKey.value();
    if (!apiKey) {
      throw new HttpsError("internal", "Gemini API key is not configured.");
    }

    const db = admin.firestore();

    // 1. Load the learned density table (promoted entries only) into a lookup map.
    const learnedMap = {};
    try {
      const snap = await db.collection("ingredient_densities").get();
      snap.forEach((doc) => {
        const d = doc.data() || {};
        if (d.count >= LEARN_PROMOTE_COUNT && d.sumDensity > 0) {
          learnedMap[doc.id] = d.sumDensity / d.count;
        }
      });
    } catch (err) {
      console.error("Failed to read ingredient_densities:", err.message);
    }

    try {
      const { ingredients: converted, candidates } =
        await convertIngredientsFromList(ingredients, apiKey, learnedMap);

      // 2. Persist fresh observations so the table grows for ingredients that recur.
      //    Store running sum + count; the served average = sumDensity / count.
      if (Array.isArray(candidates) && candidates.length > 0) {
        try {
          const agg = {};
          for (const c of candidates) {
            if (!agg[c.key]) agg[c.key] = { name: c.name, sum: 0, n: 0 };
            agg[c.key].sum += c.density;
            agg[c.key].n += 1;
          }
          const batch = db.batch();
          for (const [key, v] of Object.entries(agg)) {
            const ref = db.collection("ingredient_densities").doc(key);
            batch.set(
              ref,
              {
                name: v.name,
                sumDensity: admin.firestore.FieldValue.increment(v.sum),
                count: admin.firestore.FieldValue.increment(v.n),
                updatedAt: Date.now(),
              },
              { merge: true }
            );
          }
          await batch.commit();
        } catch (err) {
          console.error("Failed to persist learned densities:", err.message);
        }
      }

      return { ingredients: converted };
    } catch (err) {
      throw toHttpsError(err);
    }
  }
);


// ─── Popularity counters (Discover Phase 2) ──────────────────────────────────
// saveCount / likeCount live on the shared_recipes/{recipeId} mirror and are maintained
// ONLY by these Admin-SDK triggers (clients never write them). Updates are wrapped in
// try/catch so a counter change on a missing/unpublished mirror is silently ignored.

const { onDocumentDeleted } = require("firebase-functions/v2/firestore");

async function bumpCounter(recipeId, field, delta) {
  try {
    await admin.firestore().collection("shared_recipes").doc(recipeId)
      .update({ [field]: admin.firestore.FieldValue.increment(delta) });
  } catch (err) {
    // Mirror missing (recipe unpublished/deleted) → nothing to count.
    console.log(`bumpCounter skip ${field} ${recipeId}: ${err.message}`);
  }
}

// Save count — a received reference's doc-id IS the canonical recipeId.
exports.onReceivedSaved = onDocumentCreated(
  "received_recipes/{uid}/items/{recipeId}",
  (event) => bumpCounter(event.params.recipeId, "saveCount", 1)
);
exports.onReceivedRemoved = onDocumentDeleted(
  "received_recipes/{uid}/items/{recipeId}",
  (event) => bumpCounter(event.params.recipeId, "saveCount", -1)
);

// Like count — presence of shared_recipes/{recipeId}/likes/{uid} = liked.
exports.onRecipeLiked = onDocumentCreated(
  "shared_recipes/{recipeId}/likes/{uid}",
  (event) => bumpCounter(event.params.recipeId, "likeCount", 1)
);
exports.onRecipeUnliked = onDocumentDeleted(
  "shared_recipes/{recipeId}/likes/{uid}",
  (event) => bumpCounter(event.params.recipeId, "likeCount", -1)
);

// Push notifications
exports.sendPushNotification = onDocumentCreated(
  'notifications/{uid}/items/{notifId}',
  async (event) => {
    const uid = event.params.uid;
    const data = event.data?.data();
    if (!data) return;

    // FCM token lives in a private subdoc (owner-only readable); Admin SDK bypasses rules.
    // Fall back to the legacy users/{uid}.fcmToken field for tokens stored before the move.
    const pushSnap = await admin.firestore()
      .collection('users').doc(uid).collection('private').doc('push').get();
    let fcmToken = pushSnap.data()?.fcmToken;
    if (!fcmToken) {
      const userSnap = await admin.firestore().collection('users').doc(uid).get();
      fcmToken = userSnap.data()?.fcmToken;
    }
    if (!fcmToken) { console.log('No FCM token for uid=' + uid); return; }

    const { type, fromDisplayName, recipeName } = data;
    let title, body;
    switch (type) {
      case 'follow_request':
        title = 'New Co-Chef Request';
        body  = fromDisplayName + ' wants to be co-chefs';
        break;
      case 'follow_accepted':
        title = 'Co-Chef Request Accepted';
        body  = fromDisplayName + ' accepted your co-chef request';
        break;
      case 'recipe_shared':
        title = fromDisplayName + ' shared a recipe';
        body  = recipeName ? ('"' + recipeName + '"') : 'Tap to view it in Tablefeed';
        break;
      default:
        return;
    }

    try {
      await admin.messaging().send({
        token: fcmToken,
        notification: { title, body },
        data: { type: type, shareId: data.shareId ?? '' },
        android: { notification: { channelId: 'tablefeed_social', priority: 'high' } },
      });
      console.log('Push sent: ' + type + ' -> ' + uid);
    } catch (err) {
      console.error('Push failed for uid=' + uid + ':', err.message);
    }
  }
);
