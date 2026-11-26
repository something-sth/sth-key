"""用**我们自己**的 runtime（app/src/main/assets/bongocat/keyboard/runtime.js）建验证页。

用法:
    python build_sthkey.py <model3路径> <输出目录> [--design 612x354]

与 build.py 的区别：build.py 跑的是 Axon 的 custom runtime（只做对照），
这个脚本跑的是项目自己的 runtime + `__BONGO_*` 数据格式，用来验证自研功能。
"""

import base64
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "bongocat")


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def resolve(base, rel):
    if not rel:
        return None
    p = os.path.normpath(os.path.join(base, rel.replace("\\", "/")))
    return p if os.path.isfile(p) else None


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def prepare_textures(textures, out_dir, budget=48_000_000):
    from PIL import Image

    sizes = [png_size(p) for p in textures]
    total = sum(w * h for w, h in sizes if w and h)
    sample = 1
    while total // (sample * sample) > budget:
        sample *= 2

    tex_dir = os.path.join(out_dir, "tex")
    os.makedirs(tex_dir, exist_ok=True)
    rels = []
    for i, (path, size) in enumerate(zip(textures, sizes)):
        name = f"tex_{i:02d}.png"
        dst = os.path.join(tex_dir, name)
        if sample > 1 and size:
            img = Image.open(path).convert("RGBA")
            img = img.resize(
                (max(1, size[0] // sample), max(1, size[1] // sample)), Image.LANCZOS
            )
            img.save(dst)
        else:
            shutil.copyfile(path, dst)
        rels.append("tex/" + name)
    return rels, sample, total


VK_NAMES = {
    0x01: "MouseLeft", 0x02: "MouseRight", 0x04: "MouseMiddle",
    0x08: "Backspace", 0x09: "Tab", 0x0D: "Return", 0x10: "Shift",
    0x11: "Control", 0x12: "Alt", 0x13: "CapsLock", 0x1B: "Escape",
    0x20: "Space", 0x25: "LeftArrow", 0x26: "UpArrow", 0x27: "RightArrow",
    0x28: "DownArrow", 0xBB: "Equal", 0xBC: "Comma", 0xBD: "Minus",
    0xBE: "Period", 0xBF: "Slash", 0xC0: "BackQuote", 0xDB: "BracketLeft",
    0xDC: "Backslash", 0xDD: "BracketRight", 0xDE: "Quote",
}


def vk_name(vk):
    if 0x30 <= vk <= 0x39:
        return "Num" + str(vk - 0x30)
    if 0x41 <= vk <= 0x5A:
        return "Key" + chr(ord("A") + vk - 0x41)
    if 0x60 <= vk <= 0x69:
        return "Numpad" + str(vk - 0x60)
    if 0x70 <= vk <= 0x7B:
        return "F" + str(vk - 0x70 + 1)
    return VK_NAMES.get(vk)


def find_package_root(model_root):
    cur = os.path.realpath(model_root)
    for _ in range(5):
        if os.path.isfile(os.path.join(cur, "config.json")):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent
    return None


def load_motion_bindings(model_root):
    root = find_package_root(model_root)
    if not root:
        return []
    try:
        import re

        text = open(os.path.join(root, "config.json"), "r", encoding="utf-8-sig").read()
        config = json.loads(re.sub(r"//[^\n]*", "", text))
    except Exception:
        return []

    lowered = model_root.lower()
    mode = "keyboard" if "keyboard" in lowered else ("gamepad" if "gamepad" in lowered else "standard")
    section = config.get(mode) or config.get("keyboard") or {}
    bindings = []
    for field, group in (("l2d_motion", "CAT_motion"), ("l2d_motion_lockhand", "CAT_motion_lock")):
        for index, combo in enumerate(section.get(field) or []):
            keys = [vk_name(int(v)) for v in combo if vk_name(int(v))]
            if keys:
                bindings.append({"keys": keys, "group": group, "index": index})
    return bindings


def load_hand_sprites(model_root):
    root = find_package_root(model_root)
    if not root:
        return None
    try:
        import re

        text = open(os.path.join(root, "config.json"), "r", encoding="utf-8-sig").read()
        config = json.loads(re.sub(r"//[^\n]*", "", text))
    except Exception:
        return None

    lowered = model_root.lower()
    mode = "keyboard" if "keyboard" in lowered else ("gamepad" if "gamepad" in lowered else "standard")
    section = config.get(mode) or config.get("keyboard") or {}

    result = {}
    for field, direction in (("lefthand", "lefthand"), ("righthand", "righthand")):
        combos = section.get(field) or []
        bindings = []
        for index, combo in enumerate(combos):
            keys = [vk_name(int(v)) for v in combo if vk_name(int(v))]
            if keys:
                bindings.append({"keys": keys, "index": index})
        if bindings:
            result[field.replace("hand", "")] = {"dir": direction, "bindings": bindings}
    if not result:
        combos = section.get("hand") or []
        bindings = []
        for index, combo in enumerate(combos):
            keys = [vk_name(int(v)) for v in combo if vk_name(int(v))]
            if keys:
                bindings.append({"keys": keys, "index": index})
        if bindings:
            result["hand"] = {"dir": "hand", "bindings": bindings}
    return result or None


def load_face_sprites(model_root):
    """Mver 表情贴图：配置 face → face/ 下标。"""
    root = find_package_root(model_root)
    if not root:
        return None
    try:
        import re

        text = open(os.path.join(root, "config.json"), "r", encoding="utf-8-sig").read()
        config = json.loads(re.sub(r"//[^\n]*", "", text))
    except Exception:
        return None

    lowered = model_root.lower()
    mode = "keyboard" if "keyboard" in lowered else ("gamepad" if "gamepad" in lowered else "standard")
    section = config.get(mode) or {}
    combos = section.get("face") or []
    bindings = []
    for index, combo in enumerate(combos):
        keys = [vk_name(int(v)) for v in combo if vk_name(int(v))]
        if keys:
            bindings.append({"keys": keys, "index": index})
    return {"dir": "face", "bindings": bindings} if bindings else None


def find_background(model_root):
    """Mver 桌面/键盘底图：standard 用 mousebg/tabletbg，其它用 bg。"""
    variant = os.path.dirname(model_root)
    for name in ("mousebg", "tabletbg", "bg", "background"):
        for ext in (".png", ".webp", ".jpg", ".jpeg"):
            candidate = os.path.join(variant, name + ext)
            if os.path.isfile(candidate):
                return os.path.basename(candidate)
    return None


def find_layout(model_root):
    """Mver decoration: l2d_correct / l2d_offset。"""
    root = find_package_root(model_root)
    if not root:
        return None
    try:
        config = load_json(os.path.join(root, "config.json"))
    except Exception:
        return None
    decoration = config.get("decoration") or {}
    offset = decoration.get("l2d_offset") or [0, 0]
    return {
        "correct": float(decoration.get("l2d_correct") or 1.0),
        "offsetX": float(offset[0]) if len(offset) > 0 else 0.0,
        "offsetY": float(offset[1]) if len(offset) > 1 else 0.0,
    }


def find_design(model_root):
    """Mver 设计画布：decoration.window_size + hand_offset。"""
    root = find_package_root(model_root)
    if not root:
        return None
    try:
        config = load_json(os.path.join(root, "config.json"))
    except Exception:
        return None
    decoration = config.get("decoration") or {}
    size = decoration.get("window_size") or []
    if len(size) < 2:
        return None
    offset = decoration.get("hand_offset") or [0, 0]
    return {
        "width": float(size[0]),
        "height": float(size[1]),
        "handOffsetX": float(offset[0]) if len(offset) > 0 else 0.0,
        "handOffsetY": float(offset[1]) if len(offset) > 1 else 0.0,
    }


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
    textures = [resolve(model_root, t) for t in refs.get("Textures", [])]
    textures = [t for t in textures if t]
    if not moc or not textures:
        raise SystemExit("缺 moc 或贴图")

    os.makedirs(out_dir, exist_ok=True)
    shutil.copyfile(os.path.join(ASSETS, "live2dcubismcore.min.js"), os.path.join(out_dir, "core.js"))
    shutil.copyfile(os.path.join(ASSETS, "keyboard", "runtime.js"), os.path.join(out_dir, "runtime.js"))

    tex_rels, sample, total = prepare_textures(textures, out_dir)
    with open(moc, "rb") as f:
        moc_bytes = f.read()

    physics = None
    physics_rel = refs.get("Physics")
    if physics_rel:
        physics_path = resolve(model_root, physics_rel)
        if physics_path and os.path.getsize(physics_path) <= 2 * 1024 * 1024:
            physics = load_json(physics_path)

    motions = []
    motion_groups = {}
    motion_index = {}
    total_motion_bytes = 0
    for group, items in (refs.get("Motions") or {}).items():
        indices = []
        for i, item in enumerate(items or []):
            rel = item.get("File", "") if isinstance(item, dict) else ""
            path = resolve(model_root, rel)
            if not path or os.path.getsize(path) > 1024 * 1024:
                indices.append(-1)
                continue
            total_motion_bytes += os.path.getsize(path)
            if total_motion_bytes > 8 * 1024 * 1024:
                indices.append(-1)
                continue
            key = os.path.normcase(path)
            if key not in motion_index:
                motion_index[key] = len(motions)
                motions.append({
                    "name": f"{group}/{i}",
                    "json": load_json(path),
                    "fadeIn": float(item.get("FadeInTime", 0.5) or 0.5),
                    "fadeOut": float(item.get("FadeOutTime", 0.5) or 0.5),
                })
            indices.append(motion_index[key])
        motion_groups[group] = indices

    background = find_background(model_root)
    if background:
        source = os.path.join(os.path.dirname(model_root), background)
        if os.path.isfile(source):
            shutil.copy2(source, os.path.join(out_dir, background))

    data = [
        "window.__BONGO_KEYBOARD_MOC_BASE64=" + json.dumps(base64.b64encode(moc_bytes).decode("ascii")) + ";",
        "window.__BONGO_TEXTURES=" + json.dumps(tex_rels) + ";",
        'window.__BONGO_RESOURCE_BASE="resources";',
        "window.__BONGO_IMPORTED_MODEL=true;",
        'window.__BONGO_MODEL_LAYOUT="cat";',
        "window.__BONGO_MVER_HANDS=false;",
        "window.__BONGO_DEBUG=true;",
        "window.__BONGO_HANDS_ENABLED=true;",
        "window.__BONGO_EXPRESSIONS=[];",
        "window.__BONGO_EXPR_BINDINGS=[];",
        "window.__BONGO_PHYSICS=" + (json.dumps(physics, ensure_ascii=False) if physics else "null") + ";",
        "window.__BONGO_MOTIONS=" + json.dumps(motions, ensure_ascii=False) + ";",
        "window.__BONGO_MOTION_GROUPS=" + json.dumps(motion_groups) + ";",
        "window.__BONGO_MOTION_BINDINGS=" + json.dumps(load_motion_bindings(model_root), ensure_ascii=False) + ";",
        "window.__BONGO_HANDS=" + (json.dumps(load_hand_sprites(model_root), ensure_ascii=False) if load_hand_sprites(model_root) else "null") + ";",
        "window.__BONGO_BACKGROUND=" + json.dumps(background) + ";",
        "window.__BONGO_LAYOUT=" + json.dumps(find_layout(model_root)) + ";",
        "window.__BONGO_DESIGN=" + json.dumps(find_design(model_root)) + ";",
        "window.__BONGO_FACE=" + json.dumps(load_face_sprites(model_root), ensure_ascii=False) + ";",
    ]
    with open(os.path.join(out_dir, "modeldata.js"), "w", encoding="utf-8") as f:
        f.write("\n".join(data))

    html = """<!doctype html>
<html><head><meta charset="utf-8">
<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#202124}
#stage{position:relative;width:100%;height:100%}
.layer{position:absolute;inset:0;width:100%;height:100%;display:block}
.hidden{display:none!important}
#background,#leftKey,#rightKey,#fallback{object-fit:contain;pointer-events:none}
#background{z-index:0}#fallback{z-index:1}#live2dCanvas{z-index:2}#leftKey,#rightKey{z-index:3}
#leftHand,#rightHand{position:absolute;object-fit:fill;pointer-events:none;z-index:4}
</style></head>
<body><div id="stage">
<img id="background" class="layer hidden" alt="">
<img id="fallback" class="layer hidden" alt="">
<canvas id="live2dCanvas" class="layer"></canvas>
<img id="leftKey" class="layer hidden" alt="">
<img id="rightKey" class="layer hidden" alt="">
<img id="leftHand" class="hidden" alt="">
<img id="rightHand" class="hidden" alt="">
</div>
<script src="modeldata.js"></script>
<script src="core.js"></script>
<script src="runtime.js"></script>
<script src="probe_sthkey.js"></script>
</body></html>
"""
    with open(os.path.join(out_dir, "index.html"), "w", encoding="utf-8") as f:
        f.write(html)

    probe = """(() => {
  const WATCH = ['hair1', 'hair2', 'chest1', 'Param25', 'RE1'];
  let played = false;
  /* 测"按键 → 手部贴图"：按下 KeyA，看左手图是否显示 */
  setInterval(() => {
    try {
      if (!played && window.AxonBongoCat && window.AxonBongoCat.isReady && window.AxonBongoCat.isReady()) {
        window.AxonBongoCat.key('KeyA', true);
        for (let i = 0; i < 5; i++) window.AxonBongoCat.debugTick(1 / 60);
        played = true;
      }
    } catch (_) {}
  }, 300);
  /* 自动把指针往右推，验证物理是否跟随鼠标（ParamAngleX 变化 → 头发角度变化） */
  setInterval(() => {
    try {
      if (window.AxonBongoCat && window.AxonBongoCat.mouseDelta) {
        window.AxonBongoCat.mouseDelta(90, 0, innerWidth, innerHeight);
      }
    } catch (_) {}
  }, 250);
  const report = () => {
    try {
      const ready = !!(window.AxonBongoCat && window.AxonBongoCat.isReady && window.AxonBongoCat.isReady());
      const canvas = document.getElementById('live2dCanvas');
      const renderer = window.__STHKEY_RENDERER || null;
      const params = {};
      if (renderer) {
        for (const id of WATCH) {
          const index = renderer.paramIndex.get(id);
          if (index !== undefined) params[id] = Number(renderer.parameters.values[index].toFixed(3));
        }
      }
      const debug = renderer && renderer.physics && renderer.physics.debug ? renderer.physics.debug : {};
      const lh = document.getElementById('leftHand');
      const rh = document.getElementById('rightHand');
      const hand = {
        left: !!(lh && !lh.classList.contains('hidden')),
        leftSrc: lh ? lh.getAttribute('src') : null,
        right: !!(rh && !rh.classList.contains('hidden')),
        rightSrc: rh ? rh.getAttribute('src') : null,
      };
      const angleX = renderer && renderer.paramIndex.get('ParamAngleX') !== undefined
        ? Number(renderer.parameters.values[renderer.paramIndex.get('ParamAngleX')].toFixed(2)) : null;
      document.title = 'PROBE ' + JSON.stringify({
        ready,
        canvas: canvas ? [canvas.width, canvas.height] : null,
        physics: !!window.__BONGO_PHYSICS,
        angleX,
        params,
        motion: window.AxonBongoCat && window.AxonBongoCat.motionState ? window.AxonBongoCat.motionState() : null,
        groups: window.AxonBongoCat && window.AxonBongoCat.motionGroups ? window.AxonBongoCat.motionGroups() : [],
        hand,
        debug,
      });
    } catch (e) { document.title = 'PROBE_ERR ' + e; }
  };
  setInterval(report, 300);
  report();
})();
"""
    with open(os.path.join(out_dir, "probe_sthkey.js"), "w", encoding="utf-8") as f:
        f.write(probe)

    print(json.dumps({
        "model3": model3,
        "out": out_dir,
        "textures": len(textures),
        "texturePixels": total,
        "downsample": sample,
        "physics": bool(physics),
        "physicsSettings": len(physics.get("PhysicsSettings", [])) if physics else 0,
        "motions": len(motions),
        "motionGroups": sorted(motion_groups.keys()),
        "mocBytes": len(moc_bytes),
        "modelDataKiB": round(os.path.getsize(os.path.join(out_dir, "modeldata.js")) / 1024, 1),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
