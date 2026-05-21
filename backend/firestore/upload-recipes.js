/**
 * Upload seed recipes to Firestore.
 *
 * Usage:
 *   1. Go to Firebase Console → Project Settings → Service Accounts
 *   2. Click "Generate new private key" → save as `serviceAccountKey.json` in this folder
 *   3. Run:
 *        cd firestore
 *        npm install firebase-admin
 *        node upload-recipes.js
 *
 *   The script reads seed-cookie-recipe.json and writes each top-level key
 *   as a document in the "recipes" collection.
 */

const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");

// --- Configuration ---
const SERVICE_ACCOUNT_PATH = path.join(__dirname, "serviceAccountKey.json");
const SEED_FILE = path.join(__dirname, "seed-recipes.json");
const COLLECTION = "recipes";

// --- Init Firebase Admin ---
if (!fs.existsSync(SERVICE_ACCOUNT_PATH)) {
  console.error(
    "ERROR: serviceAccountKey.json not found.\n" +
      "Download it from Firebase Console → Project Settings → Service Accounts → Generate new private key.\n" +
      "Save it as: firestore/serviceAccountKey.json"
  );
  process.exit(1);
}

const serviceAccount = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT_PATH, "utf8"));

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
});

const db = admin.firestore();

// --- Upload ---
async function upload() {
  const data = JSON.parse(fs.readFileSync(SEED_FILE, "utf8"));
  const docIds = Object.keys(data);

  console.log(`Found ${docIds.length} recipe(s) to upload.\n`);

  for (const docId of docIds) {
    const recipe = data[docId];
    console.log(`  Uploading: ${docId} — "${recipe.title}"`);

    await db.collection(COLLECTION).doc(docId).set(recipe, { merge: true });

    console.log(`  ✓ Done\n`);
  }

  console.log("All recipes uploaded successfully!");
}

upload().catch((err) => {
  console.error("Upload failed:", err);
  process.exit(1);
});
