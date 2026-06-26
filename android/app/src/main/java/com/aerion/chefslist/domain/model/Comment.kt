package com.aerion.chefslist.domain.model

data class Comment(
    val id: String,
    val recipeId: String,
    val authorId: String,
    val authorDisplayName: String,
    val content: String,
    val createdAt: Long
)
