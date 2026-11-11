# VibedByKaKi/TanukiDroid

GitLab client for Android. Fork of [OctoDroid (gh4a)](https://github.com/slapperwan/gh4a) with GitHub support replaced by multi-instance GitLab support.

The upstream project README stays in [README.md](../README.md) at the repository root.

| [![](assets/get-it-on-github.png)](https://github.com/VibedByKaKi/TanukiDroid/releases/tag/latest) | [![](assets/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2520%2522com.vibedbykaki.tanukidroid%2522%252C%2520%2522url%2522%253A%2520%2522https%253A%252F%252Fgithub.com%252FVibedByKaKi%252FTanukiDroid%2522%252C%2520%2522author%2522%253A%2520%2522VibedByKaKi%2522%252C%2520%2522name%2522%253A%2520%2522TanukiDroid%2522%252C%2520%2522preferredApkIndex%2522%253A%25200%252C%2520%2522additionalSettings%2522%253A%2520%2522%257B%255C%2522includePrereleases%255C%2522%253A%2520true%252C%2520%255C%2522fallbackToOlderReleases%255C%2522%253A%2520true%252C%2520%255C%2522apkFilterRegEx%255C%2522%253A%2520%255C%2522.%252A%255C%255C%255C%255C.apk%2524%255C%2522%252C%2520%255C%2522releaseDateAsVersion%255C%2522%253A%2520true%257D%2522%257D) |
| --- | --- |

The APK is a rolling GitHub prerelease on the [`latest`](https://github.com/VibedByKaKi/TanukiDroid/releases/tag/latest) tag. Direct download: [TanukiDroid.apk](https://github.com/VibedByKaKi/TanukiDroid/releases/download/latest/TanukiDroid.apk). In Obtainium, keep **Include pre-releases** and **Use release date as version** on if you add the repo by hand, because the tag never changes. Every CI run also uploads `TanukiDroid-release-<build-number>.apk` as a workflow artifact.

## What this fork changes

- **GitLab accounts.** Add account offers `gitlab.com`, `gitlab.gnome.org`, `invent.kde.org`, `salsa.debian.org`, `gitlab.freedesktop.org`, `gitgud.io`, `framagit.org`, `jihulab.com`, `gitlab.alpinelinux.org` or a custom base URL.
- **WebView login.** Sign in or register in the dialog. Once the session is active, a script in the page creates a personal access token with the `api` scope (name editable, default `TanukiDroid`, valid for up to 364 days) and the app stores it with the instance URL.
- **API translation.** The app still speaks the GitHub-shaped SDK of OctoDroid. `GitLabCompatInterceptor` sends each request to the GitLab REST API v4 and converts the answer back. Merge requests appear as pull requests, with the number range starting at 1,000,000,000 internally and shown as `!N`.
- **Application ID.** `com.vibedbykaki.tanukidroid`

## CI

- **Build APK** runs on push, pull request and manual dispatch. It uploads `TanukiDroid-release-<run_number>.apk` with `archive: false`. Pushes to `master` also replace the `latest` prerelease with `TanukiDroid.apk`.

## Credits

OctoDroid was created by Azwan Adli, Danny Baumann and [contributors](https://github.com/slapperwan/gh4a/graphs/contributors). This repository remains a fork of that work.
