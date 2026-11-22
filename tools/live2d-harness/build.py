"""把 Live2D 模型目录构建成一个可离线渲染的测试页（只用于本地验证）。

用法:
    python build.py <model3路径> <输出目录> [--design 612x354]

产物:
    index.html / config.js / core.js / runtime.js / probe.js / tex/*
"""

import base64
import json
import os
import shutil
import sys
import urllib.parse

# Axon 的 assets 目录（只用于本地验证，不进 APK）。
# 换机器时用环境变量 AXON_BONGOCAT_ASSETS 指过去。
AXON = os.environ.get(
    "AXON_BONGOCAT_ASSETS",
    r"D:\Project\_axon\Axon-Input-main\app\src\main\assets\bongocat",
)


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def resolve(base, rel):
    if not rel:
        return None
    p = os.path.normpath(os.path.join(base, rel.replace("\\", "/")))
    return p if os.path.isfile(p) else None


def to_uri(path):
    return "file:///" + urllib.parse.quote(path.replace("\\", "/"))


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(33)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    w = int.from_bytes(head[16:20], "big")
    h = int.from_bytes(head[20:24], "big")
    return w, h


def prepare_textures(textures, out_dir, budget=48_000_000):
    """按 2 的幂统一下采样，模拟 App 里的 TextureBudget。"""
    from PIL import Image

    sizes = [png_size(p) for p in textures]
    total = sum(w * h for w, h in sizes if w and h)
    sample = 1
    while total // (sample * sample) > budget:
        sample *= 2

    tex_dir = os.path.join(out_dir, "tex")
    os.makedirs(tex_dir, exist_ok=True)
    uris = []
    for i, (path, size) in enumerate(zip(textures, sizes)):
        if sample > 1 and size:
            img = Image.open(path).convert("RGBA")
            img = img.resize((max(1, size[0] // sample), max(1, size[1] // sample)), Image.LANCZOS)
            dst = os.path.join(tex_dir, f"tex_{i:02d}.png")
            img.save(dst)
            uris.append(to_uri(dst))
        else:
            uris.append(to_uri(path))
    return uris, sample, total


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    model3 = os.path.abspath(sys.argv[1])
    out_dir = os.path.abspath(sys.argv[2])
    design = "612x354"
    if "--design" in sys.argv:
        design = sys.argv[sys.argv.index("--design") + 1]
    dw, dh = (int(x) for x in design.split("x"))

    model_root = os.path.dirname(model3)
    model = load_json(model3)
    refs = model.get("FileReferences", {})

    moc = resolve(model_root, refs.get("Moc", ""))
    if not moc:
        raise SystemExit("moc 不存在: " + str(refs.get("Moc")))
    textures = [resolve(model_root, t) for t in refs.get("Textures", [])]
    textures = [t for t in textures if t]
    if not textures:
        raise SystemExit("没有可用贴图")

    physics = load_json(resolve(model_root, refs["Physics"])) if refs.get("Physics") and resolve(model_root, refs["Physics"]) else None
    pose = load_json(resolve(model_root, refs["Pose"])) if refs.get("Pose") and resolve(model_root, refs["Pose"]) else None
    cdi = load_json(resolve(model_root, refs["DisplayInfo"])) if refs.get("DisplayInfo") and resolve(model_root, refs["DisplayInfo"]) else None

    expressions = []
    for item in refs.get("Expressions", []) or []:
        rel = item.get("File") if isinstance(item, dict) else item
        p = resolve(model_root, rel or "")
        expressions.append(load_json(p) if p else None)

    motions = []
    motion_groups = {}
    motion_index = {}
    for group, items in (refs.get("Motions") or {}).items():
        indices = []
        for i, item in enumerate(items or []):
            p = resolve(model_root, item.get("File", ""))
            if not p:
                indices.append(-1)
                continue
            key = os.path.normcase(p)
            if key not in motion_index:
                motion_index[key] = len(motions)
                motions.append(
                    {
                        "name": f"{group}/{i}",
                        "json": load_json(p),
                        "sound": "",
                    }
                )
            indices.append(motion_index[key])
        motion_groups[group] = indices

    groups = model.get("Groups") or []
    eye = next((g.get("Ids", []) for g in groups if g.get("Name") == "EyeBlink"), [])
    lip = next((g.get("Ids", []) for g in groups if g.get("Name") == "LipSync"), [])

    os.makedirs(out_dir, exist_ok=True)
    shutil.copyfile(os.path.join(AXON, "live2dcubismcore.min.js"), os.path.join(out_dir, "core.js"))
    shutil.copyfile(os.path.join(AXON, "custom", "runtime.js"), os.path.join(out_dir, "runtime.js"))

    tex_uris, sample, total = prepare_textures(textures, out_dir)

    with open(moc, "rb") as f:
        moc_bytes = f.read()

    config = {
        "mode": "standard",
        "name": os.path.basename(out_dir),
        "designWidth": dw,
        "designHeight": dh,
        "baseUri": to_uri(model_root) + "/",
        "format": "mver016",
        "mver": True,
        "useLive2d": True,
        "spriteMode": False,
        "mverFrameRateLimit": 60,
        "mverL2dCorrect": 1.0,
        "mverRenderHandOverlays": False,
        "mverRenderMouseOverlay": False,
        "mverMouseForceMove": False,
        "background": "",
        "cover": "",
        "leftKeys": [],
        "rightKeys": [],
        "leftKeyAssets": {},
        "rightKeyAssets": {},
        "axonLive2dDisplay": True,
        "axonLive2dDisplayScale": 1.0,
        "mocBase64": base64.b64encode(moc_bytes).decode("ascii"),
        "mocVersion": 3,
        "textures": tex_uris,
        "expressions": expressions,
        "motions": motions,
        "motionGroups": motion_groups,
        "physics": physics,
        "pose": pose,
        "eyeBlinkIds": eye,
        "lipSyncIds": lip,
        "parameterDefinitions": [],
        "keyParameterMap": {},
    }

    with open(os.path.join(out_dir, "config.js"), "w", encoding="utf-8") as f:
        f.write("window.__AXON_STYLE_CONFIG__ = ")
        json.dump(config, f, ensure_ascii=False)
        f.write(";\n")

    html = """<!doctype html>
<html><head><meta charset="utf-8">
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#202124}
#stage{position:relative;width:100%;height:100%}
.layer{position:absolute;inset:0;width:100%;height:100%;display:block}
.hidden{display:none!important}
#live2dCanvas{z-index:2}</style></head>
<body><div id="stage">
<img id="background" class="layer hidden" alt="">
<img id="fallback" class="layer hidden" alt="">
<canvas id="live2dCanvas" class="layer"></canvas>
<img id="leftKey" class="layer hidden" alt="">
<img id="rightKey" class="layer hidden" alt="">
</div>
<script src="config.js"></script>
<script src="core.js"></script>
<script src="runtime.js"></script>
<script src="probe.js"></script>
</body></html>
"""
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)

    probe = """(() => {
  const report = () => {
    try {
      const s = window.AxonBongoCat && window.AxonBongoCat.debugState
        ? window.AxonBongoCat.debugState() : null;
      const ready = !!(window.AxonBongoCat && window.AxonBongoCat.isReady && window.AxonBongoCat.isReady());
      const canvas = document.getElementById('live2dCanvas');
      const payload = {
        ready,
        states: s,
        canvas: canvas ? [canvas.width, canvas.height] : null,
        at: Date.now()
      };
      window.__PROBE__ = payload;
      document.title = 'PROBE ' + JSON.stringify({
        ready, physics: s && s.physicsSettings, motions: s && s.motionBindings,
        parameters: s && s.parameterCount, error: s && s.runtimeError,
        canvas: payload.canvas
      });
    } catch (e) { document.title = 'PROBE_ERR ' + e; }
  };
  setInterval(report, 500);
  report();
})();
"""
    with open(os.path.join(out_dir, "probe.js"), "w", encoding="utf-8") as f:
        f.write(probe)

    print(json.dumps({
        "model3": model3,
        "out": out_dir,
        "textures": len(textures),
        "texturePixels": total,
        "downsample": sample,
        "expressions": len(expressions),
        "motions": len(motions),
        "motionGroups": sorted(motion_groups.keys()),
        "physics": bool(physics),
        "pose": bool(pose),
        "cdi": bool(cdi),
        "mocBytes": len(moc_bytes),
        "configKiB": round(os.path.getsize(os.path.join(out_dir, "config.js")) / 1024, 1),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
