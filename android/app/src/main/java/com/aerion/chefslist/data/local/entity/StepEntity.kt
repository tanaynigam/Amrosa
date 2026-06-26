package com.aerion.chefslist.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "steps")
data class StepEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val sectionId: String?,
    val instruction: String,
    val orderIndex: Int
)
