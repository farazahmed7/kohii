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

package kohii.v1.internal

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import androidx.recyclerview.widget.RecyclerViewUtils
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kohii.v1.core.Bucket
import kohii.v1.core.Manager
import kohii.v1.core.Playback
import kohii.v1.core.Selector
import java.lang.ref.WeakReference
import kotlin.LazyThreadSafetyMode.NONE

internal class RecyclerViewBucket(
  manager: Manager,
  override val root: RecyclerView,
  selector: Selector = defaultSelector
) : Bucket(manager, root, selector), RecyclerView.OnChildAttachStateChangeListener {

  companion object {
    fun RecyclerView.fetchOrientation(): Int {
      return when (val layout = this.layoutManager ?: return NONE_AXIS) {
        is LinearLayoutManager -> layout.orientation
        is StaggeredGridLayoutManager -> layout.orientation
        else -> {
          return if (layout.canScrollVertically()) {
            if (layout.canScrollHorizontally()) BOTH_AXIS
            else VERTICAL
          } else {
            if (layout.canScrollHorizontally()) HORIZONTAL
            else NONE_AXIS
          }
        }
      }
    }
  }

  internal class SimpleScrollListener(manager: Manager) : OnScrollListener() {

    private val weakManager = WeakReference(manager)

    override fun onScrolled(
      recyclerView: RecyclerView,
      dx: Int,
      dy: Int
    ) {
      weakManager.get()
          ?.refresh()
    }
  }

  private val scrollListener by lazy(NONE) {
    SimpleScrollListener(
        manager
    )
  }

  override fun onAdded() {
    super.onAdded()
    root.addOnScrollListener(scrollListener)
    root.addOnChildAttachStateChangeListener(this)
  }

  override fun onAttached() {
    super.onAttached()
    root.doOnLayout {
      if (root.scrollState == RecyclerView.SCROLL_STATE_IDLE) manager.refresh()
    }
  }

  override fun onRemoved() {
    super.onRemoved()
    root.removeOnScrollListener(scrollListener)
    root.removeOnChildAttachStateChangeListener(this)
  }

  override fun accepts(container: ViewGroup): Boolean {
    if (!ViewCompat.isAttachedToWindow(container)) return false
    val params = RecyclerViewUtils.fetchItemViewParams(container)
    return RecyclerViewUtils.accepts(root, params)
  }

  override fun allowToPlay(playback: Playback): Boolean {
    val container = playback.container
    return root.findContainingViewHolder(container) != null && super.allowToPlay(playback)
  }

  override fun selectToPlay(candidates: Collection<Playback>): Collection<Playback> {
    return selectByOrientation(candidates, orientation = root.fetchOrientation())
  }

  override fun onChildViewAttachedToWindow(view: View) {
    val holder = root.findContainingViewHolder(view)
    manager.master.requests.filter {
      RecyclerViewUtils.fetchViewHolder(it.key) === holder
    }
        .forEach {
          it.value.bucket = this
        }
  }

  override fun onChildViewDetachedFromWindow(view: View) {
    // do nothing
  }
}
