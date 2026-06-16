package com.qemuapk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qemuapk.display.VncCanvasView
import com.qemuapk.input.KeyboardForwarder
import com.qemuapk.input.NavigationKey
import com.qemuapk.input.TouchForwarder
import com.qemuapk.vm.VmInstance

/**
 * Screen that displays the running VM via VNC with a floating toolbar
 * for navigation and input.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VmRunningScreen(
    vmInstance: VmInstance,
    vncHost: String = "127.0.0.1",
    vncPort: Int = vmInstance.config.vncPort,
    onBack: () -> Unit,
    onStop: () -> Unit
) {
    // State to hold references to the VNC view and forwarders
    var vncView by remember { mutableStateOf<VncCanvasView?>(null) }
    var touchForwarder by remember { mutableStateOf<TouchForwarder?>(null) }
    var keyboardForwarder by remember { mutableStateOf<KeyboardForwarder?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // VNC display surface
        AndroidView(
            factory = { context ->
                VncCanvasView(context).also { view ->
                    vncView = view
                    touchForwarder = TouchForwarder(view)
                    keyboardForwarder = KeyboardForwarder(view)

                    // Connect to VNC server after a delay to let QEMU start
                    view.postDelayed({
                        view.connect(vncHost, vncPort)
                    }, 2000)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    touchForwarder?.onTouchEvent(event) ?: false
                },
            update = { /* No-op */ }
        )

        // Floating toolbar at the bottom
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                IconButton(onClick = {
                    keyboardForwarder?.sendNavigationKey(NavigationKey.BACK)
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }

                // Home button
                IconButton(onClick = {
                    keyboardForwarder?.sendNavigationKey(NavigationKey.HOME)
                }) {
                    Icon(Icons.Default.Home, contentDescription = "Home")
                }

                // Recent apps
                IconButton(onClick = {
                    keyboardForwarder?.sendNavigationKey(NavigationKey.RECENT)
                }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Recent Apps")
                }

                // Keyboard toggle
                IconButton(onClick = {
                    // Request keyboard focus
                    vncView?.requestFocus()
                }) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Keyboard")
                }

                // Refresh / reconnect VNC
                IconButton(onClick = {
                    vncView?.let { view ->
                        view.disconnect()
                        view.postDelayed({
                            view.connect(vncHost, vncPort)
                        }, 1000)
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }

                // Stop VM
                IconButton(onClick = onStop) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // VM name badge
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = vmInstance.config.name,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
