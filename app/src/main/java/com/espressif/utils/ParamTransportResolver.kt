// Copyright 2026 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.utils

import com.espressif.EspApplication
import com.espressif.ble.BleLocalControlManager

/** Transport used for device param get/set. */
enum class ParamTransport {
    WLAN,
    CLOUD,
    BLE,
    NONE
}

/**
 * Single decision point for the transport a node's params are read and written over.
 *
 * Priority is WLAN local control, then cloud, then BLE local control. BLE is last
 * because it is the fallback for nodes the cloud cannot reach - a BLE-only node, or a
 * node whose cloud path is down - not a shortcut for nodes that are already online.
 */
object ParamTransportResolver {

    @JvmStatic
    fun preferredTransport(espApp: EspApplication, nodeId: String?): ParamTransport {
        if (nodeId.isNullOrEmpty()) {
            return ParamTransport.NONE
        }
        if (espApp.localDeviceMap.containsKey(nodeId)) {
            return ParamTransport.WLAN
        }
        if (isCloudAvailable(espApp, nodeId)) {
            return ParamTransport.CLOUD
        }
        if (isBleAvailable(espApp, nodeId)) {
            return ParamTransport.BLE
        }
        return ParamTransport.NONE
    }

    /**
     * Cloud is usable only when the node itself is cloud connected and the phone has
     * internet. A BLE-only node has no MQTT connection, so it never takes this path.
     */
    @JvmStatic
    fun isCloudAvailable(espApp: EspApplication, nodeId: String?): Boolean {
        if (nodeId.isNullOrEmpty()) {
            return false
        }
        val node = espApp.nodeMap[nodeId] ?: return false
        return node.isOnline && EspNetworkMonitor.isConnectedToNetwork()
    }

    /** BLE is reachable when a session is open, or the node was seen advertising. */
    @JvmStatic
    fun isBleAvailable(espApp: EspApplication, nodeId: String?): Boolean {
        if (nodeId.isNullOrEmpty()) {
            return false
        }
        val bleManager = BleLocalControlManager.getInstance(espApp)
        return bleManager.isConnected(nodeId) || bleManager.isDiscovered(nodeId)
    }

    /**
     * True when WLAN or the cloud can serve this node, so BLE must be neither used nor
     * advertised for it. Asked instead of [preferredTransport] where BLE reachability is
     * itself being decided, to avoid depending on the state being written.
     */
    @JvmStatic
    fun isHigherPriorityThanBleAvailable(espApp: EspApplication, nodeId: String?): Boolean {
        if (nodeId.isNullOrEmpty()) {
            return false
        }
        if (espApp.localDeviceMap.containsKey(nodeId)) {
            return true
        }
        return isCloudAvailable(espApp, nodeId)
    }

    /** Node advertises BLE local control in its cloud metadata. */
    @JvmStatic
    fun isBleLocalControlNode(espApp: EspApplication, nodeId: String?): Boolean {
        if (nodeId.isNullOrEmpty()) {
            return false
        }
        return espApp.nodeMap[nodeId]?.bleLocalCtrlInfo != null
    }
}
