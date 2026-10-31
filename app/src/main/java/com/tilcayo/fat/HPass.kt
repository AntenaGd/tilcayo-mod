package com.tilcayo.fat

/** Награды Хеллоуин-пасса. */
data class HPassReward(
    val label: String,
    val coins: Long = 0L,
    val boxes: Int = 0,
    val skin: Int = -1,
)

/**
 * Хеллоуин-пасс на 20 уровней.
 *
 * Уровень 1 доступен с самого начала; каждый следующий уровень
 * открывается за 100 XP. Прогресс хранится в SaveData.
 */
object HPass {
    const val LEVELS = 20
    const val XP_PER_LEVEL = 100
    private const val MAX_XP = LEVELS * XP_PER_LEVEL

    val rewards: List<HPassReward> = listOf(
        HPassReward("300 монет", coins = 300L),
        HPassReward("Обычный бокс", boxes = 1),
        HPassReward("500 монет", coins = 500L),
        HPassReward("Обычный бокс", boxes = 1),
        HPassReward("Слива", skin = 1),
        HPassReward("700 монет", coins = 700L),
        HPassReward("Обычный бокс", boxes = 1),
        HPassReward("1000 монет", coins = 1000L),
        HPassReward("Редкий бокс", boxes = 1),
        HPassReward("Хеллоуинский Маргит", skin = 18),
        HPassReward("1200 монет", coins = 1200L),
        HPassReward("Редкий бокс", boxes = 1),
        HPassReward("1500 монет", coins = 1500L),
        HPassReward("Золотой бокс", boxes = 1),
        HPassReward("Золотой", skin = 5),
        HPassReward("1800 монет", coins = 1800L),
        HPassReward("Редкий бокс", boxes = 1),
        HPassReward("2500 монет", coins = 2500L),
        HPassReward("Золотой бокс", boxes = 1),
        HPassReward("Хеллоуинский тилкайо", skin = 19),
    )

    /** Возвращает текущий уровень от 1 до 20. */
    fun level(save: SaveData): Int =
        (save.passXp.coerceIn(0, MAX_XP) / XP_PER_LEVEL + 1).coerceAtMost(LEVELS)

    fun isClaimed(save: SaveData, index: Int): Boolean {
        if (index !in 0 until LEVELS) return false
        return (save.passClaimed and (1 shl index)) != 0
    }

    fun claimable(save: SaveData): Int {
        val currentLevel = level(save)
        return (0 until currentLevel).count { !isClaimed(save, it) }
    }

    /**
     * Забирает награду. null означает, что уровень ещё закрыт
     * или награда уже была получена.
     */
    fun claim(save: SaveData, index: Int): String? {
        if (index !in 0 until LEVELS) return null
        if (index + 1 > level(save) || isClaimed(save, index)) return null

        val reward = rewards[index]
        save.passClaimed = save.passClaimed or (1 shl index)

        val parts = mutableListOf<String>()
        if (reward.coins > 0L) {
            save.coins += reward.coins
            parts += "+${reward.coins} монет"
        }
        if (reward.boxes > 0) {
            save.freeBoxes += reward.boxes
            parts += "+${reward.boxes} бокс"
        }
        if (reward.skin >= 0) {
            val duplicate = save.giveSkin(reward.skin)
            parts += if (duplicate) {
                "дубликат скина — компенсация монетами"
            } else {
                "скин «${Skins.all[reward.skin].name}»"
            }
        }

        save.save()
        return parts.joinToString(", ").ifBlank { reward.label }
    }
}
