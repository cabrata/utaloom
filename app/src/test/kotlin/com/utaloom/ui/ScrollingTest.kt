package com.utaloom.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.utaloom.data.Song
import com.utaloom.data.Stores
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import org.junit.Test

/** Exercises the actual Compose input pipeline, including AWT's mouse-emulated touchscreen events. */
// ponytail: reuse the pinned Compose scene API for tests, use compose-ui-test if its API changes on upgrade.
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ScrollingTest {
    @Test
    fun settingsAndLibraryScreensScrollWithRightSideNavigation() {
        val library = Stores.library.value
        Stores.library.update { it.copy(liked = List(60) { i -> Song("scroll$i", "Scrolling song $i", listOf("Utaloom")) }) }
        try {
            for (screen in listOf<@Composable () -> Unit>({ SettingsScreen() }, { SongListScreen("Liked songs", "liked") }, { TogetherScreen() })) {
                Scene(screen).use { ui ->
                    check(ui.verticalOffset() == 0f)
                    ui.drag(Offset(50f, 270f), Offset(50f, 90f))
                    check(ui.verticalOffset() > 0f) { "The actual screen must scroll using a touchscreen-style drag" }
                }
                Scene(screen).use { ui ->
                    ui.drag(Offset(474f, 6f), Offset(474f, 240f))
                    check(ui.verticalOffset() > 0f) { "The actual screen must expose a draggable right-side scrollbar" }
                }
            }
        } finally { Stores.library.update { library } }
    }

    @Test
    fun listsScrollBothWaysWithoutActivatingRows() {
        for (type in listOf(PointerType.Mouse, PointerType.Touch)) {
            val state = LazyListState()
            var clicks = 0
            Scene {
                VScrollColumn(Modifier.fillMaxSize(), state) {
                    items(60) { Text("Song $it", Modifier.fillMaxWidth().height(60.dp).clickable { clicks++ }) }
                }
            }.use { ui ->
                ui.drag(Offset(150f, 250f), Offset(150f, 70f), type)
                check(position(state) > 100) { "$type must scroll down the list" }
                check(clicks == 0) { "Dragging must not activate a song" }
                val before = position(state)
                ui.drag(Offset(150f, 70f), Offset(150f, 230f), type)
                check(position(state) < before) { "$type must scroll back up" }
                ui.pointer(PointerEventType.Press, Offset(150f, 80f), true, type)
                ui.pointer(PointerEventType.Release, Offset(150f, 80f), false, type)
                check(clicks == 1) { "A tap must still activate a song" }
            }
        }
    }

    @Test
    fun wheelAndRightScrollbarNavigateTheList() {
        val state = LazyListState()
        Scene {
            VScrollColumn(Modifier.fillMaxSize(), state) {
                items(60) { Text("Song $it", Modifier.fillMaxWidth().height(60.dp)) }
            }
        }.use { ui ->
            ui.wheel(Offset(150f, 100f))
            check(position(state) > 0) { "Mouse wheel must scroll vertically" }
        }
        val thumbState = LazyListState()
        Scene {
            VScrollColumn(Modifier.fillMaxSize(), thumbState) {
                items(60) { Text("Song $it", Modifier.fillMaxWidth().height(60.dp)) }
            }
        }.use { ui ->
            ui.drag(Offset(474f, 12f), Offset(474f, 230f))
            check(thumbState.firstVisibleItemIndex > 20) { "Dragging the right scrollbar must navigate deep into the list" }
        }
    }

    @Test
    fun nestedCarouselsKeepHorizontalAndVerticalGesturesSeparate() {
        for (type in listOf(PointerType.Mouse, PointerType.Touch)) {
            val vertical = LazyListState()
            val horizontal = LazyListState()
            Scene {
                VScrollColumn(Modifier.fillMaxSize(), vertical) {
                    item {
                        HScrollRow(PaddingValues(0.dp), state = horizontal) {
                            items(20) { Text("Card $it", Modifier.width(160.dp).height(150.dp).clickable {}) }
                        }
                    }
                    items(30) { Text("Section $it", Modifier.fillMaxWidth().height(60.dp)) }
                }
            }.use { ui ->
                ui.drag(Offset(380f, 75f), Offset(100f, 83f), type)
                check(position(horizontal) > 100) { "$type must retain horizontal carousel dragging" }
                check(position(vertical) == 0) { "Horizontal drag must not move the page" }
                val horizontalBefore = position(horizontal)
                ui.drag(Offset(220f, 135f), Offset(212f, 30f), type)
                check(position(vertical) > 50) { "Vertical drag over a carousel must move the page" }
                check(position(horizontal) == horizontalBefore) { "Vertical drag must not move the carousel" }
            }
        }
        val vertical = LazyListState()
        val horizontal = LazyListState()
        Scene {
            VScrollColumn(Modifier.fillMaxSize(), vertical) {
                item { HScrollRow(PaddingValues(0.dp), state = horizontal) { items(20) { Text("Card $it", Modifier.width(160.dp).height(150.dp)) } } }
                items(30) { Text("Section $it", Modifier.height(60.dp)) }
            }
        }.use { ui ->
            ui.wheel(Offset(220f, 75f))
            check(position(vertical) > 0) { "Vertical wheel over a carousel must reach its parent page" }
            check(position(horizontal) == 0) { "Vertical wheel must not move a carousel sideways" }
        }
    }

    @Test
    fun plainContentScrollsAndShortContentKeepsItsHeight() {
        val state = ScrollState(0)
        Scene {
            VScrollBox(Modifier.fillMaxSize(), state) { Column { repeat(30) { Text("Line $it", Modifier.height(60.dp)) } } }
        }.use { ui ->
            ui.drag(Offset(150f, 250f), Offset(150f, 70f))
            check(state.value > 100) { "Plain lyrics and dialogs must support dragging" }
            val before = state.value
            ui.wheel(Offset(150f, 100f))
            check(state.value > before) { "Plain content must retain wheel scrolling" }
        }
        val short = ScrollState(0)
        Scene { VScrollBox(state = short) { Text("Short dialog") } }.use {
            check(short.maxValue == 0) { "A short dialog must not acquire artificial scroll range" }
            check(short.viewportSize in 1..100) { "The scrollbar must not stretch a short dialog to the window height" }
        }
    }

    private fun position(state: LazyListState) = state.firstVisibleItemIndex * 60 + state.firstVisibleItemScrollOffset

    private class Scene(content: @Composable () -> Unit) : AutoCloseable {
        private val recomposer = FrameRecomposer(Dispatchers.Unconfined)
        private var owner: SemanticsOwner? = null
        private val context = object : PlatformContext by PlatformContext.Empty() {
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owner = semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owner = null }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
            }
        }
        private val scene = CanvasLayersComposeScene(recomposer, size = IntSize(480, 320), platformContext = context)
        private val surface = Surface.makeRasterN32Premul(480, 320)
        private var time = 0L
        init { scene.setContent { MaterialTheme { content() } }; frame(5) }

        private fun frame(count: Int = 1) {
            repeat(count) {
                time += 16_000_000
                recomposer.performFrame(time)
                scene.measureAndLayout()
                scene.draw(surface.canvas.asComposeCanvas())
            }
        }

        fun pointer(event: PointerEventType, at: Offset, pressed: Boolean, type: PointerType = PointerType.Mouse) {
            scene.sendPointerEvent(event, at, timeMillis = time / 1_000_000, type = type,
                buttons = PointerButtons(isPrimaryPressed = pressed))
            frame()
        }

        fun drag(from: Offset, to: Offset, type: PointerType = PointerType.Mouse) {
            pointer(PointerEventType.Press, from, true, type)
            repeat(12) { i -> pointer(PointerEventType.Move, from + (to - from) * ((i + 1) / 12f), true, type) }
            repeat(12) { pointer(PointerEventType.Move, to, true, type) } // No fling, deterministic final offset.
            pointer(PointerEventType.Release, to, false, type)
            frame(8)
        }

        fun wheel(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, 3f), timeMillis = time / 1_000_000)
            frame(40)
        }

        fun verticalOffset(): Float {
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            val node = nodes(checkNotNull(owner).rootSemanticsNode).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            return node.config[SemanticsProperties.VerticalScrollAxisRange].value()
        }

        override fun close() { scene.close(); recomposer.close(); surface.close() }
    }
}
