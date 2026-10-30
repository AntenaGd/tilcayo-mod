package com.tilcayo.fat

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast

/**
 * Секретное debug-меню. Положи файл рядом с Model.kt (папка fat).
 * Вызов: DebugMenu.show(context, save) { /* обновить экран */ }
 */
object DebugMenu {

    fun show(context: Context, save: SaveData, onChanged: () -> Unit = {}) {
        val actions = listOf<Pair<String, () -> String>>(
            "💰 +10 000 монет" to {
                save.coins += 10_000L
                "Монет: ${save.coins}"
            },
            "🎨 Открыть все скины" to {
                val n = Skins.all.size
                save.ownedSkins = if (n >= 63) -1L else (1L shl n) - 1L
                "Скинов открыто: $n"
            },
            "🏡 Открыть все фермы" to {
                save.unlocked = Farms.COUNT
                "Ферм открыто: ${Farms.COUNT}"
            },
            "🍔 Максимум жира всему стаду" to {
                val herd = save.herd
                for (i in herd.indices) herd[i] = Balance.MAX_FAT
                "Уровень всех: ${Balance.MAX_LEVEL}"
            },
            "🐣 Добавить питомца" to {
                if (save.farm.herd.size < Balance.MAX_HERD) {
                    save.farm.addPet()
                    save.farm.normalize()
                    "В стаде: ${save.farm.herd.size}"
                } else {
                    "Стадо уже полное"
                }
            },
            "⏩ Мгновенно родить малыша" to {
                if (save.pregnant()) {
                    save.breedEnd = 1L
                    save.checkBirth()
                    "Малыш родился"
                } else {
                    "Сейчас никто не вынашивает"
                }
            },
            "📦 +5 бесплатных боксов" to {
                save.freeBoxes += 5
                "Боксов: ${save.freeBoxes}"
            },
            "🪙 Забрать доход за offline" to {
                "Получено: ${save.collect()}"
            },
        )

        AlertDialog.Builder(context)
            .setTitle("Debug")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                val msg = actions[which].second()
                save.save()
                onChanged()
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }
}
