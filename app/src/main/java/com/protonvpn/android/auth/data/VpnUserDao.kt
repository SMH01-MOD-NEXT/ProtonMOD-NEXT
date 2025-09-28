/*
 * Copyright (c) 2021 Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.android.auth.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.proton.core.data.room.db.BaseDao
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId


@Dao
abstract class VpnUserDao : BaseDao<VpnUser>() {

    @Query("SELECT * FROM VpnUser WHERE userId = :userId")
    protected abstract fun getRawByUserId(userId: UserId): Flow<VpnUser?>

    fun getByUserId(userId: UserId): Flow<VpnUser> =
        getRawByUserId(userId).map { user ->
            (user ?: VpnUser(
                userId = userId,
                subscribed = 1,
                services = VpnUser.VPN_SUBSCRIBED_FLAG,
                delinquent = 0,
                credit = 0,
                hasPaymentMethod = false,
                status = 0,
                expirationTime = Int.MAX_VALUE,
                planName = "plus",
                planDisplayName = "Plus",
                maxTier = VpnUser.PLUS_TIER,
                maxConnect = 10,
                name = "mod",
                groupId = "mod",
                password = "",
                updateTime = System.currentTimeMillis(),
                sessionId = SessionId("mod"),
                autoLoginName = null
            )).copy(
                subscribed = 1,
                services = VpnUser.VPN_SUBSCRIBED_FLAG,
                maxTier = VpnUser.PLUS_TIER
            )
        }
}

