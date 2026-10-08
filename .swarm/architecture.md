# Architecture

**Platform:** android
**Stack:** Kotlin + Jetpack Compose + Android SDK

Swarm AI Android Application built with Kotlin, Jetpack Compose, Material 3, Room, and Coroutines supporting agent visualization, model management, interactive swarm building, and local inference.

## Files
- `build.gradle` — Project-level Gradle configuration
- `settings.gradle` — Project settings and module declarations
- `app/build.gradle` — App-level Gradle configuration for Compose, Room, and dependencies
- `app/src/main/AndroidManifest.xml` — App manifest with internet and storage permissions
- `app/src/main/java/com/swarm/ai/MainActivity.kt` — Main entry point hosting the Jetpack Compose navigation host
- `app/src/main/java/com/swarm/ai/ui/theme/Theme.kt` — Material 3 theme configuration for Swarm AI
- `app/src/main/java/com/swarm/ai/data/local/SwarmDatabase.kt` — Room database instance for local data persistence
- `app/src/main/java/com/swarm/ai/data/repository/SwarmRepository.kt` — Repository managing agents, models, and settings data flows
- `app/src/main/java/com/swarm/ai/ui/screens/OnboardingScreen.kt` — Onboarding introduction and setup screen
- `app/src/main/java/com/swarm/ai/ui/screens/HomeScreen.kt` — Main dashboard and swarm status overview
- `app/src/main/java/com/swarm/ai/ui/screens/ModelsScreen.kt` — AI model management and inference configuration screen
- `app/src/main/java/com/swarm/ai/ui/screens/AgentsScreen.kt` — Agent visualization and interactive list screen
- `app/src/main/java/com/swarm/ai/ui/screens/BuildScreen.kt` — Interactive AI swarm building tools and task execution screen
- `app/src/main/java/com/swarm/ai/ui/screens/SettingsScreen.kt` — Settings and provider configuration screen

## Conventions
- Kotlin coding conventions
- Jetpack Compose declarative UI
- Material 3 design guidelines
- MVVM architecture with Repository pattern
- Kotlin Coroutines and Flow for reactive state

## Commands
```json
{
  "install": "./gradlew installDebug",
  "build": "./gradlew build",
  "test": "./gradlew test"
}
```
