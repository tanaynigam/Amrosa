package com.aerion.tablefeed.domain.model

data class UserProfile(
    val uid: String,
    val displayName: String,
    val email: String? = null,
    val photoUrl: String? = null,
    val createdAt: Long = 0L
)
