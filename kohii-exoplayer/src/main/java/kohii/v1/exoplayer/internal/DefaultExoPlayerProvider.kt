/*
 * Copyright (c) 2019 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.v1.exoplayer.internal

import android.content.Context
import androidx.core.util.Pools
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
import com.google.android.exoplayer2.LoadControl
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.util.Util
import kohii.v1.media.Media
import kohii.v1.onEachAcquired
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import kotlin.math.max

/**
 * @author eneim (2018/10/27).
 */
internal class DefaultExoPlayerProvider(
  context: Context,
  private val bandwidthMeterFactory: BandwidthMeterFactory = DefaultBandwidthMeterFactory(),
  private val loadControl: LoadControl = DefaultLoadControl.Builder().createDefaultLoadControl(),
  private val renderersFactory: RenderersFactory = DefaultRenderersFactory(
      context.applicationContext
  ).setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
) : ExoPlayerProvider {

  companion object {
    // Max number of Player instance are cached in the Pool
    // Magic number: Build.VERSION.SDK_INT / 6 --> API 16 ~ 18 will set pool size to 2, etc.
    internal val MAX_POOL_SIZE = max(Util.SDK_INT / 6, Runtime.getRuntime().availableProcessors())
  }

  private val context = context.applicationContext
  private val playerPool = Pools.SimplePool<Player>(MAX_POOL_SIZE)

  init {
    // Adapt from ExoPlayer demo app.
    val cookieManager = CookieManager()
    cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    if (CookieHandler.getDefault() !== cookieManager) {
      CookieHandler.setDefault(cookieManager)
    }
  }

  override fun acquirePlayer(media: Media): Player {
    val result = playerPool.acquire() ?: DefaultExoPlayer(
        context,
        renderersFactory,
        DefaultTrackSelector(context),
        loadControl,
        bandwidthMeterFactory.createBandwidthMeter(context),
        Util.getLooper()
    )

    if (result is SimpleExoPlayer) {
      // If not mute --> need to handle audio focus
      result.setAudioAttributes(result.audioAttributes, result.volume > 0F)
    }
    return result
  }

  override fun releasePlayer(
    media: Media,
    player: Player
  ) {
    if (!playerPool.release(player)) {
      player.release()
    }
  }

  override fun cleanUp() {
    playerPool.onEachAcquired { it.release() }
  }
}
