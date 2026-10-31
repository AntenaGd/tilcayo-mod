package com.tilcayo.fat

import android.content.Context
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Еда, которой можно откармливать тилкайо на ферме. */
class FoodItem(val emoji: String, val name: String, val cost: Int, val fat: Int)

object Balance {
    const val MAX_LEVEL = 10
    const val MAX_HERD = 9
    const val OFFLINE_CAP_SEC = 3 * 3600L

    /** Сколько жира нужно, чтобы достичь уровня L: растёт быстрее линейного, поэтому поздние уровни дорогие. */
    private val thresholds = IntArray(MAX_LEVEL) { i -> if (i == 0) 0 else (100.0 * i.toDouble().pow(1.6)).toInt() }
    val MAX_FAT = thresholds[MAX_LEVEL - 1]

    fun fatFor(level: Int) = thresholds[(level - 1).coerceIn(0, MAX_LEVEL - 1)]

    fun level(fat: Int): Int {
        var l = 1
        while (l < MAX_LEVEL && fat >= thresholds[l]) l++
        return l
    }

    val foods = listOf(
        FoodItem("🥕", "Морковка", 20, 20),
        FoodItem("🍔", "Бургер", 110, 100),
        FoodItem("🍰", "Тортик", 440, 400),
    )

    val names = listOf(
        "Бублик", "Пончик", "Жорик", "Пельмень", "Батон",
        "Кабачок", "Колобок", "Пирожок", "Борщ",
    )

    /** Цена нового тилкайо при данном размере стада: 400, 800, 1600… */
    fun breedCost(herdSize: Int): Long = 400L shl (herdSize - 1)

    /** Какого уровня должен быть родитель. */
    fun breedLevel(herdSize: Int) = min(MAX_LEVEL, 3 + herdSize)

    /** Сколько «вынашивается» малыш: 3 минуты × размер стада. */
    fun breedMs(herdSize: Int) = 180_000L * herdSize
}

/** Одна ферма: своё стадо, свои наряды и своя беременность. */
class Farm {
    val herd = mutableListOf(0)
    val skin = mutableListOf(0)
    var selected = 0
    var breedEnd = 0L

    fun addPet(fat: Int = 0, skinId: Int = 0) {
        herd.add(fat)
        skin.add(skinId)
    }

    fun normalize() {
        if (herd.isEmpty()) herd.add(0)
        while (skin.size < herd.size) skin.add(0)
        while (skin.size > herd.size) skin.removeAt(skin.size - 1)
        for (i in skin.indices) if (skin[i] !in Skins.all.indices) skin[i] = 0
        if (selected !in herd.indices) selected = 0
    }
}

/** Всё, что сохраняется между запусками. */
class SaveData(context: Context) {
    private val sp = context.getSharedPreferences("tilcayo", Context.MODE_PRIVATE)

    var coins: Long = sp.getLong("coins", 50L)
    var best: Int = sp.getInt("best", 0)
    var sound: Boolean = sp.getBoolean("sound", true)
    var lastCollect: Long = sp.getLong("lastCollect", System.currentTimeMillis())

    /** Сколько ферм открыто и какая сейчас выбрана. */
    var unlocked: Int = sp.getInt("unlocked", 1).coerceIn(1, Farms.COUNT)
    var activeFarm: Int = sp.getInt("activeFarm", 0)

    /** Какие скины открыты (битовая маска). */
    var ownedSkins: Long = sp.getLong("ownedSkins", 1L) or 1L

    /** Включённые модификаторы забега (битовая маска). */
    var mods: Int = sp.getInt("mods", 0)

    /** Лучшее время босс-раша по каждому боссу (мс, 0 — ещё не побеждён). */
    val rushBest = LongArray(Bosses.all.size) { sp.getLong("rushBest$it", 0L) }

    /** Проверка обновлений. */
    var lastUpdateCheck: Long = sp.getLong("lastUpdateCheck", 0L)
    var remoteCode: Int = sp.getInt("remoteCode", 0)
    var remoteName: String = sp.getString("remoteName", "") ?: ""
    var remoteNotes: String = sp.getString("remoteNotes", "") ?: ""
    var remoteApk: String = sp.getString("remoteApk", "") ?: ""
    var dismissedCode: Int = sp.getInt("dismissedCode", 0)

    /** Бесплатные боксы (за победу над боссом). */
    var freeBoxes: Int = sp.getInt("freeBoxes", 0)

    /** Хеллоуин-пасс: опыт и забранные награды (битовая маска по уровням). */
    var passXp: Int = sp.getInt("passXp", 0)
    var passClaimed: Int = sp.getInt("passClaimed", 0)

    val farms: List<Farm> = List(Farms.COUNT) { i ->
        val sfx = if (i == 0) "" else "$i"
        Farm().also { f ->
            f.herd.clear()
            (sp.getString("herd$sfx", if (i == 0) "0" else "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.forEach { f.herd.add(it) }
            (sp.getString("skins$sfx", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.forEach { f.skin.add(it) }
            f.selected = sp.getInt("selected$sfx", 0)
            f.breedEnd = sp.getLong("breedEnd$sfx", 0L)
            f.normalize()
        }
    }

    init {
        if (activeFarm !in 0 until unlocked) activeFarm = 0
    }

    val farm: Farm get() = farms[activeFarm]
    val herd: MutableList<Int> get() = farm.herd
    var selected: Int
        get() = farm.selected
        set(v) { farm.selected = v }
    var breedEnd: Long
        get() = farm.breedEnd
        set(v) { farm.breedEnd = v }

    fun level(i: Int) = Balance.level(herd[i])
    fun skinOf(i: Int) = farm.skin[i]
    fun setSkin(i: Int, id: Int) { farm.skin[i] = id }

    fun ownsSkin(id: Int) = (ownedSkins shr id) and 1L == 1L

    /** Выдаёт скин; true — это был дубликат и вернулись деньги. */
    fun giveSkin(id: Int): Boolean {
        if (ownsSkin(id)) {
            coins += Rarity.refund[Skins.all[id].rarity]
            save()
            return true
        }
        ownedSkins = ownedSkins or (1L shl id)
        save()
        return false
    }

    fun totalLevels(): Int {
        var n = 0
        for (fi in 0 until unlocked) n += farms[fi].herd.sumOf { Balance.level(it) }
        return n
    }

    /** Множитель монет в забеге: чем больше и жирнее стада, тем жирнее куш. */
    fun coinMultiplier() = 1f + (totalLevels() - 1) * 0.04f

    fun incomePerMin(): Float {
        var v = 0f
        for (fi in 0 until unlocked) v += farms[fi].herd.sumOf { Balance.level(it) } * 0.5f * Farms.all[fi].incomeMul
        return v
    }

    fun pending(): Long {
        val sec = min((System.currentTimeMillis() - lastCollect) / 1000, Balance.OFFLINE_CAP_SEC)
        return (sec.coerceAtLeast(0) * incomePerMin() / 60f).toLong()
    }

    fun collect(): Long {
        val got = pending()
        coins += got
        lastCollect = System.currentTimeMillis()
        save()
        return got
    }

    fun unlockFarm(): Boolean {
        if (unlocked >= Farms.COUNT) return false
        val cost = Farms.all[unlocked].unlockCost
        if (coins < cost) return false
        coins -= cost
        activeFarm = unlocked
        unlocked++
        farms[activeFarm].apply { herd.clear(); skin.clear(); addPet(); selected = 0; breedEnd = 0L }
        save()
        return true
    }

    fun pregnant() = breedEnd > 0L

    fun breedLeftMs() = max(0L, breedEnd - System.currentTimeMillis())

    /** Рождается ли сейчас малыш на какой-нибудь ферме. Возвращает true в момент рождения. */
    fun checkBirth(): Boolean {
        var any = false
        val now = System.currentTimeMillis()
        for (fi in 0 until unlocked) {
            val f = farms[fi]
            if (f.breedEnd > 0L && now >= f.breedEnd) {
                f.breedEnd = 0L
                if (f.herd.size < Balance.MAX_HERD) f.addPet()
                any = true
            }
        }
        if (any) save()
        return any
    }

    fun save() {
        val e = sp.edit()
            .putLong("coins", coins)
            .putInt("best", best)
            .putBoolean("sound", sound)
            .putLong("lastCollect", lastCollect)
            .putInt("unlocked", unlocked)
            .putInt("activeFarm", activeFarm)
            .putLong("ownedSkins", ownedSkins)
            .putInt("freeBoxes", freeBoxes)
            .putInt("passXp", passXp)
            .putInt("passClaimed", passClaimed)
            .putInt("mods", mods)
            .putLong("lastUpdateCheck", lastUpdateCheck)
            .putInt("remoteCode", remoteCode)
            .putString("remoteName", remoteName)
            .putString("remoteNotes", remoteNotes)
            .putString("remoteApk", remoteApk)
            .putInt("dismissedCode", dismissedCode)
        for (i in rushBest.indices) e.putLong("rushBest$i", rushBest[i])
        for (i in farms.indices) {
            val sfx = if (i == 0) "" else "$i"
            val f = farms[i]
            e.putString("herd$sfx", f.herd.joinToString(","))
            e.putString("skins$sfx", f.skin.joinToString(","))
            e.putInt("selected$sfx", f.selected)
            e.putLong("breedEnd$sfx", f.breedEnd)
        }
        e.apply()
    }
}
