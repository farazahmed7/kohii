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

package kohii.v1.core

import android.graphics.Rect
import android.os.Handler
import android.os.Message
import android.view.ViewGroup
import androidx.collection.arraySetOf
import androidx.core.app.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kohii.v1.core.Manager.OnSelectionListener
import kohii.v1.distanceTo
import kohii.v1.internal.Organizer
import kohii.v1.internal.PlayableDispatcher
import kohii.v1.media.VolumeInfo
import kohii.v1.partitionToMutableSets
import java.util.ArrayDeque
import kotlin.properties.Delegates

class Group(
  internal val master: Master,
  internal val activity: ComponentActivity
) : DefaultLifecycleObserver, Handler.Callback {

  companion object {
    const val DELAY = 2 * 1000L / 60 /* about 2 frames */
    const val MSG_REFRESH = 1

    private val managerComparator = Comparator<Manager> { o1, o2 -> o2.compareTo(o1) }
  }

  override fun handleMessage(msg: Message): Boolean {
    if (msg.what == MSG_REFRESH) this.refresh()
    return true
  }

  internal val managers = ArrayDeque<Manager>()
  internal val organizer = Organizer()

  @Suppress("RemoveExplicitTypeArguments")
  private var stickyManager: Manager? by Delegates.observable<Manager?>(
      initialValue = null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        if (to != null) { // a Manager is promoted
          to.sticky = true
          managers.push(to)
        } else {
          require(from != null && from.sticky)
          if (managers.peek() === from) {
            from.sticky = false
            managers.pop()
          }
        }
      }
  )

  internal var groupVolume: VolumeInfo by Delegates.observable(
      initialValue = VolumeInfo(),
      onChange = { _, from, to ->
        if (from == to) return@observable
        // Update VolumeInfo of all Managers. This operation will then callback to this #applyVolumeInfo
        managers.forEach { it.managerVolume = to }
      }
  )

  internal val volumeInfo: VolumeInfo
    get() = groupVolume

  internal var lock: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      managers.forEach { it.lock = value }
    }

  private val handler = Handler(this)
  private val dispatcher = PlayableDispatcher(master)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass !== other?.javaClass) return false
    other as Group
    if (activity !== other.activity) return false
    return true
  }

  override fun hashCode(): Int {
    return activity.hashCode()
  }

  private val playbacks: Collection<Playback>
    get() = managers.flatMap { it.playbacks.values }

  override fun onCreate(owner: LifecycleOwner) {
    master.onGroupCreated(this)
  }

  override fun onDestroy(owner: LifecycleOwner) {
    handler.removeCallbacksAndMessages(null)
    owner.lifecycle.removeObserver(this)
    master.onGroupDestroyed(this)
  }

  override fun onStart(owner: LifecycleOwner) {
    dispatcher.onStart()
  }

  override fun onStop(owner: LifecycleOwner) {
    dispatcher.onStop()
    handler.removeMessages(MSG_REFRESH)
  }

  internal fun findBucketForContainer(container: ViewGroup): Bucket? {
    check(ViewCompat.isAttachedToWindow(container))
    return managers.asSequence()
        .mapNotNull { it.findBucketForContainer(container) }
        .firstOrNull()
  }

  internal fun onRefresh() {
    handler.removeMessages(MSG_REFRESH)
    handler.sendEmptyMessageDelayed(
        MSG_REFRESH,
        DELAY
    )
  }

  private fun refresh() {
    val playbacks = this.playbacks // save a cache to prevent re-mapping
    playbacks.forEach { it.onRefresh() } // update token

    val toPlay = linkedSetOf<Playback>() // Need the order.
    val toPause = arraySetOf<Playback>()

    stickyManager?.let {
      val (canPlay, canPause) = it.splitPlaybacks()
      toPlay.addAll(canPlay)
      toPause.addAll(canPause)
    } ?: managers.forEach {
      val (canPlay, canPause) = it.splitPlaybacks()
      toPlay.addAll(canPlay)
      toPause.addAll(canPause)
    }

    val oldSelection = organizer.selection
    val newSelection = if (lock) emptyList() else organizer.selectFinal(toPlay)

    // Next: as Playbacks are split into 2 collections, we then release unused resources and prepare
    // the ones that need to. We do so by updating Playback's distance to candidate.
    updatePlaybackDistances(playbacks, newSelection)

    (toPause + toPlay + oldSelection - newSelection).mapNotNull { it.playable }
        .forEach { dispatcher.pause(it) }

    if (newSelection.isNotEmpty()) {
      newSelection.mapNotNull { it.playable }
          .forEach { dispatcher.play(it) }

      val grouped = newSelection.groupBy { it.manager }
      this.managers.asSequence()
          .filter { it.host is OnSelectionListener }
          .forEach {
            (it.host as OnSelectionListener).onSelection(grouped[it] ?: emptyList())
          }
    }
  }

  private fun updatePlaybackDistances(
    playbacks: Collection<Playback>,
    selection: Collection<Playback>
  ) {
    // biggest Rect that covers all selected Playbacks
    val cover = selection.fold(Rect()) { acc, playback ->
      acc.union(playback.token.containerRect)
      return@fold acc
    }

    // Update distanceToPlay
    // calculate a target: Pair<Pair<Int, Int>, Pair<Int, Int>> to save information.
    val target = (cover.centerX() to cover.width() / 2) to (cover.centerY() to cover.height() / 2)
    if (target.first.second > 0 && target.second.second > 0) {
      // update distance of non-selected Playback first, so they can release unused/obsoleted
      // resource before the selected ones who consume a lot of resources.
      playbacks.partitionToMutableSets(
          predicate = { it.isActive },
          transform = { it }
      )
          .also { (active, inactive) ->
            inactive.forEach { it.distanceToPlay = Int.MAX_VALUE }
            // TODO better way that doesn't require allocating new collection?
            (active - selection).sortedBy { it.token.containerRect distanceTo target }
                .forEachIndexed { index, playback -> playback.distanceToPlay = index }
          }
      selection.forEach { it.distanceToPlay = 0 }
    }
  }

  internal fun onManagerDestroyed(manager: Manager) {
    if (stickyManager === manager) stickyManager = null
    if (managers.remove(manager)) master.onGroupUpdated(this)
    if (managers.size == 0) master.onLastManagerDestroyed(this)
  }

  // This operation should:
  // - Ensure the order of Manager by its Priority
  // - Ensure stickyManager is in the head.
  internal fun onManagerCreated(manager: Manager) {
    if (managers.isEmpty()) master.onFirstManagerCreated(this)
    val updated: Boolean
    // 1. Pop out the sticky Manager if available.
    val sticky = if (managers.peek()?.sticky == true) managers.pop() else null

    // 2. Add the Manager to the queue using its Priority if available.
    if (manager.host is Prioritized) {
      if (!managers.contains(manager)) {
        updated = managers.add(manager)
        val temp = managers.sortedWith(
            managerComparator
        )
        managers.clear()
        managers.addAll(temp)
      } else
        updated = false
    } else {
      updated = !managers.contains(manager) && managers.add(manager)
    }

    // 3. Push the sticky Manager back to head of the queue.
    if (sticky != null) managers.push(sticky)
    if (updated) {
      master.engines.forEach { it.value.prepare(manager) }
      master.onGroupUpdated(this)
    }
  }

  // Expected result:
  // - If 'manager' is not added to Group yet, through Exception.
  // - The 'manager' should be on the head of the 'managers' queue after this operation, though the
  // 'managers' queue should still be able to remember its original location for the unsticking.
  internal fun stick(manager: Manager) {
    stickyManager = manager
  }

  internal fun unstick(manager: Manager?) {
    if (manager == null || stickyManager === manager) stickyManager = null
  }
}
