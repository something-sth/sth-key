(() => {
  'use strict';

  const DESIGN_WIDTH = 612;
  const DESIGN_HEIGHT = 354;
  const FRAME_INTERVAL = 1000 / 60;
  const DAMPING_DECAY = 0.75;

  // Exact input groups for BongoCat's original standard (mouse + keyboard) model.
  const LEFT_KEYS = new Set([
    'Alt', 'AltGr', 'BackQuote', 'Backspace', 'CapsLock', 'Control', 'ControlLeft',
    'ControlRight', 'Delete', 'Escape', 'Fn', 'KeyA', 'KeyB', 'KeyC', 'KeyD',
    'KeyE', 'KeyF', 'KeyG', 'KeyH', 'KeyI', 'KeyJ', 'KeyK', 'KeyL', 'KeyM',
    'KeyN', 'KeyO', 'KeyP', 'KeyQ', 'KeyR', 'KeyS', 'KeyT', 'KeyU', 'KeyV',
    'KeyW', 'KeyX', 'KeyY', 'KeyZ', 'Meta', 'Num0', 'Num1', 'Num2', 'Num3',
    'Num4', 'Num5', 'Num6', 'Num7', 'Num8', 'Num9', 'Return', 'Shift',
    'ShiftLeft', 'ShiftRight', 'Slash', 'Space', 'Tab',
  ]);
  const RIGHT_KEYS = new Set();


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
    ready: false,
  };

  const stage = document.getElementById('stage');
  const canvas = document.getElementById('live2dCanvas');
  const fallback = document.getElementById('fallback');
  const leftImage = document.getElementById('leftKey');
  const rightImage = document.getElementById('rightKey');

  let renderer = null;
  let lastFrameTime = 0;
  let animationHandle = 0;

  /*
   * ============================================================
   * 纯色背景 + 模型不透明度（与 keyboard/runtime.js 同一套语义）
   * ============================================================
   * 背景垫在最底层（模型、包底图都在它上面）；宽高是**模型内容矩形**的百分比，
   * 以内容中心为轴对称伸缩；模型不透明度只乘在画布与 DOM 贴图上，背景独立。
   */
  let BG_ENABLED = false;
  let BG_WIDTH = 100;
  let BG_HEIGHT = 100;
  let BG_RGB = 0xFFFFFF;
  let BG_OPACITY = 100;
  let BG_RECT = null;
  let MODEL_OPACITY = 1;

  function clampNumber(value, min, max, fallback) {
    const number = Number(value);
    if (!Number.isFinite(number)) return fallback;
    return Math.min(max, Math.max(min, Math.round(number)));
  }

  /*
   * 模型图层容器（整层不透明度）—— 与 keyboard/runtime.js 同一套做法。
   *
   * ⚠️ 模型不透明度必须整组合成一次：逐个元素乘 alpha 会让人透过皮肤
   * 看到眼睛等内部图层（"透视"感）。背景（包底图 / 纯色底）不在容器里。
   */
  /* `#background` 是模型包自带底图，跟着模型不透明度一起淡；纯色底独立 */
  const MODEL_LAYER_IDS = ['background', 'fallback', 'live2dCanvas', 'leftKey', 'rightKey'];
  let modelLayerElement = null;

  function modelLayerRoot() {
    if (modelLayerElement && modelLayerElement.isConnected) return modelLayerElement;
    let wrapper = document.getElementById('modelLayer');
    if (!wrapper) {
      wrapper = document.createElement('div');
      wrapper.id = 'modelLayer';
      wrapper.style.position = 'absolute';
      wrapper.style.inset = '0';
      wrapper.style.pointerEvents = 'none';
      wrapper.style.zIndex = '1';
      wrapper.style.opacity = String(MODEL_OPACITY);
      const container = document.getElementById('stage') || document.body;
      container.appendChild(wrapper);
      for (const id of MODEL_LAYER_IDS) {
        const element = document.getElementById(id);
        if (element && element.parentNode !== wrapper) wrapper.appendChild(element);
      }
    }
    modelLayerElement = wrapper;
    return wrapper;
  }

  let backgroundLayer = null;

  /** 背景层动态创建（不改 index.html，存量模型也能生效） */
  function ensureBackgroundLayer() {
    if (backgroundLayer && backgroundLayer.isConnected) return backgroundLayer;
    let element = document.getElementById('solidBackground');
    if (!element) {
      element = document.createElement('div');
      element.id = 'solidBackground';
      element.style.position = 'absolute';
      element.style.pointerEvents = 'none';
      element.style.zIndex = '0';
      element.style.display = 'none';
      const container = document.getElementById('stage') || document.body;
      container.insertBefore(element, container.firstChild);
    }
    backgroundLayer = element;
    return element;
  }

  function updateBackgroundLayer() {
    const element = ensureBackgroundLayer();
    if (!BG_ENABLED || !BG_RECT) {
      element.style.display = 'none';
      return;
    }
    const dpr = Math.max(1, devicePixelRatio || 1);
    const r = (BG_RGB >> 16) & 0xff;
    const g = (BG_RGB >> 8) & 0xff;
    const b = BG_RGB & 0xff;
    element.style.display = 'block';
    element.style.left = (BG_RECT.x / dpr) + 'px';
    element.style.top = (BG_RECT.y / dpr) + 'px';
    element.style.width = (BG_RECT.w / dpr) + 'px';
    element.style.height = (BG_RECT.h / dpr) + 'px';
    element.style.backgroundColor = `rgba(${r}, ${g}, ${b}, ${BG_OPACITY / 100})`;
  }

  function applyModelOpacity() {
    modelLayerRoot().style.opacity = String(MODEL_OPACITY);
  }

  /*
   * 设计画布图层（包底图 / 键帽 / 封面）默认铺满整个窗口。
   * 背景把窗口撑大后必须钉回设计画布那块矩形，否则桌子/键帽会和模型错位。
   */
  const DESIGN_LAYER_IDS = ['background', 'leftKey', 'rightKey', 'fallback'];

  function syncDesignLayers(rect) {
    const dpr = Math.max(1, devicePixelRatio || 1);
    for (const id of DESIGN_LAYER_IDS) {
      const element = document.getElementById(id);
      if (!element) continue;
      if (!rect) {
        element.style.left = '';
        element.style.top = '';
        element.style.width = '';
        element.style.height = '';
        element.style.right = '';
        element.style.bottom = '';
        continue;
      }
      element.style.left = (rect.x / dpr) + 'px';
      element.style.top = (rect.y / dpr) + 'px';
      element.style.width = (rect.w / dpr) + 'px';
      element.style.height = (rect.h / dpr) + 'px';
      element.style.right = 'auto';
      element.style.bottom = 'auto';
      element.style.objectFit = 'fill';
    }
  }

  /* 渲染开始前就把画布/封面/键帽搬进模型容器（换父节点只在这一刻做，避免闪） */
  modelLayerRoot();

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
      image.removeAttribute('src');
      return;
    }
    image.src = `resources/${side}-keys/${encodeURIComponent(key)}.png`;
    image.classList.remove('hidden');
  }

  function syncHandOverrides() {
    if (!renderer) return;
    renderer.setOverride('CatParamLeftHandDown', state.leftKey ? 1 : 0);
    // Standard source model has no CatParamRightHandDown; its right-side interaction is the mouse.
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
    key(key, pressed) {
      const rawValue = String(key || '');
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
      // Standard BongoCat source model exposes real mouse-button parameters.
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

    /** 纯色背景（语义与 keyboard/runtime.js 完全一致） */
    setBackground(enabled, widthPercent, heightPercent, rgb, opacityPercent) {
      const previousEnabled = BG_ENABLED;
      const previousWidth = BG_WIDTH;
      const previousHeight = BG_HEIGHT;
      BG_ENABLED = Boolean(enabled);
      BG_WIDTH = clampNumber(widthPercent, 20, 200, 100);
      BG_HEIGHT = clampNumber(heightPercent, 20, 200, 100);
      BG_RGB = (Number(rgb) || 0) & 0xFFFFFF;
      BG_OPACITY = clampNumber(opacityPercent, 0, 100, 100);

      /* 只有几何变化才重排画布；只改颜色/透明度时别做无谓的 resize */
      const geometryChanged = previousEnabled !== BG_ENABLED ||
        previousWidth !== BG_WIDTH || previousHeight !== BG_HEIGHT;
      if (geometryChanged && renderer) renderer.resize();
      else updateBackgroundLayer();

      if (previousEnabled !== BG_ENABLED && !window.__sthkeyBgLogged) {
        window.__sthkeyBgLogged = true;
        console.log('[background] ' + (BG_ENABLED ? '开启' : '关闭')
          + ' ' + BG_WIDTH + '%×' + BG_HEIGHT + '% #'
          + BG_RGB.toString(16).toUpperCase().padStart(6, '0')
          + ' ' + BG_OPACITY + '%');
      }
    },

    /**
     * 模型不透明度 0..100（**只影响模型**，背景不受影响）。
     *
     * ⚠️ 不走 Android 的 `view.alpha`：那会把背景一起淡掉。
     * 这里改的是模型容器那一层的 CSS opacity —— 整组合成，观感和旧版一致。
     */
    setModelOpacity(percent) {
      const next = clampNumber(percent, 0, 100, 100);
      if (Math.abs(MODEL_OPACITY - next / 100) < 0.0001) return;
      MODEL_OPACITY = next / 100;
      applyModelOpacity();
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
      this.resize();
      addEventListener('resize', () => this.resize(), { passive: true });
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
      /* 背景 >100% 时窗口已经按百分比变大，内容要按放大后的框 contain（关背景时 1:1） */
      const expandX = BG_ENABLED ? Math.max(1, BG_WIDTH / 100) : 1;
      const expandY = BG_ENABLED ? Math.max(1, BG_HEIGHT / 100) : 1;
      this.scale = Math.min(
        width / (DESIGN_WIDTH * expandX),
        height / (DESIGN_HEIGHT * expandY),
      );
      this.offsetX = (width - DESIGN_WIDTH * this.scale) * 0.5;
      this.offsetY = (height - DESIGN_HEIGHT * this.scale) * 0.5;

      /* 内容矩形 = 设计画布铺进画布后的那块（与上面 scale/offset 一致） */
      const basis = {
        x: this.offsetX,
        y: this.offsetY,
        w: DESIGN_WIDTH * this.scale,
        h: DESIGN_HEIGHT * this.scale,
      };
      if (!BG_ENABLED) {
        BG_RECT = null;
        syncDesignLayers(null);
        updateBackgroundLayer();
        return;
      }

      const bgWidth = basis.w * (BG_WIDTH / 100);
      const bgHeight = basis.h * (BG_HEIGHT / 100);
      const cx = basis.x + basis.w * 0.5;
      const cy = basis.y + basis.h * 0.5;

      /* 窗口尺寸由原生侧按"内容比例 × 百分比"算；被屏幕夹过时才需要整体缩一点 */
      const unionWidth = Math.max(basis.w, bgWidth);
      const unionHeight = Math.max(basis.h, bgHeight);
      const k = Math.min(1, width / Math.max(1, unionWidth), height / Math.max(1, unionHeight));
      if (k < 0.999) {
        this.scale *= k;
        this.offsetX = cx - (cx - this.offsetX) * k;
        this.offsetY = cy - (cy - this.offsetY) * k;
      }

      /* basis 已是画布像素，bgWidth/bgHeight 也是像素 —— 不再乘 scale */
      BG_RECT = {
        x: cx - bgWidth * k * 0.5,
        y: cy - bgHeight * k * 0.5,
        w: bgWidth * k,
        h: bgHeight * k,
      };
      /* 包底图 / 键帽 / 封面钉回设计画布矩形，避免被拉满变大的窗口 */
      syncDesignLayers({
        x: cx - basis.w * k * 0.5,
        y: cy - basis.h * k * 0.5,
        w: basis.w * k,
        h: basis.h * k,
      });
      updateBackgroundLayer();
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

      /*
       * ⚠️⚠️ 绘制顺序：**必须优先 renderOrders**
       * ============================================================
       * Core 5.1.0 的 drawables 里 `drawOrders` 和 `renderOrders` **两个都存在**，
       * 但含义不同：drawOrders 是建模时的描画順，renderOrders 才是 Core 算出来的
       * 实际绘制顺序（同一模型实测排序差异很大，清宵 294/313 位置不同）。
       *
       * 之前为兼容 Core 6 写成 `d.drawOrders || d.renderOrders`，在 Core 5 上会选中
       * drawOrders → 层叠顺序错乱（贴图错位、眼睛被盖住）。Core 6 里 renderOrders
       * 已被移除，所以这里必须反过来：有 renderOrders 用 renderOrders。
       */
      const drawOrders = d.renderOrders || d.drawOrders || [];
      const order = Array.from({ length: d.count }, (_, index) => index)
        .sort((a, b) => drawOrders[a] - drawOrders[b]);

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

      const raw = atob(window.__BONGO_STANDARD_MOC_BASE64 || '');
      const bytes = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);

      const [texture0, texture1, texture2] = await Promise.all([
        loadImage('demomodel.1024/texture_00.png'),
        loadImage('demomodel.1024/texture_01.png'),
        loadImage('demomodel.1024/texture_02.png'),
      ]);

      const moc = core.Moc.fromArrayBuffer(bytes.buffer);
      if (!moc) throw new Error('moc parse failed');
      const model = core.Model.fromMoc(moc);
      if (!model) throw new Error('model init failed');

      renderer = new CoreRenderer(core, model, [texture0, texture1, texture2]);
      syncHandOverrides();
      renderer.setOverride('ParamMouseLeftDown', (state.mouseButtons & 1) ? 1 : 0);
      renderer.setOverride('ParamMouseRightDown', (state.mouseButtons & 2) ? 1 : 0);
      fallback.classList.add('hidden');
      /* 页面就绪前原生侧可能已经推过不透明度/背景参数 —— 这里补一次 */
      applyModelOpacity();
      updateBackgroundLayer();
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
