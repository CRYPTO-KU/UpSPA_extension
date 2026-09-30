# Fixture: pending_intent

Demonstrates the `pending_intent` gate catching a `PendingIntent` built two
ways wrong at once:

1. `target` is built from a bare implicit `Intent(actionString)`;
   no explicit component, and (deliberately) nothing elsewhere in this file
   that the gate's factory-method heuristic (`.newIntent(...)`) would
   recognize as evidence of an explicit target.
2. `PendingIntent.getBroadcast(context, 0, target, 0)` passes `0` for flags;
   neither the immutable nor the mutable flag is set, which silently defaults
   to mutable on API 31 and below.

This is **not** meant to hit the gate's Autofill-authentication exception
(the one legitimate case where a mutable `PendingIntent` is correct,
because the framework needs to attach extras before delivery),
there is no `FillResponse`/`setAuthentication`/`IntentSender`/`AutofillManager`
anywhere in this file, on purpose.
