package su.reya.coop

import kotlinx.coroutines.flow.StateFlow

interface ConnectivityMonitor {
    val isMobileData: StateFlow<Boolean>
}
