# Privacy Policy

Last updated: 10 July 2026

This privacy policy describes how the **Cardamum** Android app (package `org.pimalaya.cardamum`, the "app") handles your data. Cardamum is a free and open-source contacts app published by the Pimalaya project. Its source code is available at https://github.com/pimalaya/cardamum-android.

## Summary

Cardamum does not collect, sell, or share your personal data with us or with any third party. It is a client that syncs your contacts between your device and the contacts servers **you** choose to connect to. Everything the app stores stays on your device, except the data it exchanges with those servers on your behalf.

## The data the app handles

- **Contacts.** The app reads, stores, and writes contact data (names, phone numbers, email addresses, postal addresses, organizations, notes, photos, and the other fields of a vCard) for the addressbooks you connect. This data is kept in the app's private on-device storage and, when you enable it, projected into your device's system Contacts so other apps can see it.
- **Account credentials.** To connect to a server the app stores the credentials you provide: a password, or the OAuth 2.0 tokens obtained when you sign in through your provider. These are kept in Android Keystore-backed storage on your device and are sent only to the server you configured, or to that provider's own identity endpoint when refreshing a token.
- **Server configuration.** The addresses, usernames, and protocol settings of the accounts you add are stored on your device so the app can reconnect.

## What we do not do

- We do not operate any server of our own; the app talks only to the providers you configure.
- We do not collect analytics or usage statistics.
- The app contains no advertising, no trackers, and no third-party analytics or advertising SDKs.
- We have no access to your contacts, your credentials, or any other data the app holds.

## Network connections and third parties

The app makes network connections only to:

- **The contacts servers you configure** (CardDAV, JMAP, Google, or Microsoft), to sync your contacts and to authenticate. Data you send to those servers is governed by their own privacy policies.
- **Identity providers**, when you sign in with OAuth 2.0 (for example Google or Microsoft), to obtain and refresh access tokens.
- **A public DNS-over-HTTPS resolver**, during onboarding only, to discover a provider's contacts server. When you enter an email address the app sends the domain part of that address (for example `example.com`) to Cloudflare's resolver at `https://cloudflare-dns.com/dns-query`, and may query the provider's autoconfiguration endpoints, to locate the right server. This step is subject to Cloudflare's privacy policy.

## Permissions and why the app needs them

- **Contacts (`READ_CONTACTS`, `WRITE_CONTACTS`)**: to sync your addressbooks with the device's system Contacts, in both directions.
- **Sync settings (`READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`)**: to register each connected addressbook as a device account so it appears in your contacts apps.
- **Network access (`INTERNET`, `ACCESS_NETWORK_STATE`)**: to reach the servers you configure and to read the active network's settings for server discovery.

## Data retention and deletion

The app stores your data only for as long as the corresponding account exists on your device. You are in control:

- Removing an account inside the app deletes its stored credentials, its cached contacts, and the device accounts created for it.
- Uninstalling the app removes all data it stored on your device.

Contacts that were already synced to a remote server, or written into your device's system Contacts, are not removed by uninstalling; delete them on the server or in your contacts app if you want them gone.

## Children

The app is not directed at children and does not knowingly collect data from anyone. It has no data collection of its own.

## Changes to this policy

We may update this policy from time to time. Changes are published to this file in the app's public repository, and the "Last updated" date above reflects the latest revision.

## Contact

For any question about this policy or the app's handling of data, reach the Pimalaya project:

- Chat on [Matrix](https://matrix.to/#/#pimalaya:matrix.org)
- News on [Mastodon](https://fosstodon.org/@pimalaya) or [RSS](https://fosstodon.org/@pimalaya.rss)
- Mail at [pimalaya.org@posteo.net](mailto:pimalaya.org@posteo.net)
