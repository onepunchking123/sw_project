package com.example.careguardcamera.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** 데모용 단순 액션. 전화는 다이얼러를 열고, 미구현 기능은 토스트로 알린다. */
fun Context.dial(number: String) {
    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
        .onFailure { Toast.makeText(this, "전화 앱을 열 수 없어요", Toast.LENGTH_SHORT).show() }
}

fun Context.comingSoon(feature: String) {
    Toast.makeText(this, "$feature 은(는) 준비 중이에요", Toast.LENGTH_SHORT).show()
}
