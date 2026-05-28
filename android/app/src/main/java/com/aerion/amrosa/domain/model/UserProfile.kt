package com.aerion.amrosa.domain.model

data class UserProfile(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val createdAt: Long = 0L
)
