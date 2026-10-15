package com.my.kizzy.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The images to register, as Discord's external-assets endpoint wants them. */
@Serializable
data class ExternalAssetRequest(
    val urls: List<String>,
)

/**
 * One registered image.
 *
 * Discord answers with a list in the order it was asked, one entry per URL. The path is relative to
 * its media proxy and is not a URL - it becomes an asset key by having `mp:` put in front of it.
 */
@Serializable
data class ExternalAsset(
    @SerialName("external_asset_path")
    val path: String? = null,
)
