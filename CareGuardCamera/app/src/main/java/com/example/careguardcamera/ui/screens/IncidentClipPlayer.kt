package com.example.careguardcamera.ui.screens

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.delay

/**
 * 긴급 상황의 낙상 전후 현장 영상. 카메라가 카운트다운 뒤에 올리므로 처음에는 없을 수 있어
 * 받을 수 있을 때까지 몇 초 간격으로 다시 불러온다. 받은 영상은 반복 재생한다.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun IncidentClipPlayer(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
    }
    var ready by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready = true
            }

            override fun onPlayerError(error: PlaybackException) {
                ready = false
                // 권한이 없거나(403) 보관 기간이 지난(410) 영상은 다시 받아도 같다.
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 403 || code == 410) attempt = MAX_ATTEMPTS
                failed = true
            }
        }
        player.addListener(listener)
        // 119에 전화하느라 앱을 떠나면 재생을 멈춘다.
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(url, attempt) {
        if (url == null) return@LaunchedEffect
        failed = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(failed) {
        if (!failed || attempt >= MAX_ATTEMPTS) return@LaunchedEffect
        delay(RETRY_DELAY_MILLIS)
        attempt++
    }

    Box(modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            "현장 녹화 클립",
            style = CGType.Caption,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        if (!ready) {
            Text(
                when {
                    url == null -> "현장 영상을 불러올 수 없어요."
                    failed && attempt >= MAX_ATTEMPTS -> "현장 영상이 도착하지 않았어요. 카메라 설정에 따라 영상이 없을 수 있어요."
                    else -> "카메라에서 현장 영상을 받는 중이에요…"
                },
                style = CGType.Label,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }
    }
}

private const val RETRY_DELAY_MILLIS = 3_000L
// 카메라 업로드 재시도(최대 약 23초)를 넉넉히 덮는다.
private const val MAX_ATTEMPTS = 20
