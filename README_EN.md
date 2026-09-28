# Nivqo

[繁體中文](README.md) | **English**

Nivqo is an independent third-party open-source project focused on Facebook / Messenger localization and feature integration. It is based on Morphe Manager, HushFacebook, and HushMessenger, with Traditional Chinese support, Facebook / Messenger integration, and HushMessenger settings embedded directly inside Messenger.

The main reason this project exists is that the upstream projects currently do not provide complete Chinese language support. Nivqo therefore adds a Traditional Chinese interface and related usability adjustments so Chinese-speaking users can more easily use the Facebook / Messenger plugin features.

Nivqo is not intended to replace the upstream projects. Its purpose is to fill the current gap for Chinese-speaking users. **If the upstream projects later provide official Chinese plugin support and HushMessenger settings/features are officially integrated directly into Messenger, this project will be discontinued and removed.**

> Nivqo is not an official product of Meta, Facebook, Messenger, Morphe, or SysAdminDoc. Those names are used only to describe compatibility and upstream sources.

## Highlights

### Facebook

- Traditional Chinese / English HushFacebook interface
- Facebook video, Reels, and Stories download features
- Controls for sponsored, suggested, and other distracting content
- Feed, notification, playback, appearance, and download options
- HushFacebook settings integrated into Facebook settings
- Facebook / Messenger redirect control
- Keeps the original HushFacebook feature set while following compatible builds

### Messenger

- Traditional Chinese / English HushMessenger interface
- HushMessenger settings embedded directly inside Messenger
- Long-press the Messenger logo at the upper-left of the Messenger home screen to open settings
- Controls for selected recommendations, ads, and other distracting content
- Messenger personalization and chat-related controls
- Can be used together with the matching patched Facebook build

## Important: the exact build number matters

**Check the APK `versionCode`. The visible Facebook / Messenger version name is not enough.**

Meta may publish multiple different builds under the same version name. Their DEX / resource layouts can differ, so a different `versionCode` may result in:

- patch failure
- patch targets not found
- missing or broken features
- crashes or unexpected behavior after installation

Use one of the supported builds below.

### Facebook

| Version name | Supported versionCode | Architecture / notes |
| --- | ---: | --- |
| 580.0.0.51.74 | `475019344` | arm64-v8a, current primary upstream-tested build |
| 577.0.0.50.72 | `474426275` | arm64-v8a, compatible build |

### Messenger

| Version name | Supported versionCode | Architecture |
| --- | --- | --- |
| 580.0.0.49.91 | `346013387` / `346013440` / `346013442` | arm64-v8a |

> **The same versionName does not guarantee compatibility. Use the versionCode as the deciding value.**  
> If Manager reports an unsupported build, verify that the original APK matches one of the exact build codes above.

## Usage

1. Install Nivqo Manager.
2. Prepare an original Facebook or Messenger APK whose `versionCode` exactly matches a supported build above.
3. Select Facebook / Messenger and the patches you want in Manager.
4. Your own APK signing key is created when you begin patching.
5. Future updates to the same patched app must continue using the same signing key.

### Signing key

Each user should sign patched APKs with their own Manager key.

- Nivqo does **not** ship with the author's private patch signing key.
- Export and back up your signing key before reinstalling Manager or clearing its data.
- If the original key is lost, newly signed APKs generally cannot update the old patched installation in place.

## Releases

GitHub Releases contain **only the official Nivqo Manager APK**.

This repository does not redistribute complete Facebook or Messenger APKs. Obtain the original app build yourself and make sure its `versionCode` matches the supported list before patching it with Nivqo.

Current Manager:

- App: Nivqo
- Package: `app.nivqo.manager`
- Manager base version: `1.32.0`

## Source code

Main source trees:

- `source/morphe-manager` — Nivqo Manager / Morphe Manager derivative
- `source/hushfacebook` — Facebook patches and localization
- `source/hushmessenger` — Messenger patches, localization, and embedded settings entry
- `source/morphe-patcher`
- `source/morphe-patches-gradle-plugin`
- `source/morphe-library`
- `source/jadb`


## Upstream projects and thanks

Special thanks to **SysAdminDoc**:

- [SysAdminDoc](https://github.com/SysAdminDoc)
- [HushFacebook](https://github.com/SysAdminDoc/HushFacebook)
- [HushMessenger](https://github.com/SysAdminDoc/HushMessenger)

Thanks also to the Morphe project:

- [Morphe Manager](https://github.com/MorpheApp/morphe-manager)
- [Morphe organization](https://github.com/MorpheApp)

Nivqo modifications do not imply official support or endorsement from any upstream author. Original LICENSE / NOTICE files are preserved in their respective source trees.

See [SOURCES.md](docs/SOURCES.md) for more detail.

## Support the author

If Nivqo is useful to you, you can support the author.

### International

[Buy Me a Coffee](https://buymeacoffee.com/SkillGodAK)

### Bank transfer

<img src="assets/donate-bank.jpg" alt="Bank donation QR code" width="360">

### WeChat

<img src="assets/donate-wechat.jpg" alt="WeChat donation QR code" width="360">

## License

Nivqo's GPL-derived modifications are provided under the applicable GPLv3 terms. Third-party components retain their own original licenses. See the `LICENSE` / `NOTICE` files in each source directory.

Nivqo is an independent third-party project and is not officially affiliated with Meta, Facebook, Messenger, Morphe, or SysAdminDoc.