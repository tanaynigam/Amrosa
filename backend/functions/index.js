const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");
const {
  parseRecipeFromUrl,
  parseRecipeFromContent,
  formatRecipeFromText,
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
 * Output: { recipe: { ...Amrosa schema... } }
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
 * Output: { recipe: { ...Amrosa schema... } }
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
 * Format a freeform recipe typed by the user into the structured Amrosa schema.
 * Used by the "Add New Recipe → Type it out" flow in the Personal tab.
 *
 * Input:  { text: string }
 * Output: { recipe: { ...Amrosa schema... } }
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


// Push notifications
exports.sendPushNotification = onDocumentCreated(
  'notifications/{uid}/items/{notifId}',
  async (event) => {
    const uid = event.params.uid;
    const data = event.data?.data();
    if (!data) return;

    const userSnap = await admin.firestore().collection('users').doc(uid).get();
    const fcmToken = userSnap.data()?.fcmToken;
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
        body  = recipeName ? ('"' + recipeName + '"') : 'Tap to view it in Amrosa';
        break;
      default:
        return;
    }

    try {
      await admin.messaging().send({
        token: fcmToken,
        notification: { title, body },
        data: { type: type, shareId: data.shareId ?? '' },
        android: { notification: { channelId: 'amrosa_social', priority: 'high' } },
      });
      console.log('Push sent: ' + type + ' -> ' + uid);
    } catch (err) {
      console.error('Push failed for uid=' + uid + ':', err.message);
    }
  }
);
