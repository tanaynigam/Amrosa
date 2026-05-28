package com.aerion.amrosa.domain.model

/**
 * A notification item stored in Firestore at
 * notifications/{uid}/items/{notifId}.
 *
 * Types:
 *   "follow_request"  — someone sent a follow request
 *   "follow_accepted" — someone accepted your follow request
 *   "recipe_shared"   — someone shared a recipe directly with you
 */
data class SocialNotification(
    val id: String,
    val type: String,
    val fromUid: String,
    val fromDisplayName: String,
    /** For recipe_shared: the document ID in shared_to/{uid}/recipes/{shareId}. */
    val shareId: String? = null,
    /** For recipe_shared: human-readable recipe title. */
    val recipeName: String? = null,
    val createdAt: Long,
    val read: Boolean
)
