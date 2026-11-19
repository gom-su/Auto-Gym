package com.example.workoutpoc.wear

import android.util.Log
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.workoutApp
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

class PhonePanelListenerService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path !in paths) return null
        if (request.size > 2_048) return Tasks.forResult(PanelProtocol.encode(PanelReply(ReplyResult.REJECTED)))
        val completion = TaskCompletionSource<ByteArray>()
        workoutApp.scope.launch(Dispatchers.Default) {
            val reply = try {
                workoutApp.catalog.restore()
                workoutApp.wearBridge.handler.reply(path, request)
            } catch (e: CancellationException) {
                completion.trySetException(e)
                throw e
            } catch (e: SerializationException) {
                PanelReply(ReplyResult.REJECTED)
            } catch (e: Exception) {
                Log.w("AutoGymWear", "Falló la solicitud del reloj", e)
                PanelReply(ReplyResult.ERROR)
            }
            completion.trySetResult(PanelProtocol.encode(reply))
        }
        return completion.task
    }

    private companion object {
        val paths = setOf(PanelProtocol.STATE_PATH, PanelProtocol.ACTION_PATH, PanelProtocol.SUMMARY_PATH)
    }
}
