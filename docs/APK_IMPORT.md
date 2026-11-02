# APK import and inspection

QuestPhoneXR can select an APK through Android's Storage Access Framework, copy it into
the app-private `files/imported-apks` directory, and keep a local index of imported packages.

The archive inspector reads package name, label, version, min/target Android API, required
hardware features, native library ABIs, and Quest/Meta-related metadata keys. The library
shows conservative manifest checks for a newer minSdk, a native ABI mismatch, missing
required device features, and likely Quest/Meta-specific packaging.

This is inspection only:
- No APK is installed by importing it.
- No imported code is executed.
- A clean manifest report does not prove launchability, graphics compatibility, tracking,
  controls, or performance.
- Quest-only applications still require a compatible runtime or a real port. A normal
  Android activity cannot turn an arbitrary Quest APK into a phone-native VR game.

Imported APKs remain private to QuestPhoneXR. Deleting an entry removes both its metadata
and the copied archive. Importing another version of the same package replaces the prior
local copy.


## Optional Android installation

Imported packages can be handed to Android's system package installer using a temporary
FileProvider content URI. On Android 8 and newer, the user may first need to authorize
QuestPhoneXR as an allowed source for app installs. Android then shows its own confirmation
and handles signature conflicts, package validation, and the actual installation.

This action installs a normal Android-compatible APK into Android. It does not install the
package into a separate QuestPhoneXR runtime, does not emulate a Quest headset, and does not
make Quest-only applications launchable on a phone. Manifest inspection warnings should be
reviewed before proceeding.
