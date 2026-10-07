package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.BlobSasIssuer
import com.azure.core.credential.TokenCredential
import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.storage.blob.BlobServiceClient
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.blob.models.UserDelegationKey
import com.azure.storage.blob.sas.BlobSasPermission
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues
import com.azure.storage.common.sas.SasProtocol
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Azure implementation of [BlobSasIssuer] (docs/requests/backend-admin-blob-sas.md; infra lane): user-delegation SAS
 * signed with a key obtained by the api's managed identity (role Storage Blob Delegator plus Storage Blob Data
 * Contributor on the account, infra/modules/identities.bicep). No account key exists anywhere: the storage account
 * refuses shared-key access, and nothing secret is logged (a SAS is a bearer URL, so callers must not log it either).
 *
 * - [writeSas]: create and write on that ONE blob, HTTPS only, from five minutes ago (phone clock skew) until
 *   `expiresAt`. A SAS cannot cap the size; `maxBytes` is enforced by the caller before issuing and by the
 *   blob-created check on the server.
 * - [readUrl]: read on that one blob for [readTtl] (phones fetch tutorials and assets during the day, offline after).
 * The delegation key is cached and renewed when less than [keyRenewBefore] of its life remains.
 */
class AzureBlobSasIssuer internal constructor(
    private val service: BlobServiceClient,
    private val container: String,
    private val keyProvider: (OffsetDateTime, OffsetDateTime) -> UserDelegationKey,
    private val now: () -> Instant = Instant::now,
    private val readTtl: Duration = Duration.ofHours(24),
    private val keyLife: Duration = Duration.ofDays(2),
    private val keyRenewBefore: Duration = Duration.ofHours(25),
) : BlobSasIssuer {
    @Volatile private var key: UserDelegationKey? = null

    private fun delegationKey(until: OffsetDateTime): UserDelegationKey = synchronized(this) {
        val cached = key
        val t = now().atOffset(ZoneOffset.UTC)
        if (cached != null && cached.signedExpiry.isAfter(t.plus(keyRenewBefore)) && !cached.signedExpiry.isBefore(until)) return cached
        val fresh = keyProvider(t.minusMinutes(5), maxOf(t.plus(keyLife), until))
        key = fresh
        fresh
    }

    private fun sign(blobPath: String, expiry: OffsetDateTime, permission: BlobSasPermission): String {
        require(blobPath.isNotBlank() && !blobPath.startsWith("/") && ".." !in blobPath.split('/')) { "invalid blob path" }
        val start = now().atOffset(ZoneOffset.UTC).minusMinutes(5)
        val values = BlobServiceSasSignatureValues(expiry, permission).setStartTime(start).setProtocol(SasProtocol.HTTPS_ONLY)
        val blob = service.getBlobContainerClient(container).getBlobClient(blobPath)
        return blob.blobUrl + "?" + blob.generateUserDelegationSas(values, delegationKey(expiry))
    }

    override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(expiresAt.isAfter(now())) { "expiresAt must be in the future" }
        return sign(blobPath, expiresAt.atOffset(ZoneOffset.UTC), BlobSasPermission().setCreatePermission(true).setWritePermission(true))
    }

    override fun readUrl(blobPath: String): String =
        sign(blobPath, now().plus(readTtl).atOffset(ZoneOffset.UTC), BlobSasPermission().setReadPermission(true))

    companion object {
        /**
         * The issuer for this environment, or null when blob storage is not configured (local runs, CI image smoke).
         * Reads ARON_BLOB_ACCOUNT and ARON_BLOB_CONTAINER_MEDIA (infra/apps.bicep); AZURE_CLIENT_ID picks the
         * user-assigned managed identity. Building it makes no network call; the first SAS fetches the key.
         */
        fun fromEnvironment(env: (String) -> String? = System::getenv): AzureBlobSasIssuer? {
            val account = env("ARON_BLOB_ACCOUNT")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val container = env("ARON_BLOB_CONTAINER_MEDIA")?.trim()?.takeIf { it.isNotEmpty() } ?: "media"
            val credential: TokenCredential = DefaultAzureCredentialBuilder()
                .apply { env("AZURE_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { managedIdentityClientId(it) } }
                .build()
            val service = BlobServiceClientBuilder().endpoint("https://$account.blob.core.windows.net").credential(credential).buildClient()
            return AzureBlobSasIssuer(service, container, { start, expiry -> service.getUserDelegationKey(start, expiry) })
        }
    }
}
