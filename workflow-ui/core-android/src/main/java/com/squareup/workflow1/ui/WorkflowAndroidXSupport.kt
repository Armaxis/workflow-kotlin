package com.squareup.workflow1.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistry.SavedStateProvider
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Namespace for some helper functions for interacting with the AndroidX libraries.
 */
public object WorkflowAndroidXSupport {

  /**
   * Tries to get the parent lifecycle from the current view via [ViewTreeLifecycleOwner], if that
   * fails it looks up the context chain for a [LifecycleOwner], and if that fails it just returns
   * null.
   *
   * By explicitly looking at the context if the view tree is missing an owner, we can automatically
   * handle cases where a custom Activity, for example, has overridden setContentView and thus
   * disabled the ViewTreeOwner integration.
   */
  @WorkflowUiExperimentalApi
  public fun lifecycleOwnerFromViewTreeOrContext(view: View): LifecycleOwner? =
    ViewTreeLifecycleOwner.get(view) ?: view.context.lifecycleOwnerOrNull()

  /**
   * Tries to get the parent [SavedStateRegistryOwner] from the current view tree's view via
   * [ViewTreeSavedStateRegistryOwner], if that fails it looks up the context chain for a one,
   * and if that fails it just returns null.
   *
   * By explicitly looking at the context if the view tree is missing an owner, we can automatically
   * handle cases where a custom Activity, for example, has overridden setContentView and thus
   * disabled the ViewTreeOwner integration.
   */
  @WorkflowUiExperimentalApi
  public fun savedStateRegistryOwnerFromViewTreeOrContext(view: View): SavedStateRegistryOwner? =
    ViewTreeSavedStateRegistryOwner.get(view) ?: view.context.savedStateRegistryOwnerOrNull()

  /**
   * Computes a string key for using with a `SavedStateRegistry` that will be globally unique.
   * Requires View IDs to be set for any views that are siblings and have the same rendering type.
   * The key is generated by combining the rendering [compatibility keys][Named.keyFor] of each
   * rendering starting with the current one and going up the view tree to the root. Each rendering
   * key is also associated with the ID of the [View] it's tagged on.
   */
  @WorkflowUiExperimentalApi
  public fun compositeViewIdKey(view: View): String {
    return generateSequence(view) { it.parent as? ViewGroup }
      // This will generate the list of ancestor IDs from child -> parent, so it will be in the
      // reverse order we want (child/parent/root).
      .map { it.id.toString() }
      // Sequences can only be iterated in order, so we need a list to reverse it.
      .toMutableList()
      // Reverse in-place.
      .apply { reverse() }
      .joinToString(separator = "/")
  }

  /**
   * Returns an [OnAttachStateChangeListener] that should be registered on a [View] to wire up a
   * [SavedStateRegistryClient] to the view's [ViewTreeSavedStateRegistryOwner].
   *
   * The returned listener should be registered on a view using
   * [View.addOnAttachStateChangeListener].
   */
  @WorkflowUiExperimentalApi
  public fun createSavedStateRegistryClient(
    client: SavedStateRegistryClient
  ): OnAttachStateChangeListener = SavedStateRegistryClientImpl(client)

  private tailrec fun Context.lifecycleOwnerOrNull(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    else -> (this as? ContextWrapper)?.baseContext?.lifecycleOwnerOrNull()
  }

  private tailrec fun Context.savedStateRegistryOwnerOrNull(): SavedStateRegistryOwner? =
    when (this) {
      is SavedStateRegistryOwner -> this
      else -> (this as? ContextWrapper)?.baseContext?.savedStateRegistryOwnerOrNull()
    }

  /**
   * Allows an object to be wired up to a [SavedStateRegistry] via [createSavedStateRegistryClient].
   */
  public interface SavedStateRegistryClient {
    /**
     * The key passed to [SavedStateRegistry] methods that uniquely identifies this client's data
     * and registrations to the registry. This value must be unique relative to the registry
     * instance that it is used with – this typically means relative to the local "navigation frame"
     * (e.g. backstack frame).
     */
    public val savedStateRegistryKey: String

    /**
     * Called when the client is registered and the [SavedStateRegistry] is asked to save state.
     * Directly corresponds to [SavedStateProvider.saveState].
     */
    public fun onSaveToRegistry(bundle: Bundle)

    /**
     * Called when the client is ready to be restored – that is, the managed view is both attached
     * to a window and its [ViewTreeLifecycleOwner] is in at least the `CREATED` state.
     *
     * @param bundle The [Bundle] returned from [SavedStateRegistry.consumeRestoredStateForKey].
     * Will be null if the registry had no entry for this [savedStateRegistryKey], i.e. the view is
     * not being restored.
     */
    public fun onRestoreFromRegistry(bundle: Bundle?)
  }

  @OptIn(WorkflowUiExperimentalApi::class)
  private class SavedStateRegistryClientImpl(
    private val client: SavedStateRegistryClient
  ) : OnAttachStateChangeListener,
    LifecycleEventObserver,
    SavedStateProvider {

    private var lifecycle: Lifecycle? = null
    private var registry: SavedStateRegistry? = null

    private val key by lazy(NONE, client::savedStateRegistryKey)

    override fun onViewAttachedToWindow(v: View) {
      val owner = requireNotNull(savedStateRegistryOwnerFromViewTreeOrContext(v)) {
        "Expected to find either a ViewTreeSavedStateRegistryOwner in the view tree, or a " +
          "SavedStateRegistryOwner in the Context chain."
      }
      lifecycle = owner.lifecycle
      registry = owner.savedStateRegistry

      // Even though we're attached, we can't try to consume restored state until the owning
      // lifecycle is in at least the CREATED state.
      lifecycle!!.addObserver(this)

      // The exception thrown by this function doesn't include the key, so it's not very helpful for
      // debugging. If it throws, we wrap it with a more descriptive exception.
      try {
        registry!!.registerSavedStateProvider(key, this)
      } catch (e: Exception) {
        throw IllegalArgumentException("Error registering SavedStateProvider for key: $key", e)
      }
    }

    override fun onViewDetachedFromWindow(v: View) {
      // Don't care if these throw for any reason, since the reason the view is being detached may
      // be that onAttached threw and things are in an inconsistent state.
      runCatching { registry?.unregisterSavedStateProvider(key) }
      runCatching { lifecycle?.removeObserver(this) }
      // Null everything out just to be safe wrt memory leaks.
      registry = null
      lifecycle = null
    }

    override fun onStateChanged(
      source: LifecycleOwner,
      event: Event
    ) {
      if (event == ON_CREATE) {
        lifecycle!!.removeObserver(this)

        // At this point we know the view we're managing is attached (since we're only registered
        // as a lifecycle observer in between calls to onAttached and onDetached), and we are now
        // in the CREATED state, so we can finally consume restored state.
        val restoredBundle = registry!!.consumeRestoredStateForKey(key)

        // restoredBundle will be null if there was no state to restore. We still call
        // performRestore so the client instance knows it's no longer waiting for a restore call
        // _with_ a bundle.
        client.onRestoreFromRegistry(restoredBundle)
      }
    }

    override fun saveState(): Bundle = Bundle().also(client::onSaveToRegistry)
  }
}
