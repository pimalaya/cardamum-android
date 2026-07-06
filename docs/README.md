# Cardamum for Android: documentation

Reference entries, one file per topic, added over time.

- [design.md](design.md): the core idea, the screens, and the target sync engine (hub and spoke).
- [contacts-mapping.md](contacts-mapping.md): the Android ContactsContract to vCard mapping contract, including the custom property policy every backend follows.
- [google-people.md](google-people.md): the Google People API backend, with the full vCard 4.0 to People field mapping.
- [custom-data.md](custom-data.md): how the Google and Graph backends preserve vCard data they cannot express natively (the stash), and how provider-only data rides the vCard as X-GOOGLE-* / X-MSGRAPH-* properties.
- [merged-view.md](merged-view.md): the contact-first pivot: one merged list over per-account replicas (UID-linked), addressbooks demoted to a filter, and the staged plan towards memberships, cross-account copy and divergence handling.
