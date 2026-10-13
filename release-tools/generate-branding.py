"""Export platform artwork from a single text-free master; requires Pillow."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
MASTER = ROOT / "branding/logo-master.png"
image = Image.open(MASTER).convert("RGBA")
if image.width != image.height:
    raise SystemExit("branding/logo-master.png must be square")

def export(path, size, mode="RGBA"):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize(size, Image.Resampling.LANCZOS).convert(mode).save(path)

icons = "desktop/tauri-app/src-tauri/icons"
for name, side in [("32x32.png", 32), ("128x128.png", 128), ("128x128@2x.png", 256), ("icon.png", 512)]:
    export(f"{icons}/{name}", (side, side))
image.resize((256, 256), Image.Resampling.LANCZOS).save(ROOT / icons / "icon.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
export("desktop/tauri-app/src/assets/opencambridge-original.png", (512, 512))
export("desktop/tauri-app/public/favicon.png", (64, 64))
export("android/app/src/main/res/drawable-nodpi/ocb_logo.png", (512, 512))
export("branding/generated/play-icon-512.png", (512, 512))
if (ROOT / "branding/generated/play-icon-512.png").stat().st_size > 1024 * 1024:
    raise SystemExit("Play icon exceeds 1 MiB")
for density, side in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    for name in ("ic_launcher", "ic_launcher_round"):
        export(f"android/app/src/main/res/mipmap-{density}/{name}.png", (side, side))
# Adaptive foreground: leave enough room for circular/squircle launcher masks.
foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
foreground.alpha_composite(image.resize((288, 288), Image.Resampling.LANCZOS), (72, 72))
foreground.save(ROOT / "android/app/src/main/res/drawable-nodpi/ocb_launcher_foreground.png")

generated = ROOT / "branding/generated"
generated.mkdir(parents=True, exist_ok=True)
# WiX places its own page titles over the blank left area of the banner.
banner = Image.new("RGB", (493, 58), "white")
banner.paste(image.resize((48, 48), Image.Resampling.LANCZOS).convert("RGB"), (435, 5))
banner.save(generated / "msi-banner.bmp")
dialog = Image.new("RGB", (493, 312), "white")
dialog.paste(Image.new("RGB", (164, 312), "black"), (0, 0))
dialog.paste(image.resize((150, 150), Image.Resampling.LANCZOS).convert("RGB"), (7, 81))
dialog.save(generated / "msi-dialog.bmp")
print("Generated Android, Windows, MSI and Play icon assets from", MASTER)
feature_path = ROOT / "branding/banner-background.png"
if feature_path.exists():
    feature = Image.open(feature_path).convert("RGB").resize((1024, 500), Image.Resampling.LANCZOS)
    # Artwork stays brand-neutral: changing the master updates every banner too.
    feature.paste(image.resize((220, 220), Image.Resampling.LANCZOS).convert("RGB"), (395, 50))
    draw = ImageDraw.Draw(feature)
    fonts = Path("C:/Windows/Fonts")
    title = ImageFont.truetype(str(fonts / "segoeuib.ttf"), 39)
    subtitle = ImageFont.truetype(str(fonts / "segoeui.ttf"), 21)
    draw.text((505, 284), "OpenCamBridge", font=title, fill="white", anchor="mt")
    draw.text((505, 344), "Your phone. Your webcam.", font=subtitle, fill="#b9d8f4", anchor="mt")
    feature.save(generated / "play-feature-1024x500.png")
    feature.resize((1600, 782), Image.Resampling.LANCZOS).save(generated / "website-banner-1600x782.png")
    social = Image.new("RGB", (1200, 630), "black")
    social.paste(feature.resize((1200, 586), Image.Resampling.LANCZOS), (0, 22))
    social.save(generated / "social-preview-1200x630.png")
product = json.loads((ROOT / "branding/product.json").read_text(encoding="utf-8"))
kotlin = "// Generated from branding/product.json.\npackage com.opencambridge.android.ui\n\nobject ProductLinks {\n"
for key, value in product.items():
    kotlin += f"    const val {key} = {json.dumps(value)}\n"
kotlin += "}\n"
(ROOT / "android/app/src/main/java/com/opencambridge/android/ui/ProductLinks.kt").write_text(kotlin, encoding="utf-8")
