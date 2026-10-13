# One master logo

Replace `logo-master.png` with a square PNG, then run:

```powershell
python release-tools/generate-branding.py
```

The master is the hydra/camera symbol only, without lettering. Generated files
are used by Android launcher/About, desktop About/window/shortcut icons, MSI
banner/dialog artwork, store icon and marketing banners. Keep text separate from the mark.
`generate-branding.py` requires Pillow. No artwork generation service is needed
when changing an already prepared master.

`banner-background.png` is a brand-neutral generated illustration. The script
composes the current master and separate typography over it, so replacing the
logo also changes the Play feature graphic, website banner and social preview.
Typography uses the local Windows Segoe UI fonts; font files are not distributed.
Product/support links live in `product.json`; the script also generates Android
`ProductLinks.kt` from that file. Desktop reads the same JSON directly.

The initial master was prepared with the built-in image generator from the
user's supplied artwork: remove all lettering and separator, preserve the
hydra-and-camera emblem, center on black with generous margins. Original input
and generated master are not claims of trademark clearance.
