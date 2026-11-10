# EndXR input IPC security

## Protected endpoint

The native EndXR runtime reads the latest controller, head-orientation and estimated hand-pose snapshot from:

`content://com.noyclic.questphonexr.endxrinput/controller_state`

The provider is exported because Android loads the OpenXR runtime into a client application's process. Exporting it does **not** mean every installed application can read the snapshot.

The provider keeps the signature permission `com.noyclic.questphonexr.permission.READ_INPUT_SNAPSHOT` as its default read permission. The paired `runtime-client-probe` APK is built in the same GitHub Actions run as the main debug APK, so both packages share the Android debug signing certificate.

For the controller snapshot only, the provider declares a scoped `<grant-uri-permission android:path="/controller_state" />` entry while its general `android:grantUriPermissions` flag remains false. Android's explicit URI grant can give one package access to that one URI even when it does not hold the provider's ordinary read permission. The grant does not enable writes or expose other provider paths. See the Android documentation for [grant-uri-permission](https://developer.android.com/guide/topics/manifest/grant-uri-permission-element) and [Context.grantUriPermission](https://developer.android.com/reference/android/content/Context#grantUriPermission(java.lang.String,android.net.Uri,int)).

## Companion diagnostic client

The separate `runtime-client-probe` APK can still use the signature permission directly.

**Install the main APK and probe APK from the same workflow run.** Do not mix debug artifacts from different CI runs. For a future release build, the app and its companion probe must be signed with the same release certificate.

The probe reports `provider_permission=GRANTED` when Android granted the signature permission and `provider_query=PASS` when it can read and validate the expected snapshot columns.

A successful query verifies only the IPC boundary and snapshot schema. It does not prove that input is continuously being produced while another application is in the foreground.

## User-controlled package authorization

The EndXR readiness screen includes **Управление доступом к вводу**. The APK library also offers a managed **Запустить через NextVRera + EndXR** path for installed OpenXR candidates. The picker is built from installed third-party applications that expose a launcher activity; system apps and QuestPhoneXR itself are excluded. The user sees the application label and exact package name, then must approve the grant in a confirmation dialog. The ordinary **Открыть установленное приложение** action does not grant IPC access.

After confirmation, QuestPhoneXR calls `grantUriPermission(packageName, controllerStateUri, FLAG_GRANT_READ_URI_PERMISSION)`. It grants read-only access to exactly `content://com.noyclic.questphonexr.endxrinput/controller_state`. It does not grant write access, wildcard paths, APK storage access or Android-wide input injection. The snapshot contains controller buttons/axes, head orientation and approximate hand poses; values older than the provider's freshness window are neutralized.

The approval record stores the exact package name and a SHA-256 fingerprint derived from the package's current signing certificate. On the next QuestPhoneXR launch, the app re-issues the temporary URI grant only if that package is still installed, still exposes a launcher activity, and has the same signing certificate. A missing package or changed signer causes the old grant to be revoked and its stored approval removed. Because Android URI grants are temporary, the user must open QuestPhoneXR after a device restart before a previously approved game can access the snapshot again.

The same screen lists current approvals and allows the user to revoke one package at a time. The managed launch flow also reconciles a previously approved grant before launching the game. Revocation does not affect the companion diagnostic APK or any other selected app.

This permission is a transport prerequisite, not automatic game compatibility. A third-party game must still use the documented EndXR input URI and expected column schema, or use OpenXR input that the runtime implements. The current runtime does not inject Android gamepad events into another application, and no external commercial game has yet been validated against this input contract.

## Validation

JVM tests cover package-name validation, signer-fingerprint validation and malformed stored approval records. The Android UI smoke test checks that the access-management action appears in the runtime-readiness dialog. The separately signed probe still verifies that signature-permission access works.

Physical-device testing is still required to verify that an explicitly authorized external APK can query the provider continuously while the MediaPipe foreground service is active.
