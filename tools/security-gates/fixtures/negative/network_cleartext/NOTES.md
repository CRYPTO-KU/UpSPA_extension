# Fixture: network_cleartext

This fixture deliberately contains two violations from the same network
check family: it requests `INTERNET` even though the current `mobile-dev`
walking skeleton intentionally has no network capability, and it permits
unencrypted traffic app-wide. Both findings belong to the same gate, while
the rest of the manifest is kept clean so no unrelated gate fires.
