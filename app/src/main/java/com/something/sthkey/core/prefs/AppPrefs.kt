package com.something.sthkey.core.prefs

import android.content.Context
import android.content.SharedPreferences
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.ui.EditMode
import com.something.sthkey.ui.EditorPanelSide

/**
 * 轻量设置存储（键值型，非配置数据）。
 *
 * 这里只放"应用级开关"，不放按键配置本身：
 * 配置数据以后是 zip 压缩包 + 目录式存储（见 domain/config 的说明），
 * 两者的生命周期不同，混在一起会导致迁移困难。
 */
class AppPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /*
     * ============================================================
     * 采集模式
     *
     * 注意：这里保存的是用户**选择**的模式（可能为"自动"），
     * 与运行时**实际生效**的模式不是一回事——后者由采集层解析后回报。
     * ============================================================
     */

    var keyMode: KeyMode
        get() = KeyMode.fromId(prefs.getString(KEY_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_MODE, value.id).apply()
        }

    /*
     * ============================================================
     * SetupScreen
     * ============================================================
     */

    /** 用户是否已完整走过一次引导；用于"权限齐全直接进主页"的判断之一 */
    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()
        }

    /**
     * 已经确认过的"更新公告"版本号。
     *
     * 存的是当时的 `BuildConfig.VERSION_NAME`：与当前版本不一致
     * （或从未确认过）时才弹公告。因此换版本号即弹新公告，
     * 不需要额外的开关，也不会因为忘记改某个常量而漏弹。
     */
    var announcementVersion: String?
        get() = prefs.getString(KEY_ANNOUNCEMENT_VERSION, null)
        set(value) {
            prefs.edit().putString(KEY_ANNOUNCEMENT_VERSION, value).apply()
        }

    /*
     * ============================================================
     * 当前生效的配置
     *
     * 只存 id，不存配置内容本身：配置内容以后由 zip 包承载，
     * 两者分开存才能让"导入一份别人的配置"变成一个纯文件操作。
     * ============================================================
     */

    /*
     * ============================================================
     * 旧版本的"当前生效的配置"
     *
     * ⚠️ 这个字段**已被废弃，不要在新代码里读写**。
     *
     * "当前配置"这个概念已经从数据层删除了（多悬浮窗之后，每一份开着的配置
     * 都有自己的窗口，"当前"不再有意义）。这里保留唯一的原因是**迁移**：
     * 老用户那份"全局单份悬浮窗坐标"要归到当时正在用的配置上，
     * 而只有这个键记得当时是谁。迁移做完就会把它删掉（见 OverlayLayouts）。
     *
     * 也就是说它是个**只读一次的墓碑**，不是状态。
     * ============================================================
     */

    /** @deprecated 只在 [com.something.sthkey.domain.overlay.OverlayLayouts.migrateLegacy] 里读一次 */
    var activeConfigId: String?
        get() = prefs.getString(KEY_ACTIVE_CONFIG_ID, null)
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_CONFIG_ID, value).apply()
        }

    /** 一次性迁移读完后把墓碑清掉 */
    fun clearLegacyActiveConfigId() {
        prefs.edit().remove(KEY_ACTIVE_CONFIG_ID).apply()
    }

    /**
     * 全部配置的 JSON 文本。
     *
     * 由 [com.something.sthkey.data.config.ConfigStore] 读写，格式见
     * [com.something.sthkey.data.config.JsonConfigCodec]。以后换 zip 存储时，
     * 这里会变成"配置目录路径"，但接口形状不变。
     */
    var configsJson: String?
        get() = prefs.getString(KEY_CONFIGS_JSON, null)
        set(value) {
            prefs.edit().putString(KEY_CONFIGS_JSON, value).apply()
        }

    /**
     * 全局字体库的 JSON 文本。
     *
     * 字体是**全局**的：导入一次，所有配置都能选，所以它不属于任何单个配置，
     * 放这里而不是塞进配置里。格式见
     * [com.something.sthkey.domain.font.FontRegistry]。
     */
    var importedFontsJson: String?
        get() = prefs.getString(KEY_IMPORTED_FONTS, null)
        set(value) {
            prefs.edit().putString(KEY_IMPORTED_FONTS, value).apply()
        }

    /**
     * 全局 Live2D 模型库的 JSON 文本。
     *
     * 和字体一样是**全局**的：导入一次，所有配置都能选。
     * 格式见 [com.something.sthkey.domain.live2d.Live2DModels]。
     * 模型文件本身在 `filesDir/live2d_models/<uuid>/`，这里只存元信息。
     */
    var importedLive2DModelsJson: String?
        get() = prefs.getString(KEY_IMPORTED_LIVE2D_MODELS, null)
        set(value) {
            prefs.edit().putString(KEY_IMPORTED_LIVE2D_MODELS, value).apply()
        }

    /*
     * ============================================================
     * 悬浮窗运行状态
     * ============================================================
     */

    /**
     * 是否已经做过"本次进程的悬浮窗状态同步"。
     *
     * 悬浮窗服务可能被系统杀掉、或用户从最近任务里划掉应用，
     * 这时持久化的"开关=开"就和实际情况不符了。
     * 首次读取开关时同步一次真实状态，避免显示一个假的"已开启"。
     */
    var overlayStateSynced: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_STATE_SYNCED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_STATE_SYNCED, value).apply()
        }

    /*
     * ============================================================
     * 采集开关
     * ============================================================
     */

    /**
     * 用户是否希望采集处于开启状态。
     *
     * 与悬浮窗开关**分开存**：采集是"读输入设备"，悬浮窗是"画在屏幕上"，
     * 两者以后可能独立开关（例如只采集不显示、或调试时只显示不采集）。
     * 现在由悬浮窗开关联动，但数据上不耦合。
     */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()
        }

    /*
     * ============================================================
     * 悬浮窗开关
     * ============================================================
     */

    /**
     * 用户希望悬浮窗处于开启状态。
     *
     * 这是**旧版本的单一开关**，多悬浮窗之后由 [overlayEnabledIdsJson] 取代。
     * 保留读写是为了迁移（见 `OverlayLayouts.migrateLegacy`），迁移完就清空。
     */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()
        }

    /**
     * 当前开着悬浮窗的配置 id 列表（JSON 数组）。
     *
     * 与位置设置分开存：启动时要**单独**读它来决定是否恢复采集，
     * 不该顺带解析一堆位置数据。空数组 = 全关。
     */
    var overlayEnabledIdsJson: String?
        get() = prefs.getString(KEY_OVERLAY_ENABLED_IDS, null)
        set(value) {
            prefs.edit().putString(KEY_OVERLAY_ENABLED_IDS, value).apply()
        }

    /**
     * 每个悬浮窗的本机设置（JSON 对象：配置 id → 可触摸/坐标/偏移）。
     *
     * **刻意不放进配置对象**：这些值跟设备屏幕强相关，换台设备就不对了，
     * 所以它们不随配置导出 —— 放在这里天然满足。
     * 格式见 [com.something.sthkey.domain.overlay.OverlayLayouts]。
     */
    var overlayLayoutsJson: String?
        get() = prefs.getString(KEY_OVERLAY_LAYOUTS, null)
        set(value) {
            prefs.edit().putString(KEY_OVERLAY_LAYOUTS, value).apply()
        }

    /**
     * 上次**已知的屏幕尺寸**（宽度，像素）。
     *
     * 悬浮窗坐标是像素，屏幕尺寸一变（旋转 / 折叠 / 分屏 / 显示尺寸调整）
     * 那些坐标的含义就变了，必须按新旧尺寸重新锚定，见
     * [com.something.sthkey.capture.OverlayBounds.rescaleToScreen]。
     *
     * 之所以要**存下来**：变化可能发生在应用没运行的期间（用户横着屏幕用了一阵、
     * 或者改了显示尺寸），下次启动时进程内没有"上次"可比较 ——
     * 只有落盘的这一份能告诉我们是"同一块屏幕接着用"还是"屏幕变了"。
     *
     * 与坐标同属"本机屏幕相关"，所以也不随配置导出。
     */
    var overlayScreenWidth: Int
        get() = prefs.getInt(KEY_OVERLAY_SCREEN_WIDTH, 0)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_SCREEN_WIDTH, value).apply()
        }

    /** 上次已知的屏幕高度（像素）；说明见 [overlayScreenWidth] */
    var overlayScreenHeight: Int
        get() = prefs.getInt(KEY_OVERLAY_SCREEN_HEIGHT, 0)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_SCREEN_HEIGHT, value).apply()
        }

    /*
     * ============================================================
     * 悬浮窗位置
     *
     * 记住用户拖到的位置：每次启动都回到屏幕左上角会很难用，
     * 尤其按键显示通常要放在画面某个角落才不挡视线。
     * ============================================================
     */

    /** 悬浮窗左上角 X 坐标（像素；-1 表示尚未设置过） */
    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, DEFAULT_OVERLAY_POSITION)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_X, value).apply()
        }

    /** 悬浮窗左上角 Y 坐标（像素；-1 表示尚未设置过） */
    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, DEFAULT_OVERLAY_POSITION)
        set(value) {
            prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()
        }

    /*
     * ============================================================
     * 调试页
     * ============================================================
     */

    /** 调试页日志过滤：最低级别名，null 表示不过滤 */
    var logMinLevel: String?
        get() = prefs.getString(KEY_LOG_MIN_LEVEL, null)
        set(value) {
            prefs.edit().putString(KEY_LOG_MIN_LEVEL, value).apply()
        }

    /** 调试页日志关键字过滤 */
    var logQuery: String
        get() = prefs.getString(KEY_LOG_QUERY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_LOG_QUERY, value).apply()
        }

    /*
     * ============================================================
     * 配置编辑方式
     * ============================================================
     */

    /** 配置编辑页的生效方式：实时生效 / 保存生效 */
    var editMode: EditMode
        get() = EditMode.fromId(prefs.getString(KEY_EDIT_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_EDIT_MODE, value.id).apply()
        }

    /*
     * ============================================================
     * 自定义 Key 编辑器
     * ============================================================
     */

    /**
     * 属性面板停在哪一边（下 / 左 / 右 / 自动）。
     *
     * 保存的是用户**选的那个值**，包括"自动" ——
     * 不存"自动最终解析成了什么"，那样用户就看不出自己在用自动模式了。
     * 解析发生在界面层（见 [EditorPanelSide.resolved]）。
     */
    var editorPanelSide: EditorPanelSide
        get() = EditorPanelSide.fromId(prefs.getString(KEY_EDITOR_PANEL_SIDE, null))
        set(value) {
            prefs.edit().putString(KEY_EDITOR_PANEL_SIDE, value.id).apply()
        }

    /**
     * "自动"是否已经生效过一次。
     *
     * ============================================================
     * 为什么要这个标记
     * ============================================================
     * 需求是"自动只在**首次**打开自定义编辑界面时生效"。差别很实际：
     *
     * - 用户第一次进来，面板自动落到左边（平板）；
     * - 他手动切到下方 —— 这时如果还是"自动"，**下次打开又会跑回左边**，
     *   看起来就像设置没被记住；
     * - 所以一旦用户做过选择，[editorPanelSide] 就不再是 AUTO，
     *   这个标记只是用来把"还没选过"和"选过自动"区分开。
     */
    var editorPanelSideChosen: Boolean
        get() = prefs.getBoolean(KEY_EDITOR_PANEL_SIDE_CHOSEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_EDITOR_PANEL_SIDE_CHOSEN, value).apply()
        }

    /*
     * ============================================================
     * 编辑器画布上的两种边框
     * ============================================================
     * 都默认**开**：它们是"摆出来的东西在屏幕上占多大"的唯一提示，
     * 而"组件贴到窗口边界会被裁"这个坑恰恰要看得见边界才能避开。
     * 做成开关是因为画布上的框多了会挤，用户想清干净时能关掉。
     */

    /** 悬浮窗范围（内容包围盒那条实线框） */
    var editorShowWindowFrame: Boolean
        get() = prefs.getBoolean(KEY_EDITOR_SHOW_WINDOW_FRAME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_EDITOR_SHOW_WINDOW_FRAME, value).apply()
        }

    /** 当前选中组件的边框 */
    var editorShowSelectedFrame: Boolean
        get() = prefs.getBoolean(KEY_EDITOR_SHOW_SELECTED_FRAME, true)
        set(value) {
            prefs.edit().putBoolean(KEY_EDITOR_SHOW_SELECTED_FRAME, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "sthkey_settings"
        private const val KEY_MODE = "key_mode"
        private const val KEY_SHIZUKU_BACKEND = "shizuku_backend"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_ANNOUNCEMENT_VERSION = "announcement_version"
        private const val KEY_ACTIVE_CONFIG_ID = "active_config_id"
        private const val KEY_CONFIGS_JSON = "configs_json"
        private const val KEY_IMPORTED_FONTS = "imported_fonts"
        private const val KEY_IMPORTED_LIVE2D_MODELS = "imported_live2d_models"
        private const val KEY_OVERLAY_STATE_SYNCED = "overlay_state_synced"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_ENABLED_IDS = "overlay_enabled_ids"
        private const val KEY_OVERLAY_LAYOUTS = "overlay_layouts"
        private const val KEY_OVERLAY_SCREEN_WIDTH = "overlay_screen_width"
        private const val KEY_OVERLAY_SCREEN_HEIGHT = "overlay_screen_height"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_LOG_MIN_LEVEL = "log_min_level"
        private const val KEY_LOG_QUERY = "log_query"
        private const val KEY_EDIT_MODE = "edit_mode"
        private const val KEY_EDITOR_PANEL_SIDE = "editor_panel_side"
        private const val KEY_EDITOR_PANEL_SIDE_CHOSEN = "editor_panel_side_chosen"
        private const val KEY_EDITOR_SHOW_WINDOW_FRAME = "editor_show_window_frame"
        private const val KEY_EDITOR_SHOW_SELECTED_FRAME = "editor_show_selected_frame"

        /**
         * 悬浮窗尚未被拖动过的位置标记。
         *
         * 用一个"无效值"而不是 0：0 是合法坐标（屏幕左上角），
         * 无法区分"用户拖到了左上角"和"还没设置过"。
         */
        private const val DEFAULT_OVERLAY_POSITION = -1

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
