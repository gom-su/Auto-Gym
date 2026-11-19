package com.example.workoutpoc.wearapp

import android.content.Context
import android.net.Uri
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

internal data class WearPhone(val id: String, val nearby: Boolean)

internal interface WearPanelTransport {
    fun listen(
        onPhonesChanged: (Set<WearPhone>) -> Unit,
        onPanelChanged: (String) -> Unit,
    )

    fun stopListening()
    suspend fun reachablePhones(): Set<WearPhone>
    suspend fun request(nodeId: String, path: String, body: ByteArray): ByteArray
}

internal class GoogleWearPanelTransport(context: Context) : WearPanelTransport {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val data = Wearable.getDataClient(context)
    private val messages = Wearable.getMessageClient(context)
    private var capabilityListener: CapabilityClient.OnCapabilityChangedListener? = null
    private var dataListener: DataClient.OnDataChangedListener? = null

    override fun listen(onPhonesChanged: (Set<WearPhone>) -> Unit, onPanelChanged: (String) -> Unit) {
        val phoneListener = CapabilityClient.OnCapabilityChangedListener {
            onPhonesChanged(it.nodes.toWearPhones())
        }
        val panelListener = DataClient.OnDataChangedListener { events ->
            events.mapNotNull { event -> event.dataItem.uri.host }
                .toSet()
                .forEach(onPanelChanged)
        }
        capabilityListener = phoneListener
        dataListener = panelListener
        capabilities.addListener(phoneListener, PanelProtocol.PHONE_CAPABILITY)
        data.addListener(
            panelListener,
            Uri.Builder().scheme("wear").authority("*").path(PanelProtocol.CHANGED_PATH).build(),
            DataClient.FILTER_LITERAL,
        )
    }

    override fun stopListening() {
        capabilityListener?.let { capabilities.removeListener(it, PanelProtocol.PHONE_CAPABILITY) }
        dataListener?.let { data.removeListener(it) }
        capabilityListener = null
        dataListener = null
    }

    override suspend fun reachablePhones(): Set<WearPhone> =
        capabilities.getCapability(PanelProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await().nodes.toWearPhones()

    override suspend fun request(nodeId: String, path: String, body: ByteArray): ByteArray =
        messages.sendRequest(nodeId, path, body).await()
}

private fun Set<Node>.toWearPhones(): Set<WearPhone> =
    mapTo(mutableSetOf()) { WearPhone(it.id, it.isNearby) }
