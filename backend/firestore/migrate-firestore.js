/**
 * One-off Firestore migration: copy ALL data from the old project (amrosa-2ec82)
 * into the new project (chef-s-list) for the Chef's List rebrand.
 *
 * It recursively copies every top-level collection AND every nested subcollection
 * (personal_recipes/{uid}/recipes, recipe_notes/{id}/notes, shared_recipes/{id}/likes,
 * notifications/{uid}/items, received_recipes/{uid}/items, shared_to/{uid}/recipes, …),
 * preserving document IDs and paths exactly. Because IDs/paths are preserved, this only
 * lines up per-user data if the Auth users were migrated first WITH THE SAME UIDs:
 *
 *     firebase auth:export users.json --project amrosa-2ec82
 *     firebase auth:import users.json --project chef-s-list
 *
 * Setup:
 *   1. Download a service-account key from EACH project
 *      (Project Settings → Service accounts → Generate new private key) and save as:
 *        backend/firestore/serviceAccountKey.source.json   (amrosa-2ec82)
 *        backend/firestore/serviceAccountKey.dest.json     (chef-s-list)
 *      (both are gitignored)
 *   2. From backend/firestore/:  node migrate-firestore.js          (copies everything)
 *                                node migrate-firestore.js --dry-run  (counts only, no writes)
 *
 * Safe to re-run: documents are written with set() (overwrite), so a second run just
 * re-copies. It never deletes anything in either project.
 */

const admin = require("firebase-admin");

const DRY_RUN = process.argv.includes("--dry-run");
const BATCH_LIMIT = 400; // Firestore batched-write cap is 500; stay under.

const srcKey = require("./serviceAccountKey.source.json");
const dstKey = require("./serviceAccountKey.dest.json");

const srcApp = admin.initializeApp({ credential: admin.credential.cert(srcKey) }, "source");
const dstApp = admin.initializeApp({ credential: admin.credential.cert(dstKey) }, "dest");
const src = srcApp.firestore();
const dst = dstApp.firestore();

let docCount = 0;

/** Recursively copy a source collection ref into the matching dest collection ref. */
async function copyCollection(srcColRef, dstColRef, depthLabel) {
  // Use listDocuments() (NOT .get()) so we also enumerate "missing ancestor" documents.
  // The app writes e.g. personal_recipes/{uid}/recipes/{id} WITHOUT creating the
  // personal_recipes/{uid} parent doc — so .get() returns nothing and the recipes underneath
  // would be silently skipped. listDocuments() returns those phantom parents too.
  const docRefs = await srcColRef.listDocuments();
  if (docRefs.length === 0) return;

  let batch = dst.batch();
  let pending = 0;
  let copied = 0;
  for (const srcDocRef of docRefs) {
    const snap = await srcDocRef.get();
    if (snap.exists) {
      if (!DRY_RUN) {
        batch.set(dstColRef.doc(srcDocRef.id), snap.data());
        pending++;
        if (pending >= BATCH_LIMIT) { await batch.commit(); batch = dst.batch(); pending = 0; }
      }
      docCount++;
      copied++;
    }
    // Recurse into subcollections whether or not the parent document itself exists.
    const subcols = await srcDocRef.listCollections();
    for (const sub of subcols) {
      await copyCollection(sub, dstColRef.doc(srcDocRef.id).collection(sub.id), depthLabel + "→");
    }
  }
  if (!DRY_RUN && pending > 0) await batch.commit();
  console.log(`  ${depthLabel} ${srcColRef.path}: ${copied} docs`);
}

async function main() {
  console.log(
    `\nMigrating Firestore  ${srcKey.project_id}  →  ${dstKey.project_id}` +
    (DRY_RUN ? "   (DRY RUN — no writes)\n" : "\n")
  );
  const topCollections = await src.listCollections();
  for (const col of topCollections) {
    console.log(`\n• Collection: ${col.id}`);
    await copyCollection(col, dst.collection(col.id), " ");
  }
  console.log(`\n✅ Done. ${DRY_RUN ? "Would copy" : "Copied"} ${docCount} documents.`);
  process.exit(0);
}

main().catch((e) => {
  console.error("\n❌ Migration failed:", e);
  process.exit(1);
});
