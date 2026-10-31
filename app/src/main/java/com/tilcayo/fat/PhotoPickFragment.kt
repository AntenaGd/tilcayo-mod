package com.tilcayo.fat

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.net.Uri

/** Невидимый фрагмент: открывает галерею и возвращает выбранное фото (для фона меню TB). */
@Suppress("DEPRECATION")
class PhotoPickFragment : Fragment() {
    var onPicked: ((Uri?) -> Unit)? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ) {
            onPicked?.invoke(if (resultCode == Activity.RESULT_OK) data?.data else null)
            onPicked = null
        }
        try {
            fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val REQ = 7771
    }
}
