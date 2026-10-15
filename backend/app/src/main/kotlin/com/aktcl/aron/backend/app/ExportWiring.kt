package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.ExportStore
import java.time.Instant

/** Export files go to the `exports` container of the same storage account (ARON_BLOB_CONTAINER_EXPORTS), read through short-lived user-delegation links; no account key exists. */
fun exportStoreFromEnvironment(): ExportStore =
    AzureBlobSasIssuer.fromEnvironment(containerVar = "ARON_BLOB_CONTAINER_EXPORTS", defaultContainer = "exports")?.let { az ->
        object : ExportStore {
            override fun put(path: String, bytes: ByteArray) = az.putBytes(path, bytes)
            override fun readUrl(path: String, expiresAt: Instant): String = az.readSas(path, expiresAt)
        }
    } ?: ExportStore.Unconfigured
