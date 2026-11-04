# Game library and compatibility records

## The three things the library keeps separate

1. **Project availability** — a project page, source tree, Quest APK or Android port exists.
2. **Adaptation path** — an Android build exists, source must be ported, or a Quest build needs phone-specific changes.
3. **Device test result** — the game was actually launched and played on a particular phone model and Android version.

Only the third item affects the device-specific compatibility badge. Project availability must never be presented as proof that an APK works on a phone.

## Initial catalog

- **Baldi's Basics Classic VR** — public project for a Classic VR port with Quest and PCVR support. A Quest APK is not automatically a phone build. Source: https://github.com/hanabira-mitsu/bbvr
- **Open Saber** — a Quest Open Saber project exists, and a separate Android/SBS mobile port has also been listed. These are distinct builds and must be tested separately. Quest project: https://github.com/mstritt/OpenSaberVR-Quest ; Android port listing: https://sasukidasprime.itch.io/open-saber-port
- **Granny VR** — the project page describes Android and Quest versions, but device compatibility is unverified. Source: https://gogozooom.itch.io/granny-vr
- **QuestCraft** — an open-source standalone Minecraft Java VR project for compatible VR headsets. Phone support would require a substantial port/adaptation and is considered high-load until measured. Source: https://github.com/QuestCraftPlusPlus/QuestCraft

## First phone-game launch: Open Saber Mobile

The first practical launch target is the separately published Android Mobile Port, not the Quest-only APK. Its publisher lists Android, touch controls and VR/SBS support; these are publisher claims and still require a physical-device test. QuestPhoneXR's library has a direct launcher shortcut that searches installed launcher apps for the Open Saber name and opens the Android port. It does not inject hand tracking into the game or convert the Quest APK.

Install the Android/SBS build from https://sasukidasprime.itch.io/open-saber-port, then use **Запустить Open Saber Mobile · Android/SBS** in the game library. If it is not installed, the shortcut opens the port page. Record launch, stereo mode, controls, FPS and heat separately.

## Test status rules

- Not tested on this phone
- Launches and playable on this phone
- Launches with limitations on this phone
- Failed to launch or crashed on this phone

The report is scoped to manufacturer + model; Android version is shown separately. This prevents a test on another phone from being shown as a positive result for the current phone.

## Manual test report fields

- Device manufacturer and model
- Android release and API level
- QuestPhoneXR app version
- Timestamp
- Outcome
- Average FPS and minimum FPS when measured
- Notes about controls, crashes, heat and visual issues

Reports created in the current prototype are stored in the app's local SharedPreferences. They are not submitted to a server and are not visible on another user's device. The community database, moderation, export/import and automatic performance sampling have not been implemented.

## Game adapter boundary

The generic input contract preserves tracked hands, sticks, trigger/grip values and raw Android key codes. A game-specific provider must explicitly register the game IDs it supports. This is an SDK-side boundary for a port, source integration or supported plugin; it is not a global input injector.

A Quest-only APK may depend on Meta-specific services, Quest runtime extensions, controller profiles, native libraries or performance assumptions. Such games need a real Android port or a compatible runtime implementation; changing the APK name or manifest is not enough.
