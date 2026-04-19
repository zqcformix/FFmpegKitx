package cn.chendubichen.jdlgsstdq.utils.ext

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Parcelable
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.hjq.toast.ToastUtils
import com.hjq.toast.config.IToastStyle
import com.hjq.toast.style.BlackToastStyle
import java.util.IllegalFormatException
import kotlin.random.Random

fun showToaster(
    message: String,
    gravity: Int = Gravity.CENTER,
    style: IToastStyle<*> = BlackToastStyle()
) {
    ToastUtils.setGravity(gravity)
    ToastUtils.setStyle(style)
    ToastUtils.show(message)
}

fun ComponentActivity.setExitOnBackPressedCallback(action: (() -> Unit)? = null) {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            action?.invoke()
            return
        }
    })
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 获取 Parcelable 对象，兼容不同 Android 版本。
 */
inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

/**
 * 获取 Parcelable ArrayList 对象，兼容不同 Android 版本。
 */
inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
}

fun Context.dp2px(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    value.toFloat(),
    resources.displayMetrics
).toInt()

fun Context.dp2px(value: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

val Context.layoutInflater: LayoutInflater
    get() = LayoutInflater.from(this)

val Context.inputMethodManager
    get() = this.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

fun Context.getDrawableCompat(@DrawableRes drawable: Int): Drawable =
    requireNotNull(ContextCompat.getDrawable(this, drawable))

fun Context.getColorCompat(@ColorRes color: Int) = ContextCompat.getColor(this, color)

fun TextView.setTextColorRes(@ColorRes color: Int) = setTextColor(context.getColorCompat(color))

fun TextView.setStrikeThrough(enabled: Boolean = true) {
    paintFlags = if (enabled) {
        paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    } else {
        paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }
}

fun Context.copyToClipboard(
    label: String = "label",
    text: String,
    showToast: Boolean = true
) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)

    if (showToast) {
        ToastUtils.setGravity(Gravity.CENTER)
        ToastUtils.show("已复制到剪贴板")
    }
}

fun Context.pasteFromClipboard(
    showToast: Boolean = true,
    onPaste: (String) -> Unit
) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = clipboard.primaryClip
    val text = if (clipData != null && clipData.itemCount > 0) {
        clipData.getItemAt(0).coerceToText(this).toString()
    } else {
        ""
    }
    if (text.isNotEmpty()) {
        onPaste(text)
    } else if (showToast) {
        ToastUtils.setGravity(Gravity.CENTER)
        ToastUtils.show("剪贴板为空")
    }
}

fun Context.vibrate(duration: Long = 50, amplitude: Int? = null) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val effect = if (amplitude != null) {
        VibrationEffect.createOneShot(duration, amplitude)
    } else {
        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    vibrator.vibrate(effect)
}

// 检测字符串是否包含 HTML 标签
private fun containsHtmlTags(text: String): Boolean {
    return text.contains(Regex("<(\"[^\"]*\"|'[^']*'|[^'\">])*>"))
}

// 解析随机范围，支持整数和小数
fun parseRandomRange(range: String): String {
    if (range.isBlank()) return "" // 如果是空字符串，直接返回空字符串

    val bounds = range.split("-").mapNotNull { it.toDoubleOrNull() }
    if (bounds.size == 2 && bounds[0] < bounds[1]) {
        val randomValue = Random.nextDouble(bounds[0], bounds[1])

        // 动态计算小数位数
        val decimalPlaces = range.split("-").maxOfOrNull {
            it.substringAfter(".", "").length
        } ?: 0

        return if (decimalPlaces > 0) {
            "%.${decimalPlaces}f".format(randomValue) // 动态保留小数位
        } else {
            randomValue.toInt().toString() // 否则返回整数
        }
    }

    return range // 如果格式不合法，直接返回原值
}

// 处理 HTML 标签、占位符和随机范围
fun String.fromHtmlStr(vararg args: Any?): CharSequence {
    // 处理占位符和随机范围
    val processedArgs = args.map {
        if (it is String && it.contains("-")) {
            parseRandomRange(it) // 如果是随机范围字符串，解析为随机值
        } else {
            it?.toString() ?: "" // 普通参数直接转换，null 转为空字符串
        }
    }.toTypedArray()

    // 格式化字符串
    val formattedText = try {
        if (processedArgs.isNotEmpty()) {
            String.format(this, *processedArgs)
        } else {
            this
        }
    } catch (_: IllegalFormatException) {
        this // 格式化失败时，返回原字符串
    }

    // 判断是否包含 HTML 标签并进行处理
    return if (containsHtmlTags(formattedText)) {
        Html.fromHtml(formattedText, Html.FROM_HTML_MODE_COMPACT) // 解析 HTML 标签
    } else {
        formattedText // 普通字符串直接返回
    }
}

internal fun Long.formatAsTime(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}