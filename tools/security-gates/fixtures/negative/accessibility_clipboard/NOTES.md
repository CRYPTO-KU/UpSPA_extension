# Fixture: accessibility_clipboard

A `<service>` declares the platform's accessibility-binding permission.
UpSPA has no legitimate use for that API, and Google Play now policy-prohibits
this class of automation for an app like this regardless.
`allowBackup` is explicitly false and the one activity is a correctly
configured launcher, so this fixture doesn't also trip `backup_config`
or `exported_components`.
