---
name: project-ios-implementation
description: iOS app implementation plan and status for Amrosa — port from Android, no new features
metadata:
  type: project
---

iOS app implementation in progress. Goal: feature-for-feature port of the Android app, then focus on iOS testing. No new features.

**Why:** User wants to ship iOS alongside Android with identical feature set.

**How to apply:** When working on iOS, match Android behavior exactly. Reference DatabaseSeeder.kt for exact recipe data. Reference AuthRepository.kt for auth flow. Seeding is DISABLED (seeded_v11 — fresh installs start empty, recipes restore via cloud sync on login).

**Tech stack decided:**
- iOS 17+ / Swift 6 / SwiftUI / SwiftData
- Swift Package Manager (not CocoaPods)
- Firebase iOS SDK 11.x (FirebaseAuth, FirebaseFirestore, FirebaseStorage)
- GoogleSignIn-iOS 8.x
- Kingfisher 8.x (image loading)
- xcodegen for project generation

**Bundle ID:** com.aerion.amrosa
**Firebase project:** amrosa-2ec82 (user needs to add iOS app and download GoogleService-Info.plist)

**Status:** Implementation started [[feedback-ios-no-new-features]]
