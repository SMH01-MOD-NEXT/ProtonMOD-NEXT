/*
 * Copyright (c) 2024 Proton AG
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.protonvpn.android.redesign.search.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.protonvpn.android.R
import com.protonvpn.android.auth.data.VpnUser
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.redesign.countries.Translator
import com.protonvpn.android.redesign.countries.ui.ServerFilterType
import com.protonvpn.android.redesign.countries.ui.ServerGroupItemData
import com.protonvpn.android.redesign.countries.ui.ServerGroupUiItem
import com.protonvpn.android.redesign.countries.ui.ServerGroupsMainScreenSaveState
import com.protonvpn.android.redesign.countries.ui.ServerGroupsMainScreenState
import com.protonvpn.android.redesign.countries.ui.ServerGroupsViewModel
import com.protonvpn.android.redesign.countries.ui.ServerListViewModelDataAdapter
import com.protonvpn.android.redesign.countries.ui.sortedForUi
import com.protonvpn.android.redesign.main_screen.ui.ShouldShowcaseRecents
import com.protonvpn.android.redesign.search.FetchServerResult
import com.protonvpn.android.redesign.search.SearchServerRemote
import com.protonvpn.android.utils.DebugUtils
import com.protonvpn.android.vpn.ConnectTrigger
import com.protonvpn.android.vpn.VpnConnect
import com.protonvpn.android.vpn.VpnStatusProviderUI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.transformLatest
import me.proton.core.presentation.savedstate.state
import java.util.Locale
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.map


private val remoteSearchDelay = 2.5.seconds

sealed class SearchViewState {
    data object ZeroScreen : SearchViewState()
    data class Result(val result: ServerGroupsMainScreenState) : SearchViewState()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    dataAdapter: ServerListViewModelDataAdapter,
    private val searchDataAdapter: SearchViewModelDataAdapter,
    private val remoteSearch: SearchServerRemote,
    connect: VpnConnect,
    shouldShowcaseRecents: ShouldShowcaseRecents,
    currentUser: CurrentUser,
    vpnStatusProviderUI: VpnStatusProviderUI,
    translator: Translator,
) : ServerGroupsViewModel<SearchViewState>(
    "search_view",
    savedStateHandle,
    dataAdapter,
    connect,
    shouldShowcaseRecents,
    currentUser,
    vpnStatusProviderUI,
    translator,
    defaultMainSavedState = ServerGroupsMainScreenSaveState(
        selectedFilter = ServerFilterType.All
    )
) {
    private var remoteSearchDisabled = false
    private var searchQuery by savedStateHandle.state<String>("", "search_query")
    val searchQueryFlow = savedStateHandle.getStateFlow("search_query", searchQuery)
    private val userTierFlow = currentUser.vpnUserFlow.map {
        it?.userTier ?: VpnUser.FREE_TIER
    }

    init {
        searchQueryFlow
            .transformLatest<String, Unit> { query ->
                if (!remoteSearchDisabled) {
                    delay(remoteSearchDelay)
                    val result = remoteSearch(query)
                    if (result is FetchServerResult.TryLater)
                        remoteSearchDisabled = true
                }
            }
            .launchIn(viewModelScope)
    }

    override fun mainScreenState(
        savedStateFlow: Flow<ServerGroupsMainScreenSaveState>,
        userTier: Int?,
        locale: Locale,
        currentConnection: ActiveConnection?
    ): Flow<SearchViewState> =
        searchQueryFlow.flatMapLatest { query ->
            if (query.isEmpty()) {
                mainSaveState = mainSaveState.copy(selectedFilter = ServerFilterType.All)
                flowOf(SearchViewState.ZeroScreen)
            } else combine(
                savedStateFlow,
                searchDataAdapter.search(query, locale),
                userTierFlow,
            ) { savedState, queryResult, effectiveTier ->
                val filter = savedState.selectedFilter
                val result = queryResult[filter] ?: SearchResults.empty

                val filteredResult = result.copy(
                    countries = result.countries.filter { it.tier <= effectiveTier },
                    states   = result.states.filter   { it.tier <= effectiveTier },
                    cities   = result.cities.filter   { it.tier <= effectiveTier },
                    servers  = result.servers.filter  { it.tier <= effectiveTier }
                )

                val uiItems = buildList {
                    if (!filteredResult.isEmpty() && effectiveTier == VpnUser.FREE_TIER)
                        add(ServerGroupUiItem.Banner(ServerGroupUiItem.BannerType.Search(dataAdapter.countriesCount())))
                    addAll(resultSection(R.string.country_filter_countries_list_header, filteredResult.countries, filter, effectiveTier, currentConnection, locale))
                    addAll(resultSection(R.string.country_filter_states_list_header,   filteredResult.states,   filter, effectiveTier, currentConnection, locale))
                    addAll(resultSection(R.string.country_filter_cities_list_header,   filteredResult.cities,   filter, effectiveTier, currentConnection, locale))
                    addAll(resultSection(R.string.country_filter_servers_list_header,  filteredResult.servers,  filter, effectiveTier, currentConnection, locale))
                }

                val onFilterSelect = { selectedFilter: ServerFilterType ->
                    mainSaveState = ServerGroupsMainScreenSaveState(selectedFilter)
                }

                SearchViewState.Result(
                    ServerGroupsMainScreenState(
                        selectedFilter = filter,
                        filterButtons = getFilterButtons(
                            ServerFilterType.entries.toSet(),
                            filter,
                            allLabel = R.string.country_filter_all,
                            ServerFilterType.entries.filter { queryResult[it].isEmpty() }.toSet(),
                            onItemSelect = onFilterSelect
                        ),
                        items = uiItems,
                    )
                )
            }
        }

    private fun resultSection(
        @StringRes header: Int,
        items: List<ServerGroupItemData>,
        filter: ServerFilterType,
        userTier: Int?,
        activeConnection: ActiveConnection?,
        locale: Locale
    ) : List<ServerGroupUiItem> = buildList {
        if (items.isNotEmpty()) {
            add(ServerGroupUiItem.Header(header, items.size, null))
            addAll(
                items
                    .sortedForUi(locale)
                    .sortedBy { it.textMatch?.index != 0 } // Prefer first word matches
                    .map { it.toState(userTier, filter, activeConnection) }
            )
        }
    }

    fun setQuery(query: String) {
        searchQuery = query
    }

    override fun connectTrigger(item: ServerGroupItemData): ConnectTrigger {
        val description = "Search UI"
        return when (item) {
            is ServerGroupItemData.City -> {
                if (item.cityStateId.isState) ConnectTrigger.SearchState("$description: state")
                else ConnectTrigger.SearchCity("$description: city")
            }
            is ServerGroupItemData.Country -> ConnectTrigger.SearchCountry("$description: country")
            is ServerGroupItemData.Server -> ConnectTrigger.SearchServer("$description: server")
            is ServerGroupItemData.Gateway -> {
                DebugUtils.fail("No gateways expected in search results")
                ConnectTrigger.GatewaysGateway(description)
            }
        }
    }
}
