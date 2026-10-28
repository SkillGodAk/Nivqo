# Creating a custom header logo

The header logo is the branding at the top of the patched app, where YouTube shows its
wordmark. Morphe can replace it with your own image, and includes a creator that builds the
required density folders for you.

The flow mirrors [Creating a custom app icon](custom-app-icon.md), only the creator dialog
differs.

> [!NOTE]
> Changing this option requires re-patching the app to take effect.

## Where to find it

- **Simple mode** - **Settings → Patch options**, then **Custom header logo** under the
  app.
- **Expert mode** - on the patch selection screen, find the header patch, make sure it is
  enabled, and tap the settings icon on its card.

Both open a dialog with a folder field, a **Create custom header** button, and expandable
**Instructions** with the exact layout required for a folder you prepare yourself.

## Preparing your images

- **Any PNG or JPEG works.** A PNG with a transparent background blends naturally with the
  app bar.
- **Margins do not matter.** Empty space around the logo in the file is ignored, so the image
  starts at the size and place of the stock logo.
- **You usually need two.** Most apps use a separate logo for the light and the dark theme.
  Apps that only have a dark theme, such as YouTube Music, show a single section.

## Creating the header

Tap **Create custom header**.

<p align="center">
  <img src="images/custom-header/01-header-creator.jpg" width="320" alt="Create custom header dialog with light and dark previews" />
</p>

Each theme has its own section with an editor, a **Preview** of the app bar with your logo in
it, and a scale slider:

1. Tap **Select image** under **Light theme header** and pick the image.
2. Drag it inside the editor to position it, pinch to zoom, or use the slider. The dashed box
   marks where the stock logo sits, and guides appear when the image lines up with it.
   **Change image** swaps the image later.
3. Repeat for **Dark theme header**.
4. Tap **Create custom header** and choose a folder to save into.

The info icon in the dialog header opens the same guidance in the app.

Morphe writes `morphe_branding/morphe_header_youtube` (or `morphe_header_music` for YouTube
Music) inside the folder you picked, containing a `drawable-hdpi` through `drawable-xxxhdpi`
set of `morphe_header_custom_light.png` and `morphe_header_custom_dark.png`, plus a
`.nomedia` file so the pieces stay out of your gallery. The path is filled into the option
automatically.

In Simple mode, tap **Save** before leaving the dialog. Then patch the app.

## Preparing images manually

**Select folder** accepts any folder with the right layout. Tap **Instructions** for the
authoritative list, the short version is:

- `drawable-hdpi`, `drawable-xhdpi`, `drawable-xxhdpi`, and `drawable-xxxhdpi`, each with
  `morphe_header_custom_light.png` and `morphe_header_custom_dark.png`
- Sizes per density: 194x72, 258x96, 387x144, and 512x192 px

## Troubleshooting

| Problem | What to do |
| --- | --- |
| The logo looks too small or too large | Resize it in the editor until it fills the dashed box the way you want |
| The logo has a visible box around it | Use a PNG with a transparent background instead of a JPEG |
| Only one theme section is shown | That app only uses a single header, which is expected for YouTube Music |
| The header did not change after installing | Patch options only apply when the app is patched again |

## Next steps

- [Creating a custom app icon](custom-app-icon.md)
- [Patching an app in Expert mode](patching-expert-mode.md)
