package ch.lkmc.kirsch.integration

interface AssetCredentialSigner {
    val available: Boolean
    val unavailableReason: String
    fun sign(asset: ByteArray): ByteArray
}

interface RemoteProcessingGateway {
    val available: Boolean
    val unavailableReason: String
    fun submitAttested(asset: ByteArray): Nothing
}

object NoAssetCredentialSigner : AssetCredentialSigner {
    override val available = false
    override val unavailableReason = "No C2PA trust, certificate, timestamp, rotation, or revocation configuration"
    override fun sign(asset: ByteArray): ByteArray = error(unavailableReason)
}

object NoRemoteProcessingGateway : RemoteProcessingGateway {
    override val available = false
    override val unavailableReason = "No reviewed backend, attestation, economics, or deletion contract"
    override fun submitAttested(asset: ByteArray): Nothing = error(unavailableReason)
}
