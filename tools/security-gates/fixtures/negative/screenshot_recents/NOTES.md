# Fixture: screenshot_recents

`CredentialUnlockActivity` is a real `ComponentActivity` subclass (so the
gate recognizes it as an Activity) whose name matches the credential-naming
trigger ("Unlock"), and the window is never marked as screenshot or
recents-protected anywhere in the file; its content could be captured
in a screenshot or the recents-switcher thumbnail.
