# 🤝 Contributing to Flowboard

Thank you for your interest in contributing to Flowboard! We welcome community contributions, bug fixes, performance optimizations, and documentation improvements.

---

## 🛠️ Development Setup

### Prerequisites
* **Android Studio:** Ladybug (2024.2+) or newer.
* **JDK:** OpenJDK 17 or JDK 21.
* **Android SDK:** API Level 35 (Android 15) with Build Tools 35.0.0+.
* **Minimum Device API:** Android 7.0+ (API 24).

### Clone and Build
```bash
# Clone repository
git clone https://github.com/LaZZy-Bear/flowboard.git
cd flowboard

# Run all unit tests
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

---

## 🧪 Testing Guidelines

Flowboard maintains strict stability guarantees:
* **All existing unit tests must pass.** Run `./gradlew testDebugUnitTest` before submitting any PR.
* **Add unit tests for new features/bug fixes** under `app/src/test/java/com/flowboard/ime/testing/`.
* **Zero crash policy:** IME services run in the system UI process; unhandled exceptions can crash system keyboards. Ensure all public APIs and async loaders include appropriate error handling.

---

## 🌿 Git Branch & Commit Conventions

### Branch Naming
* `feature/<feature-name>` (e.g., `feature/word-trigram-support`)
* `fix/<bug-description>` (e.g., `fix/floating-window-overlap`)
* `docs/<topic>` (e.g., `docs/architecture-update`)
* `refactor/<module>` (e.g., `refactor/scoring-engine`)

### Commit Message Format
Use [Conventional Commits](https://www.conventionalcommits.org/):
```text
feat(engine): add adaptive word trigram weighting
fix(ui): prevent keyboard overlap in landscape mode
docs(readme): add architectural diagram for scoring pipeline
test(tuning): add test cases for easy text parser
```

---

## 🚀 Pull Request Checklist

Before submitting a Pull Request:
1. [ ] Code follows official Kotlin Coding Conventions.
2. [ ] All unit tests pass locally (`./gradlew testDebugUnitTest`).
3. [ ] Code builds without errors (`./gradlew assembleDebug`).
4. [ ] No unnecessary or sensitive files (`local.properties`, `.idea/`, `.gradle/`) are included.
5. [ ] Relevant documentation or `walkthrough.md` is updated.

---

## 💬 Community & Code of Conduct
Please review and adhere to our [Code of Conduct](CODE_OF_CONDUCT.md) in all project interactions.
