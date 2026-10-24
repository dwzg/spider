# Spider Solitaire for Android

A small, dependency-free Spider Solitaire app written in Kotlin.

## Features

- 1, 2 or 4 suits (easy / medium / hard)
- Drag cards, or tap a card to send it to the best spot
- Unlimited undo and cycling hints
- Short animations for moves and deals
- Game is saved automatically when you leave the app
- Win statistics per difficulty

## Rules

- Build runs downward (K → A). Any card can go on a card one rank higher.
- Only runs of the **same suit** can be moved together.
- A full King-to-Ace run of one suit is removed automatically (+100 points).
- Tap the stock (top right) to deal one card onto every column. All columns must be non-empty to deal.
- Each move or deal costs 1 point. Start score is 500.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
./gradlew test assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.
Every push also builds the APK on GitHub Actions — download it from the run's **Artifacts** section.
