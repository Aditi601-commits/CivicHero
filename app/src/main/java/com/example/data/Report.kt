package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reports")
data class Report(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val hazardCategory: String, // Extracted by AI (e.g. Pothole, Water Leakage, Damaged Streetlight, Waste Management, Public Infrastructure)
    val severity: String,       // Extracted by AI (Low, Medium, High)
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val status: String = "Pending", // Pending, Verified, Work In Progress, Resolved
    val latitude: Double,
    val longitude: Double,
    val locationName: String,     // E.g. "5th Avenue, Sector 3"
    val imageUrl: String? = null, // Path or encoded base64 bitmap
    val additionalImages: String? = null, // Pipe-separated additional images
    val videoUris: String? = null,        // Pipe-separated video uris/paths
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val points: Int = 150,
    val level: Int = 1,
    val reportsSubmitted: Int = 3,
    val verificationsDone: Int = 12,
    val currentStreak: Int = 4
)

data class LeaderboardUser(
    val rank: Int,
    val name: String,
    val points: Int,
    val level: Int,
    val isCurrentUser: Boolean = false
)
