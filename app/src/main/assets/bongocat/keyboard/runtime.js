(() => {
  'use strict';

  const DESIGN_WIDTH = 612;
  const DESIGN_HEIGHT = 354;
  const FRAME_INTERVAL = 1000 / 60;
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
  setImageWithFallback(document.getElementById('background'), 'background.png');
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
      renderer.setOverride('CatParamLeftHandDown', 0);
      renderer.setOverride('CatParamRightHandDown', 0);
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
    key(key, pressed) {
      const rawValue = String(key || '');
      /*
       * ⚠️ 组合键匹配要用**原始键名**（`rawValue`）:表情绑定里的键名来自
       * 包配置的虚拟键码，与 `inputKey()` 的左右手镜像无关。
       */
      if (pressed) state.pressedKeys.add(rawValue);
      else state.pressedKeys.delete(rawValue);
      evaluateExpressionBindings();

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
      syncHandOverrides();
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
