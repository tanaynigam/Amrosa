/**
 * One-off backfill: add `searchTokens` to existing shared_recipes mirrors so the
 * Discover cross-scope search (Phase 3a) can find recipes published before the field existed.
 *
 * Usage:
 *   cd backend/firestore
 *   node backfill-search-tokens.js
 *
 * Requires serviceAccountKey.json in this folder (same as upload-recipes.js).
 * Idempotent: re-running just recomputes the tokens.
 */

const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");

const SERVICE_ACCOUNT_PATH = path.join(__dirname, "serviceAccountKey.json");
if (!fs.existsSync(SERVICE_ACCOUNT_PATH)) {
  console.error("ERROR: serviceAccountKey.json not found in backend/firestore/.");
  process.exit(1);
}
admin.initializeApp({ credential: admin.credential.cert(require(SERVICE_ACCOUNT_PATH)) });
const db = admin.firestore();

// Must match SharedRecipeService.searchTokens (Kotlin).
function searchTokens(title, tags) {
  const text = `${title || ""} ${(tags || []).join(" ")}`.toLowerCase();
  const words = text.split(/[^a-z0-9]+/).filter((w) => w.length >= 2);
  return [...new Set(words)].slice(0, 30);
}

async function main() {
  const snap = await db.collection("shared_recipes").get();
  console.log(`Scanning ${snap.size} shared_recipes docs…`);
  let updated = 0;
  const batchSize = 400;
  let batch = db.batch();
  let pending = 0;

  for (const doc of snap.docs) {
    const d = doc.data();
    const tokens = searchTokens(d.title, d.tags);
    batch.update(doc.ref, { searchTokens: tokens });
    updated++;
    pending++;
    if (pending >= batchSize) {
      await batch.commit();
      batch = db.batch();
      pending = 0;
    }
  }
  if (pending > 0) await batch.commit();
  console.log(`Done. Wrote searchTokens to ${updated} docs.`);
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
