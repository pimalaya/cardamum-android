# Cardamum for Android: monetization and support

The app is free and fully functional in every channel. Money comes from an optional, occasional support prompt on Google Play with one-time pay-what-you-want tiers, plus real services sold outside the app. There is no subscription and no paywall. This model supersedes the subscription gate described in subscription-entitlement.md.

## Why this shape

Cardamum is a vCard database and sync console: it syncs into the OS Contacts app rather than replacing it, so it is opened rarely, not many times a day. The audience is the FOSS and privacy crowd, for whom a free client already exists one tap away (DavX5), and for whom recurring rent on a local tool with zero per-user server cost reads as renting nothing. So the client stays free everywhere, and the paid layer never withholds functionality: it buys recognition, a shared cause, and a personal thank-you. Nothing here gates a feature, which keeps the app FOSS rather than crippleware and means self-builders lose nothing.

## Distribution: what is free

- The core app is free and complete in every channel: GitHub CI APKs, self-build, F-Droid, and Google Play. No feature is gated anywhere.
- Google Play is the only channel that carries the support prompt and the paid tiers. F-Droid cannot ship them by policy, and self-builds do not carry them by design.

## The support prompt

- A dismissable panel, one tap to close, never blocking.
- Shown at most once per rolling 7 days. The metric that matters is interruptions per month, not per launch; a 7-day cap self-normalizes across light and heavy users to roughly four per month at most, which is the Wikipedia-ish zone: noticeable, not enraging.
- Fires only on genuine user-initiated foreground opens, never on background sync or headless runs.
- Silenced permanently only by paying any tier. There is no free opt-out: a per-showing dismiss closes it until the next 7-day window, and only payment stops it for good. A free "no thanks" hatch would be used to skip donating, so there is none.
- The panel stays minimal: the ask plus the one-tap dismiss, plus at most one line of aggregate social proof ("Join N supporters"), and that line is shown only once N is large enough to be flattering. No scrollable backer list lives inside the panel; recognition lives in the About/Supporters screen and on the website.

## Tiers

One-time, pay-what-you-want, cumulative. Each tier includes everything below it. All are digital entitlements through Play Billing.

- 5 EUR, Supporter: removes the support prompt permanently. Payment is per app: this pays Cardamum, not Pimalaya, so it silences only Cardamum's prompt. A small supporter mark in the About screen.
- 10 EUR, Backer: name listed among the app's backers, in the About/Supporters screen (for example a foldable list). Opt-in, with a pseudonym or anonymous option.
- 25 EUR, Patron: beta access on Play; a share of this tier is given to NLnet, published transparently each year. It is framed as "a share of this tier funds NLnet" (a Pimalaya donation), not as a tax-deductible donation by the buyer, who receives no receipt.
- 50 EUR, Sponsor: public recognition on two fixed surfaces, the name on pimalaya.org and in the Pimalaya GitHub organization README; and eligibility for a 15-minute one-to-one thank-you video call.

### The thank-you call is a gesture, not a service

The 50 EUR call is a 15-minute one-to-one video meeting, a cup of tea or coffee over video: a thank-you, explicitly not consulting, support, or any service. It is an eligibility ("Sponsors can request it"), not a guaranteed deliverable: offered on request, subject to availability, capped, and fulfilled outside Play. Most Sponsors will not book it, and the offer itself is the reward. It must stay clearly separate from, and cheaper than, paid consulting, so it never trains people to buy expert time for 50 EUR.

## Cross-cutting rules

- No core feature is ever gated. Paid tiers buy recognition, a cause, and a thank-you, never functionality.
- Every name-listing is opt-in and allows a pseudonym or anonymous. The whole audience chose a privacy-first contacts app; publishing a real name as a reward is off-brand.
- Social proof is an aggregate count only, and only shown when the number flatters. Small per-tier counts (early "3 people paid this") are negative social proof and stay hidden.
- Public recognition is on two fixed surfaces only, pimalaya.org and the Pimalaya GitHub organization README; project READMEs carry no supporter list.

## Services are separate and external

Real services (consulting, protocol and self-hosting education, priority support) are not sold in the app. The app shows only a "contact me for services" listing; the transaction happens off-Play through Stripe or similar. Person-to-person and real-world services are exempt from Play Billing anyway, so this also keeps the store cut off them. They are priced at market rate, above the Sponsor thank-you chat, and are the real income lever; the in-app tiers are goodwill.

## Scope: per app, shared recognition

The same model applies to Himalaya and Calendula, but each app is paid independently: a payment pays that app (it removes that app's prompt and lists the backer for it), not Pimalaya as a whole. Paying in one app does not silence another's prompt, and that is intended, since the donation buys the app, not the project. What is shared at the organization level is only the giving (NLnet) and the Sponsor recognition surfaces (pimalaya.org and the Pimalaya GitHub organization README).

## Supersedes

This replaces the subscription paywall in subscription-entitlement.md, which holds the whole Google Play build behind an active subscription. Under this plan the Play build is free and ungated like the others. The src/google entitlement gate and paywall are removed, or repurposed into the support-prompt frequency store plus one-time-purchase billing.

## Not in this model

One-time tiers make income inflow-dependent (a sawtooth), not recurring. Recurring revenue, if ever wanted, comes from a future hosted service (the Posteo blueprint: a service with real per-user cost and low churn), never from a subscription on the client itself.
