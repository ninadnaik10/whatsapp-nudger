# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**WhatsApp Nudger**: an Android app (Kotlin, Jetpack Compose, Material 3) that reads incoming WhatsApp notifications through a `NotificationListenerService`. Gemma 4 E2B runs **on-device** via LiteRT-LM and decides, per message, `notify | digest | mute` plus a `message_type` (`personal, urgent, event, payment, business_update, promotion, greeting, forward, spam, scam, unknown`). The labels mirror the sibling HackerRank Orchestrate "Message Notification Router" spec (`../hackerrank-orchestrate-august26`). That repo is only a reference; this app is a separate project.

The package/applicationId is `com.hackday.notifrouter`. The display name was changed but the ID was kept on purpose. Changing it makes Android treat the app as a new one: the 2.6 GB model on the device, the database and the permission grants would all be lost.

## Toolchain and commands

No Android Studio is required. The toolchain lives in the home directory:
- JDK 21: `~/.local/jdks/jdk-21`. The system only has a JRE, with no `javac`.
- Android SDK: `~/Android/Sdk` (platforms 36 and 37.0, build-tools 36, platform-tools)
- The user's shell sets a stale `ANDROID_SDK_ROOT`; unset it, or AGP warns or misresolves the SDK.

```bash
export JAVA_HOME=~/.local/jdks/jdk-21 ANDROID_HOME=~/Android/Sdk; unset ANDROID_SDK_ROOT
./gradlew assembleDebug                      # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                  # JVM unit tests
./gradlew testDebugUnitTest --tests 'com.hackday.notifrouter.RouterLogicTest.parser rejects unknown action'
./gradlew lintDebug                          # lint errors fail the build

./run-on-phone.sh        # build + install + push models/gemma-4-E2B-it.litertlm + grant permissions (incl. listener) + launch
./run-on-phone.sh logs   # logcat for tags RouterListener, Router, GemmaEngine, NotificationParser
```

Iterating on a connected phone (keeps the app's data, model and settings):
```bash
A=~/Android/Sdk/platform-tools/adb; P=com.hackday.notifrouter
$A install -r app/build/outputs/apk/debug/app-debug.apk
$A shell cmd notification allow_listener $P/$P.service.RouterListenerService   # rebind listener after reinstall
```

- **On-device benchmark (debug builds only):** `BenchReceiver` in `app/src/debug`.
  ```bash
  adb shell am broadcast -a com.hackday.notifrouter.BENCH -p com.hackday.notifrouter --ez vision true --ez mtp true --ei runs 6
  adb logcat -s Bench
  ```
  - Add `--es image /sdcard/Android/data/com.hackday.notifrouter/files/<file>.jpg` to run an image with/without comparison.
  - Use the benchmark to measure any prompt or engine change on real hardware before keeping it.
- **Inspecting the DB:** `adb exec-out run-as com.hackday.notifrouter cat databases/notif-router.db` (also pull the `-wal` and `-shm` files) → open with sqlite3.
- **No adb taps on the dev phone:** it's a Xiaomi, which blocks `adb shell input` (INJECT_EVENTS). Use the bench broadcast, the DB and logcat instead.

## Versions (pinned in `gradle/libs.versions.toml`)

- **Build:** AGP 9.4 with **built-in Kotlin**. Don't apply `org.jetbrains.kotlin.android`; it's declared `apply false` in the root only to pin the KGP version. Gradle 9.8.1, KSP 2.3.x (Room).
- **SDK levels:** compileSdk/targetSdk 37 (current AndroidX libs require 37), minSdk 31.
- **Inference:** LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.18.0`. Check API questions against the AAR's classes (`javap`), not the docs: the docs lag the published API (e.g. `Message` has no `.text`; use `toString()`).
- **Icons:** Material 3 no longer pulls in material-icons, so icons are vector drawables in `res/drawable/ic_*.xml`.

## Architecture: message pipeline

`RouterListenerService` is the entry point and owns a **single serial queue**. Phones can afford one inference at a time, and ordering matters for dedup.

1. **`onNotificationPosted`** records `latest[sbn.key]`, kicks off `NotificationParser.prefetchImages` on IO **immediately**, then enqueues.
   - The prefetch is essential: Android revokes our read grant on a notification's image URIs as soon as the notification is cancelled, which strict mode does.
2. **`NotificationParser.parse`** turns one `StatusBarNotification` into N `ParsedNotification`s, one per MessagingStyle message.
   - WhatsApp always uses MessagingStyle. Its non-MessagingStyle notifications ("New messages from N chats", backups) are skipped, and in strict mode they're cleared via `isMessageSummary`.
   - A null `Person` means the device owner's own message (inline reply); skip it.
   - Media is detected from placeholder text: `📷`, `🎤`, `💟 Sent a sticker`, …
3. **`awaitMedia`**: new photo/sticker messages without image bytes are held up to 3 s, polling `latest[sbn.key]`. WhatsApp attaches the picture only after the download finishes.
4. **`Router.process`** dedups via `findNearDuplicate`: same package, chat, sender and text, `messageTime` within **2 s**.
   - WhatsApp double-posts messages under two keys about 0.5 s apart, and re-posts all unread messages on every update.
   - A wider window would swallow repeated stickers or "ok"s.
   - Duplicates return the stored decision (`fresh=false`). If a duplicate now carries an image that the original decision lacked, `redecideWithImage` re-runs Gemma and sets `previousAction`.
5. **`Router.decide`**: `RuleEngine.signals` → `RuleEngine.hardRule` (confident scam/OTP cases skip the LLM) → `llmDecision` (Gemma, with the last 6 history rows from the same chat/sender plus reaction/correction counts) → `RuleEngine.fallback` → quiet-hours downgrade.
   - `Router.preview` runs the same pipeline without persisting. The Try screen uses it, so test messages never reach the feed or history.
6. **`act()`** in the service:
   - **notify:** leave WhatsApp's notification alone. In strict mode, cancel it and re-post via `Poster` on our high-importance channel, tagged `msg:<id>`, with BigPicture when an image exists. Updates stay silent (`setOnlyAlertOnce`).
   - **digest:** cancel and update the silent digest summary.
   - **mute:** cancel.
   - When a late image changes a decision, `act()` withdraws or posts our alert accordingly.
7. **`onNotificationRemoved`** records the user's reaction: `opened` (click), `dismissed`, or `read_in_app` (WhatsApp app-cancel). Our own cancels are filtered out via `ownCancels`. Reactions and UI corrections ("Should be …") feed later prompts as personalization.

`NotifRouterApp` holds the singletons (`db`, `settings`, `parser`, `router`, `poster`); access them via `context.app`. `AppSettings` is SharedPreferences: `enabled`, `strict`, `quietStart`, `quietEnd`.

## Gemma / LiteRT-LM constraints (measured on device; don't regress)

- **Output format is one line**: `action|message_type|confidence|evidence_ids|reason`.
  - Three few-shot turns (`PromptBuilder.FEW_SHOT`, sent as `initialMessages`) pin the format.
  - Generation stops as soon as a complete line appears (`stopAfterLine` + `cancelProcess()`).
  - `DecisionParser` splits with `limit = 5` and falls back to JSON.
  - Result: about 1.1 s per message.
- **Don't use `ResponseFormat.json` (constrained decoding).** It costs about 5 s of grammar setup per conversation, and without `additionalProperties:false` the model pads extra keys. Measured median: 10.8 s vs 1.1 s.
- **One fresh `Conversation` per message**, so the KV cache never accumulates across notifications. The `Engine` is loaded once per process and serialized by a `Mutex`.
- **`ExperimentalFlags.enableSpeculativeDecoding` (MTP)** is process-wide and read at `initialize()`, so it can't be toggled per conversation. Enable it on the GPU backend only.
- **Vision:** set `visionBackend` or images are ignored. Its first-ever GPU compile takes about 10 s; later loads are cached in `cacheDir`. Per-message cost is negligible for text messages and about +1 s when an image is attached.
- **Warm-up:** `ensureLoaded` runs a warm-up generation so the first real notification doesn't pay GPU kernel compilation.
- **Backend fallback order:** GPU+vision → GPU → CPU.
- **Image tracking:** `GenResult.usedImage` is what tells you Gemma actually received an image. It's persisted as `imageStatus` (`sent_to_gemma | missing | denied | read | none`) and logged as `image=seen|missing|denied`.

## Persistence

Room DB `notif-router.db`, single table `messages`. It stores decisions, reactions, corrections, image status and `digestCleared`.

When changing the schema:
- Bump the version **and add a `Migration`** in `AppDatabase.kt`.
- `fallbackToDestructiveMigration` exists, but it would wipe the user's personalization history.
- Existing migrations: 1→2 added `imageStatus`; 2→3 deleted old Try-screen rows (`packageName = 'simulator'`).

## Platform limits to keep in mind

- **The app can't prevent WhatsApp's original alert;** it only reacts after posting. Strict mode works because the user silences WhatsApp's channels. Neither an app nor adb can change another app's channel importance.
- **Voice-note audio is never in the notification.** Images arrive only once downloaded, and stickers can take 10+ s.
- **OTPs may be redacted** from notifications for untrusted listeners on newer Android.

### Listener lifecycle (Xiaomi/HyperOS)

**What goes wrong:**
- Swiping the app away from recents runs "SwipeUpClean", which kills the process (`kill -9`).
- The system then tries to rebind the listener. **Xiaomi's Autostart switch must be ON**, otherwise `AutoStartManagerService` rejects the bind (logcat: `AutStart Unable to bind notification listener service`). The app then stays deaf, even though notification access is still granted.

**What the app does about it:**
- `RouterListenerService.connected` (a StateFlow) tracks whether the listener is actually bound; the Home warning card and Settings use it, not just the permission.
- `ensureBound()` toggles the service component disabled→enabled to force a rebind. It runs from the Reconnect button, and 1.5 s after `MainActivity.onResume`. Never call it at process start: that can cancel an in-flight bind.
- `NotificationListenerService.requestRebind()` does **not** help here; it only reverses `requestUnbind()`.
- On connect, `catchUp()` routes WhatsApp notifications that were posted while the app was unbound.

**Double binding:**
- The system sometimes binds the listener twice (seen after `adb install -r`), so every post is delivered twice.
- `onNotificationPosted` ignores a repeat with the same `sbn.key` and `postTime`.
- `cmd notification disallow_listener` followed by `allow_listener` resets it to a single binding.
