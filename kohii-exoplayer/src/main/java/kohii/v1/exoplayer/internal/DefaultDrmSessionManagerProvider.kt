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
import android.media.MediaDrm
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException
import com.google.android.exoplayer2.drm.DrmSessionManager
import com.google.android.exoplayer2.drm.ExoMediaCrypto
import com.google.android.exoplayer2.drm.FrameworkMediaDrm
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.Util.getDrmUuid
import kohii.v1.exoplayer.R
import kohii.v1.media.Media

/**
 * @author eneim (2018/10/27).
 */
internal class DefaultDrmSessionManagerProvider(
  private val context: Context,
  private val httpDataSourceFactory: HttpDataSource.Factory
) : DrmSessionManagerProvider {

  override fun provideDrmSessionManager(media: Media): DrmSessionManager<out ExoMediaCrypto> {
    val mediaDrm = media.mediaDrm ?: return DrmSessionManager.getDummyDrmSessionManager()
    var drmSessionManager: DrmSessionManager<out ExoMediaCrypto>? = null
    var errorStringId = R.string.error_drm_unknown

    val drmSchemeUuid = getDrmUuid(mediaDrm.type)
    if (drmSchemeUuid == null || MediaDrm.isCryptoSchemeSupported(drmSchemeUuid)) {
      errorStringId = R.string.error_drm_unsupported_scheme
    } else {
      val drmCallback =
        HttpMediaDrmCallback(requireNotNull(mediaDrm.licenseUrl), httpDataSourceFactory)
      mediaDrm.keyRequestPropertiesArray?.let {
        for (i in 0 until it.size - 1 step 2) {
          drmCallback.setKeyRequestProperty(it[i], it[i + 1])
        }
      }

      drmSessionManager = DefaultDrmSessionManager.Builder()
          .setUuidAndExoMediaDrmProvider(drmSchemeUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
          .setKeyRequestParameters(emptyMap())
          .setMultiSession(mediaDrm.multiSession)
          .build(drmCallback)
    }

    if (drmSessionManager == null) {
      val error = context.getString(errorStringId)
      Toast.makeText(context, error, LENGTH_SHORT)
          .show()
      throw DrmSessionException(RuntimeException(error))
    }

    return drmSessionManager
  }
}
