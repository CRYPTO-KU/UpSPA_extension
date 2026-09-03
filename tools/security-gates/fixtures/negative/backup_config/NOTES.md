# Fixture: backup_config

`android:allowBackup` is not set at all on `<application>`, which
behaves identically to an explicit `true`; app data can be extracted
via `adb backup` or cloud auto-backup.
The one activity present is a correctly configured launcher, so
this fixture doesn't also trip `exported_components`.
