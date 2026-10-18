package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.BlobSasIssuer
import com.aktcl.aron.backend.masterdata.UnconfiguredBlobSasIssuer
import com.aktcl.aron.backend.media.PhotoBlobWriter
import com.aktcl.aron.backend.media.PhotoReadIssuer
import com.aktcl.aron.backend.media.UnconfiguredPhotoBlobWriter
import com.aktcl.aron.backend.media.UnconfiguredPhotoReadIssuer

/**
 * The blob ports of the API: SAS issuer (admin assets, content, tutorials, PDA upload, media SAS), photo writer
 * (multipart media fallback), photo read issuer and the pack picture reader. Production derives all three from one [AzureBlobSasIssuer] when
 * ARON_BLOB_ACCOUNT is set, else the 503 implementations; tests pass an in-memory store to [Wiring.production].
 */
class BlobPorts(
    val sas: BlobSasIssuer, val photoWriter: PhotoBlobWriter, val photoRead: PhotoReadIssuer,
    /** Pack picture bytes (PARITY-SPEC s2.6), read with the API's identity. */
    val imageRead: com.aktcl.aron.backend.media.BlobBytesReader = com.aktcl.aron.backend.media.UnconfiguredBlobBytesReader,
) {
    companion object {
        fun fromEnvironment(): BlobPorts {
            val az = AzureBlobSasIssuer.fromEnvironment() ?: return BlobPorts(UnconfiguredBlobSasIssuer, UnconfiguredPhotoBlobWriter, UnconfiguredPhotoReadIssuer)
            return BlobPorts(az, PhotoBlobWriter(az::putBytes), PhotoReadIssuer(az::readSas), com.aktcl.aron.backend.media.BlobBytesReader(az::readBytes))
        }
    }
}
