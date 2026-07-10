# Subscription entitlement and the offline gate

The Google Play build holds the whole app behind an active subscription; the FOSS builds (GitHub CI, F-Droid) are ungated and carry none of this. The gate must not fight the offline-first premise: a paying subscriber has to be able to open the app on a plane. This describes the entitlement model.

## Where it lives

- `Billing.enforce(Activity)` is called once at launch from `MainActivity`. The FOSS binding is a no-op; the Google binding (`src/google`) enforces the gate. All billing code, the paywall, and the entitlement cache live in the `google` source set, so no other variant sees Google code.
- The check is client-side. Play purchases are signed by Google, so this is trustworthy on a normal device but not piracy-proof; that is fine, because the same app is freely available from GitHub CI and F-Droid.

## The offline model: cache plus grace window

The app must never block startup on a network call. The entitlement is cached locally and trusted for a grace window:

- Cache (SharedPreferences, `google` only): `entitled` (bool) and `lastVerified` (timestamp).
- On launch, gate on the cache alone: if `entitled` and `now - lastVerified` is within the grace window, the app opens immediately, offline, with no network wait.
- A Play verification runs in the background (non-blocking). When it returns it refreshes the cache: sets `entitled` and `lastVerified = now`.
- The app is blocked (the paywall shown) only when the cache says not-entitled, or the grace window has lapsed without a successful re-verify. A lapsed subscription therefore surfaces at the next launch, which is good enough; there is no need to interrupt a running session.

Grace window: 7 to 14 days of offline use after the last successful verification. A subscriber can go that long with no connectivity; we only need occasional network to renew the lease.

## Notes and constraints

- `queryPurchasesAsync` already reads the Play Store app's local purchase cache, so it often succeeds with no network. The grace-window cache is still needed to keep it off the startup critical path and to give a defined offline lease.
- A `Purchase` from `queryPurchasesAsync` does not expose the exact subscription expiry; that needs the Play Developer API server-side, which this client-only app does not have. So the cache records "last seen active at T", and the grace window is the offline allowance after T, not a precise expiry.
- The Play-managed free trial reports as an active purchase, so the trial is covered by the same check with no extra logic.
- Backing out of the paywall leaves the app (nothing is usable without a subscription), rather than slipping past the gate.

## Status

Implemented (Google build, src/google):

- Entitlement (SharedPreferences billing store): entitled plus last_verified, isValid = entitled and within a 14 day grace window, record(entitled) stamped as of now.
- GoogleBilling.enforce gates on the cache: within the grace window it opens the app and re-verifies against Play in the background (PlayVerifier, headless one-shot query); otherwise it shows the paywall.
- PaywallActivity connects to Play, checks for an active subscription, records every definitive Play answer into the cache (a failed query never marks a subscriber lapsed), lets subscribers through and otherwise offers the subscription.
- Subscriptions.isActive is the shared purchased-state check for the paywall and the background verifier.
- Product id wired to the real base plan cardamum (trial offer cardamum-trial).
