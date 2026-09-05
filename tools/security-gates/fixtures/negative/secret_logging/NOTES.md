# Fixture: secret_logging

Two intended failures, both in the same check family:

1. A logging call with the recovered signing key in its arguments.
2. A `SharedPreferences` write persisting the master password.

Both route through plain local variables with obviously secret-shaped
names, not through a wrapper function; this fixture proves the gate
catches the direct case. It's deliberately not designed to also prove
the gate catches indirection through a helper function; that limitation
is disclosed in `secret_logging.py`'s own docstring instead,
since retesting a known disclosed gap would not add evidence,
just restate it.
