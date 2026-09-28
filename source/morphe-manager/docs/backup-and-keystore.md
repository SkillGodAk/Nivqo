# Backing up Morphe and your keystore

Everything Morphe knows lives in its own app data: your settings, your patch sources, your
patch selections, and the keystore every patched APK is signed with. Uninstalling Morphe or
clearing its data takes all of it, and the keystore is the one piece you cannot recreate.

Back it up before you reinstall Morphe, move to another device, or reset your phone.

## Why the keystore matters

Morphe signs every APK it produces with a keystore it generated on first use. Android
compares signatures when installing, so:

- **Same keystore** - a repatched build installs straight over the previous one as an
  update, and the app keeps its data.
- **Different keystore** - Android refuses. The only way to install is to uninstall the
  patched app first, which erases its data.

Reinstalling Morphe or clearing its storage generates a new keystore. Without an exported
copy, every app you patched before becomes un-updatable in place.

> [!TIP]
> Apps installed through [root mount](installers.md#root-mount-in-detail) are unaffected,
> because they are mounted over the stock app rather than installed.

## Exporting the keystore

Open **Settings → System → Import & export → Signing key**. The dialog shows the key Morphe
signs with: its alias, the date it was created, and its SHA-256 fingerprint. The badge beside
the alias is tinted from that fingerprint, so two different keys look different at a glance.
Tap **Export** and Morphe writes a `Morphe.keystore` file wherever you choose.

Until you patch your first app there is no key yet, and the dialog only offers **Import**.

> [!TIP]
> To check that two devices sign with the same key, compare the fingerprints shown in this
> dialog on each of them.

> [!WARNING]
> Treat this file as private. Anyone holding it can sign APKs that your device will accept as
> updates to your patched apps.

## Importing the keystore

In the same dialog tap **Import** and pick the file. Morphe detects the format and first tries
the alias and password combinations it knows, so a keystore it exported itself usually
imports without a prompt. Once it is in, the dialog shows the imported key's details.

If that fails, the **Enter keystore credentials** dialog asks for:

- **Username (Alias)**
- **Password**
- **Keystore format**

Keystores Morphe generated use `Morphe` as both alias and password. A mismatch is reported
as **Incorrect keystore credentials**.

## Backing up your settings

**Settings → System → Import & export → Morphe settings** lists what a backup can carry, each
with a checkbox:

| Section | What it holds |
| --- | --- |
| **Appearance** | Theme, colors, background and language |
| **Home screen** | App groups, hidden apps, sorting and the home screen toggles |
| **Patching and installing** | Installer, patcher, file picker and saved APK settings, completion sounds |
| **Updates** | Manager prereleases and background update checks |
| **Sources** | Custom patch sources, their prerelease choices, and the GitHub token when allowed |
| **Patch selections** | Chosen patches and their options for every app |

Every section is ticked to begin with. **Export** writes only the ticked ones to a
`morphe_manager_settings.json`. **Import** applies only the ticked sections the file carries,
so you can, for example, bring back just your sources and leave everything else as it is.

When **Sources** or **Patch selections** is ticked, Morphe asks how to apply them:

| Mode | Effect |
| --- | --- |
| **Replace existing** | Match the backup exactly. Anything missing from the backup is removed |
| **Merge with existing** | Add what the backup has, leave your current items unchanged |

Unticked sections are left untouched in either mode.

The keystore is not part of that file. It is exported on its own, as above, so a settings
backup carries neither the signing key nor its credentials.

> [!NOTE]
> A GitHub personal access token is only included if you enabled **Include in settings
> export** next to it. If you did, keep the exported file private, it contains the token.

## Backing up patch selections

Saved patch selections and patch options survive uninstalling a patched app, and travel with
the settings backup while **Patch selections** is ticked. They can also be moved on their own:
**Settings → System → Files & storage → Patch selections** lists them per app and per source,
with its own export and import, and the same **Replace** or **Merge** choice, see
[Storage and saved data](storage-and-saved-data.md#patch-selections).

This is what makes a repatch reproduce the build you had, so include it if you care about
your Expert mode choices.

## Saved APKs

**Settings → System** also keeps the APK copies, controlled by two switches:

- **Keep original APKs** - the pre-patch file, so repatching does not need a new download.
  One version per app is kept.
- **Keep patched APKs** - a copy of the result, so it can be exported or reinstalled without
  patching again.

Both lists let you export the files, individually or as a zip, and delete what you no longer
need, see [Storage and saved data](storage-and-saved-data.md).

## A complete backup

1. **Keystore** - the one thing that cannot be regenerated.
2. **Morphe settings** with every section ticked - your configuration, patch sources and
   patch selections.
3. Optionally, exported **patched or original APKs**.

## Restoring on a new device

1. Install Morphe.
2. Import the **keystore** first, before patching anything, so new builds match what your
   old device installed.
3. Import **Morphe settings**. Your custom patch sources come back and start updating, and
   your patch selections are applied after them.
4. Patch as usual, see [Updating a patched app](updating-patched-apps.md).

> [!NOTE]
> Selections for a custom source can only be applied once that source has loaded. If some
> are missing right after the import, import the file again with only **Patch selections**
> ticked.

## Troubleshooting

| Problem | What to do |
| --- | --- |
| "Incorrect keystore credentials" | The alias, password, or format does not match. Morphe's own keystores use `Morphe` for alias and password |
| The **Signing key** dialog offers no **Export** | Morphe has not generated a key yet. Patch an app once, then export |
| Signature conflict after reinstalling Morphe | The keystore changed. Import your backup, then patch again. Without a backup, uninstall the patched app and start fresh |
| Imported settings removed sources you still wanted | **Replace existing** matches the backup exactly. Use **Merge with existing** to keep what you have |
| Patch selections did not come back | Tick **Patch selections** both when exporting and when importing Morphe settings, or restore them from **Settings → System → Files & storage → Patch selections** |

## Next steps

- [Updating a patched app](updating-patched-apps.md)
- [Managing patch sources](patch-sources.md)
- [Choosing how patched apps are installed](installers.md)
