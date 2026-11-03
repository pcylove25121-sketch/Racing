package com.pcylove.idleauto

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 레이아웃 XML 없이 코드로 화면을 그리기 위한 작은 도우미들. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0xFFF2F0F7.toInt())
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }
}

fun Context.text(s: CharSequence, sizeSp: Float = 15f, bold: Boolean = false, color: Int = 0xFF1B1830.toInt()): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

fun Context.button(label: String, onClick: (View) -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    setOnClickListener(onClick)
}

fun Context.numberField(value: Long, widthDp: Int = 96): EditText = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    setText(value.toString())
    setSelectAllOnFocus(true)
    gravity = Gravity.END
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
}

fun Context.row(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

fun Context.labeled(label: String, field: View, hint: String? = null): LinearLayout {
    val l = text(label, 14f).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(row(l, field))
        hint?.let { addView(text(it, 12f, color = 0xFF6F6A8A.toInt())) }
    }
    return wrap
}

fun LinearLayout.space(hDp: Int) {
    addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(hDp)))
}

fun EditText.longValue(default: Long): Long = text.toString().trim().toLongOrNull() ?: default
fun EditText.intValue(default: Int): Int = text.toString().trim().toIntOrNull() ?: default
