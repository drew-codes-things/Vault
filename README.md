<div align="center">

# Vault

**A private, end-to-end encrypted file vault for Android - like Snapchat's "My Eyes Only", but for any file, any video length, on a server you own.**

[![Kotlin](https://img.shields.io/badge/kotlin-drew?style=flat-square&logo=kotlin&logoColor=white&color=7F52FF)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/jetpack%20compose-drew?style=flat-square&logo=jetpackcompose&logoColor=white&color=4285F4)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/android-drew?style=flat-square&logo=android&logoColor=white&color=3DDC84)](https://developer.android.com/)
[![License](https://img.shields.io/badge/license-all%20rights%20reserved-red?style=flat-square)](LICENSE)

</div>

---

## Overview

Vault is a native Android app (Kotlin + Jetpack Compose) for keeping photos,
videos and any other file somewhere only you can open. Everything is encrypted
on the phone before it's uploaded, so the server only ever stores scrambled
data - it can't see file names, types, sizes or contents, and neither can
anyone who gets hold of it.

Vault talks to your own self-hosted Vault server over HTTPS. The server (Go,
standard library only) and the Android app's source are both in this
repository.

---

## How It Works

```
Choose a passkey
        |
Argon2id stretches it into two keys
        |
   +-----------------------------+-----------------------------+
   |                                                           |
Vault key (never leaves the phone)            Login key (server keeps only a hash)
   |
Every file gets its own random key
        |
Sealed in 1 MiB chunks with AES-256-GCM  ->  names, sizes and thumbnails encrypted too
        |
Uploaded to your server as unreadable blobs
```

**The passkey can't be recovered or reset.** If you forget it, your files are
gone for good - that's the point.

---

## Features

- **End-to-end encryption** - your passkey and vault key never leave the phone
- **Any file, any size** - photos, videos of any length, documents, anything
- **Built-in viewer** - photos and streaming video playback, with thumbnails; videos replay when they finish (toggle with the repeat button)
- **Folders** - starts with Photos, Videos and Documents; add or delete your own
- **Offline mode** - files added without a connection are kept encrypted on the phone and upload automatically later
- **Resumable uploads** - big files pick up where they left off if the connection drops
- **Fingerprint unlock** - optional, on top of the passkey
- **Hidden in recent apps** - the app switcher shows a blank screen, not your files
- **Delete** - remove files permanently from the vault

---

## Setup

1. Install the APK (see [Download](#download)).
2. Open Vault and enter your Vault server's address (`https://...`).
3. The first time, switch on **First-time setup**, choose a passkey and paste
   the server's one-time setup token.
4. On any other phone, enter the same server address and passkey.

Changing the server address later wipes that phone's offline copy and stored
keys, because they belong to the old server.

Requires Android 9 or newer.

---

## Download

Grab `Vault-1.2.apk` (about 10 MB) from the [Releases](../../releases) page,
open it on your phone and allow installs from your browser or file manager
when Android asks.

To check the download:

```
SHA-256:              e0f87a298257066e587e03cb9d135161df1b0ed844fa37a9e9f9e1dcedc2b06b
Signing certificate:  5352d597c8b00e76f032df64c9f91f30965d4753156087c677d19b95af5bd017
```

Updates are signed with the same certificate, so they install over the top
without losing anything.

---

## Self-Hosting the Server

The server stores only encrypted blobs, so a plain copy of its data folder is a
full backup. With Docker:

```bash
echo "VAULT_SETUP_TOKEN=$(openssl rand -hex 32)" > .env && chmod 600 .env
docker compose up -d --build
```

It listens on `127.0.0.1:8092`. Put it behind HTTPS (a reverse proxy, or a
Cloudflare Tunnel pointing at `http://localhost:8092`) - the app only accepts
`https://` addresses. The `VAULT_SETUP_TOKEN` is what you paste into the app's
first-time setup; it only works once.

Without Docker:

```bash
cd server
VAULT_DATA=./data VAULT_SETUP_TOKEN=<random> go run .
```

If you use Cloudflare, keep upload chunks at 32 MiB or less - it rejects
request bodies over 100 MB on free plans.

---

## Build

```bash
cd server && go build -o vault-server . && go test -race ./...
cd ../android
./gradlew :core:test            # crypto + end-to-end tests against the real server
./gradlew :app:assembleRelease  # app.vault.public
```

The APK lands at `android/app/build/outputs/apk/release/app-release.apk`.
Requires JDK 17+ and the Android SDK.

---

## Get the Code

Clone with git:

```bash
git clone https://github.com/drew-codes-things/Vault.git
```

Or with the [GitHub CLI](https://cli.github.com/):

```bash
gh repo clone drew-codes-things/Vault
```

## License

All rights reserved - free to download and use, but not to copy, modify or redistribute. See [LICENSE](LICENSE).

Made by [Drew](https://github.com/drew-codes-things)
