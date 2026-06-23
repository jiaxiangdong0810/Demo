# Project Instructions

- Do not run Gradle build commands in this project, including `./gradlew`, `gradle`, `assembleDebug`, or test tasks that invoke Gradle.
- When validation is needed, use source-level checks only: inspect changed files, search references, check syntax-looking issues manually, and use lightweight non-Gradle checks such as `git diff --check`.
- The reason is that Gradle repeatedly hits user-directory cache permission errors in this environment.
