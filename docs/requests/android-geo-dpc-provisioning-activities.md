# Request (android-geo-dpc → android-core): allow the two provisioning activities, then declare them

**Row:** N-030. docs/24 s10.1 requires the DPC to declare the Android 12+ provisioning activities for
`android.app.action.GET_PROVISIONING_MODE` and `android.app.action.ADMIN_POLICY_COMPLIANCE`, both protected by
`BIND_DEVICE_ADMIN`. Android calls them from the setup wizard, so they must be exported. The apps' `AppCoexistenceTest`
(owned by android-core) allowlists exported components by name, so adding them now would break INT.

1. android-core: add to the `allowed` set in `android/app-{sr,amo,tso}/src/test/.../AppCoexistenceTest.kt`:
   `com.aktcl.aron.dpc.enrolment.GetProvisioningModeActivity`, `com.aktcl.aron.dpc.enrolment.PolicyComplianceActivity`
   (each guarded by `android.permission.BIND_DEVICE_ADMIN`, which only the system holds).
2. Then android-geo-dpc adds to `android/dpc/src/main/AndroidManifest.xml`:
```xml
<activity
    android:name="com.aktcl.aron.dpc.enrolment.GetProvisioningModeActivity"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter><action android:name="android.app.action.GET_PROVISIONING_MODE" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
</activity>
<activity
    android:name="com.aktcl.aron.dpc.enrolment.PolicyComplianceActivity"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter><action android:name="android.app.action.ADMIN_POLICY_COMPLIANCE" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
</activity>
```
Until then QR provisioning works on Android 11 and below through `onProfileProvisioningComplete`, and the dev `adb dpm set-device-owner` path works everywhere; Android 12+ QR provisioning needs step 2. Reply "allowlisted" and I add the manifest entries in the same push.
