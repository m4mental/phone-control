package com.example.phonecontrol

enum class AppCategory(val title: String, val badge: String, val colorHex: String) {
    MAIN("Main Profile", "MAIN", "#00BFA5"),
    DUAL("Dual / Clone", "DUAL", "#FF9800"),
    PRIVATE("Private Space", "PRIVATE", "#E91E63");

    companion object {
        fun fromUserId(userId: Int): AppCategory {
            return when {
                userId == 0 -> MAIN
                userId == 999 || userId == 95 -> DUAL
                else -> PRIVATE
            }
        }
    }
}
