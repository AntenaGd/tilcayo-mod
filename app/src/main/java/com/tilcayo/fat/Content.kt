package com.tilcayo.fat

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private fun c(v: Long) = v.toInt()

object Rarity {
    const val COMMON = 0
    const val RARE = 1
    const val EPIC = 2
    const val LEGEND = 3

    val names = listOf("Обычный", "Редкий", "Эпический", "Легендарный")
    val colors = intArrayOf(c(0xFFB0BEC5), c(0xFF42A5F5), c(0xFFAB47BC), c(0xFFFFC107))

    /** Сколько монет возвращается за дубликат. */
    val refund = longArrayOf(150L, 600L, 2500L, 10000L)
}

/** Скин — перекраска тилкайо плюс (необязательно) аксессуар и спецэффект. */
class Skin(
    val name: String,
    val rarity: Int,
    val hue: Float = 0f,
    val sat: Float = 1f,
    val mul: FloatArray = floatArrayOf(1f, 1f, 1f),
    val add: Float = 0f,
    /** Эмодзи-аксессуар: на макушке или на глазах. */
    val acc: String? = null,
    val accOnEyes: Boolean = false,
    val fx: Int = FX_NONE,
    val alpha: Float = 1f,
    /** Имя картинки в res/drawable-nodpi (без .png). null — обычный тилкайо. */
    val sprite: String? = null,
) {
    val plain = hue == 0f && sat == 1f && mul[0] == 1f && mul[1] == 1f && mul[2] == 1f && add == 0f && fx != FX_RAINBOW

    companion object {
        const val FX_NONE = 0
        const val FX_SPARK = 1
        const val FX_RAINBOW = 2
        const val FX_GHOST = 3
        const val FX_STARS = 4
        const val FX_FIRE = 5
    }
}

object Skins {
    val all: List<Skin> = listOf(
        Skin("Классика", Rarity.COMMON),
        Skin("Слива", Rarity.COMMON, hue = 250f),
        Skin("Мята", Rarity.COMMON, hue = 120f),
        Skin("Лагуна", Rarity.COMMON, hue = 175f),
        Skin("Розовая пантера", Rarity.RARE, hue = 300f, sat = 1.2f, acc = "🎀"),
        Skin("Золотой", Rarity.RARE, sat = 1.3f, mul = floatArrayOf(1.35f, 1.2f, 0.55f), add = 18f, fx = Skin.FX_SPARK),
        Skin("Снежок", Rarity.RARE, sat = 0.15f, mul = floatArrayOf(1.25f, 1.3f, 1.4f), add = 25f, acc = "❄️"),
        Skin("Шоколадка", Rarity.RARE, sat = 0.8f, mul = floatArrayOf(0.62f, 0.5f, 0.45f), acc = "🍫"),
        Skin("Радужный", Rarity.EPIC, sat = 1.5f, acc = "🌈", fx = Skin.FX_RAINBOW),
        Skin("Призрак", Rarity.EPIC, sat = 0.2f, mul = floatArrayOf(0.85f, 0.95f, 1.3f), add = 20f, acc = "😇", fx = Skin.FX_GHOST, alpha = 0.7f),
        Skin("Космо", Rarity.EPIC, hue = 200f, mul = floatArrayOf(0.55f, 0.65f, 1.25f), acc = "🛸", fx = Skin.FX_STARS),
        Skin("Золотой король", Rarity.LEGEND, sat = 1.4f, mul = floatArrayOf(1.4f, 1.22f, 0.5f), add = 22f, acc = "👑", fx = Skin.FX_SPARK),
        Skin("Дракончик", Rarity.LEGEND, hue = -30f, sat = 1.7f, mul = floatArrayOf(1.3f, 0.75f, 0.7f), acc = "🔥", fx = Skin.FX_FIRE),
        Skin("Маргит", Rarity.LEGEND, sprite = "margit"),
        Skin("Mever (автор чита)", Rarity.LEGEND, sprite = "mever"),
        Skin("Пузатый марги", Rarity.LEGEND, sprite = "puzaty_margi"),
        Skin("Пузико клод", Rarity.LEGEND, sprite = "puziko_claude"),
        Skin("Нарезанный♥️", Rarity.LEGEND, sprite = "narezanny"),
    )

    private val cache = arrayOfNulls<ColorMatrixColorFilter>(all.size)

    private fun hueArray(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        return floatArrayOf(
            0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
            0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
            0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private fun build(s: Skin, hueExtra: Float): ColorMatrixColorFilter {
        val m = ColorMatrix()
        val h = s.hue + hueExtra
        if (h != 0f) m.postConcat(ColorMatrix(hueArray(h)))
        if (s.sat != 1f) m.postConcat(ColorMatrix().also { it.setSaturation(s.sat) })
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    s.mul[0], 0f, 0f, 0f, s.add,
                    0f, s.mul[1], 0f, 0f, s.add,
                    0f, 0f, s.mul[2], 0f, s.add,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        return ColorMatrixColorFilter(m)
    }

    /** Фильтр цвета для скина; радужный меняется со временем [t]. */
    fun filter(id: Int, t: Float): ColorMatrixColorFilter? {
        val s = all[id]
        if (s.plain) return null
        if (s.fx == Skin.FX_RAINBOW) return build(s, t * 140f)
        return cache[id] ?: build(s, 0f).also { cache[id] = it }
    }
}

class BoxType(val name: String, val emoji: String, val price: Long, val odds: IntArray)

object Boxes {
    val all = listOf(
        BoxType("Обычный бокс", "📦", 700L, intArrayOf(78, 19, 3, 0)),
        BoxType("Редкий бокс", "🎁", 3500L, intArrayOf(35, 47, 16, 2)),
        BoxType("Золотой бокс", "💎", 15000L, intArrayOf(0, 50, 40, 10)),
    )

    fun roll(box: BoxType, rnd: Random): Int {
        var x = rnd.nextInt(100)
        var rarity = 0
        for (r in box.odds.indices) {
            x -= box.odds[r]
            if (x < 0) { rarity = r; break }
        }
        val pool = Skins.all.indices.filter { Skins.all[it].rarity == rarity }
        return pool[rnd.nextInt(pool.size)]
    }
}

/** Тема фермы: как выглядит и сколько платит. */
class FarmTheme(
    val name: String,
    val emoji: String,
    val unlockCost: Long,
    val incomeMul: Float,
    val skyTop: Int,
    val skyBot: Int,
    val hillA: Int,
    val hillB: Int,
    val ground: Int,
    val fence: Int,
    val fenceDark: Int,
    /** 0 — солнце, 1 — жаркое оранжевое, 2 — бледное, 3 — Земля в небе. */
    val sun: Int,
    val props: String,
    val decor: List<String>,
    val snow: Boolean = false,
    val stars: Boolean = false,
)

object Farms {
    const val COUNT = 4

    val all = listOf(
        FarmTheme(
            "Луг", "🌿", 0L, 1f,
            c(0xFF4FB3F6), c(0xFFD9F3FF), c(0xFF8BD17F), c(0xFF6CC067), c(0xFF5DBB57), c(0xFFD9A760), c(0xFFB98544),
            0, "🌳", listOf("🌼", "🌷", "🍄", "🌱", "🌸"),
        ),
        FarmTheme(
            "Дюны", "🏜️", 8000L, 1.6f,
            c(0xFFF4A259), c(0xFFFFE9C7), c(0xFFE8C07A), c(0xFFD9A860), c(0xFFEACB87), c(0xFFC98B4A), c(0xFF9C6431),
            1, "🌵", listOf("🌵", "🌾", "🐚", "🦎", "🌻"),
        ),
        FarmTheme(
            "Снега", "❄️", 40000L, 2.4f,
            c(0xFF8FB8E8), c(0xFFEAF4FF), c(0xFFF2F8FF), c(0xFFDCEBFA), c(0xFFE6F0F8), c(0xFFE8EEF6), c(0xFF9FB6CC),
            2, "🌲", listOf("❄️", "⛄", "🎄", "🌨️", "⭐"), snow = true,
        ),
        FarmTheme(
            "Луна", "🌙", 160000L, 4f,
            c(0xFF0B0B2A), c(0xFF2A1B5A), c(0xFF6B6B8F), c(0xFF55557A), c(0xFF8A8AA8), c(0xFFB0B8C8), c(0xFF6E7890),
            3, "🛸", listOf("⭐", "🌑", "✨", "🔭", "🚀"), stars = true,
        ),
    )
}

/** Модификатор забега: меняет правила и множитель монет. */
class Mod(val id: Int, val emoji: String, val name: String, val desc: String, val bonus: Float, val excludes: Int = -1)

object Mods {
    const val SPARSE = 0
    const val DENSE = 1
    const val VIRUS = 2
    const val LAVA = 3
    const val NIGHT = 4
    const val MIRROR = 5

    val all = listOf(
        Mod(SPARSE, "🍃", "Тихая гавань", "Намного меньше препятствий", -0.5f, excludes = DENSE),
        Mod(DENSE, "🌲", "Дремучий лес", "Препятствий больше, передышек почти нет", 0.25f, excludes = SPARSE),
        Mod(VIRUS, "🦠", "Вирусы", "На экране всплывают окна с вирусами. Закрывай их!", 0.2f),
        Mod(LAVA, "🌋", "Лавовый шторм", "Лава поднимается в 1,6 раза быстрее", 0.3f),
        Mod(NIGHT, "🌑", "Тёмная ночь", "Видно только вокруг тилкайо", 0.25f),
        Mod(MIRROR, "🔄", "Зеркало", "Свайпы работают наоборот", 0.2f),
    )

    fun has(mask: Int, id: Int) = (mask shr id) and 1 == 1

    /** Итоговый множитель монет от набора модификаторов. */
    fun multiplier(mask: Int): Float {
        var m = 1f
        for (md in all) if (has(mask, md.id)) m *= 1f + md.bonus
        return m
    }

    /** Включает/выключает модификатор, снимая несовместимый. */
    fun toggle(mask: Int, id: Int): Int {
        var r = mask xor (1 shl id)
        val ex = all[id].excludes
        if (has(r, id) && ex >= 0) r = r and (1 shl ex).inv()
        return r
    }

    fun count(mask: Int) = all.count { has(mask, it.id) }
}

/** Босс для режима «Босс-раш». */
class BossDef(val name: String, val hp: Int, val reward: Long, val par: Float)

object Bosses {
    val all = listOf(
        BossDef("Толстый Котозаяц", 6, 500L, 60f),
    )
}
