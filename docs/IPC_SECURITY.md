# EndXR input IPC security

## Protected endpoint

The native EndXR runtime reads the latest controller, head-orientation and estimated hand-pose snapshot from:

`content://com.noyclic.questphonexr.endxrinput/controller_state`

The provider is exported because Android loads the OpenXR runtime into a client application's process. Exporting it does **not** mean every installed application should be able to read the snapshot.

The app declares the signature permission `com.noyclic.questphonexr.permission.READ_INPUT_SNAPSHOT`. The provider sets it as `android:readPermission`. Android grants this permission only to packages signed with the same signing certificate as QuestPhoneXR. The provider does not allow temporary URI grants, and its query remains read-only. Writes are rejected by the provider implementation.

## Current supported client

The separate `runtime-client-probe` APK is built in the same GitHub Actions run as the main debug APK. Both debug APKs therefore use the same Android debug signing certificate, and the probe can request the signature permission.

**Install the main APK and probe APK from the same workflow run.** Do not mix debug artifacts from different CI runs. For a future release build, the app and its companion probe must be signed with the same release certificate.

The probe reports `provider_permission=GRANTED` when Android granted the signature permission and `provider_query=PASS` when it can read and validate the expected snapshot columns.

A successful query verifies only the IPC boundary and snapshot schema. It does not prove that input is continuously being produced while another application is in the foreground.

## Third-party game access is intentionally not enabled yet

This signature policy currently authorizes only trusted packages signed with the app's certificate, such as the paired diagnostic client. An arbitrary third-party game APK will not have this permission.

Before enabling third-party games, implement an explicit, user-controlled, package-scoped authorization flow. It must identify the exact installed package selected by the user, show that package in a clear authorization prompt, grant access only to the selected package and only for the required input URI, revoke access when the user disables the integration or removes the game, and avoid granting access to all installed apps or exposing a general-purpose IPC method.

Until that flow exists, the provider must remain closed to unrelated packages. Do not weaken the permission to `normal` or remove `readPermission` to make a game query succeed.

## Validation

The Android CI instrumentation test launches the probe as a separate package. It checks that the probe reports the expected signature permission and can read the snapshot. The test must be run using the two APKs from the same workflow run.

Physical-device testing is still required to validate the complete OpenXR runtime selection and external-game lifecycle on the target phone.
