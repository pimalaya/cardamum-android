# Cardamum for Android: monetization and support

The app is free and fully functional in every channel. Money comes from a support prompt on Google Play, framed as important to the project's survival rather than a casual tip, with one-time pay-what-you-want tiers, plus real services sold outside the app. There is no subscription and no paywall: the app never stops working, but the ask is earnest. This model supersedes the subscription gate described in subscription-entitlement.md.

## Why this shape

Cardamum is a vCard database and sync console: it syncs into the OS Contacts app rather than replacing it, so it is opened rarely, not many times a day. The audience is the FOSS and privacy crowd, for whom a free client already exists one tap away (DavX5), and for whom recurring rent on a local tool with zero per-user server cost reads as renting nothing. So the client stays free everywhere, and the paid layer never withholds functionality: it buys recognition, a shared cause, and a personal thank-you. Nothing here gates a feature, which keeps the app FOSS rather than crippleware and means self-builders lose nothing.

## Distribution: what is free

- The core app is free and complete in every channel: GitHub CI APKs, self-build, F-Droid, and Google Play. No feature is gated anywhere.
- Google Play is the only channel that carries the support prompt and the paid tiers. F-Droid cannot ship them by policy, and self-builds do not carry them by design.

## The support prompt

- A dismissable panel, one tap to close, never blocking.
- Shown at most once per rolling 3 days, roughly twice a week (up to about ten times a month). This is deliberately more present than a light tip jar, because the ask is framed as important to the project's continuation, not optional goodwill. The metric is interruptions per month, not per launch; the 3-day cap self-normalizes across light and heavy users.
- Fires only on genuine user-initiated foreground opens, never on background sync or headless runs.
- Silenced permanently only by paying any tier. There is no free opt-out: a per-showing dismiss closes it until the next 3-day window, and only payment stops it for good. A free "no thanks" hatch would be used to skip donating, so there is none.
- The panel stays minimal: the ask plus the one-tap dismiss, plus at most one line of aggregate social proof ("Join N supporters"), and that line is shown only once N is large enough to be flattering. No scrollable backer list lives inside the panel; recognition lives in the About/Supporters screen and on the website.

### The ask is earnest, not a casual tip

The copy frames paying as what keeps the project alive: Cardamum is built and maintained by one person on unpaid time, and continued development and maintenance depend on people paying for it. The register is serious and direct (the Wikipedia tone), not a shrug-optional "buy me a coffee". It stays honest, though: the pitch is the developer's time and the project's continuation, never manufactured server-cost urgency, since the app is free, keeps working, and costs little per user. This audience sees through fake doom, so the seriousness has to be real.

## Tiers

One-time, pay-what-you-want, cumulative. Each tier includes everything below it. All are digital entitlements through Play Billing.

- 5 EUR, Supporter: removes the support prompt permanently, and funds Cardamum's development, maintenance and releases. Payment is per app: this pays Cardamum, not Pimalaya, so it silences only Cardamum's prompt. A discreet Supporter badge shown to you in the app (local, About screen).
- 10 EUR, Backer: your name listed among the app's backers, in the About/Supporters screen and in the Cardamum GitHub repository README. Opt-in, with a pseudonym or anonymous option.
- 25 EUR, Patron: beta access on Play; and a share of this tier is given to NLnet, published transparently each year (framed as "a share of this tier funds NLnet", a Pimalaya donation, not a tax-deductible donation by the buyer, who receives no receipt).
- 50 EUR, Sponsor: your name on pimalaya.org and in the Pimalaya GitHub organization README; and first-class attention on Cardamum's GitHub (a Sponsor's issues are looked at first).
- 100 EUR, Benefactor: first-class attention across all Pimalaya GitHub repositories (widening the Cardamum-only promise to the whole ecosystem); and the option of a 15-minute one-to-one thank-you video call.

### The fast-support and call perks are best-effort gestures, not services

The Sponsor and Benefactor extras are goodwill, explicitly not consulting, a support contract, or any paid service. Fast support is best-effort: the promise is to look at a paying supporter's issues first (Sponsor on Cardamum, Benefactor across all Pimalaya repositories), never a response-time guarantee or an SLA, and "first" naturally dilutes as supporters multiply, which is acceptable at these rarely-hit tiers. The video call is a 15-minute cup of tea over video: on request, subject to availability, capped, fulfilled outside Play, and most Benefactors will not book it. All of these must stay clearly separate from, and cheaper than, paid consulting and paid support, so they never train people to buy expert time for 100 EUR.

### Play listing copy

Customer-facing store descriptions, each under 200 characters, cumulative, in the earnest register. The "option of" and "looked at first" wording keeps the best-effort framing so the copy never over-promises a guarantee.

- Supporter, 5 EUR: Directly funds Cardamum's development, maintenance and releases, all by one person on unpaid time. Silences the support prompt for good and adds a discreet Supporter badge in the app.
- Backer, 10 EUR: Everything in Supporter, plus your name (or alias) listed among Cardamum's backers, in the app and in the project's GitHub repository. Keeps an independent, open-source contacts app going.
- Patron, 25 EUR: Everything in Backer, plus early beta access, and a share of this tier funds NLnet each year. Real backing for open, privacy-first software you fully own.
- Sponsor, 50 EUR: Everything in Patron, plus your name on pimalaya.org and the Pimalaya GitHub, and first-class attention on Cardamum's GitHub: your issues looked at first.
- Benefactor, 100 EUR: Everything in Sponsor, plus first-class attention across all Pimalaya repositories, and the option of a 15-minute one-to-one thank-you video call. The fullest way to back the project.

## Cross-cutting rules

- No core feature is ever gated. Paid tiers buy recognition, a cause, and a thank-you, never functionality.
- Every name-listing is opt-in and allows a pseudonym or anonymous. The whole audience chose a privacy-first contacts app; publishing a real name as a reward is off-brand.
- Social proof is an aggregate count only, and only shown when the number flatters. Small per-tier counts (early "3 people paid this") are negative social proof and stay hidden.
- Public recognition escalates by tier: in-app plus the Cardamum repository README at Backer, then pimalaya.org plus the Pimalaya GitHub organization README at Sponsor. Each named surface is a single file or page, never a name duplicated across every repository.

## Services are separate and external

Real services (consulting, protocol and self-hosting education, priority support) are not sold in the app. The app shows only a "contact me for services" listing; the transaction happens off-Play through Stripe or similar. Person-to-person and real-world services are exempt from Play Billing anyway, so this also keeps the store cut off them. They are priced at market rate, above the Benefactor thank-you call, and are the real income lever; the in-app tiers are goodwill.

## Scope: per app, shared recognition

The same model applies to Himalaya and Calendula, but each app is paid independently: a payment pays that app (it removes that app's prompt and lists the backer for it), not Pimalaya as a whole. Paying in one app does not silence another's prompt, and that is intended, since the donation buys the app, not the project. What is shared at the organization level is the giving (NLnet), the Sponsor and Benefactor recognition (pimalaya.org and the Pimalaya GitHub organization README), and the Benefactor all-Pimalaya fast support. Per-app recognition (the in-app backers list and the Cardamum repository README) stays with the app.

## Supersedes

This replaces the subscription paywall in subscription-entitlement.md, which holds the whole Google Play build behind an active subscription. Under this plan the Play build is free and ungated like the others. The src/google entitlement gate and paywall are removed, or repurposed into the support-prompt frequency store plus one-time-purchase billing.

## Not in this model

One-time tiers make income inflow-dependent (a sawtooth), not recurring. Recurring revenue, if ever wanted, comes from a future hosted service (the Posteo blueprint: a service with real per-user cost and low churn), never from a subscription on the client itself.

## Landed

The support prompt shipped 2026-07-12, replacing the subscription gate. The Billing seam's verb is now prompt (FOSS builds keep binding a no-op); the src/google gate (PaywallActivity, PlayVerifier, the subscription Entitlement cache) is gone, repurposed as planned: SupportStore holds the paid flag plus the last-showing stamp (the rolling 3-day cap; a fresh install seeds the window so the first ask comes 3 days in, not over the onboarding), and SupportActivity is the dismissable panel (spinner first: an already-owned tier recorded from the Play purchases query closes it unseen, so a reinstall or second device never sees the ask; only an unpaid answer reveals it and stamps the window). The panel is framed as the app's price list, not a donation banner (banner-blindness kills the headline-plea-button shape): a statement of fact as the title, two declarative lines, the bold deal line ("Pay once, whatever feels right. This screen never shows again."), then the five tiers as radio options (ids cardamum5, cardamum10, cardamum25, cardamum50, cardamum100, created in the Play Console; the label is the name plus the price, the description always visible below, the cheapest checked by default; live Play names, prices and descriptions when reachable, static fallback strings otherwise), plus the fixed bottom bar: the uppercase LATER dismiss on the left and the accent pay button on the right, its label following the selected tier's amount. The pay button opens the Play purchase sheet; paying any tier records the flag and silences the prompt for good, and the purchase is acknowledged. The prompt fires only on genuine user-initiated foreground opens: a fresh MainActivity start that is neither an OAuth redirect nor a headless adb sync hook, and background syncs never see it. Since 2026-07-14 the drawer also carries a Support row (between Synchronize accounts and About) opening the same panel on demand, cadence-free: the Billing seam grew open (immediate show) and supported (drives the row's visibility, so the FOSS builds hide it and still carry no Google code). Still to come from this document: the social-proof line, the About/Supporters screen (Supporter badge, Backer listing), the Backer and Sponsor name surfaces, Patron beta access, and the Sponsor/Benefactor fast-support and thank-you-call gestures.

The tier ladder was revised after this shipped, first to 5 / 10 / 50 / 100 and then to a five-tier 5 / 10 / 25 / 50 / 100 EUR (Supporter / Backer / Patron / Sponsor / Benefactor). The five-tier form re-aligns the shipped price-based ids: cardamum5, cardamum10, cardamum25 and cardamum50 all match their prices again, so only a new 100 EUR product is needed (cardamum100, or better a tier-named id). Play product ids cannot be renamed or reused once created; tier-named ids would have avoided this churn, so any new products should be tier-named.
