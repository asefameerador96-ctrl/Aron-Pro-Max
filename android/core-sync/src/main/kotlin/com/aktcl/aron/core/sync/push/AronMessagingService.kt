package com.aktcl.aron.core.sync.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM entry point (N-038). Data messages only; both calls return at once (local work and a WorkManager job), well inside
 * FCM's time limit. A message that arrives before the app shell is installed is dropped: a push is only a nudge.
 */
class AronMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PushRuntime.handler?.onMessage(message.data)
    }

    override fun onNewToken(token: String) {
        PushRuntime.handler?.onNewToken(token)
    }
}
