(() => {
  'use strict';

  const DESIGN_WIDTH = 612;
  const DESIGN_HEIGHT = 354;
  let FRAME_INTERVAL = 1000 / 60;
  const DAMPING_DECAY = 0.75;
  const BUILTIN_RESOURCE_BASE = 'file:///android_asset/bongocat/keyboard/resources';
  const IMPORTED_MODEL = Boolean(window.__BONGO_IMPORTED_MODEL);
  const MODEL_LAYOUT = String(window.__BONGO_MODEL_LAYOUT || (IMPORTED_MODEL ? 'cat' : 'native'));
  /*
   * 这个包是不是 Mver 风格（自带逐键 PNG 手）。
   *
   * ⚠️ 现在**不再用它决定"收不收手臂"**（导入模型一律不收，见 syncHandOverrides）。
   * 保留它是因为接下来要做的"PNG 手合成"需要知道这个信息，
   * 由导入器在 `modeldata.js` 里写好。
   */
  const MVER_HANDS = Boolean(window.__BONGO_MVER_HANDS);

  /*
   * ============================================================
   * 表情（Cubism 标准 `.exp3.json`，由导入器预解析后内联）
   * ============================================================
   * `[{"name":"正常眼","params":[["Param14",0,"Add"], …]}]`
   *
   * ⚠️ 必须内联而不是让页面 fetch:`file://` 页面读本地文件被 WebView 拒绝，
   * moc 当初也是因为这个才用 base64（见导入器类注释）。
   */
  const EXPRESSIONS = Array.isArray(window.__BONGO_EXPRESSIONS) ? window.__BONGO_EXPRESSIONS : [];
  /* `[{"keys":["Alt","Numpad1"],"index":0}]` —— 按下这组键就切到第 0 个表情 */
  const EXPR_BINDINGS = Array.isArray(window.__BONGO_EXPR_BINDINGS) ? window.__BONGO_EXPR_BINDINGS : [];

  /*
   * ============================================================
   * 物理（Cubism 标准 `physics3.json`，由导入器内联）
   * ============================================================
   * ⚠️ 这里自己实现，不引第三方 runtime：
   * 算法照 Cubism 的"摆锤 + 粒子链"规范（Normalization → Input → Particles → Output），
   * 只做**单步积分**，不做官方的多帧插值（视觉上足够，代码量小很多）。
   */
  const PHYSICS_JSON = window.__BONGO_PHYSICS || null;
  const PHYSICS_MAX_WEIGHT = 100;
  const PHYSICS_AIR_RESISTANCE = 5;
  const PHYSICS_MOVEMENT_THRESHOLD = 0.001;

  /*
   * ============================================================
   * 动作（Cubism 标准 `motion3.json`，由导入器内联）
   * ============================================================
   * 曲线四种类型（Linear / Bezier / Stepped / InverseStepped）在这里求值。
   */
  const MOTIONS = Array.isArray(window.__BONGO_MOTIONS) ? window.__BONGO_MOTIONS : [];
  const MOTION_GROUPS = (window.__BONGO_MOTION_GROUPS && typeof window.__BONGO_MOTION_GROUPS === 'object')
    ? window.__BONGO_MOTION_GROUPS
    : {};
  /* 按键组合 → 动作（Mver config.json 的 l2d_motion / l2d_motion_lockhand） */
  const MOTION_BINDINGS = Array.isArray(window.__BONGO_MOTION_BINDINGS) ? window.__BONGO_MOTION_BINDINGS : [];

  /*
   * Mver 手部贴图：按下哪个键就把对应编号的手 PNG 盖在模型上，
   * 同时把模型手臂参数设为 1 收起来（不然两双手会同时出现）。
   */
  const HANDS = (window.__BONGO_HANDS && typeof window.__BONGO_HANDS === 'object') ? window.__BONGO_HANDS : null;
  /* Mver 表情贴图：face/ 整幅图，按键组合触发 */
  const FACE = (window.__BONGO_FACE && typeof window.__BONGO_FACE === 'object') ? window.__BONGO_FACE : null;
  const LAYOUT_PARAMS = (window.__BONGO_LAYOUT && typeof window.__BONGO_LAYOUT === 'object')
    ? window.__BONGO_LAYOUT
    : null;
  /* Mver 设计画布（decoration.window_size）：所有 full-frame 贴图的坐标原点 */
  const MVER_DESIGN = (window.__BONGO_DESIGN && typeof window.__BONGO_DESIGN === 'object')
    ? window.__BONGO_DESIGN
    : null;
  const pressedKeyOrder = [];

  /**
   * 手图图层**按需创建**。
   *
   * ⚠️ 不能只在页面里找现成的 `#leftHand`：老模型页没有这两个元素，
   * 那样 `getElementById` 是 null，手图永远画不出来（表现就是"按键没反应"）。
   */
  function ensureHandLayer(id) {
    let element = document.getElementById(id);
    if (!element) {
      element = document.createElement('img');
      element.id = id;
      element.style.position = 'absolute';
      element.style.pointerEvents = 'none';
      element.style.objectFit = 'fill';
      element.style.zIndex = '4';
      const stage = document.getElementById('stage') || document.body;
      stage.appendChild(element);
    }
    /*
     * ⚠️ 页面模板里的 `<img id="leftHand" class="hidden">` 带 `hidden` 类，
     * 而 `.hidden { display: none !important }` —— 内联 `style.display='block'` 压不过它。
     * 结果就是：标准模式的动态图层能显示，键盘模式的左右手永远看不见，
     * 同时手臂参数已被置 1 → 表现成"按一下手直接消失"。
     */
    element.classList.remove('hidden');
    element.style.display = 'none';
    return element;
  }
  const leftHandImage = HANDS ? ensureHandLayer('leftHand') : null;
  const rightHandImage = HANDS ? ensureHandLayer('rightHand') : null;
  const singleHandImage = HANDS ? ensureHandLayer('handLayer') : null;
  let faceLayer = null;

  /*
   * 手柄摇杆贴图（`stick_offset_L/R` + left_stick.png / right_stick.png）。
   *
   * 坐标是**设计画布像素**：中心 = (x + lx*radius, y + ly*radius)，
   * 半径来自配置第三个数。按下 L3/R3 时换成 `*_down.png`。
   */
  /*
   * 手柄摇杆：**不画 PNG 贴图**，而是驱动模型参数。
   *
   * ⚠️ 这是对照 Axon 的 `syncGamepadStickHandParameters` 后才改对的：
   * Mver 的 `left_stick.png` 是**纯贴图模式**用的；Live2D 模型自己的手/摇杆
   * 由 `CatParamStickLX/LY/RX/RY`、`CatParamStickShow*Hand`、`CatParamStick*Down`
   * 这些参数控制。之前我拿 PNG 盖上去，所以位置和效果都不对。
   */
  function applyGamepadStickParameters() {
    if (!renderer) return;
    const axisIds = ['CatParamStickLX', 'CatParamStickLY', 'CatParamStickRX', 'CatParamStickRY'];
    if (!axisIds.some((id) => renderer.range(id))) return;

    const setAxis = (id, raw) => {
      const range = renderer.range(id);
      if (!range) return;
      const normalized = Math.max(-1, Math.min(1, Number(raw) || 0));
      const value = normalized >= 0 ? normalized * range[1] : (-normalized) * range[0];
      renderer.setOverride(id, value);
    };
    setAxis('CatParamStickLX', state.stickLeft.x);
    setAxis('CatParamStickLY', state.stickLeft.y);
    setAxis('CatParamStickRX', state.stickRight.x);
    setAxis('CatParamStickRY', state.stickRight.y);

    const leftDown = state.pressedKeys.has('GamepadL3');
    const rightDown = state.pressedKeys.has('GamepadR3');
    const leftActive = Math.abs(state.stickLeft.x) > 0.04 ||
      Math.abs(state.stickLeft.y) > 0.04 || leftDown;
    const rightActive = Math.abs(state.stickRight.x) > 0.04 ||
      Math.abs(state.stickRight.y) > 0.04 || rightDown;

    if (renderer.range('CatParamStickShowLeftHand')) {
      /*
       * ⚠️ 按键手优先：只要这一侧正在显示按键手图，就不要再显示"摇杆手"。
       * 否则会同时冒出两只手（用户实测：推着摇杆按 A，左摇杆手 + 按键手一起出现）。
       * 摇杆的 LX/LY 仍然照常写，所以手收起来之后摇杆动作不会断。
       */
      renderer.setOverride(
        'CatParamStickShowLeftHand',
        leftActive && !state.handSpriteLeft ? 1 : 0,
      );
    }
    if (renderer.range('CatParamStickShowRightHand')) {
      renderer.setOverride(
        'CatParamStickShowRightHand',
        rightActive && !state.handSpriteRight ? 1 : 0,
      );
    }
    if (renderer.range('CatParamStickLeftDown')) {
      renderer.setOverride('CatParamStickLeftDown', leftDown ? 1 : 0);
    }
    if (renderer.range('CatParamStickRightDown')) {
      renderer.setOverride('CatParamStickRightDown', rightDown ? 1 : 0);
    }
  }

  // When an arbitrary Live2D character is imported without a matching BongoCat keyboard skin,
  // place the visible character where the built-in cat lives instead of preserving an unrelated
  // desktop-model canvas offset (which commonly puts the character off-screen/right).
  const CAT_TARGET = { x: 82, y: 4, width: 448, height: 286 };

  // These are the exact keyboard resource groups shipped by BongoCat's keyboard model.
  const LEFT_KEYS = new Set([
    'Alt', 'AltGr', 'BackQuote', 'Backspace', 'CapsLock', 'Control', 'ControlLeft',
    'ControlRight', 'Delete', 'Escape', 'Fn', 'KeyA', 'KeyB', 'KeyC', 'KeyD',
    'KeyE', 'KeyF', 'KeyG', 'KeyH', 'KeyI', 'KeyJ', 'KeyK', 'KeyL', 'KeyM',
    'KeyN', 'KeyO', 'KeyP', 'KeyQ', 'KeyR', 'KeyS', 'KeyT', 'KeyU', 'KeyV',
    'KeyW', 'KeyX', 'KeyY', 'KeyZ', 'Meta', 'Num0', 'Num1', 'Num2', 'Num3',
    'Num4', 'Num5', 'Num6', 'Num7', 'Num8', 'Num9', 'Return', 'Shift',
    'ShiftLeft', 'ShiftRight', 'Slash', 'Space', 'Tab',
  ]);
  const RIGHT_KEYS = new Set(['DownArrow', 'LeftArrow', 'RightArrow', 'UpArrow']);


  // Horizontal input mirror only. The cat model and background never flip.
  // Pairs follow the exact key rows drawn by the bundled BongoCat keyboard asset.
  const MIRROR_KEYS = new Map([
    ['BackQuote', 'Backspace'], ['Backspace', 'BackQuote'],
    ['Num1', 'Num0'], ['Num0', 'Num1'],
    ['Num2', 'Num9'], ['Num9', 'Num2'],
    ['Num3', 'Num8'], ['Num8', 'Num3'],
    ['Num4', 'Num7'], ['Num7', 'Num4'],
    ['Num5', 'Num6'], ['Num6', 'Num5'],
    ['Tab', 'Delete'], ['Delete', 'Tab'],
    ['KeyQ', 'KeyP'], ['KeyP', 'KeyQ'],
    ['KeyW', 'KeyO'], ['KeyO', 'KeyW'],
    ['KeyE', 'KeyI'], ['KeyI', 'KeyE'],
    ['KeyR', 'KeyU'], ['KeyU', 'KeyR'],
    ['KeyT', 'KeyY'], ['KeyY', 'KeyT'],
    ['CapsLock', 'Return'], ['Return', 'CapsLock'],
    ['KeyA', 'KeyL'], ['KeyL', 'KeyA'],
    ['KeyS', 'KeyK'], ['KeyK', 'KeyS'],
    ['KeyD', 'KeyJ'], ['KeyJ', 'KeyD'],
    ['KeyF', 'KeyH'], ['KeyH', 'KeyF'],
    ['KeyG', 'KeyG'],
    ['ShiftLeft', 'ShiftRight'], ['ShiftRight', 'ShiftLeft'],
    ['KeyZ', 'Slash'], ['Slash', 'KeyZ'],
    ['KeyX', 'KeyM'], ['KeyM', 'KeyX'],
    ['KeyC', 'KeyN'], ['KeyN', 'KeyC'],
    ['KeyV', 'KeyB'], ['KeyB', 'KeyV'],
    ['ControlLeft', 'ControlRight'], ['ControlRight', 'ControlLeft'],
    ['Alt', 'AltGr'], ['AltGr', 'Alt'],
    ['LeftArrow', 'RightArrow'], ['RightArrow', 'LeftArrow'],
    ['UpArrow', 'UpArrow'], ['DownArrow', 'DownArrow'],
    ['Space', 'Space'], ['Meta', 'Meta'], ['Escape', 'Escape'], ['Fn', 'Fn'],
    ['Control', 'Control'], ['Shift', 'Shift'],
  ]);

  const state = {
    leftKey: null,
    rightKey: null,
    mouseButtons: 0,
    cursorX: 0.5,
    cursorY: 0.5,
    targetX: 0.5,
    targetY: 0.5,
    pointerActive: false,
    globalReverse: false,
    /* 当前按住的键（用于匹配"组合键 → 表情"） */
    pressedKeys: new Set(),
    /* 当前表情下标；-1 = 没有表情 */
    expressionIndex: -1,
    /* 手柄摇杆（-1..1，Y 向上为正） */
    stickLeft: { x: 0, y: 0 },
    stickRight: { x: 0, y: 0 },
    /* 当前左右手图是否正在显示（按键手优先于摇杆手） */
    handSpriteLeft: false,
    handSpriteRight: false,
    /* 当前显示的表情贴图下标；-1 = 没有（Mver 的 face 是开关语义） */
    faceIndex: -1,
    ready: false,
  };

  const stage = document.getElementById('stage');
  const canvas = document.getElementById('live2dCanvas');
  const fallback = document.getElementById('fallback');
  const leftImage = document.getElementById('leftKey');
  const rightImage = document.getElementById('rightKey');

  function resourceUrl(base, relativePath) {
    return `${String(base || '').replace(/\/$/, '')}/${relativePath}`;
  }

  function setImageWithFallback(image, relativePath) {
    const preferredBase = window.__BONGO_RESOURCE_BASE || BUILTIN_RESOURCE_BASE;
    const preferred = resourceUrl(preferredBase, relativePath);
    const fallbackUrl = resourceUrl(BUILTIN_RESOURCE_BASE, relativePath);
    image.onerror = () => {
      if (image.src !== fallbackUrl) {
        image.onerror = null;
        image.src = fallbackUrl;
      }
    };
    image.src = preferred;
  }

  // Imported model packs can carry their own keyboard/cover. Apply the skin as one unit.
  // Individual missing files fall back to the bundled BongoCat resources instead of blanking.
  const backgroundImage = document.getElementById('background');
  const modelBackground = typeof window.__BONGO_BACKGROUND === 'string'
    ? window.__BONGO_BACKGROUND
    : null;
  if (modelBackground) {
    /*
     * Mver 的桌面/键盘底图（standard: mousebg/tabletbg，其它: bg）。
     * 它和窗口同比例（如 1600×1000），所以直接铺满；没有它时人物就只剩上半身。
     */
    backgroundImage.classList.remove('hidden');
    backgroundImage.style.objectFit = 'fill';
    backgroundImage.style.display = 'block';
    backgroundImage.src = modelBackground;
  } else if (IMPORTED_MODEL) {
    /* 导入的模型没带底图：不要露出内置猫的键盘背景 */
    backgroundImage.classList.add('hidden');
  } else {
    setImageWithFallback(backgroundImage, 'background.png');
  }
  setImageWithFallback(fallback, 'cover.png');

  let renderer = null;
  let lastFrameTime = 0;
  let animationHandle = 0;

  // CubismEyeBlink defaults from the same Cubism framework used by easy-live2d.
  const blink = {
    phase: 'first',
    phaseStartedAt: 0,
    nextAt: 0,
  };

  function nextBlinkTime(now) {
    // CubismEyeBlink: userTime + random * (2 * 4.0 - 1.0)
    return now + Math.random() * 7000;
  }

  function eyeBlinkValue(now) {
    if (blink.phase === 'first') {
      blink.phase = 'interval';
      blink.nextAt = nextBlinkTime(now);
      return 1;
    }

    if (blink.phase === 'interval') {
      if (now >= blink.nextAt) {
        blink.phase = 'closing';
        blink.phaseStartedAt = now;
      }
      return 1;
    }

    if (blink.phase === 'closing') {
      let t = (now - blink.phaseStartedAt) / 100;
      if (t >= 1) {
        t = 1;
        blink.phase = 'closed';
        blink.phaseStartedAt = now;
      }
      return 1 - t;
    }

    if (blink.phase === 'closed') {
      const t = (now - blink.phaseStartedAt) / 50;
      if (t >= 1) {
        blink.phase = 'opening';
        blink.phaseStartedAt = now;
      }
      return 0;
    }

    let t = (now - blink.phaseStartedAt) / 150;
    if (t >= 1) {
      t = 1;
      blink.phase = 'interval';
      blink.nextAt = nextBlinkTime(now);
    }
    return t;
  }

  function keySide(key) {
    if (LEFT_KEYS.has(key)) return 'left';
    if (RIGHT_KEYS.has(key)) return 'right';
    return null;
  }

  function inputKey(key) {
    if (!state.globalReverse) return key;
    return MIRROR_KEYS.get(key) || key;
  }

  function setOverlay(side, key) {
    const image = side === 'left' ? leftImage : rightImage;
    if (!key) {
      image.classList.add('hidden');
      image.onerror = null;
      image.removeAttribute('src');
      return;
    }
    const relative = `${side}-keys/${encodeURIComponent(key)}.png`;
    setImageWithFallback(image, relative);
    image.classList.remove('hidden');
  }

  /*
   * 按键盘时要不要去动模型的左右手参数。
   *
   * ⚠️⚠️ 用户报的 bug:
   * > 第三方模型，按按键手部会直接消失
   *
   * 原因:这一类包（Mver 键盘猫包）在 `config.json` 里把每个键映射到
   * `lefthand/<N>.png` / `righthand/<N>.png`。按下时 **Mver 画那张 PNG**，
   * 同时 `CatParamLeftHandDown=1` 让模型把自己的手臂收起来（免得和 PNG 重叠）。
   *
   * 我们只做了"收起"这一半 —— PNG 手没画，于是手直接消失。
   * 所以这类包（模型包里有 `lefthand/` + `righthand/`）**不去动**这两个参数:
   * 宁可没有按压动画，也不能让手没了。
   */
  /**
   * 切到第 index 个表情。
   *
   * @param index -1 = 取消表情
   */
  function applyExpression(index) {
    const next = Number(index);
    if (!Number.isInteger(next) || next < 0 || next >= EXPRESSIONS.length) {
      state.expressionIndex = -1;
      if (renderer) renderer.expression = null;
      return;
    }
    state.expressionIndex = next;
    if (renderer) renderer.expression = EXPRESSIONS[next].params || [];
  }

  /**
   * 按下的键满足某个"组合键 → 表情"绑定时切过去。
   *
   * ⚠️ 要求**全部**键都按住（组合键语义）:Mver 的 `l2d_expression` 里
   * `[[18,219],…]` 这种就是"Alt + 某个键"。
   *
   * ⚠️ 匹配不上时**不做任何事**:不能让"按 A"把表情重置掉，
   * 那样普通按键会把用户刚切的表情清掉。
   */
  /**
   * 按键组合 → 动作（Mver 的 `l2d_motion` / `l2d_motion_lockhand`）。
   *
   * ⚠️ 只在**组合刚凑齐**时触发一次：按住不放不会每帧重播。
   * 松开任意一个键后清空状态，同一组合可以再次触发。
   */
  let activeMotionBinding = -1;
  function evaluateMotionBindings(pressed) {
    if (!renderer || !renderer.motionPlayer || MOTION_BINDINGS.length === 0) return;

    if (!pressed) {
      if (activeMotionBinding >= 0) {
        const keys = MOTION_BINDINGS[activeMotionBinding].keys || [];
        if (!keys.every((key) => state.pressedKeys.has(String(key)))) {
          activeMotionBinding = -1;
        }
      }
      return;
    }

    for (let i = 0; i < MOTION_BINDINGS.length; i++) {
      if (i === activeMotionBinding) continue;
      const binding = MOTION_BINDINGS[i];
      const keys = Array.isArray(binding.keys) ? binding.keys : [];
      if (keys.length === 0) continue;
      if (!keys.every((key) => state.pressedKeys.has(String(key)))) continue;

      const group = String(binding.group || 'CAT_motion');
      const index = Number(binding.index) || 0;
      if (renderer.motionPlayer.playedGroup(group, index, false)) {
        activeMotionBinding = i;
      }
      return;
    }
  }

  function applyHandSprites() {
    if (!HANDS || !renderer) return;

    const resolve = (config, element) => {
      if (!config || !element) return false;
      let chosen = -1;
      for (let i = pressedKeyOrder.length - 1; i >= 0; i--) {
        const key = pressedKeyOrder[i];
        if (!state.pressedKeys.has(key)) continue;
        const binding = (config.bindings || []).find((item) => {
          const keys = item.keys || [];
          return keys.includes(key) && keys.every((k) => state.pressedKeys.has(String(k)));
        });
        if (binding) {
          chosen = Number(binding.index);
          break;
        }
      }

      if (chosen < 0) {
        element.style.display = 'none';
        return false;
      }

      const src = String(config.dir || '') + '/' + chosen + '.png';
      if (element.getAttribute('src') !== src) element.setAttribute('src', src);
      element.style.display = 'block';

      /*
       * Mver 的 full-frame 贴图（手/脸/键盘/背景）都是**设计画布窗口尺寸**的整幅图，
       * 从 (0,0) 以原始像素大小绘制 —— 不是按模型画布拉伸的。
       * （卡提 1400²/1400²、星野 1600×1000/1600×1000、超天酱 612×352/612×354）
       */
      const dpr = Math.max(1, devicePixelRatio || 1);
      const scale = renderer.designScale || renderer.scale;
      const naturalWidth = Math.max(1, element.naturalWidth || 0);
      const naturalHeight = Math.max(1, element.naturalHeight || 0);
      element.style.left = ((renderer.designX || 0) + (renderer.handOffsetX || 0) * scale) / dpr + 'px';
      element.style.top = ((renderer.designY || 0) + (renderer.handOffsetY || 0) * scale) / dpr + 'px';
      element.style.width = (naturalWidth * scale / dpr) + 'px';
      element.style.height = (naturalHeight * scale / dpr) + 'px';
      return true;
    };

    const left = resolve(HANDS.left, leftHandImage);
    const right = resolve(HANDS.right, rightHandImage);
    /* standard 等单层包：一张图同时代表双手 */
    const single = resolve(HANDS.hand, singleHandImage);
    state.handSpriteLeft = Boolean(left || single);
    state.handSpriteRight = Boolean(right || single);
    renderer.setOverride('CatParamLeftHandDown', state.handSpriteLeft ? 1 : 0);
    renderer.setOverride('CatParamRightHandDown', state.handSpriteRight ? 1 : 0);
  }

  /**
   * Mver 的表情是**开关**语义（不是按住显示）：
   * 组合键按下的瞬间切换"显示/隐藏"，而且同一时间只亮一个。
   * 见 Mver `catfunc.cpp` 的 `drawface()`（state 翻转，其余全清）。
   */
  function toggleFaceOnPress() {
    if (!FACE) return;
    for (const binding of FACE.bindings || []) {
      const keys = binding.keys || [];
      if (keys.length === 0) continue;
      if (!keys.every((k) => state.pressedKeys.has(String(k)))) continue;
      const index = Number(binding.index);
      state.faceIndex = state.faceIndex === index ? -1 : index;
      return;
    }
  }

  function applyFaceSprites() {
    if (!FACE || !renderer) return;
    const element = ensureFaceLayer();

    if (state.faceIndex < 0) {
      element.style.display = 'none';
      return;
    }

    const src = String(FACE.dir || 'face') + '/' + state.faceIndex + '.png';
    if (element.getAttribute('src') !== src) element.setAttribute('src', src);
    element.style.display = 'block';

    /*
     * Mver 的 face 贴图**不做任何定位/缩放**（`drawface` 里直接 window.draw）：
     * 就在窗口 (0,0) 以原始像素画。别挪到设计画布中心，否则对不上。
     */
    const dpr = Math.max(1, devicePixelRatio || 1);
    const scale = renderer.designScale || renderer.scale;
    const naturalWidth = Math.max(1, element.naturalWidth || 0);
    const naturalHeight = Math.max(1, element.naturalHeight || 0);
    element.style.left = ((renderer.designX || 0)) / dpr + 'px';
    element.style.top = ((renderer.designY || 0)) / dpr + 'px';
    element.style.width = (naturalWidth * scale / dpr) + 'px';
    element.style.height = (naturalHeight * scale / dpr) + 'px';
  }

  function ensureFaceLayer() {
    if (faceLayer) return faceLayer;
    let element = document.getElementById('faceLayer');
    if (!element) {
      element = document.createElement('img');
      element.id = 'faceLayer';
      element.style.position = 'absolute';
      element.style.pointerEvents = 'none';
      element.style.objectFit = 'fill';
      element.style.zIndex = '7';
      const stage = document.getElementById('stage') || document.body;
      stage.appendChild(element);
    }
    element.classList.remove('hidden');
    faceLayer = element;
    return element;
  }

  function evaluateExpressionBindings() {
    if (EXPR_BINDINGS.length === 0 || state.pressedKeys.size === 0) return;
    for (const binding of EXPR_BINDINGS) {
      const keys = Array.isArray(binding.keys) ? binding.keys : [];
      if (keys.length === 0) continue;
      if (!keys.every((key) => state.pressedKeys.has(String(key)))) continue;
      if (state.expressionIndex !== binding.index) applyExpression(binding.index);
      return;
    }
  }

  /**
   * 把模型内容的比例回报给原生侧。
   *
   * ⚠️ 只在**比例明显变化**时回报（1% 以上），否则每次 resize 都会往 Java 侧
   * 发一次调用，窗口会反复重排。
   *
   * ⚠️ 用 `addJavascriptInterface` 暴露的对象，页面上是 `window.SthKeyBridge`；
   * 不存在时（例如内置模型页面）静默跳过。
   */
  let lastReportedAspect = 0;
  function reportContentAspect(contentWidth, contentHeight) {
    const aspect = Number(contentWidth) / Math.max(1, Number(contentHeight));
    if (!Number.isFinite(aspect) || aspect <= 0) return;
    if (lastReportedAspect > 0 && Math.abs(lastReportedAspect - aspect) / lastReportedAspect < 0.01) return;
    lastReportedAspect = aspect;

    try {
      const bridge = window.SthKeyBridge;
      if (bridge && typeof bridge.onContentAspect === 'function') {
        bridge.onContentAspect(aspect);
      }
    } catch (_) {
      /* 桥不存在/被拒绝时不影响渲染 */
    }
  }

  function syncHandOverrides() {
    if (!renderer) return;
    /*
     * ⚠️ **所有导入的模型都不去动这两个参数**。
     *
     * 第一版只对"目录里有 `lefthand/` + `righthand/`"的包跳过 —— 结果实测样本的
     * `standard` 变体用的是 `hand/`（不是 lefthand/righthand），没被识别出来，
     * 于是标准模式下手照样消失。
     *
     * ⚠️ 教训:**别按资源目录名判断**。社区包的命名五花八门
     * （`lefthand/` `righthand/` `hand/` `left_hand/`，甚至散在根目录的 png）。
     *
     * 而这些包的共同点是:模型自带的 `CatParam*HandDown` 是给"收起手臂、让位给 PNG 手"
     * 用的，我们**不画 PNG 手**，所以对任何导入模型设 1 都是错的。
     *
     * ⚠️ 显式置 0（而不是"不碰"）:上一次按键可能已经把它设成 1。
     */
    if (IMPORTED_MODEL) {
      /*
       * 有手部贴图：由 `applyHandSprites()` 根据按键决定"显示哪只手 + 收不收手臂"；
       * 没有手部贴图：继续强制 0，避免把模型手臂收起来却没人接手（老 bug）。
       */
      if (!HANDS) {
        renderer.setOverride('CatParamLeftHandDown', 0);
        renderer.setOverride('CatParamRightHandDown', 0);
      }
      return;
    }
    renderer.setOverride('CatParamLeftHandDown', state.leftKey ? 1 : 0);
    renderer.setOverride('CatParamRightHandDown', state.rightKey ? 1 : 0);
  }

  function applyPointerOverrides(xRatio, yRatio) {
    if (!renderer) return;
    const ids = [
      'ParamMouseX', 'ParamMouseY', 'ParamAngleX', 'ParamAngleY', 'ParamAngleZ',
      'ParamEyeBallX', 'ParamEyeBallY',
    ];

    for (const id of ids) {
      const range = renderer.range(id);
      if (!range) continue;
      const [min, max] = range;
      let value;

      if (id.endsWith('Z')) {
        const dragX = 2 * xRatio - 1;
        const dragY = 1 - 2 * yRatio;
        value = dragX * dragY * min;
      } else {
        let ratio = id.endsWith('X') ? xRatio : yRatio;
        if (state.globalReverse && id === 'ParamMouseX') {
          ratio = 1 - ratio;
        }
        /*
         * ============================================================
         * ⚠️ 左右轴（X）用 `min + ratio * (max - min)`，上下轴（Y）用反过来的写法
         * ============================================================
         *
         * 原来两个轴都写成 `max - ratio * (max - min)`（照搬自 BongoCat 原版
         * runtime）。对**标准 Cubism 取值范围**来说，那条公式把 X 轴的方向算反了:
         *
         * | 光标位置 | ratio | 原公式给 ParamAngleX(-30..30) | 期望 |
         * |---|---|---|---|
         * | 最左 | 0 | **+30** | -30（头朝左） |
         * | 最右 | 1 | **-30** | +30（头朝右） |
         *
         * 同一份公式也套在 `ParamEyeBallX`（-1..1）上 —— 光标往右时瞳孔会往左，
         * 与头部一起反。用户报的「鼠标移动时头部跟随左右颠倒」就是它。
         *
         * ⚠️ 上下轴（Y）**保持原写法**:它没有同样的反馈（光标在上 → ParamAngleY 取
         * max = 抬头），这次不改，避免把没报问题的方向也翻掉。
         *
         * ⚠️ `ParamAngleZ`（歪头）跟着 X 一起翻:它原本用 `1 - 2 * xRatio`，
         * 与旧的 X 走法一致；X 翻了它不翻的话，光标在左右两端时歪头方向会和转头相反。
         */
        value = id.endsWith('X')
          ? min + ratio * (max - min)
          : max - ratio * (max - min);
      }

      renderer.setOverride(id, value);
    }
  }

  function syncGlobalReverse() {
    // Do not mirror the stage: the cat and background must remain unchanged.
    // Only input mapping and ParamMouseX are horizontally reversed.
    if (renderer && state.pointerActive) {
      applyPointerOverrides(state.cursorX, state.cursorY);
    }
  }

  window.AxonBongoCat = {
    /** 手动切表情（下标对应 model3 的 Expressions 顺序；-1 取消） */
    /**
     * 设置渲染帧率（30..120）。
     *
     * ⚠️ 只是取消/收紧 rAF 的节流，**能不能真跑到 120 取决于屏幕刷新率**；
     * 60Hz 屏设 120 仍然只有 60。
     */
    setFrameRate(fps) {
      const value = Math.max(1, Math.min(240, Number(fps) || 60));
      FRAME_INTERVAL = 1000 / value;
    },

    setExpression(index) {
      applyExpression(index);
    },

    /** 当前表情下标（-1 = 无） */
    expressionIndex() {
      return state.expressionIndex;
    },

    /** 这个模型有哪些表情名（供调试/以后做设置项） */
    expressionNames() {
      return EXPRESSIONS.map((item) => String(item.name || ''));
    },

    /**
     * 播放动作：`motion("Idle", 0)`。
     *
     * @param group 组名（model3 里的键，例如 Idle / CAT_motion_lock）
     * @param index 组内第几条；省略 = 第 0 条
     * @return 是否真的播起来了
     */
    motion(group, index) {
      return renderer && renderer.motionPlayer
        ? renderer.motionPlayer.playedGroup(String(group), index)
        : false;
    },

    /** 这个模型有哪些动作组 */
    motionGroups() {
      return Object.keys(MOTION_GROUPS);
    },

    /** 当前动作状态（调试/探针用） */
    motionState() {
      const player = renderer && renderer.motionPlayer;
      if (!player || !player.current) return null;
      return {
        name: player.current.name,
        time: Number(player.time.toFixed(3)),
        duration: Number(player.duration.toFixed(3)),
        loop: player.loop,
        weight: Number(player.lastWeight.toFixed(3)),
      };
    },

    /**
     * 只给验证台用：手动推进若干秒的渲染（无头 Chrome 的 rAF 不一定跑得够多）。
     * 没打开 `__BONGO_DEBUG` 时是空操作。
     */
    debugTick(seconds) {
      if (!window.__BONGO_DEBUG || !renderer) return false;
      renderer.render(Math.max(0.001, Number(seconds) || 0.016));
      return true;
    },

    /** 只给验证台用：直接改一个参数覆盖值，观察模型反应 */
    debugParam(id, value) {
      if (!window.__BONGO_DEBUG || !renderer) return false;
      renderer.setOverride(String(id), Number(value));
      return true;
    },
    key(key, pressed) {
      const rawValue = String(key || '');
      /*
       * ⚠️ 组合键匹配要用**原始键名**（`rawValue`）:表情绑定里的键名来自
       * 包配置的虚拟键码，与 `inputKey()` 的左右手镜像无关。
       */
      if (pressed) state.pressedKeys.add(rawValue);
      else state.pressedKeys.delete(rawValue);
      evaluateExpressionBindings();
      evaluateMotionBindings(pressed);

      /* 手部贴图：按下/松开都要重算（同一侧只显示最后按下的那个键） */
      if (pressed) pressedKeyOrder.push(rawValue);
      else {
        const at = pressedKeyOrder.indexOf(rawValue);
        if (at >= 0) pressedKeyOrder.splice(at, 1);
      }
      applyHandSprites();
      if (pressed) toggleFaceOnPress();
      applyFaceSprites();
      applyGamepadStickParameters();

      const rawSide = keySide(rawValue);
      if (!rawSide) return;

      const value = inputKey(rawValue);
      const side = keySide(value) || rawSide;

      // Reverse the input target, not the cat/background canvas.
      if (pressed) {
        if (side === 'left') state.leftKey = value;
        else state.rightKey = value;
        setOverlay(side, value);
      } else if (side === 'left' && state.leftKey === value) {
        state.leftKey = null;
        setOverlay(side, null);
      } else if (side === 'right' && state.rightKey === value) {
        state.rightKey = null;
        setOverlay(side, null);
      }

      syncHandOverrides();
    },

    mouseButtons(mask) {
      state.mouseButtons = Number(mask) || 0;
      if (!renderer) return;
      // The keyboard model currently has no ParamMouse*Down parameter, but the source sends these
      // values; setOverride intentionally becomes a no-op when the parameter is absent.
      renderer.setOverride('ParamMouseLeftDown', (state.mouseButtons & 1) ? 1 : 0);
      renderer.setOverride('ParamMouseRightDown', (state.mouseButtons & 2) ? 1 : 0);
    },

    /** 手柄摇杆：四个轴各 -1..1（Y 向上为正） */
    gamepadAxes(lx, ly, rx, ry) {
      const clampAxis = (value) => Math.max(-1, Math.min(1, Number(value) || 0));
      state.stickLeft = { x: clampAxis(lx), y: clampAxis(ly) };
      state.stickRight = { x: clampAxis(rx), y: clampAxis(ry) };
      applyGamepadStickParameters();
    },

    mouseDelta(dx, dy, screenWidth, screenHeight) {
      const width = Math.max(1, Number(screenWidth) || DESIGN_WIDTH);
      const height = Math.max(1, Number(screenHeight) || DESIGN_HEIGHT);
      state.targetX = Math.max(0, Math.min(1, state.targetX + (Number(dx) || 0) / width));
      state.targetY = Math.max(0, Math.min(1, state.targetY + (Number(dy) || 0) / height));
      state.pointerActive = true;
    },

    pointerRatio(x, y) {
      state.targetX = Math.max(0, Math.min(1, Number(x) || 0));
      state.targetY = Math.max(0, Math.min(1, Number(y) || 0));
      state.pointerActive = true;
    },

    setGlobalReverse(enabled) {
      state.globalReverse = Boolean(enabled);
      syncGlobalReverse();
    },

    clear() {
      state.leftKey = null;
      state.rightKey = null;
      state.mouseButtons = 0;
      state.cursorX = state.targetX = 0.5;
      state.cursorY = state.targetY = 0.5;
      state.pointerActive = false;
      setOverlay('left', null);
      setOverlay('right', null);
      if (renderer) {
        renderer.clearOverrides();
        syncHandOverrides();
      }
    },

    isReady() {
      return state.ready;
    },
  };

  function updatePointer(deltaMs) {
    if (!state.pointerActive || !renderer) return;
    const alpha = 1 - DAMPING_DECAY ** (deltaMs / (1000 / 60));
    const dx = state.targetX - state.cursorX;
    const dy = state.targetY - state.cursorY;

    if (Math.hypot(dx, dy) < 0.0001) {
      state.cursorX = state.targetX;
      state.cursorY = state.targetY;
    } else {
      state.cursorX += dx * alpha;
      state.cursorY += dy * alpha;
    }
    applyPointerOverrides(state.cursorX, state.cursorY);
  }

  function tick(now) {
    animationHandle = requestAnimationFrame(tick);
    if (!renderer) return;
    if (lastFrameTime && now - lastFrameTime < FRAME_INTERVAL - 0.5) return;

    const deltaMs = Math.min(100, Math.max(0.1, lastFrameTime ? now - lastFrameTime : FRAME_INTERVAL));
    lastFrameTime = now;
    updatePointer(deltaMs);
    renderer.setEyeBlink(eyeBlinkValue(now));
    renderer.render(deltaMs / 1000);
  }

  function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(gl.getShaderInfoLog(shader) || 'shader compile failed');
    }
    return shader;
  }

  /* ---------------- 动作：曲线求值 ---------------- */

  function cubicBezierValue(t0, v0, c1t, c1v, c2t, c2v, t1, v1, time) {
    const x = (s) => {
      const u = 1 - s;
      return u * u * u * t0 + 3 * u * u * s * c1t + 3 * u * s * s * c2t + s * s * s * t1;
    };
    const y = (s) => {
      const u = 1 - s;
      return u * u * u * v0 + 3 * u * u * s * c1v + 3 * u * s * s * c2v + s * s * s * v1;
    };

    let low = 0;
    let high = 1;
    let s = 0.5;
    for (let i = 0; i < 30; i++) {
      s = (low + high) / 2;
      if (x(s) < time) low = s;
      else high = s;
    }
    return y(s);
  }

  /**
   * 在一条曲线（`Segments` 一维数组）上取 time 处的值。
   *
   * 段格式：
   * - `[0, t0,v0, t1,v1, …]` 折线
   * - `[1, t0,v0, c1t,c1v, c2t,c2v, t1,v1]` 一段贝塞尔（连续多段各带 type=1）
   * - `[2, t0,v0, t1,v1]` 阶梯
   * - `[3, t0,v0, t1,v1]` 反向阶梯
   */
  function sampleMotionCurve(segments, time) {
    if (!segments || segments.length < 3) return null;
    let i = 0;

    while (i < segments.length) {
      const type = segments[i];

      if (type === 0) {
        /* 折线：找夹住 time 的两个点 */
        let previousT = segments[i + 1];
        let previousV = segments[i + 2];
        if (time <= previousT) return previousV;

        let j = i + 3;
        while (j + 1 < segments.length) {
          const nextT = segments[j];
          const nextV = segments[j + 1];
          if (time <= nextT) {
            const span = Math.max(1e-6, nextT - previousT);
            return previousV + (nextV - previousV) * ((time - previousT) / span);
          }
          previousT = nextT;
          previousV = nextV;
          j += 2;
        }
        return previousV;
      }

      if (type === 1) {
        const t0 = segments[i + 1];
        const v0 = segments[i + 2];
        const c1t = segments[i + 3];
        const c1v = segments[i + 4];
        const c2t = segments[i + 5];
        const c2v = segments[i + 6];
        const t1 = segments[i + 7];
        const v1 = segments[i + 8];
        if (time >= t0 && time <= t1) {
          return cubicBezierValue(t0, v0, c1t, c1v, c2t, c2v, t1, v1, time);
        }
        i += 8;
        continue;
      }

      if (type === 2 || type === 3) {
        const t0 = segments[i + 1];
        const v0 = segments[i + 2];
        const t1 = segments[i + 3];
        const v1 = segments[i + 4];
        if (time <= t1) return type === 2 ? v0 : (time < t1 ? v0 : v1);
        i += 5;
        continue;
      }

      /* 认不出的段类型：不再往下走，避免死循环 */
      break;
    }
    return null;
  }

  /** 一条动作的播放状态（时间、淡入淡出、循环） */
  class MotionPlayer {
    constructor() {
      this.current = null;
      this.time = 0;
      this.duration = 0;
      this.loop = false;
      this.lastWeight = 0;
    }

    playedGroup(group, index, loop) {
      const ids = MOTION_GROUPS[group];
      if (!Array.isArray(ids) || ids.length === 0) return false;
      const position = index === undefined || index === null ? 0 : Math.max(0, Math.min(ids.length - 1, index | 0));
      const motionIndex = ids[position];
      if (motionIndex < 0 || motionIndex >= MOTIONS.length) return false;
      return this.playMotion(MOTIONS[motionIndex], loop);
    }

    playMotion(motion, loop) {
      const json = motion && motion.json;
      if (!json) return false;
      this.current = motion;
      this.time = 0;
      this.duration = Math.max(0, Number(json.Meta && json.Meta.Duration) || 0);
      /*
       * ⚠️ 不能 `|| this.loop`：那样上一次的循环状态会污染这一次。
       * 不传 loop 时才用动作自己的 `Meta.Loop`。
       */
      this.loop = loop === undefined ? Boolean(json.Meta && json.Meta.Loop) : Boolean(loop);
      this.lastWeight = 0;
      return this.duration > 0;
    }

    apply(renderer, deltaSeconds) {
      if (!this.current) return;
      const motion = this.current;
      const json = motion.json;
      const curves = Array.isArray(json.Curves) ? json.Curves : [];

      this.time += Math.max(0, deltaSeconds);
      if (this.duration <= 0) {
        this.current = null;
        return;
      }
      if (this.time >= this.duration) {
        if (this.loop) this.time %= this.duration;
        else {
          this.current = null;
          return;
        }
      }

      const fadeIn = Math.max(0.05, Number(motion.fadeIn) || 0.5);
      const fadeOut = Math.max(0.05, Number(motion.fadeOut) || 0.5);
      let weight = 1;
      if (this.time < fadeIn) weight = this.time / fadeIn;
      if (!this.loop && this.time > this.duration - fadeOut) {
        weight = Math.min(weight, (this.duration - this.time) / fadeOut);
      }
      weight = Math.max(0, Math.min(1, weight));
      this.lastWeight = weight;
      if (weight <= 0) return;

      for (const curve of curves) {
        if (!curve || curve.Target !== 'Parameter') continue;
        const index = renderer.paramIndex.get(String(curve.Id || ''));
        if (index === undefined) continue;
        const value = sampleMotionCurve(curve.Segments, this.time);
        if (value === null || !Number.isFinite(value)) continue;
        const current = renderer.parameters.values[index];
        renderer.parameters.values[index] = renderer.clamp(
          index,
          current * (1 - weight) + value * weight,
        );
      }
    }
  }

  /* ---------------- 物理：参数归一化 / 方向计算 ---------------- */

  function physicsClamp(value, min, max) {
    const lo = Math.min(min, max);
    const hi = Math.max(min, max);
    return Math.max(lo, Math.min(hi, value));
  }

  /**
   * 把参数值映射到 Normalization 区间。
   *
   * ⚠️ 最后一行**照抄 Cubism 的符号约定**：未勾 Reflect 时返回负值
   * （参数增大 = 重力的反方向），不要"顺手改成正数"，否则物理方向会反。
   */
  function normalizeParameterValue(
    value, parameterMin, parameterMax, parameterDefault,
    normMin, normMax, normDefault, inverted,
  ) {
    const minValue = Math.min(parameterMin, parameterMax);
    const maxValue = Math.max(parameterMin, parameterMax);
    const clamped = physicsClamp(Number(value) || 0, minValue, maxValue);
    const minNorm = Math.min(normMin, normMax);
    const maxNorm = Math.max(normMin, normMax);
    const middleNorm = Number(normDefault) || 0;
    const middleValue = minValue + (maxValue - minValue) / 2;
    const offset = clamped - middleValue;

    let result = middleNorm;
    if (offset > 0) {
      const length = maxNorm - middleNorm;
      const span = maxValue - middleValue;
      if (span !== 0) result = middleNorm + offset * (length / span);
    } else if (offset < 0) {
      const length = minNorm - middleNorm;
      const span = minValue - middleValue;
      if (span !== 0) result = middleNorm + offset * (length / span);
    }
    return inverted ? result : -result;
  }

  function directionToRadian(from, to) {
    const a = Math.atan2(from.y, from.x);
    const b = Math.atan2(to.y, to.x);
    let radian = b - a;
    while (radian < -Math.PI) radian += Math.PI * 2;
    while (radian > Math.PI) radian -= Math.PI * 2;
    return radian;
  }

  /**
   * 一轮物理。每个 PhysicsSetting 一条粒子链（摆锤）。
   *
   * 结构：Input（参数 → 位移/角度）→ Particles（链式积分）→ Output（位移/角度 → 参数）。
   */
  class PhysicsRig {
    constructor(json) {
      /*
       * ⚠️ 不要用 `Meta.Fps`：Mver 的 physics3 里基本**没有**这个字段，
       * 之前的默认值 30 会让头发每 2 帧才更新一次 —— 60Hz 画面里就是
       * "一抽一抽"。物理按 60Hz 固定步长跑，和渲染帧率对齐。
       */
      this.fps = 60;
      const list = json && Array.isArray(json.PhysicsSettings) ? json.PhysicsSettings : [];
      this.settings = list.map((raw) => this.parseSetting(raw)).filter((s) => s.particles.length > 1);
      this.accumulator = 0;
    }

    parseSetting(raw) {
      const normalization = raw.Normalization || {};
      const positionNorm = normalization.Position || { Minimum: -10, Maximum: 10, DefaultValue: 0 };
      const angleNorm = normalization.Angle || { Minimum: -10, Maximum: 10, DefaultValue: 0 };

      const particles = (raw.Vertices || raw.Particles || []).map((vertex) => ({
        radius: Math.max(0, Number(vertex.Radius) || 0),
        mobility: Number(vertex.Mobility) || 1,
        delay: Number(vertex.Delay) || 1,
        acceleration: Number(vertex.Acceleration) || 1,
        position: { x: 0, y: 0 },
        lastPosition: { x: 0, y: 0 },
        velocity: { x: 0, y: 0 },
        /*
         * ⚠️ 初始重力是 (0, 1)，不是 (0, -1)。
         * 官方 `initialize()` 写的是 `(0,-1)` 之后立刻 `*= -1`，等价于 (0,1)；
         * 漏掉这一步，第一帧会按 π 旋转粒子链，头发/尾巴会直接贴在极限角上。
         */
        lastGravity: { x: 0, y: 1 },
      }));

      /* 初始姿态：第 0 个粒子在原点，之后沿 -Y 串起来 */
      for (let i = 1; i < particles.length; i++) {
        particles[i].position.x = particles[i - 1].position.x;
        particles[i].position.y = particles[i - 1].position.y + particles[i].radius;
        particles[i].lastPosition.x = particles[i].position.x;
        particles[i].lastPosition.y = particles[i].position.y;
      }

      return {
        inputs: Array.isArray(raw.Input) ? raw.Input : [],
        outputs: Array.isArray(raw.Output) ? raw.Output : [],
        positionNorm,
        angleNorm,
        particles,
      };
    }

    /** 先跑若干步让链子稳定，避免第一帧"弹一下" */
    stabilize(renderer, steps = 20) {
      const step = 1 / this.fps;
      for (let i = 0; i < steps; i++) this.update(renderer, step);
      this.accumulator = 0;
    }

    /**
     * 单步推进（由 CoreRenderer 的固定步长累加器调用）。
     *
     * ⚠️ 这里**不再自己累加**：以前 CoreRenderer 和 PhysicsRig 各有一个累加器，
     * 两次取整叠加会导致"有时走两步、有时不走"，表现为抽搐。
     */
    evaluate(renderer, deltaSeconds) {
      if (this.settings.length === 0) return;
      this.update(renderer, Math.max(1 / 240, Math.min(0.1, deltaSeconds)));
    }

    update(renderer, deltaSeconds) {
      const parameters = renderer.parameters;
      const index = renderer.paramIndex;

      for (const setting of this.settings) {
        let translateX = 0;
        let translateY = 0;
        let totalAngle = 0;

        for (const input of setting.inputs) {
          const id = String((input.Source && input.Source.Id) || '');
          const parameterIndex = index.get(id);
          if (parameterIndex === undefined) continue;

          const weight = (Number(input.Weight) || PHYSICS_MAX_WEIGHT) / PHYSICS_MAX_WEIGHT;
          const norm = input.Type === 'Angle' ? setting.angleNorm : setting.positionNorm;
          const value = normalizeParameterValue(
            parameters.values[parameterIndex],
            parameters.minimumValues[parameterIndex],
            parameters.maximumValues[parameterIndex],
            parameters.defaultValues[parameterIndex],
            Number(norm.Minimum) || 0,
            Number(norm.Maximum) || 0,
            /* ⚠️ physics3 的键名是 `Default`（不是 DefaultValue）；两种都兼容 */
            Number(norm.Default ?? norm.DefaultValue) || 0,
            Boolean(input.Reflect),
          );

          if (input.Type === 'X') translateX += value * weight;
          else if (input.Type === 'Y') translateY += value * weight;
          else if (input.Type === 'Angle') totalAngle += value * weight;
        }

        /* 照官方：把总位移按 -角度 旋转 */
        const angleRadian = (-totalAngle * Math.PI) / 180;
        const rotatedX = translateX * Math.cos(angleRadian) - translateY * Math.sin(angleRadian);
        const rotatedY = translateX * Math.sin(angleRadian) + translateY * Math.cos(angleRadian);

        const particles = setting.particles;
        particles[0].position.x = rotatedX;
        particles[0].position.y = rotatedY;

        const gravity = { x: Math.sin((totalAngle * Math.PI) / 180), y: Math.cos((totalAngle * Math.PI) / 180) };
        const gravityLength = Math.hypot(gravity.x, gravity.y) || 1;
        gravity.x /= gravityLength;
        gravity.y /= gravityLength;

        const threshold = PHYSICS_MOVEMENT_THRESHOLD * (Number(setting.positionNorm.Maximum) || 10);

        for (let i = 1; i < particles.length; i++) {
          const particle = particles[i];
          const previous = particles[i - 1];

          const forceX = gravity.x * particle.acceleration;
          const forceY = gravity.y * particle.acceleration;

          particle.lastPosition.x = particle.position.x;
          particle.lastPosition.y = particle.position.y;

          const delay = particle.delay * deltaSeconds * 30;
          const radian = directionToRadian(particle.lastGravity, gravity) / PHYSICS_AIR_RESISTANCE;

          let dirX = particle.position.x - previous.position.x;
          let dirY = particle.position.y - previous.position.y;
          const cos = Math.cos(radian);
          const sin = Math.sin(radian);
          const rotatedDirX = cos * dirX - dirY * sin;
          const rotatedDirY = sin * dirX + dirY * cos;

          let x = previous.position.x + rotatedDirX;
          let y = previous.position.y + rotatedDirY;

          x += particle.velocity.x * delay + forceX * delay * delay;
          y += particle.velocity.y * delay + forceY * delay * delay;

          let length = Math.hypot(x - previous.position.x, y - previous.position.y);
          if (length <= 0) length = 1;
          const unitX = (x - previous.position.x) / length;
          const unitY = (y - previous.position.y) / length;

          particle.position.x = previous.position.x + unitX * particle.radius;
          particle.position.y = previous.position.y + unitY * particle.radius;

          if (Math.abs(particle.position.x) < threshold) particle.position.x = 0;

          if (delay !== 0) {
            particle.velocity.x =
              ((particle.position.x - particle.lastPosition.x) / delay) * particle.mobility;
            particle.velocity.y =
              ((particle.position.y - particle.lastPosition.y) / delay) * particle.mobility;
          }

          particle.lastGravity.x = gravity.x;
          particle.lastGravity.y = gravity.y;
        }

        for (const output of setting.outputs) {
          const vertexIndex = Number(output.VertexIndex) || 0;
          if (vertexIndex < 1 || vertexIndex >= particles.length) continue;

          const destination = String((output.Destination && output.Destination.Id) || '');
          const parameterIndex = index.get(destination);
          if (parameterIndex === undefined) continue;

          const previous = particles[vertexIndex - 1].position;
          const current = particles[vertexIndex].position;
          const translationX = current.x - previous.x;
          const translationY = current.y - previous.y;

          const type = output.Type;
          let value;
          if (type === 'X') {
            value = translationX;
          } else if (type === 'Y') {
            value = translationY;
          } else {
            let parentGravity;
            if (vertexIndex >= 2) {
              const grand = particles[vertexIndex - 2].position;
              parentGravity = { x: previous.x - grand.x, y: previous.y - grand.y };
            } else {
              /*
               * 第 1 个粒子：参考方向就是"当前重力方向"。
               *
               * ⚠️ 不能取反：链子的初始姿态是 y + radius，重力方向 (0,1) 与它同向；
               * 取反会得到 π，角度输出直接顶到最大/最小（表现为头发永远贴在极限位置）。
               */
              parentGravity = { x: gravity.x, y: gravity.y };
            }
            value = directionToRadian(parentGravity, { x: translationX, y: translationY });
          }
          if (output.Reflect) value *= -1;

          if (window.__BONGO_DEBUG) {
            this.debug = this.debug || {};
            this.debug[destination] = Number(value.toFixed(4));
          }

          const scale = Number(output.Scale) || 0;
          const weight = (Number(output.Weight) || PHYSICS_MAX_WEIGHT) / PHYSICS_MAX_WEIGHT;
          const min = parameters.minimumValues[parameterIndex];
          const max = parameters.maximumValues[parameterIndex];
          const target = physicsClamp(value * scale, min, max);
          parameters.values[parameterIndex] =
            parameters.values[parameterIndex] * (1 - weight) + target * weight;
        }
      }
    }
  }

  class CoreRenderer {
    constructor(core, model, images) {
      this.core = core;
      this.model = model;
      this.drawables = model.drawables;
      this.parameters = model.parameters;
      this.canvasInfo = model.canvasinfo;
      this.paramIndex = new Map();
      this.overrides = new Map();
      /* 当前表情的参数列表；null = 没有表情（见 applyExpression） */
      this.expression = null;
      this.defaults = Array.from(this.parameters.defaultValues);
      this.eyeBlink = 1;
      this.elapsedSeconds = 0;
      /* 物理：没有 physics3 就是 null（内置模型与旧页面行为完全不变） */
      this.physics = PHYSICS_JSON ? new PhysicsRig(PHYSICS_JSON) : null;
    /*
     * 物理固定步长（Cubism 物理是按固定 dt 设计的）。
     * 之前直接把每帧的 deltaSeconds 丢进去：帧时间一抖，头发就"一抽一抽"。
     */
    this.physicsStep = 1 / 60;
    this.physicsAccumulator = 0;
      /* 动作：没有 motion3 就是 null */
      this.motionPlayer = MOTIONS.length > 0 ? new MotionPlayer() : null;

      for (let i = 0; i < this.parameters.count; i++) {
        this.paramIndex.set(String(this.parameters.ids[i]), i);
      }

      const gl = canvas.getContext('webgl', {
        alpha: true,
        antialias: true,
        stencil: true,
        premultipliedAlpha: true,
        preserveDrawingBuffer: false,
      });
      if (!gl) throw new Error('WebGL unavailable');
      this.gl = gl;
      this.program = this.createProgram(false);
      this.maskProgram = this.createProgram(true);
      this.textures = images.map((image) => this.createTexture(image));
      this.positionBuffer = gl.createBuffer();
      this.uvBuffer = gl.createBuffer();
      this.indexBuffer = gl.createBuffer();
      this.contentBounds = this.calculateContentBounds();
      this.resize();
      addEventListener('resize', () => this.resize(), { passive: true });
    }

    calculateContentBounds() {
      const originX = Number(this.canvasInfo.CanvasOriginX) || 0;
      const originY = Number(this.canvasInfo.CanvasOriginY) || 0;
      const ppu = Math.max(0.0001, Number(this.canvasInfo.PixelsPerUnit) || 1);
      let minX = Infinity;
      let minY = Infinity;
      let maxX = -Infinity;
      let maxY = -Infinity;

      for (let i = 0; i < this.drawables.count; i++) {
        if (this.drawables.opacities && this.drawables.opacities[i] <= 0.00001) continue;
        const positions = this.drawables.vertexPositions[i];
        if (!positions) continue;
        for (let p = 0; p + 1 < positions.length; p += 2) {
          const x = originX + positions[p] * ppu;
          const y = originY - positions[p + 1] * ppu;
          if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }

      if (!Number.isFinite(minX) || maxX <= minX || maxY <= minY) return null;
      return { minX, minY, maxX, maxY, width: maxX - minX, height: maxY - minY };
    }

    createProgram(mask) {
      const gl = this.gl;
      const vertex = `
        attribute vec2 aPosition;
        attribute vec2 aUv;
        varying vec2 vUv;
        uniform vec4 uCanvas;
        uniform vec2 uViewport;
        void main() {
          float px = uCanvas.x + aPosition.x * uCanvas.z;
          float py = uCanvas.y - aPosition.y * uCanvas.z;
          gl_Position = vec4(px / uCanvas.w * 2.0 - 1.0, 1.0 - py / uViewport.y * 2.0, 0.0, 1.0);
          vUv = aUv;
        }
      `;
      const fragment = mask ? `
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        void main() {
          if (texture2D(uTexture, vUv).a < 0.01) discard;
          gl_FragColor = vec4(1.0);
        }
      ` : `
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTexture;
        uniform float uOpacity;
        void main() {
          gl_FragColor = texture2D(uTexture, vUv) * uOpacity;
        }
      `;

      const program = gl.createProgram();
      gl.attachShader(program, compileShader(gl, gl.VERTEX_SHADER, vertex));
      gl.attachShader(program, compileShader(gl, gl.FRAGMENT_SHADER, fragment));
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error(gl.getProgramInfoLog(program) || 'program link failed');
      }
      return program;
    }

    createTexture(image) {
      const gl = this.gl;
      const texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      return texture;
    }

    resize() {
      const dpr = Math.max(1, devicePixelRatio || 1);
      const width = Math.max(1, Math.round(innerWidth * dpr));
      const height = Math.max(1, Math.round(innerHeight * dpr));
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      this.pixelWidth = width;
      this.pixelHeight = height;

      const stageScale = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
      const stageOffsetX = (width - DESIGN_WIDTH * stageScale) * 0.5;
      const stageOffsetY = (height - DESIGN_HEIGHT * stageScale) * 0.5;

      if (IMPORTED_MODEL && MODEL_LAYOUT === 'full') {
        // Skin packs with their own background are normalized by their Cubism canvas, so the
        // model and that background scale as one 612x354 composition.
        const modelWidth = Math.max(1, Number(this.canvasInfo.CanvasWidth) || DESIGN_WIDTH);
        const modelHeight = Math.max(1, Number(this.canvasInfo.CanvasHeight) || DESIGN_HEIGHT);
        const modelToDesign = Math.min(DESIGN_WIDTH / modelWidth, DESIGN_HEIGHT / modelHeight);
        const designLeft = (DESIGN_WIDTH - modelWidth * modelToDesign) * 0.5;
        const designTop = (DESIGN_HEIGHT - modelHeight * modelToDesign) * 0.5;
        this.scale = stageScale * modelToDesign;
        this.offsetX = stageOffsetX + designLeft * stageScale;
        this.offsetY = stageOffsetY + designTop * stageScale;
        return;
      }

      /*
       * 手部贴图包：整张 1400×1400 手图是按**设计画布**画的，
       * 所以模型也必须按画布等比例铺（不能按内容包围盒放大），否则手会对不上。
       */
      if (HANDS) {
        const canvasWidth = Math.max(1, Number(this.canvasInfo.CanvasWidth) || DESIGN_WIDTH);
        const canvasHeight = Math.max(1, Number(this.canvasInfo.CanvasHeight) || DESIGN_HEIGHT);
        const designWidth = MVER_DESIGN ? Math.max(1, Number(MVER_DESIGN.width) || canvasWidth) : canvasWidth;
        const designHeight = MVER_DESIGN ? Math.max(1, Number(MVER_DESIGN.height) || canvasHeight) : canvasHeight;
        /*
         * 设计画布 → 视口
         */
        const designScale = Math.min(width / designWidth, height / designHeight);
        const designX = (width - designWidth * designScale) * 0.5;
        const designY = (height - designHeight * designScale) * 0.5;
        this.designScale = designScale;
        this.designX = designX;
        this.designY = designY;
        this.handOffsetX = MVER_DESIGN ? (Number(MVER_DESIGN.handOffsetX) || 0) : 0;
        this.handOffsetY = MVER_DESIGN ? (Number(MVER_DESIGN.handOffsetY) || 0) : 0;

        /*
         * 模型按设计画布 contain（和 Axon 完全一致）。
         *
         * ⚠️ 不要乘 `l2d_correct`：它不是"模型放大倍数"，而是 **Mver 的屏幕缩放矫正**
         * （Windows 显示缩放 125%/150% 之类），官方文档的原话是"矫正桌面分辨率/
         * 缩放值"。卡提的 correct=1.987，乘上去模型直接 2 倍、被裁掉一大半 —— 实测踩过。
         *
         * `l2d_offset` 同理先不参与布局（Axon 也是把它夹在 letterbox 内，
         * 我们的窗口比例已经等于设计画布，夹完就是 0）。
         */
        const modelFit = Math.min(designWidth / canvasWidth, designHeight / canvasHeight);
        this.scale = modelFit * designScale;
        this.offsetX = designX + (designWidth - canvasWidth * modelFit) * 0.5 * designScale;
        this.offsetY = designY + (designHeight - canvasHeight * modelFit) * 0.5 * designScale;
        reportContentAspect(designWidth, designHeight);
        return;
      }

      if (IMPORTED_MODEL && this.contentBounds) {
        /*
         * ============================================================
         * 导入模型:按**内容实际包围盒**铺满窗口
         * ============================================================
         * 以前是"把内容塞进设计坐标里的 CAT_TARGET 矩形"，于是窗口四边
         * 留出大片透明区域（用户报的"左、右、下多一截空气"）。
         *
         * ⚠️ 现在改成**直接在设备像素里铺满**:
         * 窗口尺寸由 Kotlin 侧按"内容比例"算（比例就是下面回报上去的），
         * 所以两者比例一致时 `Math.min` 的两个候选值相等 —— 既不裁切、也不留白。
         *
         * 第一次加载时比例还没测到（窗口是 612×354），这里会短暂留白；
         * 回报之后窗口按内容比例重排，下一帧就正好铺满。
         */
        const b = this.contentBounds;
        const fit = Math.min(width / Math.max(1, b.width), height / Math.max(1, b.height));
        this.scale = fit;
        this.offsetX = (width - b.width * fit) * 0.5 - b.minX * fit;
        this.offsetY = (height - b.height * fit) * 0.5 - b.minY * fit;

        /* 把内容比例回报给原生侧（只回报一次，之后的窗口重排会再次进来） */
        reportContentAspect(b.width, b.height);
        return;
      }

      // Bundled BongoCat model: preserve the original one-to-one 612x354 layout.
      this.scale = stageScale;
      this.offsetX = stageOffsetX;
      this.offsetY = stageOffsetY;
    }

    range(id) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return null;
      return [this.parameters.minimumValues[index], this.parameters.maximumValues[index]];
    }

    clamp(index, value) {
      return Math.max(
        this.parameters.minimumValues[index],
        Math.min(this.parameters.maximumValues[index], Number(value) || 0),
      );
    }

    setOverride(id, value) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.overrides.set(index, this.clamp(index, value));
    }

    clearOverrides() {
      this.overrides.clear();
    }

    setEyeBlink(value) {
      this.eyeBlink = Math.max(0, Math.min(1, value));
    }

    setValue(id, value) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.parameters.values[index] = this.clamp(index, value);
    }

    addValue(id, value, weight) {
      const index = this.paramIndex.get(id);
      if (index === undefined) return;
      this.parameters.values[index] = this.clamp(
        index,
        this.parameters.values[index] + value * weight,
      );
    }

    prepareParameters(deltaSeconds) {
      this.elapsedSeconds += deltaSeconds;
      for (let i = 0; i < this.parameters.count; i++) {
        this.parameters.values[i] = this.defaults[i];
      }

      /*
       * 动作在呼吸之前：动作提供"基础姿势"，呼吸再往上叠。
       * 顺序反了的话，动作会被呼吸的加值顶偏，看起来像"动作幅度不对"。
       */
      if (this.motionPlayer) this.motionPlayer.apply(this, deltaSeconds);

      // EffectController.setupBreath() from easy-live2d v0.4.4.
      const t = this.elapsedSeconds * 2 * Math.PI;
      this.addValue('ParamAngleX', 15 * Math.sin(t / 6.5345), 0.5);
      this.addValue('ParamAngleY', 8 * Math.sin(t / 3.5345), 0.5);
      this.addValue('ParamAngleZ', 10 * Math.sin(t / 5.5345), 0.5);
      this.addValue('ParamBodyAngleX', 4 * Math.sin(t / 15.5345), 0.5);
      this.addValue('ParamBreath', 0.5 + 0.5 * Math.sin(t / 3.2345), 0.5);

      // CubismEyeBlink is applied before persistent user parameter overrides in easy-live2d.
      this.setValue('ParamEyeLOpen', this.eyeBlink);
      this.setValue('ParamEyeROpen', this.eyeBlink);

      // BongoCat's setParameterValueById values have highest priority and persist across frames.
      for (const [index, value] of this.overrides) {
        this.parameters.values[index] = value;
      }

      this.applyExpression();

      /*
       * 物理放在**最后**：它读的是"呼吸/眨眼/覆盖/表情"算完之后的参数，
       * 再把结果写给头发/尾巴这类输出参数。
       */
      if (this.physics) {
        /*
         * ⚠️⚠️ 物理**每帧走一步**，步长 = 这一帧的真实间隔
         * ============================================================
         * 之前固定 `1/60` + 累加器：60fps 时每帧正好一步，所以顺。
         * 但设成 90/120 后，会出现"有的帧走一步、有的帧一步都不走"
         * （90fps 尤其明显：1.5 帧才够一个 60Hz 步长），
         * 饰品位置就变成 0/1/0/1 地跳 —— 看起来就是"抽"。
         *
         * 现在每帧只走一步、步长取真实帧间隔（夹在 1/240..1/30）：
         * - 渲染多少帧，物理就走多少步，**一一对应**，不再有取整跳变；
         * - 物理公式本身按时间积分，步长变细不改变手感；
         * - 掉到 30fps 以下时步长封顶，避免一步太大导致弹簧发散。
         *
         * ⚠️ 不要改回"固定步长 + 累加器"：那正是 90/120 下饰品抽的原因。
         */
        const physicsStep = Math.min(1 / 30, Math.max(1 / 240, deltaSeconds));
        this.physics.evaluate(this, physicsStep);
      }
    }

    /**
     * 应用当前表情。
     *
     * ⚠️ 顺序:在**所有基础值（呼吸/眨眼/覆盖）都算完之后**再加表情，
     * 否则表情会被后面的覆盖值盖掉 —— 表现就是"按了快捷键没反应"。
     *
     * ⚠️ `Blend` 三种含义（Cubism 规范）:
     * - `Add`       → 在当前值上**加**
     * - `Multiply`  → **乘**
     * - `Overwrite` → 直接覆盖
     * 我们的渲染器只有"绝对赋值"，所以在这里手工算出最终值。
     */
    applyExpression() {
      if (!this.expression) return;
      for (const entry of this.expression) {
        const id = entry[0];
        const index = this.paramIndex.get(String(id));
        if (index === undefined) continue;
        const value = Number(entry[1]) || 0;
        const blend = String(entry[2] || 'Add');
        const current = this.parameters.values[index];
        let next;
        if (blend === 'Multiply') next = current * value;
        else if (blend === 'Overwrite') next = value;
        else next = current + value;
        this.parameters.values[index] = this.clamp(index, next);
      }
    }

    bindGeometry(program, drawableIndex) {
      const gl = this.gl;
      const d = this.drawables;
      gl.useProgram(program);

      const positionAttribute = gl.getAttribLocation(program, 'aPosition');
      const uvAttribute = gl.getAttribLocation(program, 'aUv');
      gl.bindBuffer(gl.ARRAY_BUFFER, this.positionBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, d.vertexPositions[drawableIndex], gl.DYNAMIC_DRAW);
      gl.enableVertexAttribArray(positionAttribute);
      gl.vertexAttribPointer(positionAttribute, 2, gl.FLOAT, false, 0, 0);

      gl.bindBuffer(gl.ARRAY_BUFFER, this.uvBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, d.vertexUvs[drawableIndex], gl.DYNAMIC_DRAW);
      gl.enableVertexAttribArray(uvAttribute);
      gl.vertexAttribPointer(uvAttribute, 2, gl.FLOAT, false, 0, 0);

      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.indexBuffer);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, d.indices[drawableIndex], gl.DYNAMIC_DRAW);

      const canvasUniform = gl.getUniformLocation(program, 'uCanvas');
      const viewportUniform = gl.getUniformLocation(program, 'uViewport');
      gl.uniform4f(
        canvasUniform,
        this.canvasInfo.CanvasOriginX * this.scale + this.offsetX,
        this.canvasInfo.CanvasOriginY * this.scale + this.offsetY,
        this.canvasInfo.PixelsPerUnit * this.scale,
        this.pixelWidth,
      );
      gl.uniform2f(viewportUniform, this.pixelWidth, this.pixelHeight);

      const opacityUniform = gl.getUniformLocation(program, 'uOpacity');
      if (opacityUniform !== null) gl.uniform1f(opacityUniform, d.opacities[drawableIndex]);

      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.textures[d.textureIndices[drawableIndex]]);
    }

    draw(drawableIndex, mask) {
      const gl = this.gl;
      const program = mask ? this.maskProgram : this.program;
      this.bindGeometry(program, drawableIndex);
      gl.drawElements(
        gl.TRIANGLES,
        this.drawables.indexCounts[drawableIndex],
        gl.UNSIGNED_SHORT,
        0,
      );
    }

    render(deltaSeconds) {
      const gl = this.gl;
      const d = this.drawables;
      this.prepareParameters(deltaSeconds);
      this.model.update();

      gl.viewport(0, 0, this.pixelWidth, this.pixelHeight);
      gl.clearColor(0, 0, 0, 0);
      gl.clearStencil(0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
      gl.disable(gl.DEPTH_TEST);
      gl.disable(gl.CULL_FACE);
      gl.enable(gl.BLEND);
      gl.blendEquation(gl.FUNC_ADD);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

      const order = Array.from({ length: d.count }, (_, index) => index)
        .sort((a, b) => d.renderOrders[a] - d.renderOrders[b]);

      for (const index of order) {
        if (!this.core.Utils.hasIsVisibleBit(d.dynamicFlags[index])) continue;
        if (d.opacities[index] <= 0.00001) continue;

        const maskCount = d.maskCounts[index];
        if (maskCount > 0) {
          gl.enable(gl.STENCIL_TEST);
          gl.clear(gl.STENCIL_BUFFER_BIT);
          gl.colorMask(false, false, false, false);
          gl.stencilMask(0xff);
          gl.stencilFunc(gl.ALWAYS, 1, 0xff);
          gl.stencilOp(gl.KEEP, gl.KEEP, gl.REPLACE);
          for (let maskIndex = 0; maskIndex < maskCount; maskIndex++) {
            this.draw(d.masks[index][maskIndex], true);
          }

          gl.colorMask(true, true, true, true);
          gl.stencilMask(0x00);
          const inverted = this.core.Utils.hasIsInvertedMaskBit(d.constantFlags[index]);
          gl.stencilFunc(inverted ? gl.NOTEQUAL : gl.EQUAL, 1, 0xff);
          gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
          this.draw(index, false);
          gl.disable(gl.STENCIL_TEST);
          gl.stencilMask(0xff);
        } else {
          this.draw(index, false);
        }
      }

      d.resetDynamicFlags();
    }
  }

  function loadImage(src) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error(`texture load failed: ${src}`));
      image.src = src;
    });
  }

  async function init() {
    try {
      let core;
      for (let attempt = 0; attempt < 300; attempt++) {
        core = window.Live2DCubismCore;
        if (core && core.Moc && core.Model) break;
        await new Promise((resolve) => setTimeout(resolve, 16));
      }
      if (!core || !core.Moc) throw new Error('Cubism Core unavailable');

      const raw = atob(window.__BONGO_KEYBOARD_MOC_BASE64 || '');
      const bytes = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);

      const textureSources = (window.__BONGO_TEXTURES && window.__BONGO_TEXTURES.length)
        ? window.__BONGO_TEXTURES
        : [
            'demomodel2.1024/texture_00.png',
            'demomodel2.1024/texture_01.png',
            'demomodel2.1024/texture_02.png',
          ];
      const textures = await Promise.all(textureSources.map(loadImage));

      const moc = core.Moc.fromArrayBuffer(bytes.buffer);
      if (!moc) throw new Error('moc parse failed');
      const model = core.Model.fromMoc(moc);
      if (!model) throw new Error('model init failed');

      renderer = new CoreRenderer(core, model, textures);
      if (HANDS) {
        console.info('[hands] loaded', JSON.stringify({
          left: (HANDS.left && HANDS.left.bindings ? HANDS.left.bindings.length : 0),
          right: (HANDS.right && HANDS.right.bindings ? HANDS.right.bindings.length : 0),
          hand: (HANDS.hand && HANDS.hand.bindings ? HANDS.hand.bindings.length : 0),
        }));
      }
      /*
       * 有 Idle 组就自动循环播放（Cubism 的标准做法）。
       * 没有 Idle 的包（比如 Mver 键盘猫）保持静止，等按键/外部调用。
       */
      if (renderer.motionPlayer) {
        const idleGroup = Object.keys(MOTION_GROUPS)
          .find((name) => name.toLowerCase() === 'idle');
        if (idleGroup) renderer.motionPlayer.playedGroup(idleGroup, 0);
      }
      if (renderer.physics) renderer.physics.stabilize(renderer);
      /* 验证台用：把 renderer 暴露出来读参数（只有显式打开 __BONGO_DEBUG 才生效） */
      if (window.__BONGO_DEBUG) window.__STHKEY_RENDERER = renderer;
      syncHandOverrides();
      applyHandSprites();
      applyFaceSprites();
      renderer.setOverride('ParamMouseLeftDown', (state.mouseButtons & 1) ? 1 : 0);
      renderer.setOverride('ParamMouseRightDown', (state.mouseButtons & 2) ? 1 : 0);
      fallback.classList.add('hidden');
      state.ready = true;

      if (!animationHandle) animationHandle = requestAnimationFrame(tick);
    } catch (error) {
      console.error('BongoCat source model init failed', error);
      fallback.classList.remove('hidden');
    }
  }

  init();
  animationHandle = requestAnimationFrame(tick);
})();
