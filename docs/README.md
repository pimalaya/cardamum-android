# Cardamum for Android: documentation

Reference entries, one file per topic, added over time.

- [design.md](design.md): the core idea, the screens, and the target sync engine (hub and spoke).
- [contacts-mapping.md](contacts-mapping.md): the Android ContactsContract to vCard mapping contract, including the custom property policy every backend follows.
- [google-people.md](google-people.md): the Google People API backend, with the full vCard 4.0 to People field mapping.
- [custom-data.md](custom-data.md): how the Google and Graph backends preserve vCard data they cannot express natively (the stash), and how provider-only data rides the vCard as X-GOOGLE-* / X-MSGRAPH-* properties.
- [merged-view.md](merged-view.md): the contact-first pivot: one merged list over per-account replicas (UID-linked), addressbooks demoted to a filter, and the staged plan towards memberships, cross-account copy and divergence handling.
- [io-offline-migration.md](io-offline-migration.md): the migration of the sync base onto the io-offline replica engine: the mapping, the stages, and what landed where.
- [providers.md](providers.md): the living log of real-provider testing (posteo, fastmail, Google, Microsoft): status, quirks, and what each round should verify.
- [addressbook-management.md](addressbook-management.md): why create / rename / delete of an addressbook is not yet supported, what each layer would need, and the intended UI once the backend lands.
- [android-15-migration.md](android-15-migration.md): the plan to raise the target to API level 35 for Play (toolchain bump, edge-to-edge insets, 16 KB native alignment), to execute later.
- [subscription-entitlement.md](subscription-entitlement.md): the Google-build subscription gate and its offline model (client-side check, cached entitlement plus a grace window, next-launch gating).
