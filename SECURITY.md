# Security policy

## Supported versions

Cardamum for Android is pre-release software; only the latest development build receives security fixes.

| Version | Supported |
|-------------------|--------------------|
| Unreleased (main) | :white_check_mark: |

## Reporting a vulnerability

Report suspected vulnerabilities privately by email to pimalaya.org@posteo.net rather than opening a public issue. Include the affected version or commit, the impact, and a reproduction when you have one; you can expect an acknowledgement within a few days and a coordinated disclosure once a fix is ready.

Cardamum stores account credentials and OAuth tokens in the Android Keystore and talks to your servers directly over TLS, so reports about credential handling, the sync bridge or the transport layer are especially welcome.
