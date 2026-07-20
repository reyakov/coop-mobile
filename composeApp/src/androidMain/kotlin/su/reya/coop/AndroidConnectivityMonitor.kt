package su.reya.coop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val manager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val _isMobileData = MutableStateFlow(checkIsMobileData())
    override val isMobileData: StateFlow<Boolean> = _isMobileData.asStateFlow()

    init {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        manager.registerNetworkCallback(
            networkRequest,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isMobileData.value = checkIsMobileData()
                }

                override fun onLost(network: Network) {
                    _isMobileData.value = checkIsMobileData()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    _isMobileData.value = checkIsMobileData()
                }
            })
    }

    private fun checkIsMobileData(): Boolean {
        val activeNetwork = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
