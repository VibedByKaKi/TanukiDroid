# VibedByKaKi/TanukiDroid

GitLab client for Android. Fork of [OctoDroid (gh4a)](https://github.com/slapperwan/gh4a) with GitHub support replaced by multi-instance GitLab support.

The upstream project README stays in [README.md](../README.md) at the repository root.

| [![](assets/get-it-on-github.png)](https://github.com/VibedByKaKi/TanukiDroid/actions/workflows/build-apk.yml) |
| --- |

CI builds a signed release APK on every push. Download the latest artifact from [Actions, Build APK](https://github.com/VibedByKaKi/TanukiDroid/actions/workflows/build-apk.yml) (`TanukiDroid-release-<build-number>.apk`, uploaded without ZIP wrapping).

## What this fork changes

- **GitLab accounts.** Add account offers `gitlab.com`, `gitlab.gnome.org`, `invent.kde.org`, `salsa.debian.org`, `gitlab.freedesktop.org`, `gitgud.io`, `framagit.org`, `jihulab.com`, `gitlab.alpinelinux.org` or a custom base URL.
- **WebView login.** Sign in or register in the dialog. Once the session is active, a script in the page creates a personal access token with the `api` scope (name editable, default `TanukiDroid`, valid for up to 364 days) and the app stores it with the instance URL.
- **API translation.** The app still speaks the GitHub-shaped SDK of OctoDroid. `GitLabCompatInterceptor` sends each request to the GitLab REST API v4 and converts the answer back. Merge requests appear as pull requests, with the number range starting at 1,000,000,000 internally and shown as `!N`.
- **Application ID.** `com.vibedbykaki.tanukidroid`

## CI

- **Build APK** runs on push, pull request and manual dispatch. It uploads `TanukiDroid-release-<run_number>.apk` with `archive: false`.

## Credits

OctoDroid was created by Azwan Adli, Danny Baumann and [contributors](https://github.com/slapperwan/gh4a/graphs/contributors). This repository remains a fork of that work.
