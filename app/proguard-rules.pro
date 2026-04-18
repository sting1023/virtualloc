# VirtuaLoc ProGuard Rules
# Keep osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Keep Compose
-keep class androidx.compose.** { *; }
