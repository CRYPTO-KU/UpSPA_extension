# Fixture: exported_components

`SecretSettingsActivity` is `exported="true"` with no `android:permission`
and no launcher intent-filter; reachable by any other app installed on
the device with no authorization check. `MainActivity` is a correctly
configured launcher entry point (the one legitimate exported-with-no-
permission case), included to prove the gate doesn't also flag it.
