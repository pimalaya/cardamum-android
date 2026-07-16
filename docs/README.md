# Cardamum for Android: documentation

Reference entries, one file per topic, added over time.

- [design.md](design.md): the core idea, the screens, and the target sync engine (hub and spoke).
- [contacts-mapping.md](contacts-mapping.md): the Android ContactsContract to vCard mapping contract, including the custom property policy every backend follows.
- [google-people.md](google-people.md): the Google People API backend, with the full vCard 4.0 to People field mapping.
- [custom-data.md](custom-data.md): how the Google and Graph backends preserve vCard data they cannot express natively (the stash), and how provider-only data rides the vCard as X-GOOGLE-* / X-MSGRAPH-* properties.
- [merged-view.md](merged-view.md): the contact-first pivot: one merged list over per-account replicas (UID-linked), addressbooks demoted to a filter, and the staged plan towards memberships, cross-account copy and divergence handling.
- [io-offline-migration.md](io-offline-migration.md): the migration of the sync base onto the io-offline replica engine: the mapping, the stages, and what landed where.
- [conflict-resolution-plan.md](conflict-resolution-plan.md): making same-field sync conflicts conservative: detect and persist (not auto-merge), then surface for manual resolution in the existing conflict form, with REV picking per-field defaults. The layer split (io-offline untouched, one small vcard-rs REV comparison, cardamum the bulk) and the staged plan.
- [phone-sync-plan.md](phone-sync-plan.md): making the phone's ContactsContract a second io-offline spoke per addressbook (two-way, the same engine as the server) instead of a one-way projection, the field-space read-back, and the staged plan with what landed.
- [providers.md](providers.md): the living log of real-provider testing (posteo, fastmail, Google, Microsoft): status, quirks, and what each round should verify.
- [addressbook-management.md](addressbook-management.md): why create / rename / delete of an addressbook is not yet supported, what each layer would need, and the intended UI once the backend lands.
- [android-15-migration.md](android-15-migration.md): the completed raise of the compile and target SDK to API level 35 for Play (toolchain bump, edge-to-edge insets, 16 KB native alignment), with the actual versions and how to verify.
- [performance.md](performance.md): living notes on sync throughput and surprising counts (per-contact pushes, full-table scans): things to profile later, not correctness or data-loss issues.
- [refactor-plan.md](refactor-plan.md): the post-review hardening and decomposition plan from the 2026-07-15 cold reviews (sync serialization, structured errors, MainActivity split), with the map of which responsibilities migrate to Rust, which stay Java deliberately, and where bridge chatter batches.
- [hub-decomposition-plan.md](hub-decomposition-plan.md): splitting the oversized hub files along existing seams, one behavior-preserving move at a time (Rust client.rs/project.rs/ffi.rs into per-domain submodules; a MainActivity EditorController and a ContactForm dialog layer), with the leave-list of files that stay whole.
- [jni-boundary.md](jni-boundary.md): the detailed problem analysis behind the JNI typing plan: how the all-JSON-string bridge works today, why the unenforced, stringly-typed, duplicated wire is a runtime-bug and drift liability (with a concrete offlinePushPlan example), and how the three levers address it. The why; the plan is the how.
- [jni-typing-plan.md](jni-typing-plan.md): making the JSON JNI boundary smaller and typed: narrow the surface (fold accountInfo, coarsen the offline plan verbs, hold the field model typed), then type the store.rs/project.rs replies the way offline.rs already is, then optionally generate the Java DTOs.
- [monetization.md](monetization.md): the support and pricing model: free in every channel, an optional once-per-7-days support prompt on Play with one-time pay-what-you-want tiers (Supporter, Backer, Patron, Sponsor), services sold externally, Pimalaya-wide supporter status. Supersedes the subscription gate.
- [subscription-entitlement.md](subscription-entitlement.md): superseded by monetization.md; the former Google-build subscription gate and its offline entitlement-cache model, kept for history and for reuse of the offline-check technique.
