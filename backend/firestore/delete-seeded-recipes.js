/**
 * Deletes the 4 seeded recipes from the shared `recipes` Firestore collection.
 * Run once: node delete-seeded-recipes.js
 *
 * These recipes are now private to the owner's personal_recipes subcollection.
 * Removing them from `recipes` ensures fresh installs start with an empty app.
 */

const admin = require('firebase-admin');
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

const db = admin.firestore();

const SEEDED_IDS = ['recipe-001', 'recipe-002', 'recipe-003', 'recipe-004'];

async function deleteSeededRecipes() {
  console.log('Deleting seeded recipes from shared `recipes` collection...\n');

  for (const id of SEEDED_IDS) {
    try {
      await db.collection('recipes').doc(id).delete();
      console.log(`  ✓ Deleted ${id}`);
    } catch (err) {
      console.error(`  ✗ Failed to delete ${id}:`, err.message);
    }
  }

  console.log('\nDone. Fresh installs will no longer receive these recipes.');
  process.exit(0);
}

deleteSeededRecipes();
