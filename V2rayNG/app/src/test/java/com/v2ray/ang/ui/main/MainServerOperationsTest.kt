package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainServerOperationsTest {

    private class FakeDataSource : MainDataSource {
        val removedGuids = mutableListOf<String>()
        val sortedSubs = mutableListOf<String>()

        override val mainServiceEvent: Flow<MainServiceEvent> = emptyFlow()
        override fun getSelectedSubscriptionId(): String = ""
        override fun setSelectedSubscriptionId(id: String) {}
        override fun getSelectServer(): String? = null
        override fun setSelectServer(guid: String) {}
        override fun getConfirmRemove(): Boolean = false
        override fun getDoubleColumnDisplay(): Boolean = false
        override fun isGroupAllDisplayEnabled(): Boolean = false
        override fun getString(resId: Int): String = ""
        override fun getString(resId: Int, vararg formatArgs: Any): String = ""
        override fun getSubscriptions(): List<SubscriptionCache> = emptyList()
        override fun getSubscriptionItem(id: String): SubscriptionItem? = null
        override fun getServerGuidList(groupId: String): List<String> = emptyList()
        override fun decodeServerConfig(guid: String): ProfileItem? = null
        override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? = null
        override fun encodeServerList(guids: List<String>, groupId: String) {}
        override fun removeServer(guid: String) { removedGuids.add(guid) }
        override fun removeAllServer(): Int = 0
        override fun removeInvalidServerByGuid(guid: String): Int = 1
        override fun removeInvalidServersInGroup(groupId: String): Int = 2
        override fun clearAllTestDelayResults(guids: List<String>) {}
        override fun sortByTestResultsForSub(subId: String) { sortedSubs.add(subId) }
        override fun getSubsList(): List<String> = listOf("sub1", "sub2")
        override suspend fun importBatchConfig(server: String?, subscriptionId: String, updateUI: Boolean): Pair<Int, Int> = Pair(1, 0)
        override fun updateConfigViaSubAll(): SubscriptionUpdateResult = SubscriptionUpdateResult(1, 1, 0, 0)
        override fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult = SubscriptionUpdateResult(1, 1, 0, 0)
        override fun shareNonCustomConfigsToClipboard(guids: List<String>): Int = guids.size
        override fun share2QRCode(guid: String): android.graphics.Bitmap? = null
        override fun share2Clipboard(guid: String): Boolean = true
        override fun sendMsg2Service(msgId: Int, content: String) {}
        override fun sendMsg2TestService(msg: TestServiceMessage, requestId: String?) {}
        override fun cancelAllPing() {}
        override fun testCurrentServerRealPing(requestId: String) {}
        override fun syncSubscriptions() {}
        override fun initAssets() {}
        override fun close() {}
    }

    @Test
    fun testRemoveDuplicateServers() {
        val ds = FakeDataSource()
        val ops = MainServerOperations(ds)
        val p1 = ProfileItem(configType = EConfigType.VMESS, server = "1.1.1.1", serverPort = "8080")
        val p2 = ProfileItem(configType = EConfigType.VMESS, server = "1.1.1.1", serverPort = "8080")
        val p3 = ProfileItem(configType = EConfigType.VLESS, server = "1.1.1.1", serverPort = "8080")
        val servers = listOf(
            ServersCache("g1", p1, 0L),
            ServersCache("g2", p2, 0L),
            ServersCache("g3", p3, 0L)
        )
        val removedCount = ops.removeDuplicateServers(servers)
        assertEquals(1, removedCount)
        assertEquals(listOf("g2"), ds.removedGuids)
    }

    @Test
    fun testSortByTestResultsAll() {
        val ds = FakeDataSource()
        val ops = MainServerOperations(ds)
        ops.sortByTestResults("")
        assertEquals(listOf("sub1", "sub2"), ds.sortedSubs)
    }
}
