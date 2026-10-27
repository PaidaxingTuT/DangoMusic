import os
import re
import sys
import zipfile
import urllib.request

def patch_189():
    print("=== Patching 1.8.9 Forge Jar with Updated Common Classes ===")
    
    # 1. Read project version
    version = "1.5.0"
    if os.path.exists("version.properties"):
        with open("version.properties", "r", encoding="utf-8") as f:
            for line in f:
                if line.startswith("version="):
                    version = line.strip().split("=", 1)[1]
    print(f"Mod version: {version}")

    # 2. Locate upstream release jar or download if not present
    base_jar = "legacy_base_1.8.9.jar"
    if not os.path.exists(base_jar):
        url = "https://github.com/Sorihiko/DangoMusic/releases/download/v1.5.0/DangoMusic-1.5.0+1.8.9.jar"
        print(f"Downloading base 1.8.9 jar from {url}...")
        urllib.request.urlretrieve(url, base_jar)
        print("Download complete.")

    # 3. Locate compiled modern 1.20.1 fabric jar to extract updated common classes
    source_jar = None
    search_paths = [
        "modern/versions/1.20.1-fabric/build/libs",
        "output",
        "dist"
    ]
    for sp in search_paths:
        if os.path.exists(sp):
            for fname in os.listdir(sp):
                if "1.20.1-fabric" in fname and fname.endswith(".jar") and not fname.endswith("-sources.jar") and not fname.endswith("-dev.jar"):
                    source_jar = os.path.join(sp, fname)
                    break
        if source_jar:
            break

    if not source_jar:
        print("Error: Could not find compiled 1.20.1-fabric jar to extract updated common classes from.")
        sys.exit(1)

    print(f"Using modern jar source: {source_jar}")

    # Packages belonging to common (platform-agnostic) code
    common_prefixes = (
        "me/sorihiko/dango/config/",
        "me/sorihiko/dango/event/",
        "me/sorihiko/dango/events/ModuleToggleEvent",
        "me/sorihiko/dango/events/TickEvent",
        "me/sorihiko/dango/events/WorldRenderEndEvent",
        "me/sorihiko/dango/font/MixedText",
        "me/sorihiko/dango/gfx/",
        "me/sorihiko/dango/i18n/",
        "me/sorihiko/dango/lyrics/Lyric",
        "me/sorihiko/dango/lyrics/TextFlash",
        "me/sorihiko/dango/module/",
        "me/sorihiko/dango/music/",
        "me/sorihiko/dango/musicui/",
        "me/sorihiko/dango/party/",
        "me/sorihiko/dango/platform/Key",
        "me/sorihiko/dango/platform/Platform",
        "me/sorihiko/dango/platform/Player",
        "me/sorihiko/dango/service/",
        "me/sorihiko/dango/shader/",
        "me/sorihiko/dango/ui/",
        "me/sorihiko/dango/utils/Color",
        "me/sorihiko/dango/DangoClient",
        "me/sorihiko/dango/DangoLogger",
        "me/sorihiko/dango/DangoMusic",
        "assets/dangomusic/",
    )

    # Exclude platform-specific packages/files
    exclude_prefixes = (
        "me/sorihiko/dango/modern/",
        "me/sorihiko/dango/fabric/",
        "me/sorihiko/dango/forge/",
        "me/sorihiko/dango/neoforge/",
        "me/sorihiko/dango/legacy/",
        "fabric.mod.json",
        "dangomusic.mixins.json",
        "META-INF/neoforge.mods.toml",
        "META-INF/mods.toml",
    )

    updated_files = {}
    with zipfile.ZipFile(source_jar, 'r') as z_src:
        for name in z_src.namelist():
            if any(name.startswith(p) for p in common_prefixes):
                if not any(name.startswith(ep) for ep in exclude_prefixes):
                    updated_files[name] = z_src.read(name)

    print(f"Extracted {len(updated_files)} common files from modern jar.")

    # 4. Create patched 1.8.9 output jar
    out_dir = "output_189"
    os.makedirs(out_dir, exist_ok=True)
    out_jar = os.path.join(out_dir, f"DangoMusic-{version}+1.8.9.jar")

    with zipfile.ZipFile(base_jar, 'r') as z_base, zipfile.ZipFile(out_jar, 'w', compression=zipfile.ZIP_DEFLATED) as z_out:
        # Write base jar files, updating or keeping
        written = set()
        for item in z_base.infolist():
            name = item.filename
            if name in updated_files:
                z_out.writestr(name, updated_files[name])
                written.add(name)
            elif name == "version.properties":
                content = f"dangomusic.version={version}\n".encode('utf-8')
                z_out.writestr(name, content)
                written.add(name)
            elif name == "mcmod.info":
                info_text = z_base.read(name).decode('utf-8')
                info_text = re.sub(r'"version":\s*"[^"]*"', f'"version": "{version}"', info_text)
                z_out.writestr(name, info_text.encode('utf-8'))
                written.add(name)
            else:
                z_out.writestr(item, z_base.read(name))
                written.add(name)

        # Write any new files that were added in modern common (e.g. new Kugou classes)
        new_count = 0
        for name, data in updated_files.items():
            if name not in written:
                z_out.writestr(name, data)
                new_count += 1

    print(f"Added {new_count} newly created classes (Kugou, etc.).")
    print(f"Patched jar created successfully at: {out_jar} (size: {os.path.getsize(out_jar)} bytes)")

if __name__ == "__main__":
    patch_189()
