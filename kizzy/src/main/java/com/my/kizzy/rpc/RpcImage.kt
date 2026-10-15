/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * RpcImage.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.rpc

import com.my.kizzy.repository.KizzyRepository

/**
 * Modified by Zion Huang
 */
sealed class RpcImage {
    /**
     * [applicationId] is the app the image is registered against - see
     * [com.my.kizzy.remote.ApiService.externalAsset]. An image already hosted by Discord does not
     * need one, which is why it is a parameter here rather than something the repository holds.
     */
    abstract suspend fun resolveImage(repository: KizzyRepository, applicationId: String?): String?

    class DiscordImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository, applicationId: String?): String {
            return "mp:${image}"
        }
    }

    class ExternalImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(repository: KizzyRepository, applicationId: String?): String? {
            return repository.getImage(applicationId, image)
        }
    }
}