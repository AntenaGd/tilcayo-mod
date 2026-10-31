package com.tilcayo.fat

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Вся игра в одном View: меню, забег, экран смерти и ферма.
 *
 * Забег рисуется в «мировых» единицах (ширина поля = 360), UI — в сетке 360x800,
 * которая центрируется на экране.
 */
class GameView(context: Context) : View(context) {

    private enum class Scene { MENU, PLAY, DEAD, FARM, WARDROBE, RUSH, MODS }
    private enum class Mode { NORMAL, RUSH_TIME, RUSH_TRAIN }
    private class Virus(var x: Float, var y: Float, val title: String, val msg: String) { var age = 0f }

    // ---------- эффекты и UI ----------
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float,
    )
    private class Pop(val x: Float, val y: Float, val text: String, val color: Int, val size: Float) { var life = 1f }
    private class Btn(val x: Float, val y: Float, val w: Float, val h: Float, val onTap: () -> Unit)

    // ---------- ферма ----------
    private class FarmFood(val emoji: String, var x: Float, var y: Float, val owner: Pet) { var z = 170f; var vz = 0f }
    private class Pet(var x: Float, var y: Float) {
        var z = 0f
        var vz = 0f
        var tx = x
        var ty = y
        var st = IDLE
        var t = 0.5f
        var face = 1
        var ph = 0f
        var hops = 0
        var spin = 0f
        var spinV = 0f
        var hvx = 0f
        var gvx = 0f
        var gvy = 0f
        var dizzy = 0f
        var squash = 0f

        companion object {
            const val IDLE = 0
            const val WALK = 1
            const val HOP = 2
            const val FLIP = 3
            const val EAT = 4
            const val FLY = 5
            const val DRAG = 6
        }
    }

    // ---------- константы ----------
    private val W = 360f
    private val WALL = 34f
    private val SPIKE_LEN = 24f
    private val MAX_AIR = Phys.MAX_AIR
    private val SWIPE_U = 16f

    // фермерский луг (в UI-единицах)
    private val GX0 = 30f
    private val GX1 = 330f
    private val GY0 = 305f
    private val GY1 = 495f

    // ---------- рисование ----------
    private val save = SaveData(context)
    private val sfx = Sfx(context)
    private val music = Music()
    private val sprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.tilcayo)
    private val bossSprite: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.boss)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }
    private val argb = ArgbEvaluator()
    private val path = Path()
    private val rect = RectF()

    private var scale = 1f
    private var viewH = 800f
    private var ui = 1f
    private var uiOx = 0f
    private var uiOy = 0f

    private val rnd = Random(System.nanoTime())
    private val stars = List(70) { Triple(rnd.nextFloat() * 360f, rnd.nextFloat() * 900f, 0.6f + rnd.nextFloat() * 1.8f) }
    private val decor = run {
        val r = Random(42)
        List(26) { Triple(GX0 - 10f + r.nextFloat() * (GX1 - GX0 + 20f), GY0 - 10f + r.nextFloat() * (GY1 - GY0 + 14f), r.nextInt(5)) }
            .sortedBy { it.second }
    }

    // ---------- состояние ----------
    private var scene = Scene.MENU
    private var sceneTime = 0f
    private var anim = 0f
    private var last = System.nanoTime()
    private var paused = false
    private val buttons = ArrayList<Btn>()
    private var toastText = ""
    private var toastT = 0f

    // ядро забега: вся физика и мир живут в Sim, здесь только отображение
    private val sim = Sim()
    private val lib: List<ChunkTemplate> = try {
        ChunkLib.parse(resources.openRawResource(R.raw.chunks).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        emptyList()
    }
    private val gen = Gen(sim, lib, rnd)

    private val px: Float get() = sim.px
    private val py: Float get() = sim.py
    private val pvx: Float get() = sim.pvx
    private val pvy: Float get() = sim.pvy
    private val side: Int get() = sim.side
    private val face: Int get() = sim.face
    private val onWall: Boolean get() = sim.onWall
    private val squash: Float get() = sim.squash
    private val shield: Float get() = sim.shield
    private val boostT: Float get() = sim.boostT
    private val inCannon: Cannon? get() = sim.inCannon
    private val jumpsLeft: Int get() = sim.jumpsLeft
    private val r: Float get() = sim.r
    private val hr: Float get() = sim.hr
    private val camY: Float get() = sim.camY
    private val lavaY: Float get() = sim.lavaY
    private val minPy: Float get() = sim.minPy
    private val runTime: Float get() = sim.time
    private val runCoins: Float get() = sim.runCoins
    private val coinMul: Float get() = sim.coinMul
    private val coins get() = sim.coins
    private val snacks get() = sim.snacks
    private val spikes get() = sim.spikes
    private val saws get() = sim.saws
    private val orbits get() = sim.orbits
    private val lasers get() = sim.lasers
    private val pillars get() = sim.pillars
    private val cannons get() = sim.cannons
    private val bumpers get() = sim.bumpers

    private val sawPos = FloatArray(2)
    private var mode = Mode.NORMAL
    private var rushBoss = 0
    private var rushT0 = 0f
    private var rushWon = false
    private var rushEnd = 0f
    private var rushTime = 0f
    private var rushNewBest = false
    private var rushFirstWin = false
    private var rushReward = 0L
    private val viruses = ArrayList<Virus>()
    private var virusTimer = 0f
    private var updateAsked = false
    private val installedCode: Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }
    private var bossBanner = 0f
    private var bossHint = 0f
    private val boss: Boss? get() = sim.boss
    private var runLevel = 1
    private var runSkin = 0
    private var shake = 0f
    private var combo = 0
    private var comboT = 0f
    private var gotSnacks = 0
    private var deathReason = ""
    private var newRecord = false
    private var earned = 0L

    // жест: тап — прыжок вперёд, свайп влево/вправо — рывок в сторону
    private var gesturePtr = -1
    private var gestureX0 = 0f
    private var gestureDone = false

    private val particles = ArrayList<Particle>()
    private val pops = ArrayList<Pop>()

    // ферма
    private val petLists = Array(Farms.COUNT) { ArrayList<Pet>() }
    private val pets: ArrayList<Pet> get() = petLists[save.activeFarm]
    private var viewFarm = 0
    private var dragPtr = -1
    private var dragIdx = -1
    private var dragMoved = false
    private var dragX0 = 0f
    private var dragY0 = 0f
    private val hist = FloatArray(18) // 6 точек: x, y, t
    private var histN = 0
    private val snowflakes = List(40) { Triple(rnd.nextFloat() * 360f, rnd.nextFloat() * 300f, 0.5f + rnd.nextFloat()) }

    // гардероб и боксы
    private var boxStage = 0 // 0 нет, 1 трясётся, 2 открыт
    private var boxT = 0f
    private var boxKind = 0
    private var boxSkin = 0
    private var boxDup = false
    private var boxFree = false
    private val farmFoods = ArrayList<FarmFood>()
    private val uiPops = ArrayList<Pop>()

    private fun k(v: Long) = v.toInt()

    init {
        sfx.enabled = save.sound
        music.start()
        music.setPlaying(save.sound)
    }

    // =====================================================================
    // Жизненный цикл
    // =====================================================================

    fun onHostPause() {
        if (scene == Scene.PLAY) paused = true
        music.setPlaying(false)
        save.save()
    }

    fun onHostResume() {
        music.setPlaying(save.sound)
    }

    fun release() {
        music.release()
        sfx.release()
    }

    /** true — нажатие «назад» обработано внутри игры. */
    fun onBack(): Boolean = when (scene) {
        Scene.PLAY -> { paused = !paused; true }
        Scene.WARDROBE -> { if (boxStage == 0) goFarm() else boxStage = 0; true }
        Scene.RUSH, Scene.MODS -> { goMenu(); true }
        Scene.DEAD, Scene.FARM -> { goMenu(); true }
        Scene.MENU -> false
    }

    private fun goMenu() {
        scene = Scene.MENU
        sceneTime = 0f
        paused = false
    }

    private fun goFarm() {
        viewFarm = save.activeFarm
        scene = Scene.FARM
        sceneTime = 0f
        paused = false
    }

    private fun toggleSound() {
        save.sound = !save.sound
        sfx.enabled = save.sound
        music.setPlaying(save.sound)
        save.save()
        sfx.play(Sfx.S.CLICK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = w / W
        viewH = h / scale
        ui = min(w / 360f, h / 800f)
        uiOx = (w - 360f * ui) / 2f
        uiOy = (h - 800f * ui) / 2f
    }


    // =====================================================================
    // Тилкайо Bypass: плавающее меню (кнопка TB)
    // =====================================================================

    private class BpRow(val label: String, val toggle: Boolean, val get: () -> Boolean, val tap: () -> Unit)

    private var bpInfCoins = false
    private var bpInfBoxes = false
    private var bpRainbowWorld = false
    private var bpSpin = false
    private var bpBig = false
    private var bpOpen = false
    private var fabX = -1f
    private var fabY = -1f
    private var fabDown = false
    private var fabMoved = false
    private var fabDX = 0f
    private var fabDY = 0f
    private var fabSX = 0f
    private var fabSY = 0f
    private var bpConsume = false
    private var bpPx = 0f
    private var bpPy = 0f
    private var bpPw = 0f
    private var bpPh = 0f
    private var bpRowH = 1f
    private var bpHeadH = 0f
    private val bpRect = RectF()
    private val bpRect2 = RectF()
    private val fabRect = RectF()
    private val panelRect = RectF()
    private val hueCm = ColorMatrix()
    private val satCm = ColorMatrix()
    private val hueArr = FloatArray(20)
    private val layerPaint = Paint()
    private val bpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bpDo(f: () -> String) {
        val m = f()
        save.save()
        toast(m)
        if (scene == Scene.FARM) syncPets()
    }

    private val bpRows: List<BpRow> by lazy {
        listOf(
            BpRow("∞  Бесконечные монеты", true, { bpInfCoins }) { bpInfCoins = !bpInfCoins },
            BpRow("∞  Бесконечные боксы", true, { bpInfBoxes }) { bpInfBoxes = !bpInfBoxes },
            BpRow("Радужный мир", true, { bpRainbowWorld }) { bpRainbowWorld = !bpRainbowWorld },
            BpRow("Тёмная радужная тема", true, { RAINBOW_DARK }) { RAINBOW_DARK = !RAINBOW_DARK },
            BpRow("Кот-крутилка", true, { bpSpin }) { bpSpin = !bpSpin },
            BpRow("Кот-гигант", true, { bpBig }) { bpBig = !bpBig },
            BpRow("+10 000 монет", false, { false }) {
                bpDo { save.coins += 10_000L; "Монет: ${save.coins}" }
            },
            BpRow("Открыть все скины", false, { false }) {
                bpDo {
                    val n = Skins.all.size
                    save.ownedSkins = if (n >= 63) -1L else (1L shl n) - 1L
                    "Скинов открыто: $n"
                }
            },
            BpRow("Открыть все фермы", false, { false }) {
                bpDo { save.unlocked = Farms.COUNT; "Ферм открыто: ${Farms.COUNT}" }
            },
            BpRow("Максимум жира всему стаду", false, { false }) {
                bpDo {
                    val herd = save.herd
                    for (i in herd.indices) herd[i] = Balance.MAX_FAT
                    "Всё стадо максимально жирное"
                }
            },
            BpRow("Добавить питомца", false, { false }) {
                bpDo {
                    if (save.farm.herd.size < Balance.MAX_HERD) {
                        save.farm.addPet()
                        save.farm.normalize()
                        "В стаде: ${save.farm.herd.size}"
                    } else {
                        "Стадо уже полное"
                    }
                }
            },
            BpRow("Родить малыша сейчас", false, { false }) {
                bpDo {
                    if (save.pregnant()) {
                        save.breedEnd = 1L
                        save.checkBirth()
                        "Малыш родился"
                    } else {
                        "Сейчас никто не вынашивает"
                    }
                }
            },
        )
    }

    /** Поворот оттенков всего экрана + чуть больше насыщенности. */
    private fun bpHue(deg: Float) {
        val r = Math.toRadians(deg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        hueArr[0] = 0.213f + cs * 0.787f - sn * 0.213f
        hueArr[1] = 0.715f - cs * 0.715f - sn * 0.715f
        hueArr[2] = 0.072f - cs * 0.072f + sn * 0.928f
        hueArr[3] = 0f; hueArr[4] = 0f
        hueArr[5] = 0.213f - cs * 0.213f + sn * 0.143f
        hueArr[6] = 0.715f + cs * 0.285f + sn * 0.140f
        hueArr[7] = 0.072f - cs * 0.072f - sn * 0.283f
        hueArr[8] = 0f; hueArr[9] = 0f
        hueArr[10] = 0.213f - cs * 0.213f - sn * 0.787f
        hueArr[11] = 0.715f - cs * 0.715f + sn * 0.715f
        hueArr[12] = 0.072f + cs * 0.928f + sn * 0.072f
        hueArr[13] = 0f; hueArr[14] = 0f
        hueArr[15] = 0f; hueArr[16] = 0f; hueArr[17] = 0f; hueArr[18] = 1f; hueArr[19] = 0f
        hueCm.set(hueArr)
        satCm.setSaturation(1.4f)
        hueCm.postConcat(satCm)
    }

    /** Скруглённая рамка с бело-чёрной обводкой, цвета которой меняются местами. */
    private fun bpFrame(c: Canvas, r: RectF, rad: Float, sw: Float, fill: Int) {
        bpPaint.style = Paint.Style.FILL
        bpPaint.color = fill
        c.drawRoundRect(r, rad, rad, bpPaint)
        val flip = ((anim * 2f).toInt() and 1) == 0
        val a = if (flip) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val b = if (flip) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        bpPaint.style = Paint.Style.STROKE
        bpPaint.strokeWidth = sw
        bpPaint.color = a
        c.drawRoundRect(r, rad, rad, bpPaint)
        bpRect2.set(r.left + sw, r.top + sw, r.right - sw, r.bottom - sw)
        bpPaint.color = b
        c.drawRoundRect(bpRect2, rad - sw, rad - sw, bpPaint)
        bpPaint.style = Paint.Style.FILL
    }

    private fun drawBypass(c: Canvas) {
        val d = resources.displayMetrics.density
        val sz = 52f * d
        if (fabX < 0f) {
            fabX = width - sz - 12f * d
            fabY = 140f * d
        }
        fabX = fabX.coerceIn(0f, max(0f, width - sz))
        fabY = fabY.coerceIn(0f, max(0f, height - sz))
        fabRect.set(fabX, fabY, fabX + sz, fabY + sz)

        if (bpOpen) {
            val rows = bpRows
            val pw = min(width - 24f * d, 340f * d)
            val headH = 58f * d
            val padB = 14f * d
            val maxH = height - 40f * d
            val rowH = min(42f * d, (maxH - headH - padB) / rows.size)
            val ph = headH + rowH * rows.size + padB
            val px = (width - pw) / 2f
            val py = ((height - ph) / 2f).coerceAtLeast(20f * d)
            bpPx = px; bpPy = py; bpPw = pw; bpPh = ph; bpRowH = rowH; bpHeadH = headH

            bpPaint.style = Paint.Style.FILL
            bpPaint.color = 0x88000000.toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bpPaint)

            panelRect.set(px, py, px + pw, py + ph)
            bpFrame(c, panelRect, 18f * d, 3f * d, 0xF2101018.toInt())
            text(c, "Тилкайо  Bypass", px + pw / 2f, py + 38f * d, 24f * d, rainbow(0f), shadow = false, maxW = pw - 30f * d)

            for ((i, row) in rows.withIndex()) {
                val top = py + headH + i * rowH
                bpRect.set(px + 12f * d, top + 2f * d, px + pw - 12f * d, top + rowH - 2f * d)
                bpPaint.style = Paint.Style.FILL
                bpPaint.color = if (row.toggle) 0x26FFFFFF else 0x14FFFFFF
                c.drawRoundRect(bpRect, 10f * d, 10f * d, bpPaint)
                val ts = min(15f * d, rowH * 0.4f)
                text(
                    c, row.label, bpRect.left + 12f * d, bpRect.centerY() + ts * 0.35f, ts,
                    align = Paint.Align.LEFT, shadow = false, maxW = pw - 100f * d,
                )
                if (row.toggle) {
                    val on = row.get()
                    val tw = 44f * d
                    val th = min(24f * d, rowH * 0.6f)
                    val tr = bpRect.right - 12f * d
                    val tl = tr - tw
                    val tcy = bpRect.centerY()
                    bpRect2.set(tl, tcy - th / 2f, tr, tcy + th / 2f)
                    bpPaint.color = if (on) rainbow(i * 40f) else 0xFF555566.toInt()
                    c.drawRoundRect(bpRect2, th / 2f, th / 2f, bpPaint)
                    bpPaint.color = 0xFFFFFFFF.toInt()
                    val kx = if (on) tr - th / 2f else tl + th / 2f
                    c.drawCircle(kx, tcy, th / 2f - 3f * d, bpPaint)
                } else {
                    text(c, "▶", bpRect.right - 20f * d, bpRect.centerY() + ts * 0.35f, ts, 0xFFAAAAAA.toInt(), shadow = false)
                }
            }
        }

        bpFrame(c, fabRect, 14f * d, 3f * d, 0xF2121218.toInt())
        text(c, "TB", fabRect.centerX(), fabRect.centerY() + sz * 0.16f, sz * 0.46f, rainbow(0f), shadow = false)
    }

    /** Возвращает true, если касание забрала кнопка TB или открытая панель. */
    private fun bpTouch(e: MotionEvent): Boolean {
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_DOWN) {
            val x = e.x
            val y = e.y
            fabDown = false
            bpConsume = false
            if (fabRect.contains(x, y)) {
                fabDown = true
                fabMoved = false
                fabDX = x - fabX
                fabDY = y - fabY
                fabSX = x
                fabSY = y
                return true
            }
            if (bpOpen) {
                bpConsume = true
                if (x >= bpPx && x <= bpPx + bpPw && y >= bpPy && y <= bpPy + bpPh) {
                    val i = ((y - bpPy - bpHeadH) / bpRowH).toInt()
                    if (y >= bpPy + bpHeadH && i in bpRows.indices) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sfx.play(Sfx.S.CLICK)
                        bpRows[i].tap()
                    }
                } else {
                    bpOpen = false
                }
                return true
            }
            return false
        }
        if (!fabDown && !bpConsume) return false
        when (act) {
            MotionEvent.ACTION_MOVE -> if (fabDown) {
                val x = e.x
                val y = e.y
                if (hypot(x - fabSX, y - fabSY) > 12f * resources.displayMetrics.density) fabMoved = true
                if (fabMoved) {
                    fabX = x - fabDX
                    fabY = y - fabDY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (fabDown && !fabMoved && act == MotionEvent.ACTION_UP) {
                    bpOpen = !bpOpen
                    if (bpOpen && scene == Scene.PLAY) paused = true
                    sfx.play(Sfx.S.CLICK)
                }
                fabDown = false
                bpConsume = false
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        anim += dt
        sceneTime += dt
        toastT = max(0f, toastT - dt)
        buttons.clear()
        if (bpInfCoins && save.coins < 900_000_000L) save.coins = 999_999_999L
        if (bpInfBoxes && save.freeBoxes < 50) save.freeBoxes = 99
        if (scene != Scene.PLAY && save.checkBirth()) {
            sfx.play(Sfx.S.BREED)
            toast("Родился малыш! 🍼")
        }

        val bpLayer = bpRainbowWorld
        if (bpLayer) {
            bpHue(anim * 90f)
            layerPaint.colorFilter = ColorMatrixColorFilter(hueCm)
            c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), layerPaint)
        }
        when (scene) {
            Scene.MENU -> drawMenu(c)
            Scene.FARM -> drawFarm(c, dt)
            Scene.WARDROBE -> drawWardrobe(c, dt)
            Scene.RUSH -> drawRush(c)
            Scene.MODS -> drawMods(c)
            Scene.PLAY, Scene.DEAD -> {
                if (scene == Scene.PLAY && !paused) {
                    var rem = dt
                    while (rem > 0f) {
                        val s = min(rem, 1f / 120f)
                        stepPlay(s)
                        rem -= s
                        if (scene != Scene.PLAY) break
                    }
                }
                stepFx(dt)
                drawWorld(c)
                if (scene == Scene.PLAY && mode == Mode.NORMAL && Mods.has(save.mods, Mods.NIGHT)) drawNight(c)
                if (scene == Scene.PLAY) { drawHud(c); drawViruses(c) } else drawDeadOverlay(c)
                if (paused) drawPause(c)
            }
        }
        if (bpLayer) c.restore()
        drawBypass(c)
        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Ввод
    // =====================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bpTouch(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                onDown(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_MOVE -> if (dragPtr >= 0) {
                val i = e.findPointerIndex(dragPtr)
                if (i >= 0) farmMove((e.getX(i) - uiOx) / ui, (e.getY(i) - uiOy) / ui)
            } else if (gesturePtr >= 0 && !gestureDone) {
                val i = e.findPointerIndex(gesturePtr)
                if (i >= 0) {
                    val dx = (e.getX(i) - gestureX0) / scale
                    if (abs(dx) > SWIPE_U) {
                        gestureDone = true
                        swipeDash(if (dx > 0f) 1 else -1)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                if (pid == gesturePtr) {
                    if (!gestureDone) tapRelease()
                    gesturePtr = -1
                }
                if (pid == dragPtr) farmUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                gesturePtr = -1
                if (dragPtr >= 0) farmUp()
            }
        }
        return true
    }

    private fun onDown(id: Int, rx: Float, ry: Float) {
        if (scene == Scene.PLAY && !paused) {
            val wx = rx / scale
            val wy = ry / scale
            if (virusTap(wx, wy)) return
            when {
                wx > W - 54f && wy < 70f -> { paused = true; sfx.play(Sfx.S.CLICK) }
                sim.boss != null && sim.ammo > 0 && hypot(wx - (W - 46f), wy - (viewH - 120f)) < 48f -> sim.shoot()
                sim.inCannon != null -> sim.fireCannon()
                sim.onWall -> sim.jump()
                sim.jumpsLeft > 0 -> { gesturePtr = id; gestureX0 = rx; gestureDone = false }
                else -> sim.jumpBuf = 0.2f
            }
            return
        }
        val ux = (rx - uiOx) / ui
        val uy = (ry - uiOy) / ui
        if (scene == Scene.DEAD && sceneTime < 0.5f) return
        val hit = buttons.asReversed().firstOrNull { ux >= it.x && ux <= it.x + it.w && uy >= it.y && uy <= it.y + it.h }
        if (hit != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sfx.play(Sfx.S.CLICK)
            hit.onTap()
        } else if (scene == Scene.FARM) {
            farmDown(ux, uy, id)
        }
    }

    /** Палец отпущен без свайпа — это тап: прыжок «вперёд», по ходу движения. */
    private fun tapRelease() {
        if (scene != Scene.PLAY || paused || sim.dead) return
        when {
            sim.inCannon != null -> sim.fireCannon()
            sim.onWall -> sim.jump()
            sim.jumpsLeft > 0 -> sim.airJump(sim.forwardDir())
            else -> sim.jumpBuf = 0.2f
        }
    }

    private fun swipeDash(dir: Int) {
        if (scene != Scene.PLAY || paused || sim.dead) return
        when {
            sim.inCannon != null -> sim.fireCannon()
            sim.onWall -> sim.jump()
            sim.jumpsLeft > 0 -> sim.airJump(if (mode == Mode.NORMAL && Mods.has(save.mods, Mods.MIRROR)) -dir else dir)
        }
    }

    // =====================================================================
    // Забег: связка Sim с эффектами и звуком
    // =====================================================================

    private fun resetCommon() {
        scene = Scene.PLAY
        sceneTime = 0f
        paused = false
        runLevel = save.level(save.selected)
        runSkin = save.skinOf(save.selected)
        shake = 0f
        combo = 0
        comboT = 0f
        gotSnacks = 0
        newRecord = false
        gesturePtr = -1
        bossBanner = 0f
        bossHint = 0f
        rushWon = false
        rushEnd = 0f
        viruses.clear()
        virusTimer = 6f
        particles.clear()
        pops.clear()
    }

    private fun startRun() {
        mode = Mode.NORMAL
        resetCommon()
        val mods = save.mods
        sim.reset(runLevel, save.coinMultiplier() * Mods.multiplier(mods), viewH)
        sim.lavaMul = if (Mods.has(mods, Mods.LAVA)) 1.6f else 1f
        gen.reset()
        gen.sparse = Mods.has(mods, Mods.SPARSE)
        gen.dense = Mods.has(mods, Mods.DENSE)
        gen.fill(sim.camY - 800f)
    }

    private fun startRush(timed: Boolean) {
        mode = if (timed) Mode.RUSH_TIME else Mode.RUSH_TRAIN
        resetCommon()
        sim.reset(runLevel, 1f, viewH)
        sim.lavaMul = 1f
        sim.startRush(Bosses.all[rushBoss].hp)
        gen.reset()
    }

    private fun restartCurrent() {
        when (mode) {
            Mode.NORMAL -> startRun()
            Mode.RUSH_TIME -> startRush(true)
            Mode.RUSH_TRAIN -> startRush(false)
        }
    }

    private fun virusSpawn() {
        val texts = listOf(
            "Windows Defender 98" to "Обнаружено 9999 вирусов! Нажмите ОК, чтобы ничего не делать",
            "Поздравляем!" to "Вы 1000000-й тилкайо! Заберите бесплатный iPhone 15",
            "ВАШ ТИЛКАЙО ЗАРАЖЁН" to "Срочно закройте это окно, пока оно не закрыло вас",
            "Обновление Java" to "Для игры в кота требуется обновить Java",
            "СКИДКА 99%" to "Горячие тилкайо в вашем районе",
            "Критическая ошибка" to "котозаяц.exe не отвечает. Завершить?",
            "Антивирус Касперов" to "Ваш антивирус устарел на 100 лет",
        )
        val t = texts[rnd.nextInt(texts.size)]
        viruses.add(Virus(8f + rnd.nextFloat() * (W - 226f), 110f + rnd.nextFloat() * (viewH - 330f), t.first, t.second))
        sfx.play(Sfx.S.WARN, 1.5f, 0.5f)
    }

    private fun stepPlay(dt: Float) {
        comboT = max(0f, comboT - dt)
        if (comboT <= 0f) combo = 0
        shake = max(0f, shake - dt)
        bossBanner = max(0f, bossBanner - dt)
        bossHint = max(0f, bossHint - dt)
        for (b in sim.bumpers) b.pop = max(0f, b.pop - dt)

        sim.step(dt)
        if (mode == Mode.NORMAL) gen.fill(sim.camY - 800f)
        handleEvents()
        sim.cleanup()

        // вирусные окна
        for (v in viruses) v.age += dt
        if (mode == Mode.NORMAL && Mods.has(save.mods, Mods.VIRUS)) {
            virusTimer -= dt
            if (virusTimer <= 0f) {
                if (viruses.size < 5) virusSpawn()
                virusTimer = max(3.5f, 11f - sim.time / 25f) + rnd.nextFloat() * 3f
            }
        }

        // победа над боссом в раше: даём доиграть анимацию
        if (rushWon) {
            rushEnd -= dt
            if (rushEnd <= 0f) { finishRush(true); return }
        }

        if (sim.boostT > 0f && rnd.nextFloat() < 0.6f) {
            particles.add(Particle(sim.px, sim.py, (rnd.nextFloat() - 0.5f) * 40f, 40f, 0.4f, 0.4f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 3f))
        }
        if (sim.dead) { finishRun(); return }

        val height = -sim.minPy
        if (!newRecord && save.best > 0 && (height / 10f).toInt() > save.best) {
            newRecord = true
            sfx.play(Sfx.S.RECORD)
            pops.add(Pop(W / 2, sim.camY + viewH * 0.3f, "НОВЫЙ РЕКОРД!", 0xFFFFD54F.toInt(), 26f))
        }
    }

    private fun handleEvents() {
        for (ev in sim.events) {
            when (ev.type) {
                Ev.JUMP -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    sfx.play(Sfx.S.JUMP)
                    dust(ev.x, ev.y, 6, 0xFFFFFFFF.toInt())
                }
                Ev.AIR -> {
                    sfx.play(Sfx.S.AIR, 1f + (MAX_AIR - sim.jumpsLeft) * 0.12f)
                    ring(ev.x, ev.y, 0xFF9BE7FF.toInt())
                }
                Ev.SLIP -> {
                    sfx.play(Sfx.S.SLIP)
                    dust(ev.x, ev.y, 6, 0xFFFFC0A0.toInt())
                }
                Ev.LAND -> {
                    sfx.play(Sfx.S.LAND, 0.9f + rnd.nextFloat() * 0.2f, 0.7f)
                    dust(ev.x - sim.side * sim.r * 0.3f, ev.y, 8, 0xFFE0D6FF.toInt())
                }
                Ev.COIN -> {
                    combo++
                    comboT = 0.7f
                    sfx.play(Sfx.S.COIN, min(1.9f, 1f + combo * 0.06f))
                    sparkle(ev.x, ev.y, 0xFFFFD54F.toInt())
                    pops.add(Pop(ev.x, ev.y - 8f, "+" + fmt(sim.coinMul), 0xFFFFE082.toInt(), 13f))
                }
                Ev.SNACK -> {
                    val sn = ev.ref as Snack
                    gotSnacks++
                    save.herd[save.selected] = min(Balance.MAX_FAT, save.herd[save.selected] + sn.fat)
                    save.save()
                    sfx.play(Sfx.S.EAT)
                    sparkle(sn.x, sn.y, 0xFFFF8A65.toInt())
                    pops.add(Pop(sn.x, sn.y - 10f, "ням! +${sn.fat} жира", 0xFFFFAB91.toInt(), 14f))
                }
                Ev.SMASH -> {
                    sfx.play(Sfx.S.SMASH)
                    shake = max(shake, 0.15f)
                    sparkle(ev.x, ev.y, 0xFFCFD8DC.toInt())
                    pops.add(Pop(ev.x, ev.y - 10f, "+" + fmt(sim.coinMul * 3), 0xFFFFE082.toInt(), 16f))
                }
                Ev.CANNON_IN -> sfx.play(Sfx.S.CAN_IN)
                Ev.CANNON_FIRE -> {
                    val a = ev.ref as Float
                    shake = 0.35f
                    sfx.play(Sfx.S.CAN_FIRE)
                    sfx.play(Sfx.S.SHIELD)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    for (i in 0 until 20) {
                        val ang = a + (rnd.nextFloat() - 0.5f) * 1.2f
                        val sp = 120f + rnd.nextFloat() * 260f
                        particles.add(Particle(ev.x, ev.y, sin(ang) * sp, -cos(ang) * sp, 0.6f, 0.6f, 0xFFFFC107.toInt(), 3f + rnd.nextFloat() * 4f))
                    }
                }
                Ev.BUMP -> {
                    (ev.ref as Bumper).pop = 0.3f
                    sfx.play(Sfx.S.BUMP, 0.9f + rnd.nextFloat() * 0.3f)
                    ring(ev.x, ev.y, 0xFFFF8AD8.toInt())
                }
                Ev.BOSS_START -> {
                    rushT0 = sim.time
                    bossBanner = 3f
                    bossHint = 6f
                    shake = 0.5f
                    sfx.play(Sfx.S.BOSS_ROAR)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                Ev.BOSS_TELE -> sfx.play(Sfx.S.WARN)
                Ev.AMMO -> {
                    sfx.play(Sfx.S.COIN, 1.7f)
                    sparkle(ev.x, ev.y, 0xFFFFD54F.toInt())
                    pops.add(Pop(ev.x, ev.y - 14f, "+патрон", 0xFFFFE082.toInt(), 15f))
                }
                Ev.SHOOT -> sfx.play(Sfx.S.THROW, 1.6f, 0.6f)
                Ev.BULLET_BLOCK -> {
                    sfx.play(Sfx.S.LAND, 1.4f)
                    pops.add(Pop(ev.x, ev.y - 14f, "он злой!", 0xFFFF8A80.toInt(), 15f))
                }
                Ev.BOSS_FIRE -> sfx.play(Sfx.S.LASER, 0.8f, 0.5f)
                Ev.BOSS_HIT -> {
                    shake = 0.45f
                    bossHint = 0f
                    sfx.play(Sfx.S.BOSS_HIT)
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    sparkle(ev.x, ev.y, 0xFFFFFFFF.toInt())
                    for (i in 0 until 14) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        val sp = 100f + rnd.nextFloat() * 200f
                        particles.add(Particle(ev.x, ev.y - 20f, cos(a) * sp, sin(a) * sp - 80f, 0.7f, 0.7f, 0xFFB0BEC5.toInt(), 3f + rnd.nextFloat() * 3f))
                    }
                    pops.add(Pop(ev.x, ev.y - 70f, if (ev.ref == 2) "ПОПАЛ!" else "БАМ!", 0xFFFFD54F.toInt(), 26f))
                }
                Ev.BOSS_DEAD -> {
                    shake = 0.7f
                    sfx.play(Sfx.S.BOSS_DEAD)
                    if (mode == Mode.NORMAL) {
                        save.freeBoxes++
                        save.save()
                        pops.add(Pop(W / 2, sim.camY + viewH * 0.3f + 70f, "🎁 бесплатный бокс со скином!", 0xFFFFFFFF.toInt(), 16f))
                    } else {
                        rushWon = true
                        rushEnd = 2.4f
                        rushTime = sim.time - rushT0
                    }
                    pops.add(Pop(W / 2, sim.camY + viewH * 0.3f, "ПОБЕДА!", 0xFFFFD54F.toInt(), 34f))
                    pops.add(Pop(W / 2, sim.camY + viewH * 0.3f + 40f, "+" + fmt(sim.coinMul * 150f) + " монет", 0xFFFFE082.toInt(), 18f))
                    for (i in 0 until 40) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        val sp = 80f + rnd.nextFloat() * 280f
                        particles.add(Particle(ev.x, ev.y, cos(a) * sp, sin(a) * sp - 120f, 1.2f, 1.2f, blockColors[i % 5], 3f + rnd.nextFloat() * 4f))
                    }
                }
                Ev.LASER_ON -> if (ev.y > sim.camY && ev.y < sim.camY + viewH) sfx.play(Sfx.S.LASER, 1.4f, 0.4f)
            }
        }
        sim.events.clear()
    }

    private fun finishRush(win: Boolean) {
        val def = Bosses.all[rushBoss]
        scene = Scene.DEAD
        sceneTime = 0f
        gesturePtr = -1
        earned = 0L
        rushNewBest = false
        rushFirstWin = false
        rushReward = 0L
        if (win && mode == Mode.RUSH_TIME) {
            val ms = (rushTime * 1000f).toLong()
            val prev = save.rushBest[rushBoss]
            if (prev == 0L || ms < prev) { save.rushBest[rushBoss] = ms; rushNewBest = true }
            rushFirstWin = prev == 0L
            rushReward = def.reward + max(0, (def.par - rushTime).toInt()) * 8L
            save.coins += rushReward
            if (rushFirstWin) save.freeBoxes++
            earned = rushReward
        }
        save.save()
        deathReason = if (win) "" else sim.deathReason
        sfx.play(if (win) Sfx.S.BOSS_DEAD else Sfx.S.DEATH)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!win) for (i in 0 until 28) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 80f + rnd.nextFloat() * 220f
            particles.add(Particle(sim.px, sim.py, cos(a) * sp, sin(a) * sp - 90f, 0.9f, 0.9f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 4f))
        }
    }

    private fun finishRun() {
        if (mode != Mode.NORMAL) { finishRush(rushWon); return }
        deathReason = sim.deathReason
        scene = Scene.DEAD
        sceneTime = 0f
        gesturePtr = -1
        earned = sim.runCoins.toLong()
        save.coins += earned
        val score = (-sim.minPy / 10f).toInt()
        if (score > save.best) save.best = score
        save.save()
        sfx.play(Sfx.S.DEATH)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        for (i in 0 until 28) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 80f + rnd.nextFloat() * 220f
            particles.add(Particle(sim.px, sim.py, cos(a) * sp, sin(a) * sp - 90f, 0.9f, 0.9f, 0xFFFFB74D.toInt(), 3f + rnd.nextFloat() * 4f))
        }
    }

    // ---------- эффекты ----------

    private fun dust(x: Float, y: Float, n: Int, color: Int) {
        for (i in 0 until n) {
            particles.add(Particle(x, y + r * 0.4f, (rnd.nextFloat() - 0.5f) * 120f, -rnd.nextFloat() * 60f, 0.45f, 0.45f, color, 2f + rnd.nextFloat() * 3f))
        }
    }

    private fun sparkle(x: Float, y: Float, color: Int) {
        for (i in 0 until 8) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = 40f + rnd.nextFloat() * 90f
            particles.add(Particle(x, y, cos(a) * sp, sin(a) * sp, 0.5f, 0.5f, color, 2f + rnd.nextFloat() * 2f))
        }
    }

    private fun ring(x: Float, y: Float, color: Int) {
        for (i in 0 until 10) {
            val a = i * 2f * PI.toFloat() / 10f
            particles.add(Particle(x, y, cos(a) * 130f, sin(a) * 130f * 0.5f, 0.35f, 0.35f, color, 2.5f))
        }
    }

    private fun stepFx(dt: Float) {
        for (q in particles) {
            q.life -= dt
            q.x += q.vx * dt
            q.y += q.vy * dt
            q.vy += 300f * dt
        }
        particles.removeAll { it.life <= 0f }
        for (q in pops) q.life -= dt * 1.1f
        pops.removeAll { it.life <= 0f }
        for (q in uiPops) q.life -= dt * 0.9f
        uiPops.removeAll { it.life <= 0f }
    }

    // =====================================================================
    // Рисование: общее
    // =====================================================================

    /** Тёмная + радужная тема. Поставь false, чтобы вернуть старые цвета. */
    private var RAINBOW_DARK = true
    private val hsv = FloatArray(3)
    private fun rainbow(offset: Float = 0f, alpha: Int = 255): Int {
        hsv[0] = ((anim * 40f + offset) % 360f + 360f) % 360f
        hsv[1] = 0.85f
        hsv[2] = 1f
        return android.graphics.Color.HSVToColor(alpha, hsv)
    }
    private var debugTaps = 0
    private var lastDebugTap = 0L

    private fun lerpColor(a: Int, b: Int, t: Float) = argb.evaluate(t.coerceIn(0f, 1f), a, b) as Int

    private val skyTop = intArrayOf(k(0xFF1B1035), k(0xFF0B2A4A), k(0xFF053B2F), k(0xFF3A0F2E), k(0xFF050510))
    private val skyBot = intArrayOf(k(0xFF4A2370), k(0xFF1D6A96), k(0xFF1F8A6A), k(0xFFB5446E), k(0xFF1A1A4A))

    private fun skyAt(height: Float, bottom: Boolean): Int {
        val pal = if (bottom) skyBot else skyTop
        val f = (height / 1800f)
        val i = floor(f).toInt()
        val t = f - i
        return lerpColor(pal[i % pal.size], pal[(i + 1) % pal.size], t)
    }

    private fun drawBackground(c: Canvas, top: Int, bottom: Int, scroll: Float, withStars: Boolean = true) {
        val topCol = if (RAINBOW_DARK) k(0xFF05030F) else top
        val botCol = if (RAINBOW_DARK) lerpColor(k(0xFF0A0618), rainbow(0f), 0.25f) else bottom
        p.style = Paint.Style.FILL
        p.color = 0xFF000000.toInt() // альфа краски домножается на шейдер, поэтому она обязана быть непрозрачной
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), topCol, botCol, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        if (RAINBOW_DARK) {
            val cols = IntArray(7) { rainbow(it * 50f, 200) }
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, cols, null, Shader.TileMode.CLAMP)
            val bh = height * 0.008f
            c.drawRect(0f, 0f, width.toFloat(), bh, p)
            c.drawRect(0f, height - bh, width.toFloat(), height.toFloat(), p)
        }
        p.shader = null
        if (!withStars) return
        c.save()
        c.scale(scale, scale)
        for ((i, s) in stars.withIndex()) {
            val y = (((s.second - scroll * 0.12f * s.third) % viewH) + viewH) % viewH
            val tw = 0.5f + 0.5f * sin(anim * 2f + i)
            val rgb = if (RAINBOW_DARK) (rainbow(i * 25f) and 0xFFFFFF) else 0xFFFFFF
            p.color = ((60 + 140 * tw).toInt() shl 24) or rgb
            c.drawCircle(s.first, y, s.third * 0.8f, p)
        }
        c.restore()
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = 0xFFFFFFFF.toInt(),
        align: Paint.Align = Paint.Align.CENTER, shadow: Boolean = true, maxW: Float = 0f,
    ) {
        tp.textSize = size
        tp.textAlign = align
        if (maxW > 0f) {
            val wdt = tp.measureText(s)
            if (wdt > maxW) tp.textSize = size * maxW / wdt
        }
        if (shadow) {
            tp.color = 0x66000000
            c.drawText(s, x, y + size * 0.07f, tp)
        }
        tp.color = color
        c.drawText(s, x, y, tp)
    }

    private fun darken(color: Int) = lerpColor(color, 0xFF000000.toInt(), 0.35f)

    private fun panel(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int = 0x66000000, rad: Float = 16f) {
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(x, y, x + w, y + h, rad, rad, p)
    }

    private fun btn(
        c: Canvas, label: String, x: Float, y: Float, w: Float, h: Float, color: Int,
        sub: String? = null, enabled: Boolean = true, onTap: () -> Unit,
    ) {
        val col = if (enabled) color else k(0xFF55556A)
        p.style = Paint.Style.FILL
        p.color = darken(col)
        c.drawRoundRect(x, y + 4f, x + w, y + h + 4f, 14f, 14f, p)
        p.color = col
        c.drawRoundRect(x, y, x + w, y + h, 14f, 14f, p)
        if (RAINBOW_DARK && enabled) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.color = rainbow(x * 0.6f + y * 0.3f)
            c.drawRoundRect(x, y, x + w, y + h, 14f, 14f, p)
            p.style = Paint.Style.FILL
        }
        p.color = 0x33FFFFFF
        c.drawRoundRect(x + 4f, y + 3f, x + w - 4f, y + h * 0.48f, 11f, 11f, p)
        if (sub == null) {
            text(c, label, x + w / 2, y + h / 2 + h * 0.16f, h * 0.44f, maxW = w - 16f)
        } else {
            text(c, label, x + w / 2, y + h * 0.46f, h * 0.36f, maxW = w - 12f)
            text(c, sub, x + w / 2, y + h * 0.82f, h * 0.24f, 0xFFFFF3C4.toInt(), maxW = w - 12f)
        }
        buttons.add(Btn(x, y, w, h, onTap))
    }

    private fun toast(msg: String) {
        toastText = msg
        toastT = 1.8f
    }

    private fun fmt(v: Float) = if (v == floor(v)) v.toInt().toString() else "%.1f".format(v)

    /** Рисует тилкайо с центром в (cx, cy); rad — «радиус пузика». */
    private val silhouette = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 22f, 0f, 0f, 0f, 0f, 18f, 0f, 0f, 0f, 0f, 38f, 0f, 0f, 0f, 1f, 0f)),
    )

    /** Рисует тилкайо с центром в (cx, cy); rad — «радиус пузика». [skin] — номер скина, [hidden] — чёрный силуэт. */
    private fun drawTilcayo(
        c: Canvas, cx: Float, cy0: Float, rad: Float, faceDir: Int, rot: Float,
        sx: Float, sy: Float, level: Int, skin: Int = 0, hidden: Boolean = false,
    ) {
        val sk = Skins.all[skin]
        val w = rad * 2.5f
        val h = w * sprite.height / sprite.width
        val cy = if (sk.fx == Skin.FX_GHOST && !hidden) cy0 - rad * 0.12f + sin(anim * 3f) * rad * 0.1f else cy0
        if (sk.fx == Skin.FX_FIRE && !hidden) {
            val fl = 0.85f + 0.2f * sin(anim * 12f)
            text(c, "🔥", cx - faceDir * w * 0.44f, cy + h * 0.14f, rad * 0.8f * fl, shadow = false)
            text(c, "🔥", cx + faceDir * w * 0.46f, cy + h * 0.2f, rad * 0.6f * (2f - fl), shadow = false)
        }
        c.save()
        c.translate(cx, cy)
        c.rotate(rot + (if (bpSpin) anim * 540f else 0f))
        val bigK = if (bpBig) 1.6f else 1f
        c.scale(sx * faceDir * bigK, sy * bigK)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        if (hidden) {
            bmpPaint.colorFilter = silhouette
        } else {
            bmpPaint.colorFilter = Skins.filter(skin, anim)
            bmpPaint.alpha = (sk.alpha * 255).toInt()
        }
        c.drawBitmap(sprite, null, rect, bmpPaint)
        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255
        c.restore()
        if (hidden) return

        val crown = level >= Balance.MAX_LEVEL && sk.acc != "👑"
        if (crown) text(c, "👑", cx + faceDir * w * 0.02f, cy - h * 0.46f * sy, rad * 0.95f, shadow = false)
        val acc = sk.acc
        if (acc != null) {
            if (sk.accOnEyes) {
                text(c, acc, cx + faceDir * w * 0.1f, cy - h * 0.02f * sy, rad * 0.9f, shadow = false)
            } else if (crown) {
                text(c, acc, cx - faceDir * w * 0.3f, cy - h * 0.38f * sy, rad * 0.7f, shadow = false)
            } else {
                text(c, acc, cx + faceDir * w * 0.02f, cy - h * 0.46f * sy, rad * 0.95f, shadow = false)
            }
        }
        when (sk.fx) {
            Skin.FX_SPARK -> for (i in 0 until 3) {
                val a = anim * 1.7f + i * 2.1f
                val tw = 0.5f + 0.5f * sin(anim * 5f + i * 2f)
                text(c, "✨", cx + cos(a) * w * 0.5f, cy + sin(a * 1.3f) * h * 0.42f, rad * (0.35f + 0.35f * tw), shadow = false)
            }
            Skin.FX_STARS -> for (i in 0 until 4) {
                val a = anim * 1.3f + i * 1.57f
                p.style = Paint.Style.FILL
                p.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx + cos(a) * w * 0.55f, cy + sin(a) * h * 0.2f - h * 0.1f, rad * 0.07f + 0.8f, p)
            }
        }
    }

    private fun drawParticles(c: Canvas) {
        for (q in particles) {
            val a = (q.life / q.maxLife).coerceIn(0f, 1f)
            p.style = Paint.Style.FILL
            p.color = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            c.drawCircle(q.x, q.y, q.size * (0.4f + 0.6f * a), p)
        }
    }

    private val blockColors = intArrayOf(k(0xFF8BD450), k(0xFFB06AE8), k(0xFFF2C230), k(0xFF35A9B8), k(0xFFFF5470))

    private fun drawBlock(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        p.style = Paint.Style.FILL
        p.color = k(0xFF241A38)
        c.drawRoundRect(x, y, x + w, y + h, 5f, 5f, p)
        p.color = color
        c.drawRoundRect(x + 2f, y + 2f, x + w - 2f, y + h - 2f, 4f, 4f, p)
        p.color = 0x55FFFFFF
        c.drawRect(x + 3f, y + 3f, x + w - 3f, y + 7f, p)
        p.color = 0x33000000
        c.drawRect(x + 3f, y + h - 8f, x + w - 3f, y + h - 3f, p)
        p.color = 0x66000000
        c.drawCircle(x + 7f, y + h * 0.5f, 2f, p)
        c.drawCircle(x + w - 7f, y + h * 0.5f, 2f, p)
    }

    // =====================================================================
    // Рисование: забег
    // =====================================================================

    private fun drawWorld(c: Canvas) {
        val height = -minPy
        drawBackground(c, skyAt(height, false), skyAt(height, true), camY)

        c.save()
        c.scale(scale, scale)
        if (shake > 0f) c.translate((rnd.nextFloat() - 0.5f) * shake * 24f, (rnd.nextFloat() - 0.5f) * shake * 24f)
        c.translate(0f, -camY)

        val top = camY - 20f
        val bot = camY + viewH + 20f

        // стены из цветных блоков
        val bh = 52f
        for (leftWall in booleanArrayOf(true, false)) {
            val x0 = if (leftWall) 0f else W - WALL
            var bi = floor(top / bh).toInt()
            while (bi * bh < bot) {
                val ci = (((bi * 3 + (if (leftWall) 0 else 2)) % 5) + 5) % 5
                drawBlock(c, x0, bi * bh, WALL, bh, blockColors[ci])
                bi++
            }
        }

        // линия рекорда
        if (save.best > 0) {
            val by = -save.best * 10f
            if (by in top..bot) {
                p.color = 0x99FFD54F.toInt()
                var xx = WALL + 4f
                while (xx < W - WALL) { c.drawRect(xx, by - 1f, xx + 8f, by + 1f, p); xx += 16f }
                text(c, "РЕКОРД ${save.best} м", W / 2, by - 6f, 11f, 0xCCFFD54F.toInt(), maxW = 160f)
            }
        }

        // столбы
        for (pl in pillars) {
            if (pl.y1 < top || pl.y0 > bot) continue
            val seg = 48f
            var yy = pl.y1
            var i = 0
            while (yy > pl.y0) {
                val sh = min(seg, yy - pl.y0)
                val col = if (pl.hot) k(0xFFFF5470) else intArrayOf(k(0xFFFF5470), k(0xFF35A9B8), k(0xFFF2C230))[i % 3]
                drawBlock(c, pl.x0, yy - sh, pl.x1 - pl.x0, sh, col)
                yy -= sh
                i++
            }
            if (pl.hot) {
                p.color = k(0xFFE0E4F5)
                path.reset()
                var ty = pl.y0 + 4f
                while (ty < pl.y1 - 12f) {
                    path.moveTo(pl.x0, ty); path.lineTo(pl.x0 - 9f, ty + 6f); path.lineTo(pl.x0, ty + 12f); path.close()
                    path.moveTo(pl.x1, ty); path.lineTo(pl.x1 + 9f, ty + 6f); path.lineTo(pl.x1, ty + 12f); path.close()
                    ty += 16f
                }
                c.drawPath(path, p)
            }
        }

        // шипы на стенах
        for (s in spikes) {
            if (s.y1 < top || s.y0 > bot) continue
            val baseX = if (s.left) WALL else W - WALL
            val sgn = if (s.left) 1f else -1f
            val n = max(1, ((s.y1 - s.y0) / 14f).toInt())
            val step = (s.y1 - s.y0) / n
            p.style = Paint.Style.FILL
            p.color = k(0xFFE0E4F5)
            path.reset()
            for (i in 0 until n) {
                val ya = s.y0 + i * step
                path.moveTo(baseX, ya)
                path.lineTo(baseX + sgn * SPIKE_LEN, ya + step / 2)
                path.lineTo(baseX, ya + step)
                path.close()
            }
            c.drawPath(path, p)
            p.color = k(0xFFFF4D6D)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
        }

        // лазеры
        for (l in lasers) {
            if (l.y < top - 20f || l.y > bot) continue
            val t = (runTime + l.phase) % l.period
            val on = t < l.onTime
            val warn = !on && t > l.period - 0.8f
            p.style = Paint.Style.FILL
            if (on) {
                p.color = 0x55FF1744
                c.drawRect(WALL, l.y - 9f, W - WALL, l.y + 9f, p)
                p.color = k(0xFFFF1744)
                c.drawRect(WALL, l.y - 4f, W - WALL, l.y + 4f, p)
                p.color = k(0xFFFFFFFF)
                c.drawRect(WALL, l.y - 1.2f, W - WALL, l.y + 1.2f, p)
            } else if (warn && ((t * 8f).toInt() % 2 == 0)) {
                p.color = 0x99FF1744.toInt()
                var xx = WALL
                while (xx < W - WALL) { c.drawRect(xx, l.y - 1f, xx + 8f, l.y + 1f, p); xx += 16f }
            }
            p.color = k(0xFF37474F)
            c.drawRoundRect(WALL - 2f, l.y - 11f, WALL + 8f, l.y + 11f, 3f, 3f, p)
            c.drawRoundRect(W - WALL - 8f, l.y - 11f, W - WALL + 2f, l.y + 11f, 3f, 3f, p)
            p.color = if (on) k(0xFFFF1744) else k(0xFF90A4AE)
            c.drawCircle(WALL + 3f, l.y, 3f, p)
            c.drawCircle(W - WALL - 3f, l.y, 3f, p)
        }

        // монетки
        for (co in coins) {
            if (co.y < top - 20f || co.y > bot) continue
            val squeeze = abs(cos(anim * 4f + co.x * 0.05f + co.y * 0.03f))
            p.style = Paint.Style.FILL
            p.color = k(0xFFFFB300)
            rect.set(co.x - 9f * squeeze - 1f, co.y - 10f, co.x + 9f * squeeze + 1f, co.y + 10f)
            c.drawOval(rect, p)
            p.color = k(0xFFFFE066)
            rect.set(co.x - 6f * squeeze, co.y - 7f, co.x + 6f * squeeze, co.y + 7f)
            c.drawOval(rect, p)
        }

        // еда
        for (sn in snacks) {
            if (sn.y < top - 20f || sn.y > bot) continue
            val bob = sin(anim * 3f + sn.y) * 3f
            p.color = 0x33FFFFFF
            c.drawCircle(sn.x, sn.y + bob, 17f, p)
            text(c, sn.emoji, sn.x, sn.y + bob + 8f, 24f, shadow = false)
        }

        // бамперы
        for (b in bumpers) {
            if (b.y < top - 30f || b.y > bot) continue
            val s = 1f + b.pop * 0.8f
            p.style = Paint.Style.FILL
            p.color = k(0xFFD81B60)
            c.drawCircle(b.x, b.y, 17f * s, p)
            p.color = k(0xFFFF80AB)
            c.drawCircle(b.x, b.y, 12f * s, p)
            p.color = k(0xFFFFFFFF)
            c.drawCircle(b.x - 4f, b.y - 4f, 3f, p)
        }

        // пушки
        for (cn in cannons) {
            if (cn.y < top - 50f || cn.y > bot + 50f) continue
            val active = inCannon === cn
            p.style = Paint.Style.FILL
            c.save()
            c.translate(cn.x, cn.y)
            if (active) {
                p.color = 0x66FFFFFF
                var dd = 30f
                while (dd < 200f) {
                    c.drawCircle(sin(cn.ang) * dd, -cos(cn.ang) * dd, 2.2f, p)
                    dd += 18f
                }
            }
            c.save()
            c.rotate(Math.toDegrees(cn.ang.toDouble()).toFloat())
            p.color = if (cn.used) k(0xFF455A64) else k(0xFF546E7A)
            c.drawRoundRect(-11f, -38f, 11f, 0f, 4f, 4f, p)
            p.color = if (cn.used) k(0xFF37474F) else k(0xFFFFB300)
            c.drawRoundRect(-13f, -40f, 13f, -32f, 3f, 3f, p)
            c.restore()
            p.color = if (cn.used) k(0xFF37474F) else k(0xFF263238)
            c.drawCircle(0f, 0f, 22f, p)
            p.color = if (cn.used) k(0xFF455A64) else k(0xFFFFB300)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            c.drawCircle(0f, 0f, 22f, p)
            p.style = Paint.Style.FILL
            if (!cn.used) {
                val pulse = 1f + 0.08f * sin(anim * 6f)
                text(c, "🚀", 0f, 7f * pulse, 20f * pulse, shadow = false)
            }
            c.restore()
        }

        // пилы
        for (s in saws) {
            if (s.dead) continue
            if (max(s.y0, s.y1) < top - 30f || min(s.y0, s.y1) > bot) continue
            s.posAt(runTime, sawPos)
            // рельса
            if (s.len > 1f && s.speed != 0f) {
                p.color = 0x33FFFFFF
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                c.drawLine(s.x0, s.y0, s.x1, s.y1, p)
                p.style = Paint.Style.FILL
            }
            drawSaw(c, sawPos[0], sawPos[1], runTime * 9f + s.phase)
        }
        for (o in orbits) {
            if (o.cy < top - 90f || o.cy > bot + 90f) continue
            p.color = 0x33FFFFFF
            c.drawCircle(o.cx, o.cy, 7f, p)
            for (i in 0 until o.n) {
                if (!o.alive[i]) continue
                val a = o.angle(runTime, i)
                val sx = o.cx + cos(a) * o.rad
                val sy = o.cy + sin(a) * o.rad
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                p.color = 0x55CFD8DC
                c.drawLine(o.cx, o.cy, sx, sy, p)
                p.style = Paint.Style.FILL
                drawSaw(c, sx, sy, runTime * 9f + i)
            }
        }

        // лава
        path.reset()
        path.moveTo(0f, bot + 400f)
        var xx = 0f
        while (xx <= W + 12f) {
            path.lineTo(xx, lavaY + sin(xx * 0.05f + anim * 3f) * 4f)
            xx += 12f
        }
        path.lineTo(W, bot + 400f)
        path.close()
        p.style = Paint.Style.FILL
        p.color = k(0xFFFF5A1F)
        c.drawPath(path, p)
        p.color = k(0xFFFFC94D)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        path.reset()
        xx = 0f
        while (xx <= W + 12f) {
            val yy = lavaY + sin(xx * 0.05f + anim * 3f) * 4f
            if (xx == 0f) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            xx += 12f
        }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        if (lavaY < bot && rnd.nextFloat() < 0.15f) {
            particles.add(Particle(rnd.nextFloat() * W, lavaY, (rnd.nextFloat() - 0.5f) * 40f, -80f - rnd.nextFloat() * 80f, 0.8f, 0.8f, k(0xFFFFB74D), 2.5f))
        }

        drawBoss(c, top, bot)

        // тилкайо
        if (scene == Scene.PLAY && inCannon == null) {
            val rot = if (onWall) side * 10f else (face * pvy / 20f).coerceIn(-25f, 25f)
            val stretch = if (onWall) 0f else min(0.18f, abs(pvy) / 3000f)
            val sx = 1f + squash * 0.22f - stretch
            val sy = 1f - squash * 0.22f + stretch
            val faceDir = if (onWall) -side else face
            // перед срывом тилкайо дрожит, вокруг него тает кольцо «хватки»
            val g = (sim.gripT / Phys.GRIP_TIME)
            val tremble = if (onWall && g > 0.7f) sin(anim * 70f) * (min(1f, g) - 0.7f) * 7f else 0f
            drawTilcayo(c, px + tremble, py, r, faceDir, rot, sx, sy, runLevel, runSkin)
            if (onWall) {
                val ringR = r * 1.4f
                rect.set(px - ringR, py - ringR, px + ringR, py + ringR)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3.5f
                if (g < 1f) {
                    p.color = lerpColor(0xFF00E676.toInt(), 0xFFFF1744.toInt(), g)
                    c.drawArc(rect, -90f, 360f * (1f - g), false, p)
                } else if ((anim * 12f).toInt() % 2 == 0) {
                    p.color = 0xFFFF1744.toInt()
                    c.drawArc(rect, -90f, 360f, false, p)
                }
                p.style = Paint.Style.FILL
                if (g >= 1f) text(c, "⬇", px, py + r * 2.2f, 18f, 0xFFFF8A80.toInt(), shadow = false)
            }
            if (shield > 0f && (shield > 0.8f || (anim * 10f).toInt() % 2 == 0)) {
                p.style = Paint.Style.FILL
                p.color = 0x3355CCFF
                c.drawCircle(px, py, r * 1.45f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f
                p.color = 0xCC8FE3FF.toInt()
                c.drawCircle(px, py, r * 1.45f, p)
                p.style = Paint.Style.FILL
            }
            if (!onWall && jumpsLeft > 0) {
                // стрелки: куда уйдёт тап (яркая) и свайп назад (тусклая)
                val fwd = sim.forwardDir()
                for (dirSign in intArrayOf(fwd, -fwd)) {
                    val bright = dirSign == fwd
                    val ax = px + dirSign * (r * 1.7f + (if (bright) 4f else 0f))
                    val sz = if (bright) 8f else 6f
                    p.style = Paint.Style.FILL
                    p.color = if (bright) 0xE6FFEB3B.toInt() else 0x66FFFFFF
                    path.reset()
                    path.moveTo(ax + dirSign * sz, py)
                    path.lineTo(ax - dirSign * sz * 0.6f, py - sz)
                    path.lineTo(ax - dirSign * sz * 0.6f, py + sz)
                    path.close()
                    c.drawPath(path, p)
                }
            }
            if (!onWall) {
                // точки доступных прыжков в воздухе
                for (i in 0 until MAX_AIR) {
                    p.color = if (i < jumpsLeft) k(0xFFFFEB3B) else 0x44FFFFFF
                    c.drawCircle(px + (i - (MAX_AIR - 1) / 2f) * 11f, py - r * 1.35f, 3.6f, p)
                }
            }
        }

        drawParticles(c)
        for (q in pops) {
            val a = q.life.coerceIn(0f, 1f)
            val col = (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
            text(c, q.text, q.x, q.y - (1f - q.life) * 40f, q.size, col, maxW = 200f)
        }
        c.restore()
    }

    private fun drawBoss(c: Canvas, top: Float, bot: Float) {
        val a = sim.arena
        val b = sim.boss
        if (a != null && a.active) {
            // потолок арены
            p.style = Paint.Style.FILL
            p.color = 0x99140A28.toInt()
            c.drawRect(WALL, a.top - 400f, W - WALL, a.top, p)
            p.color = k(0xFF7C4DFF)
            c.drawRect(WALL, a.top - 3f, W - WALL, a.top, p)
        }
        if (b == null) return
        val phase = b.hpMax - b.hp
        // телеграфы атак
        if (b.state == Boss.RAIN_TELE && a != null) {
            val blink = if ((anim * 10f).toInt() % 2 == 0) 0x55FF1744 else 0x33FF1744
            p.style = Paint.Style.FILL
            p.color = blink
            for (i in 0 until 3) {
                c.drawRect(b.cols[i] - 15f, a.top, b.cols[i] + 15f, a.bottom, p)
                text(c, "!", b.cols[i], a.top + 40f, 30f, 0xFFFF1744.toInt(), shadow = false)
            }
        }
        if (b.state == Boss.VOLLEY_TELE) {
            // красное кольцо и зелёная дуга-проход: лети в неё
            val rr = 100f
            val half = Math.toDegrees(Boss.gapHalf(phase).toDouble()).toFloat()
            val gap = Math.toDegrees(b.gapAng.toDouble()).toFloat()
            rect.set(b.x - rr, b.y - rr, b.x + rr, b.y + rr)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 7f
            val blink = if ((anim * 10f).toInt() % 2 == 0) 0xAAFF1744.toInt() else 0x55FF1744
            p.color = blink
            c.drawArc(rect, gap + half, 360f - 2f * half, false, p)
            p.color = 0xEE00E676.toInt()
            p.strokeWidth = 9f
            c.drawArc(rect, gap - half, 2f * half, false, p)
            p.style = Paint.Style.FILL
            val ax = b.x + cos(b.gapAng) * (rr + 24f)
            val ay = b.y + sin(b.gapAng) * (rr + 24f)
            text(c, "▼", ax, ay + 8f, 18f, 0xFF00E676.toInt(), shadow = false)
        }
        if (b.state == Boss.CHARGE_WARN || b.state == Boss.CHARGE_MOVE) {
            val blink = if ((anim * 12f).toInt() % 2 == 0) 0x66FF1744 else 0x22FF1744
            p.style = Paint.Style.FILL
            p.color = blink
            c.drawRect(WALL, b.y - Boss.R, W - WALL, b.y + Boss.R, p)
            text(c, if (b.dir > 0) "▶▶▶" else "◀◀◀", W / 2, b.y + 10f, 32f, 0xFFFF1744.toInt(), shadow = false)
        }
        // снаряды
        for (h in sim.hairs) {
            if (h.kind == 0) {
                p.style = Paint.Style.FILL
                p.color = k(0xFF9E9E9E)
                c.drawCircle(h.x, h.y, 9f, p)
                p.color = k(0xFFCFD8DC)
                c.drawCircle(h.x - 2f, h.y - 2f, 6f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = k(0xFF757575)
                for (j in 0 until 5) {
                    val ang = anim * 6f + j * 1.2566f
                    c.drawLine(h.x, h.y, h.x + cos(ang) * 12f, h.y + sin(ang) * 12f, p)
                }
                p.style = Paint.Style.FILL
            } else {
                text(c, "🥕", h.x, h.y + 8f, 22f, shadow = false)
            }
        }
        // патроны на земле и летящие пули
        for (am in sim.ammoItems) {
            val blink = am.life > 3f || (anim * 8f).toInt() % 2 == 0
            if (!blink) continue
            val pu = 1f + 0.12f * sin(anim * 6f)
            p.style = Paint.Style.FILL
            p.color = 0x44FFD54F
            c.drawCircle(am.x, am.y, 17f * pu, p)
            drawBulletIcon(c, am.x, am.y, 1.3f)
        }
        for (bu in sim.bullets) {
            p.style = Paint.Style.FILL
            p.color = 0x55FFC107
            c.drawCircle(bu.x - bu.vx * 0.02f, bu.y - bu.vy * 0.02f, 6f, p)
            p.color = 0xFFFFC107.toInt()
            c.drawCircle(bu.x, bu.y, 5.5f, p)
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(bu.x - 1.5f, bu.y - 1.5f, 2f, p)
        }

        // сам Толстый Котозаяц
        var sx = 1f + 0.03f * sin(anim * 2.4f)
        var sy = 1f - 0.03f * sin(anim * 2.4f)
        var rot = 0f
        var alpha = 255
        when (b.state) {
            Boss.VOLLEY_TELE, Boss.RAIN_TELE -> { val k2 = 0.1f * sin(b.t * 30f).coerceIn(-1f, 1f); sx = 1.12f + k2; sy = 1.12f - k2 }
            Boss.CHARGE_WARN -> rot = sin(b.t * 50f) * 5f
            Boss.CHARGE_GO -> rot = b.dir * 12f
            Boss.STUN -> rot = sin(anim * 18f) * 9f
            Boss.DEAD -> { rot = b.t * 400f; alpha = (255 * (1f - b.t / 2.2f)).toInt().coerceIn(0, 255) }
        }
        val w = Boss.R * 2.75f
        val h = w * bossSprite.height / bossSprite.width
        c.save()
        c.translate(b.x, b.y - 4f)
        c.rotate(rot)
        c.scale(sx, sy)
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        bmpPaint.alpha = alpha
        if (b.flash > 0f) {
            val f = b.flash / 0.5f * 160f
            bmpPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, f, 0f, 1f, 0f, 0f, f, 0f, 0f, 1f, 0f, f, 0f, 0f, 0f, 1f, 0f)))
        } else if (b.state == Boss.VOLLEY_TELE || b.state == Boss.RAIN_TELE || b.state == Boss.CHARGE_WARN) {
            bmpPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(1.15f, 0f, 0f, 0f, 20f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
        c.drawBitmap(bossSprite, null, rect, bmpPaint)
        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255
        // злые брови во время атаки, обиженные глазки в стане
        val eyeY = -h * 0.21f
        val angry = b.state != Boss.HOVER && b.state != Boss.ENTER && b.state != Boss.STUN && b.state != Boss.DEAD
        if (angry) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = k(0xFF3E1A1A)
            c.drawLine(-w * 0.26f, eyeY - 16f, -w * 0.07f, eyeY - 6f, p)
            c.drawLine(w * 0.26f, eyeY - 16f, w * 0.07f, eyeY - 6f, p)
            p.style = Paint.Style.FILL
        }
        c.restore()
        if (b.state == Boss.STUN) text(c, "💫", b.x, b.y - h * 0.55f, 26f, shadow = false)
        if (phase >= 2 && b.state != Boss.DEAD) text(c, "💢", b.x + w * 0.38f, b.y - h * 0.38f, 20f, shadow = false)
    }

    private fun drawBulletIcon(c: Canvas, x: Float, y: Float, sc: Float) {
        p.style = Paint.Style.FILL
        p.color = 0xFFFFC107.toInt()
        c.drawRoundRect(x - 3.5f * sc, y - 2f * sc, x + 3.5f * sc, y + 10f * sc, 1.5f * sc, 1.5f * sc, p)
        p.color = 0xFFFF8F00.toInt()
        path.reset()
        path.moveTo(x - 3.5f * sc, y - 2f * sc)
        path.lineTo(x, y - 10f * sc)
        path.lineTo(x + 3.5f * sc, y - 2f * sc)
        path.close()
        c.drawPath(path, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawRect(x - 3.5f * sc, y + 6f * sc, x + 3.5f * sc, y + 7.5f * sc, p)
    }

    // ---------- вирусные окна ----------

    private fun virusTap(wx: Float, wy: Float): Boolean {
        for (i in viruses.indices.reversed()) {
            val v = viruses[i]
            if (wx < v.x || wx > v.x + 210f || wy < v.y || wy > v.y + 118f) continue
            if (wx >= v.x + 186f && wy <= v.y + 22f) {
                viruses.removeAt(i)
                sfx.play(Sfx.S.CLICK)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else if (wx in (v.x + 70f)..(v.x + 140f) && wy in (v.y + 88f)..(v.y + 110f)) {
                // «ОК» только плодит новые окна
                if (viruses.size < 6) virusSpawn()
                sfx.play(Sfx.S.BUMP, 1.4f, 0.6f)
            }
            return true
        }
        return false
    }

    private fun wrap(s: String, size: Float, maxW: Float): List<String> {
        tp.textSize = size
        val out = ArrayList<String>()
        var cur = ""
        for (w in s.split(' ')) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (tp.measureText(t) > maxW && cur.isNotEmpty()) { out.add(cur); cur = w } else cur = t
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun drawViruses(c: Canvas) {
        if (viruses.isEmpty()) return
        c.save()
        c.scale(scale, scale)
        for (v in viruses) {
            val k1 = min(1f, v.age * 6f)
            c.save()
            c.translate(v.x + 105f, v.y + 59f)
            c.scale(0.6f + 0.4f * k1, 0.6f + 0.4f * k1)
            c.translate(-105f, -59f)
            p.style = Paint.Style.FILL
            p.color = 0x55000000
            c.drawRect(4f, 4f, 214f, 122f, p)
            p.color = 0xFFECECEC.toInt()
            c.drawRect(0f, 0f, 210f, 118f, p)
            p.color = 0xFF1E3C9E.toInt()
            c.drawRect(0f, 0f, 210f, 22f, p)
            text(c, v.title, 6f, 15f, 10f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, shadow = false, maxW = 170f)
            p.color = 0xFFE53935.toInt()
            c.drawRect(186f, 3f, 206f, 19f, p)
            text(c, "✕", 196f, 15f, 11f, 0xFFFFFFFF.toInt(), shadow = false)
            val lines = wrap(v.msg, 11f, 190f)
            for ((i, ln) in lines.withIndex()) text(c, ln, 10f, 42f + i * 14f, 11f, 0xFF212121.toInt(), Paint.Align.LEFT, shadow = false)
            p.color = 0xFFC8C8C8.toInt()
            c.drawRect(70f, 88f, 140f, 110f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            p.color = 0xFF616161.toInt()
            c.drawRect(70f, 88f, 140f, 110f, p)
            p.style = Paint.Style.FILL
            text(c, "OK", 105f, 104f, 12f, 0xFF212121.toInt(), shadow = false)
            c.restore()
        }
        c.restore()
    }

    private fun drawNight(c: Canvas) {
        val cx = sim.px * scale
        val cy = (sim.py - sim.camY) * scale
        val r = 230f * scale
        p.style = Paint.Style.FILL
        p.color = 0xFF000000.toInt()
        p.shader = RadialGradient(cx, cy, r, intArrayOf(0x00000000, 0x00000000, 0xF5000000.toInt()), floatArrayOf(0f, 0.46f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
    }

    // ---------- обновления ----------

    private fun checkUpdates() {
        val now = System.currentTimeMillis()
        if (updateAsked || now - save.lastUpdateCheck < 6 * 3600_000L) return
        updateAsked = true
        Thread {
            val info = Updater.fetch()
            post {
                if (info != null) {
                    save.lastUpdateCheck = System.currentTimeMillis()
                    save.remoteCode = info.code
                    save.remoteName = info.name
                    save.remoteNotes = info.notes
                    save.remoteApk = info.apk
                    save.save()
                }
            }
        }.start()
    }

    private fun openDownload() {
        try {
            val url = save.remoteApk.ifBlank { Updater.APK_FALLBACK }
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Не удалось открыть браузер")
        }
    }

    // ---------- экран босс-раша ----------

    private fun fmtTime(ms: Long) = if (ms <= 0L) "—" else "%.1f с".format(ms / 1000f)

    private fun drawRush(c: Canvas) {
        drawBackground(c, k(0xFF2B0A1A), k(0xFF6A1B3A), anim * 20f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { goMenu() }
        text(c, "БОСС-РАШ", 180f, 50f, 32f)
        text(c, "победи босса на время или потренируйся", 180f, 78f, 12f, 0xBBFFFFFF.toInt(), maxW = 330f)
        for ((i, def) in Bosses.all.withIndex()) {
            val y = 96f + i * 250f
            panel(c, 14f, y, 332f, 232f, 0x77000000, 22f)
            val bw = 128f
            val bh = bw * bossSprite.height / bossSprite.width
            val bob = sin(anim * 2f)
            rect.set(26f, y + 20f + bob * 3f, 26f + bw, y + 20f + bh + bob * 3f)
            c.drawBitmap(bossSprite, null, rect, bmpPaint)
            text(c, def.name, 196f, y + 40f, 18f, 0xFFFFF3C4.toInt(), Paint.Align.LEFT, maxW = 142f)
            text(c, "здоровье: ${def.hp}", 196f, y + 64f, 13f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT)
            text(c, "лучшее время:", 196f, y + 88f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT)
            text(c, fmtTime(save.rushBest[i]), 196f, y + 110f, 20f, 0xFFFFD54F.toInt(), Paint.Align.LEFT, maxW = 142f)
            text(c, "награда: ${def.reward} 💰 + за скорость", 196f, y + 132f, 10f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT, maxW = 142f)
            if (save.rushBest[i] == 0L) text(c, "первая победа: 🎁 бокс", 196f, y + 146f, 10f, 0xFF00E676.toInt(), Paint.Align.LEFT, maxW = 142f)
            btn(c, "⏱ НА ВРЕМЯ", 26f, y + 166f, 152f, 52f, k(0xFFE53935)) { rushBoss = i; startRush(true) }
            btn(c, "🎯 ТРЕНИРОВКА", 188f, y + 166f, 146f, 52f, k(0xFF5C6BC0)) { rushBoss = i; startRush(false) }
        }
        val y2 = 96f + Bosses.all.size * 250f
        panel(c, 14f, y2, 332f, 74f, 0x44000000, 22f)
        text(c, "❓ Новые боссы — скоро", 180f, y2 + 44f, 16f, 0x88FFFFFF.toInt())
        c.restore()
    }

    // ---------- экран модификаторов ----------

    private fun drawMods(c: Canvas) {
        drawBackground(c, k(0xFF1B1035), k(0xFF3A1E6A), anim * 20f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { goMenu() }
        text(c, "МОДИФИКАТОРЫ", 190f, 48f, 26f, maxW = 230f)
        text(c, "усложняй забег и получай больше монет", 180f, 78f, 12f, 0xBBFFFFFF.toInt(), maxW = 330f)
        for ((i, md) in Mods.all.withIndex()) {
            val y = 92f + i * 84f
            val on = Mods.has(save.mods, md.id)
            panel(c, 10f, y, 340f, 76f, if (on) 0xAA1B5E20.toInt() else 0x66000000, 16f)
            if (on) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f
                p.color = 0xFF00E676.toInt()
                c.drawRoundRect(10f, y, 350f, y + 76f, 16f, 16f, p)
                p.style = Paint.Style.FILL
            }
            text(c, md.emoji, 40f, y + 48f, 34f, shadow = false)
            text(c, md.name, 70f, y + 30f, 16f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 190f)
            for ((j, ln) in wrap(md.desc, 11f, 190f).take(2).withIndex()) text(c, ln, 70f, y + 48f + j * 13f, 11f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT, shadow = false)
            val good = md.bonus >= 0f
            val pct = Math.round(md.bonus * 100)
            panel(c, 268f, y + 14f, 72f, 28f, if (good) 0xFF2E7D32.toInt() else 0xFFC62828.toInt(), 14f)
            text(c, (if (good) "+" else "") + pct + "%", 304f, y + 34f, 15f, maxW = 64f)
            text(c, if (on) "ВКЛ" else "выкл", 304f, y + 62f, 12f, if (on) 0xFF00E676.toInt() else 0x99FFFFFF.toInt(), shadow = false)
            buttons.add(Btn(10f, y, 340f, 76f) {
                save.mods = Mods.toggle(save.mods, md.id)
                save.save()
                sfx.play(Sfx.S.CLICK)
            })
        }
        val mul = Mods.multiplier(save.mods)
        panel(c, 10f, 604f, 340f, 84f, 0x88000000.toInt(), 18f)
        text(c, "Монеты за забег", 180f, 634f, 14f, 0xBBFFFFFF.toInt())
        text(c, "x" + "%.2f".format(mul), 180f, 676f, 38f, if (mul >= 1f) 0xFF00E676.toInt() else 0xFFFF8A80.toInt())
        text(c, "Действуют только в обычных забегах, не в босс-раше", 180f, 706f, 11f, 0x88FFFFFF.toInt(), maxW = 330f)
        btn(c, "▶  ИГРАТЬ", 50f, 724f, 260f, 56f, k(0xFF00C853)) { startRun() }
        c.restore()
    }

    // ---------- итог босс-раша ----------

    private fun drawRushResult(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0x88000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)
        val a = min(1f, sceneTime * 3f)
        c.save()
        c.translate(0f, (1f - a) * 40f)
        panel(c, 24f, 150f, 312f, 430f, 0xEE231A3D.toInt(), 24f)
        val train = mode == Mode.RUSH_TRAIN
        if (rushWon) {
            text(c, "ПОБЕДА!", 180f, 205f, 44f, 0xFF00E676.toInt())
            text(c, Bosses.all[rushBoss].name + " повержен", 180f, 236f, 15f, 0xCCFFFFFF.toInt(), maxW = 280f)
            text(c, "%.1f с".format(rushTime), 180f, 310f, 60f, maxW = 280f)
            if (train) {
                text(c, "тренировка — награды нет", 180f, 350f, 15f, 0xAAFFFFFF.toInt())
            } else {
                if (rushNewBest) text(c, "🏆 НОВЫЙ РЕКОРД!", 180f, 350f, 22f, 0xFFFFD54F.toInt())
                else text(c, "лучшее: " + fmtTime(save.rushBest[rushBoss]), 180f, 350f, 17f, 0xAAFFFFFF.toInt())
                text(c, "💰 +$rushReward", 180f, 396f, 30f, 0xFFFFE082.toInt())
                if (rushFirstWin) text(c, "🎁 первая победа: бесплатный бокс!", 180f, 428f, 15f, 0xFF00E676.toInt(), maxW = 290f)
            }
        } else {
            text(c, "ПОРАЖЕНИЕ", 180f, 205f, 40f, 0xFFFF8A80.toInt())
            text(c, deathReason, 180f, 238f, 15f, 0xCCFFFFFF.toInt(), maxW = 280f)
            val hp = sim.boss?.hp
            text(c, if (hp != null) "боссу осталось: $hp ❤" else "", 180f, 320f, 22f, 0xFFFFD54F.toInt())
            text(c, if (train) "тренируйся сколько нужно" else "попробуй ещё!", 180f, 356f, 15f, 0xAAFFFFFF.toInt())
        }
        btn(c, "ЕЩЁ РАЗ", 48f, 450f, 264f, 56f, k(0xFF00C853)) { restartCurrent() }
        btn(c, "К БОССАМ", 48f, 516f, 264f, 48f, k(0xFFE53935)) { scene = Scene.RUSH; sceneTime = 0f }
        c.restore()
        c.restore()
    }

    private fun drawSaw(c: Canvas, x: Float, y: Float, ang: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(ang * 57.3f)
        path.reset()
        val teeth = 10
        for (i in 0 until teeth * 2) {
            val a = i * PI.toFloat() / teeth
            val rr = if (i % 2 == 0) 17f else 11f
            if (i == 0) path.moveTo(cos(a) * rr, sin(a) * rr) else path.lineTo(cos(a) * rr, sin(a) * rr)
        }
        path.close()
        p.style = Paint.Style.FILL
        p.color = k(0xFFCFD8DC)
        c.drawPath(path, p)
        p.color = k(0xFF90A4AE)
        c.drawCircle(0f, 0f, 8f, p)
        p.color = k(0xFFFF4D6D)
        c.drawCircle(0f, 0f, 4.5f, p)
        c.restore()
    }

    private fun drawHud(c: Canvas) {
        c.save()
        c.scale(scale, scale)
        val m = (-minPy / 10f).toInt()
        if (mode == Mode.NORMAL) {
            text(c, "$m м", W / 2, 66f, 40f)
            if (save.best > 0) text(c, "рекорд ${save.best} м", W / 2, 86f, 13f, 0xBBFFD54F.toInt())
            panel(c, 12f, 36f, 96f, 30f, 0x55000000)
            text(c, "💰 ${runCoins.toInt()}", 20f, 58f, 19f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 84f)
            if (coinMul > 1f || coinMul < 1f) text(c, "x${fmt(coinMul)}", 12f, 82f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT)
            if (combo >= 3) text(c, "комбо x$combo", 12f, 100f, 13f, 0xFFFFD54F.toInt(), Paint.Align.LEFT)
            var ix = 12f
            for (md in Mods.all) if (Mods.has(save.mods, md.id)) { text(c, md.emoji, ix, 122f, 14f, shadow = false); ix += 19f }
        } else {
            val t = if (sim.boss != null || rushWon) (if (rushWon) rushTime else sim.time - rushT0) else 0f
            text(c, if (mode == Mode.RUSH_TIME) "⏱ %.1f".format(t) else "🎯 тренировка", W / 2, 66f, 38f, maxW = 220f)
        }
        // босс: полоска здоровья, баннер, подсказка
        val bs = sim.boss
        if (bs != null && bs.state != Boss.DEAD) {
            panel(c, 40f, 94f, 280f, 34f, 0x99000000.toInt(), 17f)
            text(c, "ТОЛСТЫЙ КОТОЗАЯЦ", W / 2, 111f, 12f, 0xFFFFF3C4.toInt(), maxW = 250f)
            val segW = 250f / bs.hpMax
            for (i in 0 until bs.hpMax) {
                p.style = Paint.Style.FILL
                p.color = if (i < bs.hp) k(0xFFFF5470) else 0x44FFFFFF
                c.drawRoundRect(55f + i * segW + 2f, 117f, 55f + (i + 1) * segW - 2f, 123f, 3f, 3f, p)
            }
        }
        if (bs != null && bs.state != Boss.DEAD && bs.state != Boss.ENTER) {
            val angry = Boss.angry(bs.state)
            val label = if (angry) "🔥 ЗЛОЙ — не трогай!" else if (bs.state == Boss.STUN) "💫 оглушён" else "😌 спокоен — бей!"
            text(c, label, W / 2, 148f, 13f, if (angry) 0xFFFF8A80.toInt() else 0xFFA5D6A7.toInt(), maxW = 260f)
        }
        // кнопка стрельбы: появляется, когда есть патроны
        if (bs != null && sim.ammo > 0 && bs.state != Boss.DEAD) {
            val bx = W - 46f
            val by = viewH - 120f
            val calm = !Boss.angry(bs.state) && bs.state != Boss.STUN
            val pulse = if (calm) 1f + 0.06f * sin(anim * 9f) else 1f
            p.style = Paint.Style.FILL
            p.color = if (calm) 0xDDFF9800.toInt() else 0x99666666.toInt()
            c.drawCircle(bx, by, 34f * pulse, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(bx, by, 34f * pulse, p)
            p.style = Paint.Style.FILL
            drawBulletIcon(c, bx, by - 2f, 1.5f)
            text(c, "×${sim.ammo}", bx + 22f, by + 30f, 15f, 0xFFFFFFFF.toInt())
            if (calm) text(c, "ОГОНЬ", bx, by - 42f, 11f, 0xFFFFE082.toInt())
        }
        if (bossBanner > 0f) {
            val ba = min(1f, bossBanner).coerceIn(0f, 1f)
            val col = ((ba * 255).toInt() shl 24) or 0xFFFF1744.toInt().and(0xFFFFFF)
            text(c, "⚠ БОСС ⚠", W / 2, viewH * 0.36f, 40f, col, maxW = 330f)
            text(c, "ТОЛСТЫЙ КОТОЗАЯЦ", W / 2, viewH * 0.36f + 34f, 24f, ((ba * 255).toInt() shl 24) or 0xFFFFFF, maxW = 330f)
        } else if (bossHint > 0f && bs != null) {
            val ha = min(1f, bossHint).coerceIn(0f, 1f)
            text(c, "Пока он спокоен — коснись его! Злого не трогай", W / 2, viewH * 0.8f, 15f, ((ha * 255).toInt() shl 24) or 0xFFFFFF, maxW = 340f)
            text(c, "Кольцо пуха: лети в зелёный проход. Патроны — для стрельбы", W / 2, viewH * 0.8f + 20f, 13f, ((ha * 255).toInt() shl 24) or 0xFFFFE082.toInt().and(0xFFFFFF), maxW = 340f)
        }
        // кнопка паузы
        panel(c, W - 48f, 14f, 36f, 36f, 0x77000000, 18f)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(W - 38f, 24f, W - 33f, 40f, 2f, 2f, p)
        c.drawRoundRect(W - 27f, 24f, W - 22f, 40f, 2f, 2f, p)
        if (inCannon != null && (anim * 4f).toInt() % 2 == 0) {
            text(c, "ТАП — ВЫСТРЕЛ!", W / 2, viewH * 0.3f, 24f, 0xFFFFC107.toInt(), maxW = 300f)
        } else if (runTime < 7f && inCannon == null && sim.boss == null) {
            val a = (1f - runTime / 7f).coerceIn(0f, 1f)
            val col = ((a * 255).toInt() shl 24) or 0xFFFFFF
            text(c, "ТАП — прыжок на другую стену", W / 2, viewH * 0.78f, 17f, col, maxW = 320f)
            text(c, "в воздухе ещё 2 рывка:", W / 2, viewH * 0.78f + 24f, 14f, col, maxW = 320f)
            text(c, "ТАП — вперёд  ·  СВАЙП ← → — в сторону", W / 2, viewH * 0.78f + 44f, 14f, col, maxW = 320f)
            text(c, "на стене держишься ~1 сек, потом срываешься!", W / 2, viewH * 0.78f + 68f, 14f, ((a * 255).toInt() shl 24) or 0xFFAB91, maxW = 330f)
        }
        c.restore()
    }

    private fun drawPause(c: Canvas) {
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0xAA000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)
        text(c, "ПАУЗА", 180f, 230f, 48f)
        text(c, "ТАП — прыжок со стены, в воздухе — рывок вперёд", 180f, 262f, 12f, 0xCCFFFFFF.toInt(), maxW = 330f)
        text(c, "СВАЙП ← → в воздухе — рывок в сторону (хоть назад)", 180f, 280f, 12f, 0xCCFFFFFF.toInt(), maxW = 330f)
        btn(c, "▶  ПРОДОЛЖИТЬ", 50f, 300f, 260f, 64f, k(0xFF00C853)) { paused = false }
        btn(c, if (save.sound) "🔊  ЗВУК: ВКЛ" else "🔇  ЗВУК: ВЫКЛ", 50f, 382f, 260f, 52f, k(0xFF5C6BC0)) { toggleSound() }
        btn(c, "🏠  В МЕНЮ", 50f, 452f, 260f, 52f, k(0xFF7C4DFF)) { goMenu() }
        c.restore()
    }

    private fun drawDeadOverlay(c: Canvas) {
        if (mode != Mode.NORMAL) { drawRushResult(c); return }
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        p.color = 0x88000000.toInt()
        c.drawRect(-uiOx / ui, -uiOy / ui, 360f + uiOx / ui, 800f + uiOy / ui, p)

        val a = min(1f, sceneTime * 3f)
        c.save()
        c.translate(0f, (1f - a) * 40f)
        panel(c, 24f, 150f, 312f, 430f, 0xEE231A3D.toInt(), 24f)
        text(c, "УПС!", 180f, 205f, 44f, 0xFFFF8A80.toInt())
        text(c, deathReason, 180f, 238f, 16f, 0xCCFFFFFF.toInt(), maxW = 280f)

        val score = (-minPy / 10f).toInt()
        text(c, "$score м", 180f, 310f, 64f, 0xFFFFFFFF.toInt())
        if (newRecord) text(c, "🏆 НОВЫЙ РЕКОРД!", 180f, 345f, 22f, 0xFFFFD54F.toInt())
        else text(c, "рекорд: ${save.best} м", 180f, 345f, 18f, 0xAAFFFFFF.toInt())

        text(c, "💰 +$earned", 180f, 395f, 30f, 0xFFFFE082.toInt())
        if (gotSnacks > 0) text(c, "съедено вкусняшек: $gotSnacks 🍔", 180f, 424f, 16f, 0xFFFFAB91.toInt())

        btn(c, "ЕЩЁ РАЗ", 48f, 450f, 264f, 56f, k(0xFF00C853)) { startRun() }
        btn(c, "🐾 ФЕРМА", 48f, 516f, 264f, 48f, k(0xFF7C4DFF)) { goFarm() }
        c.restore()
        c.restore()
    }

    // =====================================================================
    // Меню
    // =====================================================================

    private fun drawMenu(c: Canvas) {
        checkUpdates()
        drawBackground(c, k(0xFF1B1035), k(0xFF5A2A8A), anim * 40f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)

        text(c, "ТОЛСТЫЙ", 180f, 118f, 54f, if (RAINBOW_DARK) rainbow(0f) else k(0xFFFFD54F))
        text(c, "ТИЛКАЙО", 180f, 176f, 60f, if (RAINBOW_DARK) rainbow(120f) else k(0xFFFFFFFF))
        text(c, "прыгай · собирай · откармливай · размножай", 180f, 206f, 13f, 0xBBFFFFFF.toInt(), maxW = 330f)

        val lvl = save.level(save.selected)
        val bob = sin(anim * 2.5f)
        drawTilcayo(c, 180f, 345f + bob * 8f, 38f + lvl * 2.6f, 1, bob * 4f, 1f + bob * 0.03f, 1f - bob * 0.03f, lvl, save.skinOf(save.selected))
        text(c, Balance.names[save.selected % Balance.names.size] + " · ур. $lvl", 180f, 440f, 19f, 0xFFFFF3C4.toInt())

        btn(c, "▶  ИГРАТЬ", 50f, 462f, 260f, 78f, k(0xFF00C853)) { startRun() }
        btn(c, "🐾  ФЕРМА", 50f, 552f, 126f, 58f, k(0xFF7C4DFF),
            sub = if (save.pending() > 0) "+${save.pending()} 💰" else if (save.freeBoxes > 0) "🎁 бокс ждёт" else null) { goFarm() }
        btn(c, "⚔  БОСС-РАШ", 184f, 552f, 126f, 58f, k(0xFFE53935)) { scene = Scene.RUSH; sceneTime = 0f }
        val mc = Mods.count(save.mods)
        btn(c, "⚙  МОДИФИКАТОРЫ", 50f, 622f, 200f, 56f, k(0xFF00897B),
            sub = if (mc > 0) "$mc вкл · монеты x" + "%.2f".format(Mods.multiplier(save.mods)) else "нет") { scene = Scene.MODS; sceneTime = 0f }
        btn(c, if (save.sound) "🔊" else "🔇", 258f, 622f, 52f, 56f, k(0xFF5C6BC0)) { toggleSound() }

        // плашка «доступно обновление» — только в главном меню
        if (save.remoteCode > installedCode && save.dismissedCode != save.remoteCode) {
            panel(c, 10f, 698f, 340f, 90f, 0xEE0D47A1.toInt(), 18f)
            text(c, "🔔 Доступна версия ${save.remoteName}", 20f, 722f, 16f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 320f)
            if (save.remoteNotes.isNotBlank()) {
                for ((j, ln) in wrap(save.remoteNotes, 10.5f, 320f).take(2).withIndex()) text(c, ln, 20f, 738f + j * 12f, 10.5f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT, shadow = false)
            }
            btn(c, "СКАЧАТЬ", 20f, 748f, 150f, 34f, k(0xFF00C853)) { openDownload() }
            btn(c, "ПОЗЖЕ", 180f, 748f, 100f, 34f, k(0xFF5C6BC0)) { save.dismissedCode = save.remoteCode; save.save() }
        }

        panel(c, 20f, 20f, 120f, 34f, 0x66000000)
        text(c, "💰 ${save.coins}", 30f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 104f)
        panel(c, 220f, 20f, 120f, 34f, 0x66000000)
        text(c, "🏆 ${save.best} м", 230f, 44f, 20f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 104f)
        c.restore()
    }

    // =====================================================================
    // Ферма: луг, по которому бродят тилкайо
    // =====================================================================

    private fun depthScale(y: Float) = 0.78f + 0.32f * ((y - GY0) / (GY1 - GY0)).coerceIn(0f, 1f)

    private fun petRadius(i: Int, pet: Pet) = (14f + save.level(i) * 2.4f) * depthScale(pet.y)

    private fun syncPets() {
        // при первом заходе расставляем всех по лугу, а новорождённый появляется рядом с родителем
        val initial = pets.isEmpty()
        while (pets.size < save.herd.size) {
            val parent = if (initial) null else pets.getOrNull(save.selected)
            val pet = Pet(
                (parent?.x ?: (GX0 + rnd.nextFloat() * (GX1 - GX0))) + (if (parent != null) (rnd.nextFloat() - 0.5f) * 30f else 0f),
                (parent?.y ?: (GY0 + rnd.nextFloat() * (GY1 - GY0))) + (if (parent != null) (rnd.nextFloat() - 0.5f) * 20f else 0f),
            )
            pet.x = pet.x.coerceIn(GX0, GX1)
            pet.y = pet.y.coerceIn(GY0, GY1)
            pet.face = if (rnd.nextBoolean()) 1 else -1
            pets.add(pet)
            if (!initial) hop(pet, 2)
        }
        while (pets.size > save.herd.size) pets.removeAt(pets.size - 1)
    }

    private fun hop(pet: Pet, n: Int) {
        pet.st = Pet.HOP
        pet.hops = n - 1
        pet.vz = 150f
        pet.hvx = pet.face * 20f
    }

    private fun flip(pet: Pet) {
        pet.st = Pet.FLIP
        pet.hops = 0
        pet.vz = 270f
        pet.spin = 0f
        pet.hvx = pet.face * 45f
    }

    private fun pickNext(pet: Pet) {
        val food = farmFoods.filter { it.owner === pet }.minByOrNull { hypot(it.x - pet.x, it.y - pet.y) }
        if (food != null) {
            pet.st = Pet.WALK; pet.tx = food.x; pet.ty = food.y
            return
        }
        val x = rnd.nextFloat()
        when {
            x < 0.45f -> {
                pet.st = Pet.WALK
                pet.tx = GX0 + rnd.nextFloat() * (GX1 - GX0)
                pet.ty = GY0 + rnd.nextFloat() * (GY1 - GY0)
            }
            x < 0.72f -> { hop(pet, 1 + rnd.nextInt(3)); sfx.play(Sfx.S.HOP, 0.9f + rnd.nextFloat() * 0.4f, 0.35f) }
            x < 0.82f -> { flip(pet); sfx.play(Sfx.S.HOP, 1.3f, 0.4f) }
            else -> { pet.st = Pet.IDLE; pet.t = 0.8f + rnd.nextFloat() * 1.8f }
        }
    }

    private fun updatePets(dt: Float) {
        // еда падает
        for (f in farmFoods) {
            if (f.z > 0f || f.vz > 0f) {
                f.vz -= 800f * dt
                f.z += f.vz * dt
                if (f.z <= 0f) { f.z = 0f; f.vz = if (f.vz < -200f) -f.vz * 0.3f else 0f }
            }
        }
        for ((i, pet) in pets.withIndex()) {
            val sp = max(18f, 56f - save.level(i) * 3.5f)
            pet.squash = max(0f, pet.squash - dt * 4f)
            when (pet.st) {
                Pet.IDLE -> {
                    if (pet.dizzy > 0f) {
                        pet.dizzy -= dt
                    } else {
                        pet.t -= dt
                        if (pet.t <= 0f) pickNext(pet)
                        if (farmFoods.any { it.owner === pet }) pickNext(pet)
                    }
                }
                Pet.WALK -> {
                    val food = farmFoods.filter { it.owner === pet }.minByOrNull { hypot(it.x - pet.x, it.y - pet.y) }
                    if (food != null) { pet.tx = food.x; pet.ty = food.y }
                    val dx = pet.tx - pet.x
                    val dy = pet.ty - pet.y
                    val dist = hypot(dx, dy)
                    if (dist < (if (food != null) 12f else 3f)) {
                        if (food != null && food.z < 6f) {
                            farmFoods.remove(food)
                            pet.st = Pet.EAT
                            pet.t = 0.9f
                            sfx.play(Sfx.S.EAT)
                            uiPops.add(Pop(pet.x, pet.y - petRadius(i, pet) * 2.4f, "ням!", 0xFFFFAB91.toInt(), 18f))
                        } else if (food == null) {
                            pet.st = Pet.IDLE
                            pet.t = 0.4f + rnd.nextFloat() * 1.5f
                        }
                    } else {
                        pet.x += dx / dist * sp * dt
                        pet.y += dy / dist * sp * dt
                        if (abs(dx) > 2f) pet.face = if (dx > 0f) 1 else -1
                        pet.ph += dt * sp * 0.03f
                    }
                }
                Pet.HOP, Pet.FLIP -> {
                    pet.vz -= 700f * dt
                    pet.z += pet.vz * dt
                    pet.x += pet.hvx * dt
                    if (pet.st == Pet.FLIP) pet.spin += dt * 480f
                    if (pet.z <= 0f) {
                        pet.z = 0f
                        pet.spin = 0f
                        if (pet.st == Pet.HOP && pet.hops > 0) {
                            pet.hops--
                            pet.vz = 150f
                        } else {
                            pet.st = Pet.IDLE
                            pet.t = 0.4f + rnd.nextFloat() * 1.2f
                        }
                    }
                }
                Pet.EAT -> {
                    pet.t -= dt
                    if (pet.t <= 0f) { pet.st = Pet.IDLE; pet.t = 0.3f }
                }
                Pet.DRAG -> {
                    // позицию задаёт палец; крен — по скорости движения
                    pet.spin += (pet.gvx * 0.04f - pet.spin) * min(1f, dt * 8f)
                }
                Pet.FLY -> {
                    pet.vz -= 700f * dt
                    pet.z += pet.vz * dt
                    pet.x += pet.gvx * dt
                    pet.y += pet.gvy * dt
                    pet.spin += pet.spinV * dt
                    // отскок от забора и краёв луга
                    if (pet.x < GX0) { pet.x = GX0; pet.gvx = abs(pet.gvx) * 0.6f; pet.spinV *= -0.6f }
                    if (pet.x > GX1) { pet.x = GX1; pet.gvx = -abs(pet.gvx) * 0.6f; pet.spinV *= -0.6f }
                    if (pet.y < GY0) { pet.y = GY0; pet.gvy = abs(pet.gvy) * 0.5f }
                    if (pet.y > GY1) { pet.y = GY1; pet.gvy = -abs(pet.gvy) * 0.5f }
                    if (abs(pet.gvx) > 6f) pet.face = if (pet.gvx > 0f) 1 else -1
                    if (pet.z <= 0f) {
                        pet.z = 0f
                        if (pet.vz < -110f) {
                            pet.vz = -pet.vz * 0.42f
                            pet.gvx *= 0.7f
                            pet.gvy *= 0.7f
                            pet.spinV *= 0.55f
                            pet.squash = 1f
                            sfx.play(Sfx.S.LAND, 0.8f + rnd.nextFloat() * 0.3f, 0.8f)
                        } else {
                            pet.vz = 0f
                            val fr = max(0f, 1f - 5f * dt)
                            pet.gvx *= fr
                            pet.gvy *= fr
                            pet.spinV *= fr
                            pet.spin += (0f - pet.spin) * min(1f, dt * 8f)
                            if (hypot(pet.gvx, pet.gvy) < 14f) {
                                pet.st = Pet.IDLE
                                pet.t = 0.6f
                                pet.spin = 0f
                                pet.spinV = 0f
                                pet.gvx = 0f
                                pet.gvy = 0f
                            }
                        }
                    }
                }
            }
            pet.x = pet.x.coerceIn(GX0 - 12f, GX1 + 12f)
            pet.y = pet.y.coerceIn(GY0, GY1)
        }
    }

    /** Какой тилкайо под пальцем (самый «передний»), либо -1. */
    private fun petAt(ux: Float, uy: Float): Int {
        var best = -1
        var bestY = -1f
        for ((i, pet) in pets.withIndex()) {
            val rad = petRadius(i, pet)
            val cy = pet.y - rad * 1.1f - pet.z
            if (abs(ux - pet.x) < rad * 1.25f && uy > cy - rad * 1.25f && uy < cy + rad * 1.2f && pet.y > bestY) {
                best = i
                bestY = pet.y
            }
        }
        return best
    }

    private fun farmDown(ux: Float, uy: Float, id: Int) {
        if (viewFarm >= save.unlocked) return
        val i = petAt(ux, uy)
        if (i < 0) return
        dragPtr = id
        dragIdx = i
        dragMoved = false
        dragX0 = ux
        dragY0 = uy
        histN = 0
    }

    private fun pushHist(ux: Float, uy: Float) {
        val tms = (System.nanoTime() / 1_000_000L).toFloat()
        if (histN == 6) {
            for (j in 0 until 15) hist[j] = hist[j + 3]
            histN = 5
        }
        hist[histN * 3] = ux; hist[histN * 3 + 1] = uy; hist[histN * 3 + 2] = tms
        histN++
    }

    private fun farmMove(ux: Float, uy: Float) {
        if (dragIdx < 0 || dragIdx >= pets.size) return
        val pet = pets[dragIdx]
        if (!dragMoved && hypot(ux - dragX0, uy - dragY0) > 10f) {
            dragMoved = true
            pet.st = Pet.DRAG
            pet.vz = 0f
            pet.spin = 0f
            pet.spinV = 0f
            save.selected = dragIdx
            save.save()
            sfx.play(Sfx.S.HOP, 1.4f, 0.6f)
        }
        if (!dragMoved) return
        val rad = petRadius(dragIdx, pet)
        val gy = (uy + rad * 1.1f + 24f).coerceIn(GY0, GY1)
        pet.gvx = (ux - pet.x) * 3f
        pet.x = ux.coerceIn(GX0 - 12f, GX1 + 12f)
        pet.y = gy
        pet.z = max(24f, gy - rad * 1.1f - uy)
        if (abs(pet.gvx) > 20f) pet.face = if (pet.gvx > 0f) 1 else -1
        pushHist(ux, uy)
    }

    private fun farmUp() {
        val idx = dragIdx
        dragPtr = -1
        dragIdx = -1
        if (idx < 0 || idx >= pets.size) return
        val pet = pets[idx]
        if (!dragMoved) {
            // обычный тап: выбрать и подпрыгнуть
            save.selected = idx
            save.save()
            if (pet.st != Pet.HOP && pet.st != Pet.FLIP) { if (rnd.nextInt(3) == 0) flip(pet) else hop(pet, 2) }
            sfx.play(Sfx.S.HOP, 1.1f, 0.6f)
            uiPops.add(Pop(pet.x, pet.y - petRadius(idx, pet) * 2.4f, "💛", 0xFFFFFFFF.toInt(), 22f))
            return
        }
        // бросок: скорость пальца за последние ~100 мс
        var vx = 0f
        var vy = 0f
        if (histN >= 2) {
            val tNow = hist[(histN - 1) * 3 + 2]
            var j = histN - 2
            while (j > 0 && tNow - hist[j * 3 + 2] < 100f) j--
            val dtMs = tNow - hist[j * 3 + 2]
            if (dtMs > 15f && (System.nanoTime() / 1_000_000L).toFloat() - tNow < 120f) {
                vx = (hist[(histN - 1) * 3] - hist[j * 3]) / dtMs * 1000f
                vy = (hist[(histN - 1) * 3 + 1] - hist[j * 3 + 1]) / dtMs * 1000f
            }
        }
        val speed = hypot(vx, vy)
        pet.st = Pet.FLY
        if (speed < 60f) {
            pet.gvx = 0f; pet.gvy = 0f; pet.vz = 0f; pet.spinV = 0f
        } else {
            pet.gvx = (vx * 0.85f).coerceIn(-650f, 650f)
            pet.gvy = (vy * 0.4f).coerceIn(-260f, 260f)
            pet.vz = (-vy * 0.7f + 30f).coerceIn(-200f, 620f)
            pet.spinV = (vx * 0.9f).coerceIn(-900f, 900f)
            if (speed > 700f) pet.dizzy = 1.6f
            sfx.play(Sfx.S.THROW, 0.8f + min(0.8f, speed / 1200f), 0.6f)
        }
    }

    private fun goWardrobe() {
        scene = Scene.WARDROBE
        sceneTime = 0f
        boxStage = 0
    }

    private fun selectFarm(i: Int) {
        viewFarm = i
        if (i < save.unlocked && save.activeFarm != i) {
            save.activeFarm = i
            save.save()
            farmFoods.clear()
            dragIdx = -1
            dragPtr = -1
        }
    }

    // ---------- рисование фермы ----------

    private fun drawFarmScenery(c: Canvas, th: FarmTheme, ox: Float, ow: Float) {
        p.style = Paint.Style.FILL
        // солнце / Земля
        when (th.sun) {
            0 -> { p.color = 0x55FFF59D; c.drawCircle(300f, 165f, 44f, p); p.color = k(0xFFFFEB3B); c.drawCircle(300f, 165f, 30f, p) }
            1 -> { p.color = 0x55FFB74D; c.drawCircle(290f, 175f, 56f, p); p.color = k(0xFFFF9800); c.drawCircle(290f, 175f, 38f, p) }
            2 -> { p.color = 0x44FFFFFF; c.drawCircle(300f, 165f, 40f, p); p.color = k(0xFFFFF8E1); c.drawCircle(300f, 165f, 26f, p) }
            else -> text(c, "🌍", 296f, 190f, 70f, shadow = false)
        }
        // облака
        if (!th.stars) {
            for (i in 0 until 3) {
                val cx = ((anim * (6f + i * 3f) + i * 140f) % 480f) - 60f
                val cy = 150f + i * 26f
                p.color = if (th.sun == 1) 0xAAFFF3E0.toInt() else 0xCCFFFFFF.toInt()
                c.drawCircle(cx, cy, 15f, p); c.drawCircle(cx + 16f, cy - 8f, 19f, p)
                c.drawCircle(cx + 36f, cy, 15f, p); c.drawRect(cx, cy, cx + 36f, cy + 15f, p)
            }
        }
        // холмы
        for (layer in 0..1) {
            path.reset()
            val base = if (layer == 0) 238f else 250f
            path.moveTo(ox, 320f)
            var xx = ox
            while (xx <= ox + ow + 10f) {
                path.lineTo(xx, base - sin(xx * 0.018f + layer * 2f) * (if (layer == 0) 16f else 10f))
                xx += 10f
            }
            path.lineTo(ox + ow, 320f)
            path.close()
            p.color = if (layer == 0) th.hillA else th.hillB
            c.drawPath(path, p)
        }
        text(c, th.props, 28f, 258f, 56f, shadow = false)
        text(c, th.props, 334f, 254f, 48f, shadow = false)

        // луг с полосами
        p.color = th.ground
        c.drawRect(ox, 252f, ox + ow, 515f, p)
        var by = 252f
        var bhh = 12f
        var band = 0
        while (by < 515f) {
            if (band % 2 == 0) { p.color = 0x14FFFFFF; c.drawRect(ox, by, ox + ow, min(515f, by + bhh), p) }
            by += bhh; bhh *= 1.13f; band++
        }
        // забор
        var fx = ox
        while (fx < ox + ow) {
            p.color = th.fence
            c.drawRoundRect(fx, 246f, fx + 8f, 276f, 3f, 3f, p)
            p.color = th.fenceDark
            c.drawRect(fx + 5f, 248f, fx + 8f, 276f, p)
            fx += 38f
        }
        p.color = th.fence
        c.drawRect(ox, 254f, ox + ow, 260f, p)
        c.drawRect(ox, 266f, ox + ow, 272f, p)
        // украшения
        for (d in decor) text(c, th.decor[d.third % th.decor.size], d.first, d.second, 13f, shadow = false)
    }

    private fun drawFarm(c: Canvas, dt: Float) {
        val ti = viewFarm.coerceIn(0, Farms.COUNT - 1)
        val th = Farms.all[ti]
        val locked = ti >= save.unlocked
        if (!locked) {
            if (save.activeFarm != ti) selectFarm(ti)
            syncPets()
            updatePets(dt)
        }
        stepFx(dt)
        drawBackground(c, th.skyTop, th.skyBot, 0f, withStars = th.stars)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)
        val ox = -uiOx / ui - 4f
        val ow = 368f + 2 * uiOx / ui

        drawFarmScenery(c, th, ox, ow)

        if (!locked) {
            // еда на земле
            for (f in farmFoods) {
                p.color = 0x33000000
                rect.set(f.x - 9f, f.y - 3f, f.x + 9f, f.y + 3f)
                c.drawOval(rect, p)
                text(c, f.emoji, f.x, f.y - 4f - f.z, 20f, shadow = false)
            }

            // тилкайо, отсортированные по глубине; несомый — всегда сверху
            val order = pets.indices.sortedBy { if (pets[it].st == Pet.DRAG) 10000f else pets[it].y }
            for (i in order) {
                val pet = pets[i]
                val lvl = save.level(i)
                val rad = petRadius(i, pet)
                val sel = i == save.selected
                // тень
                val sh = 1f / (1f + pet.z / 90f)
                p.style = Paint.Style.FILL
                p.color = ((0x40 * sh).toInt() shl 24)
                rect.set(pet.x - rad * 1.15f * sh, pet.y - rad * 0.2f, pet.x + rad * 1.15f * sh, pet.y + rad * 0.2f)
                c.drawOval(rect, p)
                if (sel) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f
                    p.color = k(0xFFFFD54F)
                    val pulse = 1f + 0.06f * sin(anim * 5f)
                    rect.set(pet.x - rad * 1.35f * pulse, pet.y - rad * 0.3f * pulse, pet.x + rad * 1.35f * pulse, pet.y + rad * 0.3f * pulse)
                    c.drawOval(rect, p)
                    p.style = Paint.Style.FILL
                }
                var rot = 0f
                var sx = 1f + 0.02f * sin(anim * 2f + i)
                var sy = 1f - 0.02f * sin(anim * 2f + i)
                var bounce = 0f
                when (pet.st) {
                    Pet.WALK -> { rot = sin(pet.ph * 2f * PI.toFloat()) * 6f; bounce = abs(sin(pet.ph * 2f * PI.toFloat())) * 2.5f }
                    Pet.FLIP -> rot = pet.spin * pet.face
                    Pet.EAT -> { sy = 1f - 0.08f * abs(sin(pet.t * 20f)); sx = 1f + 0.05f * abs(sin(pet.t * 20f)) }
                    Pet.HOP -> { val st = (pet.vz / 200f).coerceIn(-0.2f, 0.2f); sx = 1f - st * 0.4f; sy = 1f + st * 0.5f }
                    Pet.DRAG -> { rot = pet.spin.coerceIn(-25f, 25f); sy = 1.08f; sx = 0.94f }
                    Pet.FLY -> rot = pet.spin
                    Pet.IDLE -> if (pet.dizzy > 0f) rot = sin(anim * 14f) * 8f
                }
                if (pet.squash > 0f) { sy *= 1f - 0.22f * pet.squash; sx *= 1f + 0.16f * pet.squash }
                val cy = pet.y - rad * 1.1f - pet.z - bounce
                drawTilcayo(c, pet.x, cy, rad, pet.face, rot, sx, sy, lvl, save.skinOf(i))
                if (pet.dizzy > 0f && pet.st != Pet.DRAG) text(c, "💫", pet.x, cy - rad * 1.5f, 20f, shadow = false)
                if (pet.st == Pet.DRAG) {
                    text(c, "🤏", pet.x, cy - rad * 1.45f, 16f, shadow = false)
                } else if (sel) {
                    text(c, "⭐ ${Balance.names[i % Balance.names.size]} · ур.$lvl", pet.x, cy - rad * 1.55f, 12f, 0xFFFFF3C4.toInt(), maxW = 110f)
                } else {
                    text(c, "ур.$lvl", pet.x, cy - rad * 1.25f, 10f, 0xCCFFFFFF.toInt())
                }
            }
            drawParticles(c)
        }

        // снегопад
        if (th.snow) {
            p.style = Paint.Style.FILL
            p.color = 0xCCFFFFFF.toInt()
            for ((i, f) in snowflakes.withIndex()) {
                val y = (f.second + anim * 28f * f.third) % 520f
                val x = f.first + sin(anim * 1.2f + i) * 8f
                c.drawCircle(x, y, 1.2f + f.third, p)
            }
        }

        // нижняя панель
        p.style = Paint.Style.FILL
        p.color = k(0xFF3B2A1C)
        c.drawRect(ox, 512f, ox + ow, 800f + uiOy / ui + 4f, p)
        p.color = k(0xFF5C4129)
        c.drawRect(ox, 512f, ox + ow, 518f, p)

        // шапка
        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { goMenu() }
        text(c, "ФЕРМА · ${th.name.uppercase()}", 154f, 48f, 26f, maxW = 176f)
        panel(c, 250f, 18f, 100f, 36f, 0x66000000)
        text(c, "💰 ${save.coins}", 258f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 86f)

        // доход
        panel(c, 10f, 70f, 340f, 52f, 0x66000000)
        text(c, "Доход: ${fmt(save.incomePerMin())} 💰/мин", 20f, 93f, 17f, 0xFFFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        text(c, "монет в забеге: x${fmt(save.coinMultiplier())}", 20f, 112f, 12f, 0xAAFFFFFF.toInt(), Paint.Align.LEFT, maxW = 170f)
        val pend = save.pending()
        btn(c, "СОБРАТЬ", 196f, 76f, 148f, 40f, k(0xFFFFA000), enabled = pend > 0) {
            val got = save.collect()
            toast("Собрано +$got 💰")
            sfx.play(Sfx.S.BUY)
        }
        if (pend > 0) text(c, "+$pend", 270f, 70f, 13f, 0xFFFFE082.toInt())

        // вкладки ферм и гардероб
        for (i in 0 until Farms.COUNT) {
            val isLocked = i >= save.unlocked
            val label = if (isLocked) "🔒" else Farms.all[i].emoji
            val col = if (i == ti) k(0xFF00C853) else if (isLocked) k(0xFF55556A) else k(0xFF5C6BC0)
            btn(c, label, 8f + i * 67f, 128f, 62f, 34f, col) { selectFarm(i) }
        }
        btn(c, "👗 Скины", 278f, 128f, 74f, 34f, k(0xFFE91E63), sub = null) { goWardrobe() }
        if (save.freeBoxes > 0) text(c, "🎁${save.freeBoxes}", 346f, 128f, 14f, shadow = true)

        if (locked) {
            drawLockedFarm(c, ti, th)
            btn(c, "▶  В ЗАБЕГ", 8f, 708f, 344f, 70f, k(0xFF00C853)) { startRun() }
        } else {
            drawFarmControls(c)
        }

        // всплывашки
        for (q in uiPops) {
            val a = q.life.coerceIn(0f, 1f)
            text(c, q.text, q.x, q.y - (1f - q.life) * 50f, q.size, (q.color and 0x00FFFFFF) or ((a * 255).toInt() shl 24))
        }
        if (toastT > 0f) {
            val a = min(1f, toastT * 3f)
            panel(c, 40f, 190f, 280f, 34f, ((a * 200).toInt() shl 24), 17f)
            text(c, toastText, 180f, 213f, 17f, ((a * 255).toInt() shl 24) or 0xFFFFFF, maxW = 260f)
        }
        c.restore()
    }

    private fun drawLockedFarm(c: Canvas, ti: Int, th: FarmTheme) {
        p.style = Paint.Style.FILL
        p.color = 0x99000000.toInt()
        c.drawRect(-200f, 252f, 560f, 512f, p)
        panel(c, 30f, 290f, 300f, 200f, 0xEE231A3D.toInt(), 22f)
        text(c, "🔒 ${th.emoji} ${th.name}", 180f, 330f, 26f, maxW = 270f)
        text(c, "Доход фермы: x${fmt(th.incomeMul)}", 180f, 356f, 15f, 0xFFFFF3C4.toInt())
        text(c, "Свои тилкайо и свои скины", 180f, 376f, 12f, 0xAAFFFFFF.toInt())
        if (ti == save.unlocked) {
            btn(c, "ОТКРЫТЬ ЗА ${th.unlockCost} 💰", 50f, 396f, 260f, 62f, k(0xFFFFA000), enabled = save.coins >= th.unlockCost) {
                if (save.unlockFarm()) {
                    viewFarm = save.activeFarm
                    farmFoods.clear()
                    sfx.play(Sfx.S.LEVEL)
                    toast("Ферма «${th.name}» открыта! 🎉")
                    for (n in 0 until 30) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        val sp = 60f + rnd.nextFloat() * 160f
                        particles.add(Particle(180f, 380f, cos(a) * sp, sin(a) * sp - 100f, 1f, 1f, blockColors[n % 5], 3f + rnd.nextFloat() * 3f))
                    }
                } else toast("Не хватает монет: нужно ${th.unlockCost}")
            }
        } else {
            text(c, "Сначала открой «${Farms.all[save.unlocked].name}»", 180f, 430f, 15f, 0xFFFF8A80.toInt(), maxW = 270f)
        }
    }

    private fun drawFarmControls(c: Canvas) {
        val sel = save.selected
        val lvl = save.level(sel)
        val name = Balance.names[sel % Balance.names.size]
        text(c, "$name — бегун в забеге, ур. $lvl", 180f, 536f, 16f, 0xFFFFFFFF.toInt(), maxW = 340f)
        val cur = save.herd[sel]
        val info = if (lvl < Balance.MAX_LEVEL) "жир $cur / ${Balance.fatFor(lvl + 1)} до ур.${lvl + 1} · тапни — выбрать, потяни — кинуть"
        else "максимальный уровень 👑 · тапни — выбрать, потяни — кинуть"
        text(c, info, 180f, 552f, 11f, 0xAAFFFFFF.toInt(), maxW = 340f)

        // еда
        for ((i, f) in Balance.foods.withIndex()) {
            val canBuy = lvl < Balance.MAX_LEVEL && save.coins >= f.cost
            btn(c, "${f.emoji} +${f.fat}", 8f + i * 118f, 562f, 110f, 56f, k(0xFFEF6C00), sub = "${f.cost} 💰", enabled = canBuy) { feed(f) }
        }

        // размножение
        val n = save.herd.size
        val canBreed = n < Balance.MAX_HERD
        val req = Balance.breedLevel(n)
        val cost = Balance.breedCost(n)
        val preg = save.pregnant()
        val left = save.breedLeftMs() / 1000
        val breedLabel = when {
            preg -> "🍼 Роды через %d:%02d".format(left / 60, left % 60)
            !canBreed -> "Ферма переполнена!"
            else -> "💕 РАЗМНОЖИТЬ"
        }
        val breedSub = when {
            preg -> "малыш вынашивается…"
            !canBreed -> null
            else -> "нужен ур.$req+ · $cost 💰 · ${Balance.breedMs(n) / 60000} мин"
        }
        btn(c, breedLabel, 8f, 632f, 344f, 62f, k(0xFFE91E63), sub = breedSub,
            enabled = !preg && canBreed && lvl >= req && save.coins >= cost) { breed() }

        btn(c, "▶  В ЗАБЕГ", 8f, 708f, 344f, 70f, k(0xFF00C853)) { startRun() }
    }

    private fun feed(f: FoodItem) {
        val i = save.selected
        if (save.level(i) >= Balance.MAX_LEVEL) { toast("Уже максимально толстый 👑"); return }
        if (save.coins < f.cost) { toast("Не хватает монет"); return }
        val before = save.level(i)
        save.coins -= f.cost
        save.herd[i] = min(Balance.MAX_FAT, save.herd[i] + f.fat)
        save.save()
        sfx.play(Sfx.S.BUY)
        val pet = pets.getOrNull(i) ?: return
        farmFoods.add(
            FarmFood(
                f.emoji,
                (pet.x + (rnd.nextFloat() - 0.5f) * 120f).coerceIn(GX0, GX1),
                (pet.y + (rnd.nextFloat() - 0.5f) * 50f).coerceIn(GY0, GY1),
                pet,
            ),
        )
        val after = save.level(i)
        if (after > before) {
            sfx.play(Sfx.S.LEVEL)
            toast("Уровень $after! Тилкайо стал жирнее 😋")
            uiPops.add(Pop(180f, 280f, "УРОВЕНЬ $after!", 0xFFFFD54F.toInt(), 34f))
            for (n in 0 until 24) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = 60f + rnd.nextFloat() * 120f
                particles.add(Particle(pet.x, pet.y - 30f, cos(a) * sp, sin(a) * sp - 100f, 0.9f, 0.9f, blockColors[n % 5], 3f + rnd.nextFloat() * 2f))
            }
            flip(pet)
        }
    }

    private fun breed() {
        val n = save.herd.size
        val cost = Balance.breedCost(n)
        val req = Balance.breedLevel(n)
        if (save.pregnant()) return toast("Малыш ещё вынашивается…")
        if (n >= Balance.MAX_HERD) return toast("Ферма переполнена")
        if (save.level(save.selected) < req) return toast("Нужен выбранный тилкайо ур.$req+")
        if (save.coins < cost) return toast("Не хватает монет: нужно $cost")
        save.coins -= cost
        save.breedEnd = System.currentTimeMillis() + Balance.breedMs(n)
        save.save()
        sfx.play(Sfx.S.BUY)
        toast("Малыш родится через ${Balance.breedMs(n) / 60000} мин 💕")
        val par = pets.getOrNull(save.selected)
        if (par != null) uiPops.add(Pop(par.x, par.y - 70f, "💕", 0xFFF48FB1.toInt(), 30f))
    }

    // =====================================================================
    // Гардероб: боксы со скинами и примерочная
    // =====================================================================

    private fun buyBox(kind: Int, free: Boolean) {
        if (boxStage != 0) return
        val bt = Boxes.all[kind]
        if (free) {
            if (save.freeBoxes <= 0) return
            save.freeBoxes--
        } else {
            if (save.coins < bt.price) return toast("Не хватает монет: нужно ${bt.price}")
            save.coins -= bt.price
        }
        boxKind = kind
        boxFree = free
        boxSkin = Boxes.roll(bt, rnd)
        boxDup = save.giveSkin(boxSkin)
        save.save()
        boxStage = 1
        boxT = 0f
        sfx.play(Sfx.S.BOX)
    }

    private fun drawWardrobe(c: Canvas, dt: Float) {
        stepFx(dt)
        drawBackground(c, k(0xFF2A1055), k(0xFF7B2FA0), anim * 20f)
        c.save()
        c.translate(uiOx, uiOy)
        c.scale(ui, ui)

        btn(c, "◀", 10f, 14f, 52f, 44f, k(0xFF5C6BC0)) { if (boxStage == 0) goFarm() }
        text(c, "ГАРДЕРОБ", 154f, 48f, 30f, maxW = 176f)
        panel(c, 250f, 18f, 100f, 36f, 0x66000000)
        text(c, "💰 ${save.coins}", 258f, 44f, 20f, 0xFFFFE082.toInt(), Paint.Align.LEFT, maxW = 86f)

        // боксы
        for ((i, bt) in Boxes.all.withIndex()) {
            val x = 8f + i * 118f
            val y = 68f
            panel(c, x, y, 110f, 146f, 0x55000000)
            val wob = sin(anim * 3f + i) * 4f
            c.save()
            c.translate(x + 55f, y + 46f)
            c.rotate(wob)
            text(c, bt.emoji, 0f, 14f, 46f, shadow = false)
            c.restore()
            text(c, bt.name, x + 55f, y + 70f, 12f, maxW = 100f)
            text(c, "Ред ${bt.odds[1]}% · Эп ${bt.odds[2]}%", x + 55f, y + 83f, 9f, 0xCCFFFFFF.toInt(), maxW = 100f)
            text(c, "Легенд. ${bt.odds[3]}%", x + 55f, y + 94f, 9f, 0xFFFFD54F.toInt(), maxW = 100f)
            if (i == 0 && save.freeBoxes > 0) {
                btn(c, "🎁 БЕСПЛАТНО", x + 6f, y + 102f, 98f, 38f, k(0xFF00C853), sub = "осталось ${save.freeBoxes}") { buyBox(0, true) }
            } else {
                btn(c, "${bt.price} 💰", x + 6f, y + 102f, 98f, 38f, k(0xFFFFA000), enabled = save.coins >= bt.price) { buyBox(i, false) }
            }
        }

        // сетка скинов
        val sel = save.selected
        val wearing = save.skinOf(sel)
        text(c, "Надеть на: ${Balance.names[sel % Balance.names.size]} (ур.${save.level(sel)})", 180f, 238f, 15f, 0xFFFFF3C4.toInt(), maxW = 340f)
        val cw = 84f
        val ch = 98f
        for ((i, sk) in Skins.all.withIndex()) {
            val col = i % 4
            val row = i / 4
            val x = 3f + col * (cw + 6f)
            val y = 248f + row * (ch + 6f)
            val owned = save.ownsSkin(i)
            val rc = Rarity.colors[sk.rarity]
            panel(c, x, y, cw, ch, if (owned) 0x66000000 else 0x44000000, 12f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (i == wearing) 3.5f else 2f
            p.color = if (i == wearing) k(0xFFFFFFFF) else (rc and 0x00FFFFFF) or (if (owned) 0xFF000000.toInt() else 0x55000000)
            c.drawRoundRect(x, y, x + cw, y + ch, 12f, 12f, p)
            p.style = Paint.Style.FILL
            drawTilcayo(c, x + cw / 2, y + 40f, 22f, 1, 0f, 1f, 1f, 1, i, hidden = !owned)
            if (owned) {
                text(c, sk.name, x + cw / 2, y + 79f, 10f, 0xFFFFFFFF.toInt(), maxW = cw - 6f)
                if (i == wearing) text(c, "✔", x + cw - 12f, y + 16f, 14f, 0xFF00E676.toInt())
            } else {
                text(c, "?", x + cw / 2, y + 48f, 26f, 0x88FFFFFF.toInt(), shadow = false)
                text(c, "???", x + cw / 2, y + 79f, 10f, 0x88FFFFFF.toInt())
            }
            text(c, Rarity.names[sk.rarity], x + cw / 2, y + 92f, 8f, rc, maxW = cw - 6f)
            buttons.add(Btn(x, y, cw, ch) {
                if (boxStage != 0) return@Btn
                if (owned) {
                    save.setSkin(sel, i)
                    save.save()
                    sfx.play(Sfx.S.BUY)
                    toast("Надет скин «${sk.name}»")
                } else {
                    toast("Скин пока не выпал — открывай боксы!")
                }
            })
        }
        text(c, "Скинов собрано: ${Skins.all.indices.count { save.ownsSkin(it) }} / ${Skins.all.size}", 180f, 756f, 13f, 0xAAFFFFFF.toInt())
        btn(c, "▶  К ФЕРМЕ", 100f, 764f, 160f, 30f, k(0xFF00C853)) { if (boxStage == 0) goFarm() }

        if (toastT > 0f && boxStage == 0) {
            val a = min(1f, toastT * 3f)
            panel(c, 40f, 222f, 280f, 30f, ((a * 200).toInt() shl 24), 15f)
            text(c, toastText, 180f, 243f, 15f, ((a * 255).toInt() shl 24) or 0xFFFFFF, maxW = 260f)
        }

        if (boxStage != 0) drawBoxOverlay(c, dt)
        c.restore()
    }

    private fun drawBoxOverlay(c: Canvas, dt: Float) {
        boxT += dt
        // перехватываем нажатия под оверлеем
        buttons.add(Btn(-300f, -300f, 1000f, 1600f) {})
        p.style = Paint.Style.FILL
        p.color = 0xCC000000.toInt()
        c.drawRect(-300f, -300f, 700f, 1300f, p)
        val bt = Boxes.all[boxKind]
        val sk = Skins.all[boxSkin]
        val rc = Rarity.colors[sk.rarity]

        if (boxStage == 1) {
            val k1 = (boxT / 1.8f).coerceIn(0f, 1f)
            c.save()
            c.translate(180f, 330f)
            c.rotate(sin(boxT * (18f + boxT * 30f)) * (4f + k1 * 14f))
            val s = 1f + 0.18f * k1 + 0.04f * sin(boxT * 40f)
            c.scale(s, s)
            text(c, bt.emoji, 0f, 40f, 130f, shadow = false)
            c.restore()
            text(c, "Что же внутри…", 180f, 520f, 20f, 0xCCFFFFFF.toInt())
            if (boxT >= 1.8f) {
                boxStage = 2
                boxT = 0f
                sfx.play(when (sk.rarity) { 0 -> Sfx.S.REVEAL_C; 1 -> Sfx.S.REVEAL_R; 2 -> Sfx.S.REVEAL_E; else -> Sfx.S.REVEAL_L })
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val n = 20 + sk.rarity * 20
                for (j in 0 until n) {
                    val a = rnd.nextFloat() * 2f * PI.toFloat()
                    val sp = 80f + rnd.nextFloat() * (200f + sk.rarity * 80f)
                    particles.add(Particle(180f, 330f, cos(a) * sp, sin(a) * sp - 60f, 1.4f, 1.4f, if (j % 2 == 0) rc else 0xFFFFFFFF.toInt(), 3f + rnd.nextFloat() * 4f))
                }
            }
        } else {
            // свечение редкости
            val pulse = 1f + 0.05f * sin(boxT * 4f)
            for (j in 5 downTo 1) {
                p.color = (rc and 0x00FFFFFF) or ((0x18 + (5 - j) * 0x0C) shl 24)
                c.drawCircle(180f, 330f, (60f + j * 22f) * pulse, p)
            }
            val flash = (1f - boxT * 3f).coerceIn(0f, 1f)
            val pop = min(1f, boxT * 4f)
            val bob = sin(boxT * 3f)
            drawTilcayo(c, 180f, 330f + bob * 5f, 78f * (0.4f + 0.6f * pop), 1, bob * 3f, 1f, 1f, 1, boxSkin)
            text(c, Rarity.names[sk.rarity].uppercase(), 180f, 468f, 22f, rc, maxW = 300f)
            text(c, sk.name, 180f, 500f, 30f, maxW = 320f)
            if (boxDup) {
                text(c, "Уже есть! Вернули +${Rarity.refund[sk.rarity]} 💰", 180f, 532f, 16f, 0xFFFFE082.toInt(), maxW = 320f)
            } else {
                text(c, "✨ НОВЫЙ СКИН! ✨", 180f, 532f, 18f, 0xFF00E676.toInt())
            }
            if (flash > 0f) {
                p.color = ((flash * 255).toInt() shl 24) or 0xFFFFFF
                c.drawRect(-300f, -300f, 700f, 1300f, p)
            }
            btn(c, "ЗАБРАТЬ", 40f, 570f, 130f, 56f, k(0xFF00C853)) {
                if (!save.ownsSkin(boxSkin) || true) {
                    // сразу надеваем новый скин на выбранного
                    if (!boxDup) { save.setSkin(save.selected, boxSkin); save.save() }
                }
                boxStage = 0
            }
            val again = if (boxFree && save.freeBoxes > 0) true else save.coins >= bt.price
            btn(c, "ЕЩЁ РАЗ", 190f, 570f, 130f, 56f, k(0xFFFFA000), sub = if (boxFree && save.freeBoxes > 0) "бесплатно" else "${bt.price} 💰", enabled = again) {
                val free = boxFree && save.freeBoxes > 0
                boxStage = 0
                buyBox(boxKind, free)
            }
        }
        drawParticles(c)
    }
}
