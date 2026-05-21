package com.aerion.amrosa.data

import android.content.Context
import com.aerion.amrosa.data.local.entity.*
import com.aerion.amrosa.data.repository.RecipeRepository
import com.google.gson.Gson

class DatabaseSeeder(
    private val context: Context,
    private val repository: RecipeRepository,
    private val gson: Gson
) {
    private val prefs = context.getSharedPreferences("amrosa_prefs", Context.MODE_PRIVATE)

    suspend fun seedIfNeeded() {
        if (prefs.getBoolean("seeded_v6", false)) return
        if (repository.count() > 0) {
            prefs.edit().putBoolean("seeded_v6", true).apply()
            return
        }
        seedCookieRecipe()
        seedPizzaRecipe()
        seedButterChickenRecipe()
        seedMalaiKoftaRecipe()
        prefs.edit().putBoolean("seeded_v6", true).apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipe 001 — Brown Butter Chocolate Chip Cookies
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun seedCookieRecipe() {
        val recipeId = "recipe-001"
        val sectionId = "section-cookie-dough"
        val now = System.currentTimeMillis()

        val recipe = RecipeEntity(
            id = recipeId,
            title = "Brown Butter Chocolate Chip Cookies",
            description = "Rich, chewy cookies with deeply nutty browned butter and pools of dark chocolate. Rest the dough 24–48 hrs for best results.",
            sourceUrls = gson.toJson(listOf(
                "https://www.ambitiouskitchen.com/brown-butter-chocolate-chip-cookies/",
                "https://www.thecookingworld.com/recipes/claire-saffitz-chocolate-chip-cookies",
                "Claire Saffitz Makes CHOCOLATE CHIP COOKIES | Dessert Person (YouTube)"
            )),
            baseServings = 17,
            baseServingsMin = 15,
            baseServingsMax = 20,
            scaleIngredientId = "ing-cookie-008",
            scaleStep = 0.25,
            prepTimeMinutes = 35,
            cookTimeMinutes = 20,
            imageUrl = null,
            tags = gson.toJson(listOf("Dessert", "Cookies", "Baking")),
            createdAt = now,
            updatedAt = now
        )

        val sections = listOf(RecipeSectionEntity(sectionId, recipeId, "Cookie Dough", 0))

        val idButter      = "ing-cookie-001"
        val idHeavyCream  = "ing-cookie-002"
        val idGreekYogurt = "ing-cookie-003"
        val idEggs        = "ing-cookie-004"
        val idVanilla     = "ing-cookie-005"
        val idBrownSugar  = "ing-cookie-006"
        val idGranSugar   = "ing-cookie-007"
        val idFlour       = "ing-cookie-008"
        val idBakingSoda  = "ing-cookie-009"
        val idKosherSalt  = "ing-cookie-010"
        val idChocChips   = "ing-cookie-011"
        val idDarkChoc    = "ing-cookie-012"
        val idMilkChoc    = "ing-cookie-013"
        val idFlakySalt   = "ing-cookie-014"
        val idNutella     = "ing-cookie-015"

        val ingredients = listOf(
            IngredientEntity(idButter, recipeId, sectionId, "Salted Butter",
                1.0, "cup", "1 cup / 226g", "Wet Ingredients", false, null, 1.0f, 0),
            IngredientEntity(idHeavyCream, recipeId, sectionId, "Heavy Cream",
                2.0, "tablespoon", "2 tablespoon / 28g", "Wet Ingredients", false, "cream-sub", 1.0f, 1),
            IngredientEntity(idGreekYogurt, recipeId, sectionId, "Greek Yogurt",
                2.0, "tablespoon", "2 tablespoon / 28g", "Wet Ingredients", false, "cream-sub", 1.0f, 2),
            IngredientEntity(idEggs, recipeId, sectionId, "Eggs",
                2.0, "large", "2 large eggs", "Wet Ingredients", false, null, 1.0f, 3),
            IngredientEntity(idVanilla, recipeId, sectionId, "Vanilla Extract",
                1.0, "tablespoon", "1 tablespoon / 15g", "Wet Ingredients", false, null, 1.0f, 4),
            IngredientEntity(idBrownSugar, recipeId, sectionId, "Dark Brown Sugar",
                0.75, "cup", "¾ cup / 150g", "Wet Ingredients", false, null, 1.0f, 5),
            IngredientEntity(idGranSugar, recipeId, sectionId, "Granulated Sugar",
                0.75, "cup", "¾ cup / 150g", "Wet Ingredients", false, null, 1.0f, 6),
            IngredientEntity(idFlour, recipeId, sectionId, "All Purpose Flour",
                2.25, "cup", "2¼ cup", "Dry Ingredients", false, null, 1.0f, 7),
            IngredientEntity(idBakingSoda, recipeId, sectionId, "Baking Soda",
                1.0, "teaspoon", "1 teaspoon", "Dry Ingredients", false, null, 1.0f, 8),
            IngredientEntity(idKosherSalt, recipeId, sectionId, "Kosher Salt",
                0.5, "teaspoon", "½ teaspoon", "Dry Ingredients", false, null, 1.0f, 9),
            IngredientEntity(idChocChips, recipeId, sectionId, "Semi Sweet Chocolate Chips",
                0.75, "cup", "¾ cup / 135g", "Dry Ingredients", false, null, 1.0f, 10),
            IngredientEntity(idDarkChoc, recipeId, sectionId, "Dark Chocolate (chunks)",
                0.75, "cup", "¾ cup / 135g", "Dry Ingredients", false, "chocolate-type", 1.0f, 11),
            IngredientEntity(idMilkChoc, recipeId, sectionId, "Milk Chocolate (chunks)",
                0.75, "cup", "¾ cup / 135g", "Dry Ingredients", false, "chocolate-type", 1.0f, 12),
            IngredientEntity(idFlakySalt, recipeId, sectionId, "Flaky Sea Salt",
                null, null, "pinch per cookie", "Dry Ingredients", false, null, 1.0f, 13),
            IngredientEntity(idNutella, recipeId, sectionId, "Nutella",
                1.0, "teaspoon", "1 teaspoon per cookie", "Optional", true, null, 1.0f, 14)
        )

        val step1  = "step-cookie-001"; val step2  = "step-cookie-002"
        val step3  = "step-cookie-003"; val step4  = "step-cookie-004"
        val step5  = "step-cookie-005"; val step6  = "step-cookie-006"
        val step7  = "step-cookie-007"; val step8  = "step-cookie-008"
        val step9  = "step-cookie-009"; val step10 = "step-cookie-010"

        val steps = listOf(
            StepEntity(step1, recipeId, sectionId,
                "Brown the Butter: Melt the butter in a small saucepan over medium heat. Once melted, whisk constantly; the butter will begin to crackle, then foam. After a few minutes, the butter will begin to turn a golden amber color. As soon as the butter turns brown and gives off a nutty aroma, 5 to 8 minutes, remove from the heat and immediately transfer it to a medium bowl, scraping all the butter and brown bits from the pan. Set aside until cool enough to touch, about 15 minutes.", 0),
            StepEntity(step2, recipeId, sectionId,
                "Mix Dry Ingredients: In a large bowl, whisk the flour, baking soda, and salt until well combined.", 1),
            StepEntity(step3, recipeId, sectionId,
                "Mix Wet Ingredients: In the bowl of a stand mixer fitted with the paddle attachment, mix the brown butter, brown sugar, and granulated sugar until well combined, about 1 minute. Beat in the egg, egg yolk, vanilla, and heavy cream/yogurt until the mixture is smooth and creamy and similar in appearance to a caramel sauce, about 1 minute. (Alternatively, you can do this with a whisk; it will take roughly the same amount of time.)", 2),
            StepEntity(step4, recipeId, sectionId,
                "Combine all Ingredients: With the mixer running on low speed, slowly add the dry ingredients to the wet ingredients and mix just until combined, about 1 minute. Add both types of chocolate chips and mix on low speed until just incorporated into the dough (don't overmix!).", 3),
            StepEntity(step5, recipeId, sectionId,
                "Create cookie dough balls: Using a 2-ounce scoop or ¼ cup measure, scoop level portions of dough and place on a parchment-lined baking sheet as close together as possible. Flatten the dough for flatter shaped cookies - skip for goo-ier center. Optional: May stuff a teaspoon of Nutella in the center at this step.", 4),
            StepEntity(step6, recipeId, sectionId,
                "Chill the Dough: Cover the sheet tightly with plastic wrap and refrigerate for at least 12 hours and up to 48 (if you're pressed for time, a couple of hours in the refrigerator will do - just note the baked cookies won't be as chewy or wrinkly-looking).", 5),
            StepEntity(step7, recipeId, sectionId,
                "Temper the Dough: Before baking, remove the dough from the fridge and let it sit at room temperature for about 30 minutes.", 6),
            StepEntity(step8, recipeId, sectionId,
                "Pre-heat the Oven: Pre-heat the Oven to 350°F.", 7),
            StepEntity(step9, recipeId, sectionId,
                "Bake the Cookies: Bake the cookie dough portions in a flat metal sheet for 12–20 minutes. 12 min = goo-ier cookies, 18–20 min = crunchier cookies.", 8),
            StepEntity(step10, recipeId, sectionId,
                "Cooldown: Sprinkle Maldon salt over the cookies. Let sit at room temperature for at least 15 minutes before serving.", 9)
        )

        val refs = listOf(
            StepIngredientRefEntity(step1, idButter, "1 cup / 226g"),
            StepIngredientRefEntity(step2, idFlour, "2¼ cup"),
            StepIngredientRefEntity(step2, idBakingSoda, "1 teaspoon"),
            StepIngredientRefEntity(step2, idKosherSalt, "½ teaspoon"),
            StepIngredientRefEntity(step3, idBrownSugar, "¾ cup / 150g"),
            StepIngredientRefEntity(step3, idGranSugar, "¾ cup / 150g"),
            StepIngredientRefEntity(step3, idEggs, "2 large"),
            StepIngredientRefEntity(step3, idVanilla, "1 tablespoon / 15g"),
            StepIngredientRefEntity(step3, idHeavyCream, "2 tablespoon / 28g"),
            StepIngredientRefEntity(step4, idChocChips, "¾ cup / 135g"),
            StepIngredientRefEntity(step4, idDarkChoc, "¾ cup / 135g"),
            StepIngredientRefEntity(step5, idNutella, "1 teaspoon per cookie"),
            StepIngredientRefEntity(step10, idFlakySalt, "pinch per cookie")
        )

        repository.insertFullRecipe(recipe, sections, ingredients, steps, refs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipe 002 — Neopolitan Pizza
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun seedPizzaRecipe() {
        val recipeId = "recipe-002"
        val secDough     = "section-pizza-dough"
        val secMarinara  = "section-pizza-marinara"
        val secBechamel  = "section-pizza-bechamel"
        val secFinal     = "section-pizza-final"
        val now = System.currentTimeMillis()

        val recipe = RecipeEntity(
            id = recipeId,
            title = "Neopolitan Pizza",
            description = "Authentic Neopolitan-style pizza with 00 flour dough proofed 24 hours, homemade marinara and béchamel sauces, and a two-phase home oven bake for a charred, bubbly crust.",
            sourceUrls = gson.toJson(listOf(
                "How to make Neapolitan-style pizza at home | King Arthur Baking",
                "Neapolitan Pizza",
                "How to Make NEAPOLITAN PIZZA DOUGH like a World Best Pizza Chef (YouTube)",
                "Homemade Marinara Sauce - Alphafoodie",
                "Creamy Garlic White Pizza Sauce Recipe",
                "How to Knead Dough for Pizza, Bread, Pita etc.. How to Knead Dough by Hand - Kneading Technique (YouTube)"
            )),
            baseServings = 8,
            baseServingsMin = null,
            baseServingsMax = null,
            scaleIngredientId = null,
            scaleStep = 1.0,
            prepTimeMinutes = 45,
            cookTimeMinutes = 15,
            imageUrl = null,
            tags = gson.toJson(listOf("Dinner", "Italian", "Baking", "Pizza")),
            createdAt = now,
            updatedAt = now
        )

        val sections = listOf(
            RecipeSectionEntity(secDough, recipeId, "Pizza Dough", 0),
            RecipeSectionEntity(secMarinara, recipeId, "Marinara Sauce (Red Sauce)", 1),
            RecipeSectionEntity(secBechamel, recipeId, "Béchamel Sauce (White Sauce)", 2),
            RecipeSectionEntity(secFinal, recipeId, "Final Pizza", 3)
        )

        // ── Dough ingredients ──
        val idWater      = "ing-pizza-001"
        val idFlour      = "ing-pizza-002"
        val idYeast      = "ing-pizza-003"
        val idSugarD     = "ing-pizza-004"
        val idSaltD      = "ing-pizza-005"
        val idOilD       = "ing-pizza-006"
        // ── Marinara ingredients ──
        val idTomatoes   = "ing-pizza-007"
        val idGarlicM    = "ing-pizza-008"
        val idOnionM     = "ing-pizza-009"
        val idSaltM      = "ing-pizza-010"
        val idPepperM    = "ing-pizza-011"
        val idOilM       = "ing-pizza-012"
        val idSugarM     = "ing-pizza-013"
        val idBasilM     = "ing-pizza-014"
        val idRedPepper  = "ing-pizza-015"
        val idOreganoM   = "ing-pizza-016"
        // ── Béchamel ingredients ──
        val idButterB    = "ing-pizza-017"
        val idOnionB     = "ing-pizza-018"
        val idFlourB     = "ing-pizza-019"
        val idCreamB     = "ing-pizza-020"
        val idSaltB      = "ing-pizza-021"
        val idPepperB    = "ing-pizza-022"
        val idGarlicB    = "ing-pizza-023"
        val idOreganoB   = "ing-pizza-024"
        // ── Final pizza ingredients ──
        val idDoughBalls = "ing-pizza-025"
        val idOilF       = "ing-pizza-026"
        // Red sauce pizza
        val idMarinara   = "ing-pizza-027"
        val idParmR      = "ing-pizza-028"
        val idMozzR      = "ing-pizza-029"
        val idBasilF     = "ing-pizza-030"
        // Red sauce optional toppings
        val idPepperoni  = "ing-pizza-031"
        val idCapsicumR  = "ing-pizza-032"
        val idJalapeno   = "ing-pizza-033"
        val idHoney      = "ing-pizza-034"
        val idOlives     = "ing-pizza-035"
        // White sauce pizza
        val idWhiteSauce = "ing-pizza-036"
        val idParmW      = "ing-pizza-037"
        val idMozzW      = "ing-pizza-038"
        // White sauce optional toppings
        val idOnionsW    = "ing-pizza-039"
        val idCapsicumW  = "ing-pizza-040"
        val idChickenW   = "ing-pizza-041"
        val idMushroomW  = "ing-pizza-042"
        val idSpinachW   = "ing-pizza-043"
        val idGoatCheeseW= "ing-pizza-044"

        val ingredients = listOf(
            // ── Dough ──
            IngredientEntity(idWater, recipeId, secDough, "Water",
                2.5, "cup", "600 ml / 2.5 cups", "Dough", false, null, 1.0f, 0),
            IngredientEntity(idFlour, recipeId, secDough, "00 Pizza Flour",
                8.0, "cup", "8 cups / 1kg", "Dough", false, null, 1.0f, 1),
            IngredientEntity(idYeast, recipeId, secDough, "Active Dry Yeast",
                0.5, "teaspoon", "½ teaspoon", "Dough", false, null, 1.0f, 2),
            IngredientEntity(idSugarD, recipeId, secDough, "Sugar",
                2.0, "teaspoon", "2 teaspoon", "Dough", false, null, 1.0f, 3),
            IngredientEntity(idSaltD, recipeId, secDough, "Salt",
                5.0, "teaspoon", "5 teaspoon", "Dough", false, null, 1.0f, 4),
            IngredientEntity(idOilD, recipeId, secDough, "Olive Oil",
                null, null, "for drizzling", "Dough", false, null, 1.0f, 5),
            // ── Marinara ──
            IngredientEntity(idTomatoes, recipeId, secMarinara, "San Marzano Crushed Tomatoes",
                28.0, "oz", "1 can / 28oz", "Marinara", false, null, 1.0f, 0),
            IngredientEntity(idGarlicM, recipeId, secMarinara, "Garlic",
                5.0, "clove", "4–6 cloves", "Marinara", false, null, 1.0f, 1),
            IngredientEntity(idOnionM, recipeId, secMarinara, "Yellow/White Onion",
                1.0, "small", "1 small", "Marinara", true, null, 1.0f, 2),
            IngredientEntity(idSaltM, recipeId, secMarinara, "Salt",
                1.0, "tablespoon", "1 tablespoon / to taste", "Marinara", false, null, 1.0f, 3),
            IngredientEntity(idPepperM, recipeId, secMarinara, "Black Pepper",
                1.0, "teaspoon", "1 teaspoon / to taste", "Marinara", true, null, 1.0f, 4),
            IngredientEntity(idOilM, recipeId, secMarinara, "Olive Oil",
                2.0, "tablespoon", "2 tablespoon", "Marinara", false, null, 1.0f, 5),
            IngredientEntity(idSugarM, recipeId, secMarinara, "Sugar",
                0.5, "tablespoon", "½ tablespoon", "Marinara", true, null, 1.0f, 6),
            IngredientEntity(idBasilM, recipeId, secMarinara, "Fresh Basil Leaves",
                6.0, "leaf", "1–2 tablespoon / 4–6 leaves", "Marinara", false, null, 1.0f, 7),
            IngredientEntity(idRedPepper, recipeId, secMarinara, "Red Pepper Flakes",
                0.25, "teaspoon", "¼ teaspoon", "Marinara", true, null, 1.0f, 8),
            IngredientEntity(idOreganoM, recipeId, secMarinara, "Dried Oregano",
                1.0, "teaspoon", "1 teaspoon", "Marinara", true, null, 1.0f, 9),
            // ── Béchamel ──
            IngredientEntity(idButterB, recipeId, secBechamel, "Unsalted Butter",
                2.0, "tablespoon", "2 tablespoon", "Béchamel", false, null, 1.0f, 0),
            IngredientEntity(idOnionB, recipeId, secBechamel, "Yellow/White Onion",
                1.0, "small", "1 small", "Béchamel", false, null, 1.0f, 1),
            IngredientEntity(idFlourB, recipeId, secBechamel, "All Purpose Flour",
                2.0, "tablespoon", "2 tablespoon", "Béchamel", true, null, 1.0f, 2),
            IngredientEntity(idCreamB, recipeId, secBechamel, "Heavy Cream",
                1.0, "cup", "1 cup", "Béchamel", true, null, 1.0f, 3),
            IngredientEntity(idSaltB, recipeId, secBechamel, "Salt",
                0.25, "teaspoon", "¼ teaspoon / to taste", "Béchamel", false, null, 1.0f, 4),
            IngredientEntity(idPepperB, recipeId, secBechamel, "Black Pepper",
                0.25, "teaspoon", "¼ teaspoon / to taste", "Béchamel", false, null, 1.0f, 5),
            IngredientEntity(idGarlicB, recipeId, secBechamel, "Garlic",
                4.0, "clove", "4 cloves", "Béchamel", false, null, 1.0f, 6),
            IngredientEntity(idOreganoB, recipeId, secBechamel, "Dried Oregano",
                1.0, "teaspoon", "1 teaspoon", "Béchamel", true, null, 1.0f, 7),
            // ── Final Pizza ──
            IngredientEntity(idDoughBalls, recipeId, secFinal, "Pizza Dough Balls",
                8.0, "ball", "8 pizza dough balls", "Final Pizza", false, null, 1.0f, 0),
            IngredientEntity(idOilF, recipeId, secFinal, "Olive Oil",
                0.5, "tablespoon", "½ tablespoon for drizzle per pizza", "Final Pizza", false, null, 1.0f, 1),
            // Red sauce pizza per-pizza
            IngredientEntity(idMarinara, recipeId, secFinal, "Marinara Sauce",
                0.5, "cup", "½ cup", "Red Sauce Pizza", false, null, 1.0f, 2),
            IngredientEntity(idParmR, recipeId, secFinal, "Parmesan Cheese",
                0.5, "cup", "½ cup", "Red Sauce Pizza", false, null, 1.0f, 3),
            IngredientEntity(idMozzR, recipeId, secFinal, "Mozzarella Cheese",
                3.0, "oz", "2–4 oz", "Red Sauce Pizza", false, null, 1.0f, 4),
            IngredientEntity(idBasilF, recipeId, secFinal, "Fresh Basil Leaves",
                5.0, "leaf", "4–6 leaves", "Red Sauce Pizza", false, null, 1.0f, 5),
            // Red sauce optional toppings
            IngredientEntity(idPepperoni, recipeId, secFinal, "Pepperoni",
                11.0, "piece", "10–12 pieces", "Red Sauce Toppings", true, null, 1.0f, 6),
            IngredientEntity(idCapsicumR, recipeId, secFinal, "Capsicum (Julienned)",
                0.25, "cup", "¼ cup / 12 pieces", "Red Sauce Toppings", true, null, 1.0f, 7),
            IngredientEntity(idJalapeno, recipeId, secFinal, "Jalapeno",
                0.25, "cup", "¼ cup / 12 pieces", "Red Sauce Toppings", true, null, 1.0f, 8),
            IngredientEntity(idHoney, recipeId, secFinal, "Honey",
                null, null, "for drizzle", "Red Sauce Toppings", true, null, 1.0f, 9),
            IngredientEntity(idOlives, recipeId, secFinal, "Olives",
                14.0, "piece", "12–16 pieces", "Red Sauce Toppings", true, null, 1.0f, 10),
            // White sauce pizza per-pizza
            IngredientEntity(idWhiteSauce, recipeId, secFinal, "White Sauce",
                0.5, "cup", "½ cup", "White Sauce Pizza", false, null, 1.0f, 11),
            IngredientEntity(idParmW, recipeId, secFinal, "Parmesan Cheese (shredded)",
                0.5, "cup", "½ cup shredded", "White Sauce Pizza", false, null, 1.0f, 12),
            IngredientEntity(idMozzW, recipeId, secFinal, "Mozzarella Cheese",
                3.0, "oz", "2–4 oz", "White Sauce Pizza", false, null, 1.0f, 13),
            // White sauce optional toppings
            IngredientEntity(idOnionsW, recipeId, secFinal, "Onions (Julienned)",
                0.25, "cup", "¼ cup / 12 pieces", "White Sauce Toppings", true, null, 1.0f, 14),
            IngredientEntity(idCapsicumW, recipeId, secFinal, "Capsicum (Julienned)",
                0.25, "cup", "¼ cup / 12 pieces", "White Sauce Toppings", true, null, 1.0f, 15),
            IngredientEntity(idChickenW, recipeId, secFinal, "Chicken",
                11.0, "piece", "10–12 diced pieces", "White Sauce Toppings", true, null, 1.0f, 16),
            IngredientEntity(idMushroomW, recipeId, secFinal, "Mushrooms",
                11.0, "piece", "10–12 diced pieces", "White Sauce Toppings", true, null, 1.0f, 17),
            IngredientEntity(idSpinachW, recipeId, secFinal, "Spinach",
                11.0, "leaf", "10–12 leaves", "White Sauce Toppings", true, null, 1.0f, 18),
            IngredientEntity(idGoatCheeseW, recipeId, secFinal, "Goat Cheese/Feta",
                6.0, "lump", "4–8 lumps", "White Sauce Toppings", true, null, 1.0f, 19)
        )

        val sD1 = "step-pizza-d01"; val sD2 = "step-pizza-d02"; val sD3 = "step-pizza-d03"
        val sD4 = "step-pizza-d04"; val sD5 = "step-pizza-d05"; val sD6 = "step-pizza-d06"
        val sD7 = "step-pizza-d07"; val sD8 = "step-pizza-d08"
        val sM1 = "step-pizza-m01"; val sM2 = "step-pizza-m02"; val sM3 = "step-pizza-m03"
        val sM4 = "step-pizza-m04"; val sM5 = "step-pizza-m05"
        val sB1 = "step-pizza-b01"; val sB2 = "step-pizza-b02"; val sB3 = "step-pizza-b03"
        val sB4 = "step-pizza-b04"
        val sF1 = "step-pizza-f01"; val sF2 = "step-pizza-f02"; val sF3 = "step-pizza-f03"
        val sF4 = "step-pizza-f04"; val sF5 = "step-pizza-f05"; val sF6 = "step-pizza-f06"
        val sF7 = "step-pizza-f07"; val sF8 = "step-pizza-f08"

        val steps = listOf(
            // ── Dough ──
            StepEntity(sD1, recipeId, secDough,
                "Mix salt and Active dry yeast in luke warm water to make wet ingredient mixture. Then add sugar into the mixture.", 0),
            StepEntity(sD2, recipeId, secDough,
                "Slowly mix in Pizza flour bit by bit to the wet ingredient mixture while mixing it slowly into the mixture.", 1),
            StepEntity(sD3, recipeId, secDough,
                "Prepare the platform/station for kneading the dough — sprinkle flour on the platform/station. Start kneading the dough — stretch and fold the dough, push the dough in and apply pressure, then rotate the dough by 90 degrees and repeat. Knead the dough for 20 minutes.", 2),
            StepEntity(sD4, recipeId, secDough,
                "Check the dough for lump test — if you poke the dough in, and it springs back out slowly and is warm enough at around 23–26°C, it is ready.", 3),
            StepEntity(sD5, recipeId, secDough,
                "Put the dough in a covered container (not airtight) to prevent them from drying out but enough breathing holes. Let the dough rest in the container in the fridge for 24 hours and let it proof.", 4),
            StepEntity(sD6, recipeId, secDough,
                "After 24 hours, the dough should have risen. Remove the dough and let it sit for 10–15 minutes at room temperature to soften.", 5),
            StepEntity(sD7, recipeId, secDough,
                "Roll the dough and cut the dough in 8 equal pieces, roughly 200g each piece. Form each dough piece into smooth round dough balls by gathering and stretching dough from the sides and pinching and tucking them at the back.", 6),
            StepEntity(sD8, recipeId, secDough,
                "Set all dough balls in a container and apply a little olive oil on top of the dough balls. Put a damp cloth on top and place the container in a warm environment (preferably in the oven with the oven lights on). Set the dough balls to proof for 1.5–2 hours until doubled in size. The Pizza dough is ready.", 7),
            // ── Marinara ──
            StepEntity(sM1, recipeId, secMarinara,
                "In a saucepan on medium heat, add olive oil and then finely chopped onions once heated. Once the onions start to soften at around 3–5 min mark, add some salt to taste and some sugar to work towards caramelization of onions.", 8),
            StepEntity(sM2, recipeId, secMarinara,
                "Add some water to the onions and set it at low flame in the covered saucepan for 10–15 minutes until golden brown.", 9),
            StepEntity(sM3, recipeId, secMarinara,
                "Add crushed or finely chopped garlic and sauté for a minute.", 10),
            StepEntity(sM4, recipeId, secMarinara,
                "Add San Marzano Crushed Tomatoes to the saucepan and put it to medium flame. Bring the sauce to a boil and then put it at low-medium flame and let it simmer for 15–30 minutes. It is better to let it simmer for longer.", 11),
            StepEntity(sM5, recipeId, secMarinara,
                "Add the rest of the flavors — salt, black pepper, red pepper flakes, dried oregano, and fresh basil leaves. Stir in the flavors on medium flame for another 2–3 minutes. The marinara sauce is ready for the Pizza.", 12),
            // ── Béchamel ──
            StepEntity(sB1, recipeId, secBechamel,
                "In a saucepan or skillet, heat butter until it melts on medium flame. Add finely chopped onions and let it soften for about 3–5 minutes.", 13),
            StepEntity(sB2, recipeId, secBechamel,
                "Add garlic and flour and sauté for a minute.", 14),
            StepEntity(sB3, recipeId, secBechamel,
                "Add flavoring such as salt, black pepper and oregano and sauté for a minute.", 15),
            StepEntity(sB4, recipeId, secBechamel,
                "Add heavy cream or milk and continuously mix the sauce until it thickens. Optional: May add shredded Parmesan cheese at this point until it melts and sauce thickens. The white sauce is ready for the Pizza.", 16),
            // ── Final Pizza ──
            StepEntity(sF1, recipeId, secFinal,
                "Preheat Oven to 550°F in BAKE MODE for Phase 1 in home oven.", 17),
            StepEntity(sF2, recipeId, secFinal,
                "Prep the toppings: For red sauce — may sprinkle salt on capsicum and sauté in olive oil. Pepperoni, jalapeno, olives may be added directly. For white sauce — sauté onions and capsicum in olive oil until softened. Dice and sauté chicken with salt, pepper, garlic and onion powder. Sauté mushrooms until water drains. Sprinkle salt on spinach and sauté till it shrinks.", 18),
            StepEntity(sF3, recipeId, secFinal,
                "Sprinkle flour on platform and on the dough balls. For each dough ball, stretch out and flatten the dough to make a flat dough spread with rounded corners.", 19),
            StepEntity(sF4, recipeId, secFinal,
                "For red sauce pizza, spread the Marinara sauce and then shredded Parmesan cheese on the pizza. For white sauce pizza, spread Parmesan cheese on top of the pizza.", 20),
            StepEntity(sF5, recipeId, secFinal,
                "Phase 1 Oven — Put the flat dough on a metal baking sheet/Pizza tray and put it in the Oven in middle rack at 550°F in BAKE MODE for 4–5 minutes. Watch the dough until it is slightly cooked and turns off-white/golden brown. Remove the cooked dough.", 21),
            StepEntity(sF6, recipeId, secFinal,
                "Add the rest of the toppings — Margherita: break mozzarella cheese into small pieces by hand and drop evenly, add basil leaves. Red Sauce: add toppings of choice, then mozzarella and basil. White Sauce: spread white sauce, add toppings, finish with mozzarella.", 22),
            StepEntity(sF7, recipeId, secFinal,
                "Phase 2 Oven — Change Oven mode to BROIL MODE at HIGH. Put the final pizza on the top rack for about 3–4 minutes. Watch until the outer crust begins to char or turns dark brown. Wait until Mozzarella cheese completely melts and forms white puddles. Avoid Mozzarella cheese turning brown.", 23),
            StepEntity(sF8, recipeId, secFinal,
                "Take the pizza out. Add garnish — may add fresh basil leaves to red sauce pizza or honey to pepperoni pizza. May add roasted garlic or cold straciatella cheese on white pizza. Chop into 6–8 pieces and enjoy.", 24)
        )

        val refs = listOf(
            // Dough steps
            StepIngredientRefEntity(sD1, idWater, "600 ml / 2.5 cups"),
            StepIngredientRefEntity(sD1, idSaltD, "5 teaspoon"),
            StepIngredientRefEntity(sD1, idYeast, "½ teaspoon"),
            StepIngredientRefEntity(sD1, idSugarD, "2 teaspoon"),
            StepIngredientRefEntity(sD2, idFlour, "8 cups / 1kg"),
            StepIngredientRefEntity(sD8, idOilD, "for drizzling"),
            // Marinara steps
            StepIngredientRefEntity(sM1, idOilM, "2 tablespoon"),
            StepIngredientRefEntity(sM1, idOnionM, "1 small"),
            StepIngredientRefEntity(sM1, idSugarM, "½ tablespoon"),
            StepIngredientRefEntity(sM3, idGarlicM, "4–6 cloves"),
            StepIngredientRefEntity(sM4, idTomatoes, "1 can / 28oz"),
            StepIngredientRefEntity(sM5, idSaltM, "1 tablespoon"),
            StepIngredientRefEntity(sM5, idPepperM, "1 teaspoon"),
            StepIngredientRefEntity(sM5, idRedPepper, "¼ teaspoon"),
            StepIngredientRefEntity(sM5, idOreganoM, "1 teaspoon"),
            StepIngredientRefEntity(sM5, idBasilM, "4–6 leaves"),
            // Béchamel steps
            StepIngredientRefEntity(sB1, idButterB, "2 tablespoon"),
            StepIngredientRefEntity(sB1, idOnionB, "1 small"),
            StepIngredientRefEntity(sB2, idGarlicB, "4 cloves"),
            StepIngredientRefEntity(sB2, idFlourB, "2 tablespoon"),
            StepIngredientRefEntity(sB3, idSaltB, "¼ teaspoon"),
            StepIngredientRefEntity(sB3, idPepperB, "¼ teaspoon"),
            StepIngredientRefEntity(sB3, idOreganoB, "1 teaspoon"),
            StepIngredientRefEntity(sB4, idCreamB, "1 cup"),
            // Final pizza steps
            StepIngredientRefEntity(sF3, idDoughBalls, "8 pizza dough balls"),
            StepIngredientRefEntity(sF4, idMarinara, "½ cup"),
            StepIngredientRefEntity(sF4, idParmR, "½ cup"),
            StepIngredientRefEntity(sF6, idMozzR, "2–4 oz"),
            StepIngredientRefEntity(sF6, idBasilF, "4–6 leaves"),
            StepIngredientRefEntity(sF6, idWhiteSauce, "½ cup"),
            StepIngredientRefEntity(sF8, idOilF, "½ tablespoon drizzle")
        )

        repository.insertFullRecipe(recipe, sections, ingredients, steps, refs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipe 003 — Butter Chicken
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun seedButterChickenRecipe() {
        val recipeId = "recipe-003"
        val secTandoori = "section-bc-tandoori"
        val secGravy    = "section-bc-gravy"
        val secFinal    = "section-bc-final"
        val now = System.currentTimeMillis()

        val recipe = RecipeEntity(
            id = recipeId,
            title = "Butter Chicken",
            description = "Written recipe of Butter Chicken by Sanjyot Keer. Tandoori-grilled chicken shredded into a rich, slow-cooked makhani gravy finished with butter and fresh cream.",
            sourceUrls = gson.toJson(listOf(
                "Sanjyot Keer — Butter Chicken"
            )),
            baseServings = 6,
            baseServingsMin = null,
            baseServingsMax = null,
            scaleIngredientId = null,
            scaleStep = 1.0,
            prepTimeMinutes = 60,
            cookTimeMinutes = 60,
            imageUrl = null,
            tags = gson.toJson(listOf("Dinner", "Indian", "Curry", "Chicken")),
            createdAt = now,
            updatedAt = now
        )

        val sections = listOf(
            RecipeSectionEntity(secTandoori, recipeId, "Tandoori Chicken / Chicken Tikka", 0),
            RecipeSectionEntity(secGravy, recipeId, "Makhani Gravy Base", 1),
            RecipeSectionEntity(secFinal, recipeId, "Butter Chicken Final Cooking", 2)
        )

        // ── Tandoori / Marinade ──
        val idChicken    = "ing-bc-001"
        val idMustardOil = "ing-bc-002"
        val idChiliM     = "ing-bc-003"
        val idGinGarM    = "ing-bc-004"
        val idDhaniyaM   = "ing-bc-005"
        val idJeeraM     = "ing-bc-006"
        val idHaldiM     = "ing-bc-007"
        val idKasuriM    = "ing-bc-008"
        val idAmchurM    = "ing-bc-009"
        val idBlackSaltM = "ing-bc-010"
        val idGaramM     = "ing-bc-011"
        val idMintM      = "ing-bc-012"
        val idCorianderM = "ing-bc-013"
        val idSaltM      = "ing-bc-014"
        val idCurdM      = "ing-bc-015"
        val idLemonM     = "ing-bc-016"
        val idCharcoalM  = "ing-bc-017"
        val idOilGrill   = "ing-bc-018"
        // ── Gravy Base ──
        val idOilG       = "ing-bc-019"
        val idJeeraG     = "ing-bc-020"
        val idOnionsG    = "ing-bc-021"
        val idGarlicG    = "ing-bc-022"
        val idGingerG    = "ing-bc-023"
        val idGreenChiG  = "ing-bc-024"
        val idChiliG     = "ing-bc-025"
        val idDhaniyaG   = "ing-bc-026"
        val idHaldiG     = "ing-bc-027"
        val idKasuriG    = "ing-bc-028"
        val idWholeChili = "ing-bc-029"
        val idCardamomG  = "ing-bc-030"
        val idTomatoesG  = "ing-bc-031"
        val idCashewG    = "ing-bc-032"
        val idCorStemsG  = "ing-bc-033"
        val idSaltG      = "ing-bc-034"
        val idButterG    = "ing-bc-035"
        // ── Final Cooking ──
        val idButterF1   = "ing-bc-036"
        val idOilF       = "ing-bc-037"
        val idOnionsF    = "ing-bc-038"
        val idGarlicF    = "ing-bc-039"
        val idGingerF    = "ing-bc-040"
        val idGreenChiF  = "ing-bc-041"
        val idChiliF     = "ing-bc-042"
        val idGravyBase  = "ing-bc-043"
        val idWaterF     = "ing-bc-044"
        val idSugarF     = "ing-bc-045"
        val idGrillChick = "ing-bc-046"
        val idKasuriF    = "ing-bc-047"
        val idGaramF     = "ing-bc-048"
        val idSaltF      = "ing-bc-049"
        val idButterF2   = "ing-bc-050"
        val idCreamF     = "ing-bc-051"
        val idCilantroF  = "ing-bc-052"

        val ingredients = listOf(
            // ── Tandoori Chicken ──
            IngredientEntity(idChicken, recipeId, secTandoori, "Boneless Chicken",
                1.0, "kg", "1 kg", "Chicken", false, null, 1.0f, 0),
            IngredientEntity(idMustardOil, recipeId, secTandoori, "Mustard Oil",
                2.0, "tablespoon", "2 tbsp", "Marinade", false, null, 1.0f, 1),
            IngredientEntity(idChiliM, recipeId, secTandoori, "Kashmiri Red Chilli Powder",
                2.0, "tablespoon", "2 tbsp", "Marinade", false, null, 1.0f, 2),
            IngredientEntity(idGinGarM, recipeId, secTandoori, "Ginger Garlic Paste",
                2.0, "tablespoon", "2 tbsp", "Marinade", false, null, 1.0f, 3),
            IngredientEntity(idDhaniyaM, recipeId, secTandoori, "Dhaniya (Coriander) Powder",
                1.0, "tablespoon", "1 tbsp", "Marinade", false, null, 1.0f, 4),
            IngredientEntity(idJeeraM, recipeId, secTandoori, "Jeera (Cumin) Powder",
                1.0, "teaspoon", "1 tsp", "Marinade", false, null, 1.0f, 5),
            IngredientEntity(idHaldiM, recipeId, secTandoori, "Haldi (Turmeric) Powder",
                0.5, "teaspoon", "½ tsp", "Marinade", false, null, 1.0f, 6),
            IngredientEntity(idKasuriM, recipeId, secTandoori, "Kasuri Methi",
                1.0, "teaspoon", "1 tsp", "Marinade", false, null, 1.0f, 7),
            IngredientEntity(idAmchurM, recipeId, secTandoori, "Amchur Powder",
                1.0, "teaspoon", "1 tsp", "Marinade", false, null, 1.0f, 8),
            IngredientEntity(idBlackSaltM, recipeId, secTandoori, "Black Salt",
                0.5, "teaspoon", "½ tsp", "Marinade", false, null, 1.0f, 9),
            IngredientEntity(idGaramM, recipeId, secTandoori, "Garam Masala",
                1.0, "teaspoon", "1 tsp", "Marinade", false, null, 1.0f, 10),
            IngredientEntity(idMintM, recipeId, secTandoori, "Fresh Mint",
                1.0, "tablespoon", "1 tbsp", "Marinade", false, null, 1.0f, 11),
            IngredientEntity(idCorianderM, recipeId, secTandoori, "Fresh Coriander",
                1.0, "tablespoon", "1 tbsp", "Marinade", false, null, 1.0f, 12),
            IngredientEntity(idSaltM, recipeId, secTandoori, "Salt",
                null, null, "to taste", "Marinade", false, null, 1.0f, 13),
            IngredientEntity(idCurdM, recipeId, secTandoori, "Thick Curd",
                0.5, "cup", "½ cup", "Marinade", false, null, 1.0f, 14),
            IngredientEntity(idLemonM, recipeId, secTandoori, "Lemon Juice",
                1.0, "tablespoon", "1 tbsp", "Marinade", false, null, 1.0f, 15),
            IngredientEntity(idCharcoalM, recipeId, secTandoori, "Live Charcoal + Ghee",
                null, null, "for smoking", "Marinade", true, null, 1.0f, 16),
            IngredientEntity(idOilGrill, recipeId, secTandoori, "Oil",
                null, null, "for grilling", "Marinade", false, null, 1.0f, 17),
            // ── Makhani Gravy Base ──
            IngredientEntity(idOilG, recipeId, secGravy, "Oil",
                2.0, "tablespoon", "2 tbsp", "Gravy Base", false, null, 1.0f, 0),
            IngredientEntity(idJeeraG, recipeId, secGravy, "Jeera (Cumin Seeds)",
                1.0, "teaspoon", "1 tsp", "Gravy Base", false, null, 1.0f, 1),
            IngredientEntity(idOnionsG, recipeId, secGravy, "Onions (sliced)",
                4.0, "whole", "3–4 medium size", "Gravy Base", false, null, 1.0f, 2),
            IngredientEntity(idGarlicG, recipeId, secGravy, "Garlic",
                13.0, "clove", "10–15 cloves", "Gravy Base", false, null, 1.0f, 3),
            IngredientEntity(idGingerG, recipeId, secGravy, "Ginger (roughly chopped)",
                1.0, "inch", "1 inch", "Gravy Base", false, null, 1.0f, 4),
            IngredientEntity(idGreenChiG, recipeId, secGravy, "Green Chillies",
                4.0, "whole", "3–4 nos.", "Gravy Base", false, null, 1.0f, 5),
            IngredientEntity(idChiliG, recipeId, secGravy, "Kashmiri Red Chilli Powder",
                2.0, "tablespoon", "2 tbsp", "Powdered Spices", false, null, 1.0f, 6),
            IngredientEntity(idDhaniyaG, recipeId, secGravy, "Dhaniya (Coriander) Powder",
                1.0, "tablespoon", "1 tbsp", "Powdered Spices", false, null, 1.0f, 7),
            IngredientEntity(idHaldiG, recipeId, secGravy, "Haldi (Turmeric) Powder",
                0.5, "teaspoon", "½ tsp", "Powdered Spices", false, null, 1.0f, 8),
            IngredientEntity(idKasuriG, recipeId, secGravy, "Kasuri Methi",
                0.5, "teaspoon", "½ tsp", "Powdered Spices", false, null, 1.0f, 9),
            IngredientEntity(idWholeChili, recipeId, secGravy, "Whole Kashmiri Red Chillies",
                7.0, "whole", "6–7 nos.", "Whole Spices", false, null, 1.0f, 10),
            IngredientEntity(idCardamomG, recipeId, secGravy, "Green Cardamom",
                4.0, "whole", "3–4 nos.", "Whole Spices", false, null, 1.0f, 11),
            IngredientEntity(idTomatoesG, recipeId, secGravy, "Tomatoes (roughly chopped)",
                1.0, "kg", "1 kg", "Gravy Base", false, null, 1.0f, 12),
            IngredientEntity(idCashewG, recipeId, secGravy, "Cashew Nuts",
                0.33, "cup", "⅓ cup", "Gravy Base", false, null, 1.0f, 13),
            IngredientEntity(idCorStemsG, recipeId, secGravy, "Coriander Stems & Roots",
                null, null, "a small handful", "Gravy Base", false, null, 1.0f, 14),
            IngredientEntity(idSaltG, recipeId, secGravy, "Salt",
                null, null, "to taste", "Gravy Base", false, null, 1.0f, 15),
            IngredientEntity(idButterG, recipeId, secGravy, "Butter",
                2.0, "tablespoon", "2 tbsp", "Gravy Base", false, null, 1.0f, 16),
            // ── Final Cooking ──
            IngredientEntity(idButterF1, recipeId, secFinal, "Butter",
                2.0, "tablespoon", "2 tbsp", "Final Cooking", false, null, 1.0f, 0),
            IngredientEntity(idOilF, recipeId, secFinal, "Oil",
                1.0, "teaspoon", "1 tsp", "Final Cooking", false, null, 1.0f, 1),
            IngredientEntity(idOnionsF, recipeId, secFinal, "Onions (chopped)",
                1.0, "whole", "1 medium size", "Final Cooking", false, null, 1.0f, 2),
            IngredientEntity(idGarlicF, recipeId, secFinal, "Garlic (chopped)",
                3.0, "tablespoon", "3 tbsp", "Final Cooking", false, null, 1.0f, 3),
            IngredientEntity(idGingerF, recipeId, secFinal, "Ginger (chopped)",
                1.0, "tablespoon", "1 tbsp", "Final Cooking", false, null, 1.0f, 4),
            IngredientEntity(idGreenChiF, recipeId, secFinal, "Green Chillies (slit)",
                3.0, "whole", "2–3 nos.", "Final Cooking", false, null, 1.0f, 5),
            IngredientEntity(idChiliF, recipeId, secFinal, "Kashmiri Red Chilli Powder",
                1.0, "tablespoon", "1 tbsp", "Final Cooking", false, null, 1.0f, 6),
            IngredientEntity(idGravyBase, recipeId, secFinal, "Makhani Gravy Base",
                null, null, "prepared gravy base", "Final Cooking", false, null, 1.0f, 7),
            IngredientEntity(idWaterF, recipeId, secFinal, "Hot Water",
                null, null, "as required", "Final Cooking", false, null, 1.0f, 8),
            IngredientEntity(idSugarF, recipeId, secFinal, "Sugar/Honey",
                1.0, "teaspoon", "1 tsp", "Final Cooking", false, null, 1.0f, 9),
            IngredientEntity(idGrillChick, recipeId, secFinal, "Grilled Chicken (shredded)",
                null, null, "prepared chicken", "Final Cooking", false, null, 1.0f, 10),
            IngredientEntity(idKasuriF, recipeId, secFinal, "Kasuri Methi (toasted)",
                0.5, "teaspoon", "½ tsp", "Final Cooking", false, null, 1.0f, 11),
            IngredientEntity(idGaramF, recipeId, secFinal, "Garam Masala",
                0.5, "teaspoon", "½ tsp", "Final Cooking", false, null, 1.0f, 12),
            IngredientEntity(idSaltF, recipeId, secFinal, "Salt",
                null, null, "to taste", "Final Cooking", false, null, 1.0f, 13),
            IngredientEntity(idButterF2, recipeId, secFinal, "Butter",
                2.0, "tablespoon", "2 tbsp", "Finishing", false, null, 1.0f, 14),
            IngredientEntity(idCreamF, recipeId, secFinal, "Fresh Cream",
                6.0, "tablespoon", "5–6 tbsp", "Finishing", false, null, 1.0f, 15),
            IngredientEntity(idCilantroF, recipeId, secFinal, "Fresh Coriander (chopped)",
                null, null, "as required", "Garnish", true, null, 1.0f, 16)
        )

        val sT1 = "step-bc-t01"; val sT2 = "step-bc-t02"; val sT3 = "step-bc-t03"; val sT4 = "step-bc-t04"
        val sG1 = "step-bc-g01"; val sG2 = "step-bc-g02"; val sG3 = "step-bc-g03"; val sG4 = "step-bc-g04"
        val sG5 = "step-bc-g05"; val sG6 = "step-bc-g06"
        val sF1 = "step-bc-f01"; val sF2 = "step-bc-f02"; val sF3 = "step-bc-f03"
        val sF4 = "step-bc-f04"; val sF5 = "step-bc-f05"; val sF6 = "step-bc-f06"

        val steps = listOf(
            // ── Tandoori Chicken ──
            StepEntity(sT1, recipeId, secTandoori,
                "Start by slicing the chicken breast (or chicken thighs). Slice them thinly for even and faster cooking.", 0),
            StepEntity(sT2, recipeId, secTandoori,
                "For marinade, take a mixing bowl, add Mustard oil and Kashmiri Red chilli powder, mix well to bleed its natural red colour. Further add the remaining marinade ingredients and mix well. Add the thinly sliced chicken, mix and coat well and keep it marinated for at least an hour or two (do not go beyond 20 hours in the fridge).", 1),
            StepEntity(sT3, recipeId, secTandoori,
                "Optional: Smoke the marinated chicken for 4–5 minutes with live charcoal and ghee to impart smoky flavour.", 2),
            StepEntity(sT4, recipeId, secTandoori,
                "Set a grill pan or any pan and grill the chicken on both sides until golden brown in colour (you can also grill in oven for 5–6 minutes at 220°C). Let them cool for a while and shred them in thin strips. Keep aside.", 3),
            // ── Makhani Gravy Base ──
            StepEntity(sG1, recipeId, secGravy,
                "Set a stock pot on medium heat, add oil, jeera and sliced onions, sauté until onions are light golden brown.", 4),
            StepEntity(sG2, recipeId, secGravy,
                "Add garlic, ginger, green chillies, powdered spices, whole chillies and green cardamom, mix well and cook for a minute.", 5),
            StepEntity(sG3, recipeId, secGravy,
                "Add tomatoes, cashew nuts, coriander stems with its roots, salt to taste and butter, mix and cook on medium high heat for 5 minutes.", 6),
            StepEntity(sG4, recipeId, secGravy,
                "Lower the heat, cover and cook for 25–30 minutes on medium low heat. Stir in intervals to avoid sticking. The tomatoes will leave their water — if it starts to dry, add hot water as required.", 7),
            StepEntity(sG5, recipeId, secGravy,
                "Once the tomatoes are cooked, switch off the flame and let the mixture cool down to room temperature.", 8),
            StepEntity(sG6, recipeId, secGravy,
                "Transfer the mixture to a grinding jar and grind to a fine puree (may add ice cubes if in a hurry). Strain the puree with a sieve and keep aside.", 9),
            // ── Final Cooking ──
            StepEntity(sF1, recipeId, secFinal,
                "Set a wok on medium heat, add oil and butter, onions, ginger, garlic and green chillies, sauté until the onions are translucent.", 10),
            StepEntity(sF2, recipeId, secFinal,
                "Lower the heat and add Kashmiri Red chilli powder, cook for about ½ minute to one minute, mix and further add the strained Makhani gravy base. Add some hot water to adjust the consistency of the gravy.", 11),
            StepEntity(sF3, recipeId, secFinal,
                "Add sugar/honey, and cook for 15–20 minutes on medium flame, keep stirring in short intervals.", 12),
            StepEntity(sF4, recipeId, secFinal,
                "Add the shredded grilled chicken, stir well, add some hot water if required to adjust the consistency. Cook further for 5–6 minutes on medium flame.", 13),
            StepEntity(sF5, recipeId, secFinal,
                "Add Kasuri methi and garam masala, mix well and check for the seasoning, adjust salt as required.", 14),
            StepEntity(sF6, recipeId, secFinal,
                "Lower the flame and add butter and fresh cream, stir well and cook for a minute — do not overcook. Finish with freshly chopped coriander leaves. Serve hot with butter garlic paratha, naan or any Indian bread of your choice.", 15)
        )

        val refs = listOf(
            // Tandoori steps
            StepIngredientRefEntity(sT1, idChicken, "1 kg"),
            StepIngredientRefEntity(sT2, idMustardOil, "2 tbsp"),
            StepIngredientRefEntity(sT2, idChiliM, "2 tbsp"),
            StepIngredientRefEntity(sT2, idGinGarM, "2 tbsp"),
            StepIngredientRefEntity(sT2, idDhaniyaM, "1 tbsp"),
            StepIngredientRefEntity(sT2, idJeeraM, "1 tsp"),
            StepIngredientRefEntity(sT2, idHaldiM, "½ tsp"),
            StepIngredientRefEntity(sT2, idKasuriM, "1 tsp"),
            StepIngredientRefEntity(sT2, idAmchurM, "1 tsp"),
            StepIngredientRefEntity(sT2, idBlackSaltM, "½ tsp"),
            StepIngredientRefEntity(sT2, idGaramM, "1 tsp"),
            StepIngredientRefEntity(sT2, idMintM, "1 tbsp"),
            StepIngredientRefEntity(sT2, idCorianderM, "1 tbsp"),
            StepIngredientRefEntity(sT2, idSaltM, "to taste"),
            StepIngredientRefEntity(sT2, idCurdM, "½ cup"),
            StepIngredientRefEntity(sT2, idLemonM, "1 tbsp"),
            StepIngredientRefEntity(sT3, idCharcoalM, "for smoking"),
            StepIngredientRefEntity(sT4, idOilGrill, "for grilling"),
            // Gravy steps
            StepIngredientRefEntity(sG1, idOilG, "2 tbsp"),
            StepIngredientRefEntity(sG1, idJeeraG, "1 tsp"),
            StepIngredientRefEntity(sG1, idOnionsG, "3–4 medium"),
            StepIngredientRefEntity(sG2, idGarlicG, "10–15 cloves"),
            StepIngredientRefEntity(sG2, idGingerG, "1 inch"),
            StepIngredientRefEntity(sG2, idGreenChiG, "3–4 nos."),
            StepIngredientRefEntity(sG2, idChiliG, "2 tbsp"),
            StepIngredientRefEntity(sG2, idDhaniyaG, "1 tbsp"),
            StepIngredientRefEntity(sG2, idHaldiG, "½ tsp"),
            StepIngredientRefEntity(sG2, idKasuriG, "½ tsp"),
            StepIngredientRefEntity(sG2, idWholeChili, "6–7 nos."),
            StepIngredientRefEntity(sG2, idCardamomG, "3–4 nos."),
            StepIngredientRefEntity(sG3, idTomatoesG, "1 kg"),
            StepIngredientRefEntity(sG3, idCashewG, "⅓ cup"),
            StepIngredientRefEntity(sG3, idCorStemsG, "a small handful"),
            StepIngredientRefEntity(sG3, idSaltG, "to taste"),
            StepIngredientRefEntity(sG3, idButterG, "2 tbsp"),
            // Final cooking steps
            StepIngredientRefEntity(sF1, idButterF1, "2 tbsp"),
            StepIngredientRefEntity(sF1, idOilF, "1 tsp"),
            StepIngredientRefEntity(sF1, idOnionsF, "1 medium"),
            StepIngredientRefEntity(sF1, idGarlicF, "3 tbsp"),
            StepIngredientRefEntity(sF1, idGingerF, "1 tbsp"),
            StepIngredientRefEntity(sF1, idGreenChiF, "2–3 nos."),
            StepIngredientRefEntity(sF2, idChiliF, "1 tbsp"),
            StepIngredientRefEntity(sF2, idGravyBase, "prepared gravy base"),
            StepIngredientRefEntity(sF3, idSugarF, "1 tsp"),
            StepIngredientRefEntity(sF4, idGrillChick, "prepared chicken"),
            StepIngredientRefEntity(sF5, idKasuriF, "½ tsp"),
            StepIngredientRefEntity(sF5, idGaramF, "½ tsp"),
            StepIngredientRefEntity(sF5, idSaltF, "to taste"),
            StepIngredientRefEntity(sF6, idButterF2, "2 tbsp"),
            StepIngredientRefEntity(sF6, idCreamF, "5–6 tbsp"),
            StepIngredientRefEntity(sF6, idCilantroF, "as required")
        )

        repository.insertFullRecipe(recipe, sections, ingredients, steps, refs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recipe 004 — Malai Kofta
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun seedMalaiKoftaRecipe() {
        val recipeId = "recipe-004"
        val secKofta = "section-mk-kofta"
        val secFill  = "section-mk-filling"
        val secGravy = "section-mk-gravy"
        val now = System.currentTimeMillis()

        val recipe = RecipeEntity(
            id = recipeId,
            title = "Malai Kofta",
            description = "Full written recipe for Malai Kofta by Sanjyot Keer. Crispy paneer-mawa kofta balls stuffed with a saffron-nut filling, served in a silky smooth onion-cashew-melon seed gravy finished with fresh cream and butter.",
            sourceUrls = gson.toJson(listOf(
                "Sanjyot Keer — Malai Kofta"
            )),
            baseServings = 5,
            baseServingsMin = 4,
            baseServingsMax = 5,
            scaleIngredientId = null,
            scaleStep = 1.0,
            prepTimeMinutes = 20,
            cookTimeMinutes = 30,
            imageUrl = null,
            tags = gson.toJson(listOf("Dinner", "Indian", "Vegetarian", "Paneer")),
            createdAt = now,
            updatedAt = now
        )

        val sections = listOf(
            RecipeSectionEntity(secKofta, recipeId, "Kofta Balls", 0),
            RecipeSectionEntity(secFill, recipeId, "Kofta Filling", 1),
            RecipeSectionEntity(secGravy, recipeId, "Gravy", 2)
        )

        // ── Kofta Balls ──
        val idPaneer     = "ing-mk-001"
        val idMawa       = "ing-mk-002"
        val idSaltK      = "ing-mk-003"
        val idElaichiK   = "ing-mk-004"
        val idMaida      = "ing-mk-005"
        val idBakingPow  = "ing-mk-006"
        val idOilFry     = "ing-mk-007"
        // ── Kofta Filling ──
        val idSaffron    = "ing-mk-008"
        val idMilkF      = "ing-mk-009"
        val idGheeF      = "ing-mk-010"
        val idCashewF    = "ing-mk-011"
        val idAlmondsF   = "ing-mk-012"
        val idGingerF    = "ing-mk-013"
        val idGreenChiF  = "ing-mk-014"
        val idKasuriF    = "ing-mk-015"
        val idGaramF     = "ing-mk-016"
        val idSugarF     = "ing-mk-017"
        val idSaltF      = "ing-mk-018"
        val idWhitePepF  = "ing-mk-019"
        // ── Gravy ──
        val idOnionsG    = "ing-mk-020"
        val idCashewG    = "ing-mk-021"
        val idMelonG     = "ing-mk-022"
        val idOilG       = "ing-mk-023"
        val idGheeG      = "ing-mk-024"
        val idCuminG     = "ing-mk-025"
        val idCardGreen  = "ing-mk-026"
        val idCardBlack  = "ing-mk-027"
        val idCinnamon   = "ing-mk-028"
        val idBayLeaf    = "ing-mk-029"
        val idCloves     = "ing-mk-030"
        val idPepper     = "ing-mk-031"
        val idStarAnise  = "ing-mk-032"
        val idMace       = "ing-mk-033"
        val idGinGarG    = "ing-mk-034"
        val idGreenChiG  = "ing-mk-035"
        val idElaichiG   = "ing-mk-036"
        val idSugarG     = "ing-mk-037"
        val idWhitePepG  = "ing-mk-038"
        val idSaltG      = "ing-mk-039"
        val idKasuriG    = "ing-mk-040"
        val idGaramG     = "ing-mk-041"
        val idKewda      = "ing-mk-042"
        val idSugarG2    = "ing-mk-043"
        val idCreamG     = "ing-mk-044"
        val idButterG    = "ing-mk-045"
        val idChiliOil   = "ing-mk-046"
        val idKasuriGarn = "ing-mk-047"

        val ingredients = listOf(
            // ── Kofta Balls ──
            IngredientEntity(idPaneer, recipeId, secKofta, "Paneer",
                300.0, "gm", "300 gm", "Kofta", false, null, 1.0f, 0),
            IngredientEntity(idMawa, recipeId, secKofta, "Soft Fresh Mawa",
                100.0, "gm", "100 gm", "Kofta", false, null, 1.0f, 1),
            IngredientEntity(idSaltK, recipeId, secKofta, "Salt",
                null, null, "to taste", "Kofta", false, null, 1.0f, 2),
            IngredientEntity(idElaichiK, recipeId, secKofta, "Elaichi (Green Cardamom) Powder",
                null, null, "a pinch", "Kofta", false, null, 1.0f, 3),
            IngredientEntity(idMaida, recipeId, secKofta, "Refined Flour (Maida)",
                3.0, "tablespoon", "3 tbsp", "Kofta", false, null, 1.0f, 4),
            IngredientEntity(idBakingPow, recipeId, secKofta, "Baking Powder",
                0.25, "teaspoon", "¼ tsp", "Kofta", false, null, 1.0f, 5),
            IngredientEntity(idOilFry, recipeId, secKofta, "Oil",
                null, null, "for deep frying", "Kofta", false, null, 1.0f, 6),
            // ── Kofta Filling ──
            IngredientEntity(idSaffron, recipeId, secFill, "Saffron",
                9.0, "strand", "8–10 strands", "Filling", false, null, 1.0f, 0),
            IngredientEntity(idMilkF, recipeId, secFill, "Luke Warm Milk",
                1.0, "tablespoon", "1 tbsp", "Filling", false, null, 1.0f, 1),
            IngredientEntity(idGheeF, recipeId, secFill, "Ghee",
                1.0, "teaspoon", "1 tsp", "Filling", false, null, 1.0f, 2),
            IngredientEntity(idCashewF, recipeId, secFill, "Cashew Nuts (roughly chopped)",
                0.25, "cup", "¼ cup", "Filling", false, null, 1.0f, 3),
            IngredientEntity(idAlmondsF, recipeId, secFill, "Almonds (roughly chopped)",
                0.25, "cup", "¼ cup", "Filling", false, null, 1.0f, 4),
            IngredientEntity(idGingerF, recipeId, secFill, "Ginger",
                1.0, "teaspoon", "1 tsp", "Filling", false, null, 1.0f, 5),
            IngredientEntity(idGreenChiF, recipeId, secFill, "Green Chillies (chopped)",
                1.0, "whole", "1 no.", "Filling", false, null, 1.0f, 6),
            IngredientEntity(idKasuriF, recipeId, secFill, "Kasuri Methi",
                null, null, "a pinch", "Filling", false, null, 1.0f, 7),
            IngredientEntity(idGaramF, recipeId, secFill, "Garam Masala",
                null, null, "a pinch", "Filling", false, null, 1.0f, 8),
            IngredientEntity(idSugarF, recipeId, secFill, "Sugar",
                null, null, "a pinch", "Filling", false, null, 1.0f, 9),
            IngredientEntity(idSaltF, recipeId, secFill, "Salt",
                null, null, "to taste", "Filling", false, null, 1.0f, 10),
            IngredientEntity(idWhitePepF, recipeId, secFill, "White Pepper Powder",
                null, null, "a pinch", "Filling", false, null, 1.0f, 11),
            // ── Gravy ──
            IngredientEntity(idOnionsG, recipeId, secGravy, "Onions",
                5.0, "whole", "4–5 medium sized", "Gravy Base", false, null, 1.0f, 0),
            IngredientEntity(idCashewG, recipeId, secGravy, "Cashew Nuts",
                1.0, "cup", "1 cup", "Gravy Base", false, null, 1.0f, 1),
            IngredientEntity(idMelonG, recipeId, secGravy, "Melon Seeds",
                0.5, "cup", "½ cup", "Gravy Base", false, null, 1.0f, 2),
            IngredientEntity(idOilG, recipeId, secGravy, "Oil",
                1.0, "teaspoon", "1 tsp", "Fat", false, null, 1.0f, 3),
            IngredientEntity(idGheeG, recipeId, secGravy, "Ghee",
                1.0, "tablespoon", "1 tbsp", "Fat", false, null, 1.0f, 4),
            IngredientEntity(idCuminG, recipeId, secGravy, "Cumin Seeds",
                1.0, "teaspoon", "1 tsp", "Whole Spices", false, null, 1.0f, 5),
            IngredientEntity(idCardGreen, recipeId, secGravy, "Green Cardamom",
                3.0, "whole", "2–3 pods", "Whole Spices", false, null, 1.0f, 6),
            IngredientEntity(idCardBlack, recipeId, secGravy, "Black Cardamom",
                1.0, "whole", "1 no.", "Whole Spices", false, null, 1.0f, 7),
            IngredientEntity(idCinnamon, recipeId, secGravy, "Cinnamon",
                1.0, "inch", "1 inch stick", "Whole Spices", false, null, 1.0f, 8),
            IngredientEntity(idBayLeaf, recipeId, secGravy, "Bay Leaf",
                3.0, "whole", "2–3 nos.", "Whole Spices", false, null, 1.0f, 9),
            IngredientEntity(idCloves, recipeId, secGravy, "Cloves",
                3.0, "whole", "2–3 nos.", "Whole Spices", false, null, 1.0f, 10),
            IngredientEntity(idPepper, recipeId, secGravy, "Black Peppercorns",
                0.5, "teaspoon", "½ tsp", "Whole Spices", false, null, 1.0f, 11),
            IngredientEntity(idStarAnise, recipeId, secGravy, "Star Anise",
                1.0, "whole", "1 no.", "Whole Spices", false, null, 1.0f, 12),
            IngredientEntity(idMace, recipeId, secGravy, "Mace",
                1.0, "whole", "1 no.", "Whole Spices", false, null, 1.0f, 13),
            IngredientEntity(idGinGarG, recipeId, secGravy, "Ginger Garlic Paste",
                1.0, "tablespoon", "1 tbsp", "Gravy", false, null, 1.0f, 14),
            IngredientEntity(idGreenChiG, recipeId, secGravy, "Green Chillies (slit)",
                2.0, "whole", "2 nos.", "Gravy", false, null, 1.0f, 15),
            IngredientEntity(idElaichiG, recipeId, secGravy, "Cardamom Powder",
                null, null, "a pinch", "Gravy", false, null, 1.0f, 16),
            IngredientEntity(idSugarG, recipeId, secGravy, "Cheeni Sugar",
                1.0, "teaspoon", "1 tsp", "Gravy", false, null, 1.0f, 17),
            IngredientEntity(idWhitePepG, recipeId, secGravy, "White Pepper Powder",
                null, null, "a pinch", "Gravy", false, null, 1.0f, 18),
            IngredientEntity(idSaltG, recipeId, secGravy, "Salt",
                null, null, "to taste", "Gravy", false, null, 1.0f, 19),
            IngredientEntity(idKasuriG, recipeId, secGravy, "Kasuri Methi",
                null, null, "a pinch", "Gravy", false, null, 1.0f, 20),
            IngredientEntity(idGaramG, recipeId, secGravy, "Garam Masala",
                null, null, "a pinch", "Gravy", false, null, 1.0f, 21),
            IngredientEntity(idKewda, recipeId, secGravy, "Kewda Water",
                0.5, "teaspoon", "½ tsp", "Gravy", false, null, 1.0f, 22),
            IngredientEntity(idSugarG2, recipeId, secGravy, "Sugar",
                null, null, "a pinch", "Finishing", false, null, 1.0f, 23),
            IngredientEntity(idCreamG, recipeId, secGravy, "Fresh Cream",
                3.0, "tablespoon", "3 tbsp", "Finishing", false, null, 1.0f, 24),
            IngredientEntity(idButterG, recipeId, secGravy, "Butter",
                1.0, "tablespoon", "1 tbsp", "Finishing", false, null, 1.0f, 25),
            IngredientEntity(idChiliOil, recipeId, secGravy, "Chilli Oil",
                null, null, "for garnish", "Garnish", true, null, 1.0f, 26),
            IngredientEntity(idKasuriGarn, recipeId, secGravy, "Kasuri Methi",
                null, null, "for garnish", "Garnish", true, null, 1.0f, 27)
        )

        val sK1 = "step-mk-k01"; val sK2 = "step-mk-k02"; val sK3 = "step-mk-k03"
        val sK4 = "step-mk-k04"; val sK5 = "step-mk-k05"; val sK6 = "step-mk-k06"
        val sK7 = "step-mk-k07"
        val sGr1 = "step-mk-g01"; val sGr2 = "step-mk-g02"; val sGr3 = "step-mk-g03"
        val sGr4 = "step-mk-g04"; val sGr5 = "step-mk-g05"; val sGr6 = "step-mk-g06"
        val sGr7 = "step-mk-g07"; val sGr8 = "step-mk-g08"; val sGr9 = "step-mk-g09"

        val steps = listOf(
            // ── Kofta Balls ──
            StepEntity(sK1, recipeId, secKofta,
                "Start by first wrapping a paper towel around the paneer to absorb excess moisture, further grate the paneer and mawa and cream them well, use the base of your palm to cream them.", 0),
            StepEntity(sK2, recipeId, secKofta,
                "Add salt, cardamom powder & maida, make sure to add maida in batches and mix well until a dough-like consistency is formed, keep mixing until the mixture starts to leave the thal.", 1),
            StepEntity(sK3, recipeId, secKofta,
                "Add baking powder & mix well, cover the mixture and make the filling by the time.", 2),
            // ── Kofta Filling ──
            StepEntity(sK4, recipeId, secFill,
                "For the filling, mix saffron strands in warm milk, allow the saffron to bloom. Set a pan on medium heat, briefly roast the nuts, remove them and add to a mixing bowl.", 3),
            StepEntity(sK5, recipeId, secFill,
                "Add ginger, green chillies, kasuri methi, garam masala, sugar, salt & white pepper powder to the nuts, along with 3–4 tbsp of mawa & paneer mixture. Mix well — the nut filling is ready.", 4),
            // ── Shaping & Frying ──
            StepEntity(sK6, recipeId, secKofta,
                "Take a spoonful of paneer & mawa mixture, shape into lemon-sized balls, flatten and shape like a bowl, add sufficient filling, bring the ends together to seal, and shape into balls.", 5),
            StepEntity(sK7, recipeId, secKofta,
                "Set oil in wok on medium heat. Drop the shaped kofta balls in hot oil and fry on low heat until golden brown, stirring gently for even colour. Remove onto a sieve or paper towel.", 6),
            // ── Gravy ──
            StepEntity(sGr1, recipeId, secGravy,
                "Cut onions in petals and add them in a stock pot along with cashew nuts and melon seeds. Cover with enough water, bring to a boil, stir and remove scum. Boil for at least 7–8 minutes.", 7),
            StepEntity(sGr2, recipeId, secGravy,
                "After boiling, remove and cool to room temperature. Transfer to a grinding jar and make fine puree. Keep aside.", 8),
            StepEntity(sGr3, recipeId, secGravy,
                "Set a wok or pan on medium heat, add oil & ghee and all the whole spices, stir & cook on medium flame for 1–2 minutes.", 9),
            StepEntity(sGr4, recipeId, secGravy,
                "Add ginger garlic paste, stir & cook on medium flame for 1–2 minutes.", 10),
            StepEntity(sGr5, recipeId, secGravy,
                "Add the prepared onion-cashew-melon puree, stir & cook on medium flame for 2–3 minutes.", 11),
            StepEntity(sGr6, recipeId, secGravy,
                "Add green chillies, elaichi powder, sugar, white pepper & salt. Mix well and add hot water. Cook on medium flame for 10–15 minutes, stirring in intervals. Check consistency — a dipped spatula should coat well.", 12),
            StepEntity(sGr7, recipeId, secGravy,
                "Add kasuri methi, garam masala and kewda water, stir & cook for 1–2 minutes.", 13),
            StepEntity(sGr8, recipeId, secGravy,
                "Strain the gravy through a sieve using a spatula. Transfer back to the wok, adjust seasoning and consistency with hot water as needed.", 14),
            StepEntity(sGr9, recipeId, secGravy,
                "Add sugar, fresh cream & butter, stir well — the silky smooth malai kofta gravy is ready! Serve by placing hot fried koftas on a plate and pouring hot gravy on top. Garnish with kasuri methi & chilli oil. Or add koftas directly to the gravy and cook gently for 1–2 minutes. Serve hot with roti, naan or any Indian bread.", 15)
        )

        val refs = listOf(
            // Kofta steps
            StepIngredientRefEntity(sK1, idPaneer, "300 gm"),
            StepIngredientRefEntity(sK1, idMawa, "100 gm"),
            StepIngredientRefEntity(sK2, idSaltK, "to taste"),
            StepIngredientRefEntity(sK2, idElaichiK, "a pinch"),
            StepIngredientRefEntity(sK2, idMaida, "3 tbsp"),
            StepIngredientRefEntity(sK3, idBakingPow, "¼ tsp"),
            // Filling steps
            StepIngredientRefEntity(sK4, idSaffron, "8–10 strands"),
            StepIngredientRefEntity(sK4, idMilkF, "1 tbsp"),
            StepIngredientRefEntity(sK4, idGheeF, "1 tsp"),
            StepIngredientRefEntity(sK4, idCashewF, "¼ cup"),
            StepIngredientRefEntity(sK4, idAlmondsF, "¼ cup"),
            StepIngredientRefEntity(sK5, idGingerF, "1 tsp"),
            StepIngredientRefEntity(sK5, idGreenChiF, "1 no."),
            StepIngredientRefEntity(sK5, idKasuriF, "a pinch"),
            StepIngredientRefEntity(sK5, idGaramF, "a pinch"),
            StepIngredientRefEntity(sK5, idSugarF, "a pinch"),
            StepIngredientRefEntity(sK5, idSaltF, "to taste"),
            StepIngredientRefEntity(sK5, idWhitePepF, "a pinch"),
            // Frying step
            StepIngredientRefEntity(sK7, idOilFry, "for deep frying"),
            // Gravy steps
            StepIngredientRefEntity(sGr1, idOnionsG, "4–5 medium"),
            StepIngredientRefEntity(sGr1, idCashewG, "1 cup"),
            StepIngredientRefEntity(sGr1, idMelonG, "½ cup"),
            StepIngredientRefEntity(sGr3, idOilG, "1 tsp"),
            StepIngredientRefEntity(sGr3, idGheeG, "1 tbsp"),
            StepIngredientRefEntity(sGr3, idCuminG, "1 tsp"),
            StepIngredientRefEntity(sGr3, idCardGreen, "2–3 pods"),
            StepIngredientRefEntity(sGr3, idCardBlack, "1 no."),
            StepIngredientRefEntity(sGr3, idCinnamon, "1 inch stick"),
            StepIngredientRefEntity(sGr3, idBayLeaf, "2–3 nos."),
            StepIngredientRefEntity(sGr3, idCloves, "2–3 nos."),
            StepIngredientRefEntity(sGr3, idPepper, "½ tsp"),
            StepIngredientRefEntity(sGr3, idStarAnise, "1 no."),
            StepIngredientRefEntity(sGr3, idMace, "1 no."),
            StepIngredientRefEntity(sGr4, idGinGarG, "1 tbsp"),
            StepIngredientRefEntity(sGr6, idGreenChiG, "2 nos."),
            StepIngredientRefEntity(sGr6, idElaichiG, "a pinch"),
            StepIngredientRefEntity(sGr6, idSugarG, "1 tsp"),
            StepIngredientRefEntity(sGr6, idWhitePepG, "a pinch"),
            StepIngredientRefEntity(sGr6, idSaltG, "to taste"),
            StepIngredientRefEntity(sGr7, idKasuriG, "a pinch"),
            StepIngredientRefEntity(sGr7, idGaramG, "a pinch"),
            StepIngredientRefEntity(sGr7, idKewda, "½ tsp"),
            StepIngredientRefEntity(sGr9, idSugarG2, "a pinch"),
            StepIngredientRefEntity(sGr9, idCreamG, "3 tbsp"),
            StepIngredientRefEntity(sGr9, idButterG, "1 tbsp"),
            StepIngredientRefEntity(sGr9, idChiliOil, "for garnish"),
            StepIngredientRefEntity(sGr9, idKasuriGarn, "for garnish")
        )

        repository.insertFullRecipe(recipe, sections, ingredients, steps, refs)
    }
}
