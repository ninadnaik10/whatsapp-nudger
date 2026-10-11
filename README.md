# WhatsApp Nudger — on-device WhatsApp notification triage with Gemma 4

An Android app that reads incoming WhatsApp notifications and decides, **entirely on-device** with
Gemma 4 E2B (LiteRT-LM), whether each message should **notify** (interrupt now), go to a
**digest** (show later), or be **muted** (spam/scam/noise). It is the real-device version of the
HackerRank Orchestrate "Message Notification Router" and uses the same labels:

- `action`: `notify | digest | mute`
- `message_type`: `personal | urgent | event | payment | business_update | promotion | greeting | forward | spam | scam | unknown`
- plus `reason`, `confidence`, `evidence_ids` (ids of past messages that influenced the decision)

## How it works

```
WhatsApp notification
  → RouterListenerService (NotificationListenerService)
  → NotificationParser       MessagingStyle → one record per message (sender, group, text, media, image)
  → Router
      1. dedupe in Room (WhatsApp re-posts every unread message)
      2. RuleEngine.signals  links, money/KYC words, OTP, urgency, unknown number, not-in-contacts…
      3. RuleEngine.hardRule confident cases skip the LLM (OTP-sharing scams, KYC+link from strangers, OTP codes)
      4. history retrieval   last 6 messages from same chat/sender + what the user did with them
      5. GemmaEngine         Gemma 4 E2B, JSON-schema-constrained output
      6. RuleEngine.fallback when the model isn't loaded / fails
      7. quiet hours         downgrade notify → digest unless urgent & confident
  → act: notify = leave it (or re-post in strict mode) · digest = cancel + silent summary · mute = cancel
  → onNotificationRemoved records opened / dismissed / read_in_app → feeds future prompts
```

The app also lets you correct any decision ("Should be: notify/digest/mute"). Corrections are the
strongest signal in the prompt and in the fallback.

| Code | What it does |
|---|---|
| `service/RouterListenerService.kt` | Receives notifications, serial processing queue, takes action, records reactions |
| `parse/NotificationParser.kt` | Extracts fields from `StatusBarNotification` |
| `router/RuleEngine.kt` | Signals, hard rules, fallback |
| `router/Router.kt` | Orchestration + persistence |
| `llm/GemmaEngine.kt` | LiteRT-LM engine (GPU+vision → GPU → CPU fallback), one inference at a time |
| `llm/PromptBuilder.kt` | System prompt, JSON schema, per-message prompt |
| `data/AppDatabase.kt` | Room: messages, decisions, reactions, corrections |
| `ui/MainActivity.kt` | Setup checklist, "Try a message" simulator, feed, digest |

## Quick start (no Android Studio)

Needs a JDK 17–21 and the Android SDK command-line tools: platform-tools, platforms;android-37.0, build-tools.
Then, with the phone connected via USB debugging or paired over Wi-Fi:

```bash
./run-on-phone.sh        # build → install → push models/gemma-4-E2B-it.litertlm → grant permissions → launch
./run-on-phone.sh pair   # Wi-Fi pairing (Android 11+ Wireless debugging)
./run-on-phone.sh logs   # follow routing decisions in logcat
```

The script grants notification-listener access through `adb shell cmd notification allow_listener`,
so there's no trip through Settings.

## Setup (Android Studio)

Requirements: Android Studio (recent, with AGP 9.4 support), a phone running Android 12+ with
≥ 6 GB RAM (8 GB+ recommended). The model doesn't run on the emulator in any useful way.

1. Open `kotlin-app/` in Android Studio and let it sync. Or from a terminal with JDK 17–21 and the Android SDK (API 37) installed:
   ```bash
   ./gradlew installDebug
   ```
2. Download the model (≈2.6 GB, you may need to accept the Gemma license on Hugging Face first):
   `gemma-4-E2B-it.litertlm` from https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
3. Push it into the app's files dir. Install the app first, so the directory exists:
   ```bash
   adb shell mkdir -p /sdcard/Android/data/com.hackday.notifrouter/files
   adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.hackday.notifrouter/files/
   ```
4. Open the app → grant **Notification access**, **Post notifications**, and **Contacts**. Contacts is optional, but "not a saved contact" is a strong scam signal.
5. Tap **Load model**. The first load can take ~10 s.
6. Use **Try a message** to test without waiting for real traffic, then send yourself WhatsApp messages from another phone.

### Strict mode (recommended for the demo)

Android only lets the app react *after* WhatsApp's notification has already alerted. To make routing
actually control interruptions, set WhatsApp → Settings → Notifications (or the system channel
settings) to **silent**, then turn on **Strict mode**. Messages routed to `notify` are then re-posted
on the app's own high-priority channel. Tapping one opens the original chat.

## Latency

Measured on a Snapdragon 8s Gen 4 phone (Xiaomi, 11 GB RAM), Gemma 4 E2B on GPU:

| Setup | Median per message |
|---|---|
| JSON-schema constrained output (first version) | 10.8 s (≈5 s grammar setup per message + ~85 tokens) |
| Unconstrained JSON | 3.3 s (and the format drifts) |
| Unconstrained + MTP | 2.6 s |
| **One-line output `action\|type\|conf\|evidence\|reason` + few-shot + early stop + MTP** | **≈1.1 s** |

The engine also runs one warm-up generation at load, so the first real message doesn't pay
for GPU kernel compilation. Re-run the benchmark (debug builds) with:

```bash
adb shell am broadcast -a com.hackday.notifrouter.BENCH -p com.hackday.notifrouter --ez vision true --ez mtp true --ei runs 6
adb logcat -s Bench
```

## Running tests

```bash
./gradlew testDebugUnitTest   # RuleEngine + DecisionParser JVM tests
```

## Known limitations

- **Android only.** iOS doesn't let apps read other apps' notifications.
- **Voice notes**: the notification only says "🎤 Voice message (0:12)". The audio isn't accessible.
- **Images**: only when WhatsApp includes the image in the notification (MessagingStyle data URI
  or `EXTRA_PICTURE`) *and* grants read access. Otherwise we only know "📷 Photo" plus the caption.
- No forward count, business verification, or group-admin info, compared with the hackathon dataset.
  Personal history builds up from scratch on each device.
- Cancelling a WhatsApp notification removes its inline-reply action.
- If WhatsApp re-posts a notification with no new messages, the previous decision isn't re-applied.

## Ideas to extend

- Speculative decoding (MTP) on GPU: `ExperimentalFlags.enableSpeculativeDecoding = true` before `initialize()`.
- Few-shot examples from `hackerrank-orchestrate-august26/dataset/sample_messages.csv` in the system prompt.
- Scheduled digest delivery (WorkManager) with a Gemma-written summary of held messages.
- Support for more apps (Telegram, SMS, Slack) by adding package names to `AppSettings.watchedPackages`.
