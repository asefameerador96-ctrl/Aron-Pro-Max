package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.common.ClientIds

/**
 * The client uuid of a form that queues a record (a task, a verification decision, an exception review), kept across a swipe-kill:
 * `rememberSaveable` does not survive one, so a form reopened after the kill would queue a SECOND row for the same act. The uuid is
 * fixed per target ([key]) until the save succeeds ([clear]); a retry, a double tap and a relaunch all queue the one record
 * (the server and the phone upsert by that uuid). One small file per user, no personal data: uuids only.
 */
class FormDrafts(context: Context, userId: Long) {
    private val prefs = context.getSharedPreferences("aron-amo-form-drafts-$userId", Context.MODE_PRIVATE)

    @Synchronized fun uuid(key: String): String = prefs.getString(key, null) ?: ClientIds.newUuid().also { prefs.edit().putString(key, it).apply() }

    @Synchronized fun clear(key: String) { prefs.edit().remove(key).apply() }
}
