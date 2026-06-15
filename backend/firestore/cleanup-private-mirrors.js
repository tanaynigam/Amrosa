/**
 * One-off privacy cleanup: remove stale/orphan docs from `shared_recipes` so no
 * private (or no-longer-shared) recipe lingers in Discovery / chef profiles.
 *
 * For every shared_recipes/{recipeId} doc it checks the author's canonical recipe at
 * personal_recipes/{authorId}/recipes/{recipeId}:
 *   - canonical missing               → delete the mirror (recipe was deleted)
 *   - canonical visibility != shared  → delete the mirror (recipe made private)
 *   - mirror visibility not in {friends, public} → delete (shouldn't exist post-rules)
 *
 * Usage:
 *   cd backend/firestore
 *   node cleanup-private-mirrors.js          # dry run (lists what it WOULD delete)
 *   node cleanup-private-mirrors.js --apply  # actually delete
 *
 * Requires serviceAccountKey.json in this folder (same as upload-recipes.js).
 * Idempotent.
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

const APPLY = process.argv.includes("--apply");
const SHARED_TIERS = new Set(["shared", "friends", "public"]);

async function main() {
  const shared = await db.collection("shared_recipes").get();
  console.log(`Scanning ${shared.size} shared_recipes docs (${APPLY ? "APPLY" : "dry run"})…`);

  let toDelete = 0;
  let batch = db.batch();
  let pending = 0;

  for (const doc of shared.docs) {
    const data = doc.data();
    const authorId = data.authorId;
    const mirrorVis = data.visibility;
    let reason = null;

    if (!authorId) {
      reason = "no authorId";
    } else if (!SHARED_TIERS.has(mirrorVis)) {
      reason = `mirror visibility="${mirrorVis}" (not shared)`;
    } else {
      const canonical = await db
        .doc(`personal_recipes/${authorId}/recipes/${doc.id}`)
        .get();
      if (!canonical.exists) {
        reason = "canonical recipe missing (deleted)";
      } else {
        const canonVis = canonical.data().visibility;
        if (!SHARED_TIERS.has(canonVis)) {
          reason = `canonical visibility="${canonVis}" (private)`;
        }
      }
    }

    if (reason) {
      toDelete++;
      console.log(`  ${APPLY ? "DELETE" : "would delete"} ${doc.id} — ${reason}`);
      if (APPLY) {
        batch.delete(doc.ref);
        if (++pending >= 400) { await batch.commit(); batch = db.batch(); pending = 0; }
      }
    }
  }

  if (APPLY && pending > 0) await batch.commit();
  console.log(`\nDone. ${toDelete} orphan mirror doc(s) ${APPLY ? "deleted" : "found (run with --apply to delete)"}.`);
}

main().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
