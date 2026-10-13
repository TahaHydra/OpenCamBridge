"""Collect local dependency licence files and an inventory; no source-code edits."""
import json
import subprocess
from pathlib import Path
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/release/third-party"
OUT.mkdir(parents=True, exist_ok=True)
inventory = {}
for manifest in ("desktop/tauri-app/src-tauri/Cargo.toml", "windows/virtual-camera-mediafoundation/rust-frame-producer/Cargo.toml"):
    result = subprocess.run(["cargo", "metadata", "--locked", "--format-version", "1", "--manifest-path", str(ROOT / manifest)], capture_output=True, text=True, check=True)
    for package in json.loads(result.stdout)["packages"]:
        if not package.get("source"):
            continue
        key = "rust/" + package["name"] + "-" + package["version"]
        folder = Path(package["manifest_path"]).parent
        files = [p for p in folder.iterdir() if p.is_file() and p.name.lower().startswith(("license", "licence", "copying", "notice"))]
        if package.get("license_file"):
            files.append(folder / package["license_file"])
        # openh264-sys embeds the upstream codec source under a child directory.
        if package["name"] == "openh264-sys2":
            files.extend(folder.rglob("LICENSE"))
        destination = OUT / key
        destination.mkdir(parents=True, exist_ok=True)
        copied = []
        for file in dict.fromkeys(files):
            if file.is_file() and file.resolve().is_relative_to(folder.resolve()):
                name = file.name if file.parent == folder else "upstream-" + file.name
                shutil.copyfile(file, destination / name)
                copied.append(name)
        inventory[key] = {"license": package.get("license"), "files": copied, "repository": package.get("repository")}

lock = json.loads((ROOT / "desktop/tauri-app/package-lock.json").read_text(encoding="utf-8"))
for relative, package in lock.get("packages", {}).items():
    if not relative or package.get("dev"):
        continue
    folder = ROOT / "desktop/tauri-app" / relative
    if not folder.is_dir():
        continue
    name = relative.split("node_modules/")[-1]
    key = "javascript/" + name.replace("/", "_") + "-" + package["version"]
    destination = OUT / key
    destination.mkdir(parents=True, exist_ok=True)
    copied = []
    for file in folder.iterdir():
        if file.is_file() and file.name.lower().startswith(("license", "licence", "copying", "notice")):
            shutil.copyfile(file, destination / file.name)
            copied.append(file.name)
    inventory[key] = {"license": package.get("license"), "files": copied}

native = OUT / "native"
native.mkdir(exist_ok=True)
shutil.copyfile(ROOT / "windows/virtual-camera-mediafoundation/LICENSE", native / "Microsoft-VirtualCamera-MIT.txt")
nuget = ROOT / "windows/virtual-camera-mediafoundation/packages/Microsoft.VCRTForwarders.140.1.1.0"
for file in nuget.rglob("*"):
    if file.is_file() and file.name.lower().startswith(("license", "licence", "notice")):
        shutil.copyfile(file, native / ("VCRTForwarders-" + file.name))

android_inventory = ROOT / "scratch/android-release-dependencies.json"
if not android_inventory.exists():
    raise SystemExit("Export Android dependencies using android-notices.init.gradle first.")
gradle_cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
for artifact in json.loads(android_inventory.read_text(encoding="utf-8")):
    key = f"android/{artifact['group']}_{artifact['name']}-{artifact['version']}"
    destination = OUT / key
    destination.mkdir(parents=True, exist_ok=True)
    licenses, copied = [], []
    cache = gradle_cache / artifact["group"] / artifact["name"] / artifact["version"]
    for pom in cache.rglob("*.pom"):
        shutil.copyfile(pom, destination / pom.name)
        document = ET.parse(pom)
        namespace = ns if document.getroot().tag.startswith('{') else {}
        prefix = "m:" if namespace else ""
        for node in document.findall(f"{prefix}licenses/{prefix}license", namespace):
            licenses.append({"name": node.findtext(prefix + "name", default="", namespaces=namespace),
                             "url": node.findtext(prefix + "url", default="", namespaces=namespace)})
    with zipfile.ZipFile(artifact["file"]) as archive:
        for name in archive.namelist():
            if Path(name).suffix.lower() not in ('.class', '.jar') and not name.endswith('/') and Path(name).name.lower().startswith(("license", "licence", "copying", "notice")):
                target = name.replace('/', '_')
                (destination / target).write_bytes(archive.read(name))
                copied.append(target)
    inventory[key] = {"licenses": licenses, "files": copied}
(OUT / "inventory.json").write_text(json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8")
missing = [name for name, value in inventory.items() if not value["files"]]
(OUT / "README.md").write_text("# Dependency licence inventory\n\nGenerated from locked Rust metadata (including platform/build dependencies),\nnon-development npm lock entries and resolved Android release runtime artifacts.\nAndroid folders contain cached Maven POM licence declarations and any embedded\nlicence/notice text. Inclusion is conservative; not every crate is shipped in\nthe Windows executable. POM links are not substitutes for required licence text.\n\nPackages without embedded licence text (review upstream terms):\n\n" + "\n".join("- " + name for name in missing) + "\n", encoding="utf-8")
print(f"Collected notices for {len(inventory)} packages; {len(missing)} need upstream-text review.")
