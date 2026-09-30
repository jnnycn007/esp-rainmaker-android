// Copyright 2020 Espressif Systems (Shanghai) PTE LTD
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

package com.espressif;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.espressif.AppConstants.Companion.UpdateEventType;
import com.espressif.ble.BleLocalControlManager;
import com.espressif.cloudapi.ApiManager;
import com.espressif.cloudapi.ApiResponseListener;
import com.espressif.local_control.LocalControlApiManager;
import com.espressif.ui.models.EspNode;
import com.espressif.ui.models.UpdateEvent;
import com.espressif.utils.EspNetworkMonitor;
import com.espressif.utils.ParamTransportResolver;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.EventBus;

import java.util.HashMap;
import java.util.Map;

/**
 * This class will decide which transport to use for a param get/set.
 * <p>
 * Priority is WLAN local control, then cloud, then BLE local control. BLE is last
 * because it is the fallback for nodes the cloud cannot reach - a BLE-only node, or a
 * node whose cloud path is down - not a shortcut for nodes that are already online.
 * {@link ParamTransportResolver} owns the ordering; keep the branches below in step
 * with it.
 */
public class NetworkApiManager {

    private final String TAG = NetworkApiManager.class.getSimpleName();

    /**
     * Proxy reporting is debounced because a BLE param write and the chunked read it
     * triggers share one Security 2 session. Overlapping operations desynchronise the
     * AES-GCM counter and the next response fails to decrypt (AEADBadTagException).
     * Static because this class is instantiated per caller, not shared.
     */
    private static final long BLE_PROXY_REPORT_DELAY_MS = 500;
    private static final Map<String, Runnable> bleProxyReportWork = new HashMap<>();
    private static final Handler bleProxyReportHandler = new Handler(Looper.getMainLooper());

    private Context context;
    private EspApplication espApp;
    private ApiManager apiManager;
    private LocalControlApiManager localControlApiManager;

    public NetworkApiManager(Context context) {
        this.context = context;
        espApp = (EspApplication) context.getApplicationContext();
        apiManager = ApiManager.getInstance(context);
        localControlApiManager = new LocalControlApiManager(context);
    }

    /**
     * This method is used to update param values of a device.
     *
     * @param nodeId   Node id.
     * @param body     Json data to be sent in request. It contains new value of a param.
     * @param listener Listener to send success or failure.
     */
    public void updateParamValue(final String nodeId, final JsonObject body, final ApiResponseListener listener) {
        updateParamValue(nodeId, body, listener, true);
    }

    /**
     * @param reportToProxy If true, reportParamsToProxy is called after a successful BLE update.
     *                      Pass false when there are back-to-back BLE calls to avoid concurrent BLE operations.
     */
    public void updateParamValue(final String nodeId, final JsonObject body, final ApiResponseListener listener,
                                 final boolean reportToProxy) {

        if (espApp.localDeviceMap.containsKey(nodeId)) {

            localControlApiManager.updateParamValue(nodeId, body, new ApiResponseListener() {

                @Override
                public void onSuccess(Bundle data) {
                    listener.onSuccess(data);
                }

                @Override
                public void onResponseFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    updateParamValue(nodeId, body, listener, reportToProxy);
                }

                @Override
                public void onNetworkFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    updateParamValue(nodeId, body, listener, reportToProxy);
                }
            });

        } else if (ParamTransportResolver.isCloudAvailable(espApp, nodeId)) {

            // Priority 2: Cloud API, falling back to BLE if the cloud call fails.
            Log.d(TAG, "updateParamValue - using cloud for node: " + nodeId);
            apiManager.updateParamValue(nodeId, body, new ApiResponseListener() {

                @Override
                public void onSuccess(Bundle data) {
                    listener.onSuccess(data);
                }

                @Override
                public void onResponseFailure(Exception exception) {
                    Log.e(TAG, "Cloud param update failed: " + exception.getMessage());
                    updateParamValueAfterCloudFailure(nodeId, body, listener, exception);
                }

                @Override
                public void onNetworkFailure(Exception exception) {
                    Log.e(TAG, "Cloud param update network failure: " + exception.getMessage());
                    updateParamValueAfterCloudFailure(nodeId, body, listener, exception);
                }
            });

        } else if (ParamTransportResolver.isBleAvailable(espApp, nodeId)) {

            // Priority 3: BLE local control.
            Log.d(TAG, "updateParamValue - using BLE for node: " + nodeId);
            updateParamValueOverBle(nodeId, body, listener, reportToProxy, true);

        } else {
            // Nothing reachable. Still call the cloud so the caller gets the usual error.
            apiManager.updateParamValue(nodeId, body, listener);
        }
    }

    /**
     * Sends a param update over BLE local control.
     *
     * @param cloudFallback If true, a BLE failure retries on the cloud. Pass false when
     *                      BLE was itself reached as a fallback from a failed cloud call,
     *                      so a failure is not bounced back to the cloud in a loop.
     */
    private void updateParamValueOverBle(final String nodeId, final JsonObject body,
                                         final ApiResponseListener listener,
                                         final boolean reportToProxy,
                                         final boolean cloudFallback) {

        BleLocalControlManager bleManager = BleLocalControlManager.getInstance(context);
        bleManager.connectAndSendParams(nodeId, body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {
                Log.d(TAG, "BLE param update success for node: " + nodeId);
                applySetParamToLocalNodes(nodeId, body);
                listener.onSuccess(data);
                if (reportToProxy) {
                    reportParamsToProxy(nodeId);
                }
            }

            @Override
            public void onResponseFailure(Exception exception) {
                Log.e(TAG, "BLE param update failed: " + exception.getMessage());
                onBleUpdateFailed(exception);
            }

            @Override
            public void onNetworkFailure(Exception exception) {
                Log.e(TAG, "BLE connection failed: " + exception.getMessage());
                onBleUpdateFailed(exception);
            }

            private void onBleUpdateFailed(Exception exception) {
                if (cloudFallback && handleBleFailure(nodeId)) {
                    apiManager.updateParamValue(nodeId, body, listener);
                } else {
                    listener.onResponseFailure(exception);
                }
            }
        });
    }

    /**
     * The cloud param update failed. Try BLE local control if the node is reachable that
     * way, otherwise report the original cloud failure.
     */
    private void updateParamValueAfterCloudFailure(final String nodeId, final JsonObject body,
                                                   final ApiResponseListener listener,
                                                   final Exception cloudException) {

        if (!ParamTransportResolver.isBleAvailable(espApp, nodeId)) {
            listener.onResponseFailure(cloudException);
            return;
        }

        // The cloud is down, so skip reportParamsToProxy - it would just fail too.
        Log.d(TAG, "Cloud failed, falling back to BLE for node: " + nodeId);
        updateParamValueOverBle(nodeId, body, listener, false, false);
    }

    /**
     * This method is used to get param values for a given node id.
     *
     * @param nodeId   Node id.
     * @param listener Listener to send success or failure.
     */
    public void getParamsValues(final String nodeId, final ApiResponseListener listener) {

        if (espApp.localDeviceMap.containsKey(nodeId)) {

            localControlApiManager.getParamsValues(nodeId, new ApiResponseListener() {

                @Override
                public void onSuccess(Bundle data) {
                    listener.onSuccess(data);
                }

                @Override
                public void onResponseFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    getParamsValues(nodeId, listener);
                }

                @Override
                public void onNetworkFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    getParamsValues(nodeId, listener);
                }
            });

        } else if (ParamTransportResolver.isCloudAvailable(espApp, nodeId)) {

            // Priority 2: Cloud API, falling back to BLE if the cloud call fails.
            apiManager.getParamsValues(nodeId, new ApiResponseListener() {

                @Override
                public void onSuccess(Bundle data) {
                    listener.onSuccess(data);
                }

                @Override
                public void onResponseFailure(Exception exception) {
                    Log.e(TAG, "Cloud get params failed: " + exception.getMessage());
                    getParamsValuesAfterCloudFailure(nodeId, listener, exception);
                }

                @Override
                public void onNetworkFailure(Exception exception) {
                    Log.e(TAG, "Cloud get params network failure: " + exception.getMessage());
                    getParamsValuesAfterCloudFailure(nodeId, listener, exception);
                }
            });

        } else if (isBleReadReady(nodeId)) {

            // Priority 3: BLE local control (only when a session is already open).
            getParamsValuesOverBle(nodeId, listener, true);

        } else {
            // Nothing reachable. Still call the cloud so the caller gets the usual error.
            apiManager.getParamsValues(nodeId, listener);
        }
    }

    /**
     * A BLE read needs an open session - unlike a write, it will not connect on demand.
     * <p>
     * It no longer has to avoid an in-flight proxy read: BleLocalControlManager serialises
     * operations per node, so a read raised during one waits its turn instead of being
     * dropped and falling through to the cloud.
     */
    private boolean isBleReadReady(String nodeId) {
        return BleLocalControlManager.getInstance(context).isConnected(nodeId);
    }

    /**
     * Reads param values over BLE local control and applies them to the cached node.
     *
     * @param cloudFallback If true, a BLE failure retries on the cloud. Pass false when
     *                      BLE was itself reached as a fallback from a failed cloud call.
     */
    private void getParamsValuesOverBle(final String nodeId, final ApiResponseListener listener,
                                        final boolean cloudFallback) {

        BleLocalControlManager bleManager = BleLocalControlManager.getInstance(context);
        bleManager.queryParams(nodeId, json -> {
            if (json != null) {
                EspNode node = espApp.nodeMap.get(nodeId);
                if (node != null && node.getDevices() != null) {
                    for (com.espressif.ui.models.Device dev : node.getDevices()) {
                        String devName = dev.getDeviceName();
                        if (!json.has(devName)) {
                            Log.w(TAG, "Device '" + devName + "' not present in BLE params response");
                        }
                        if (json.has(devName)) {
                            try {
                                org.json.JSONObject deviceParams = json.getJSONObject(devName);
                                for (com.espressif.ui.models.Param param : dev.getParams()) {
                                    if (deviceParams.has(param.getName())) {
                                        Object val_ = deviceParams.get(param.getName());
                                        if (val_ instanceof Boolean) {
                                            Log.d(TAG, "BLE param " + param.getName() + ": "
                                                    + param.getSwitchStatus() + " -> " + val_);
                                            param.setSwitchStatus((Boolean) val_);
                                        } else if (val_ instanceof Number) {
                                            param.setValue(((Number) val_).doubleValue());
                                            param.setLabelValue(val_.toString());
                                        } else if (val_ instanceof String) {
                                            param.setLabelValue((String) val_);
                                        }
                                    }
                                }
                            } catch (org.json.JSONException e) {
                                Log.e(TAG, "Error parsing BLE params: " + e.getMessage());
                            }
                        }
                    }
                }
                listener.onSuccess(null);
            } else if (cloudFallback && handleBleFailure(nodeId)) {
                Log.e(TAG, "BLE queryParams returned null, falling back to cloud");
                apiManager.getParamsValues(nodeId, listener);
            } else {
                Log.e(TAG, "BLE queryParams returned null for node: " + nodeId);
                listener.onResponseFailure(new Exception("Failed to read params over BLE"));
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * The cloud get params failed. Try BLE local control if a session is open, otherwise
     * report the original cloud failure.
     */
    private void getParamsValuesAfterCloudFailure(final String nodeId,
                                                  final ApiResponseListener listener,
                                                  final Exception cloudException) {

        if (!isBleReadReady(nodeId)) {
            listener.onResponseFailure(cloudException);
            return;
        }

        Log.d(TAG, "Cloud failed, falling back to BLE for node: " + nodeId);
        getParamsValuesOverBle(nodeId, listener, false);
    }

    /**
     * This method is used to get node details for a given node id.
     *
     * @param nodeId   Node id.
     * @param listener Listener to send success or failure.
     */
    public void getNodeDetails(final String nodeId, final ApiResponseListener listener) {

        if (espApp.localDeviceMap.containsKey(nodeId)) {

            localControlApiManager.getNodeDetails(nodeId, new ApiResponseListener() {

                @Override
                public void onSuccess(Bundle data) {
                    listener.onSuccess(data);
                }

                @Override
                public void onResponseFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    getParamsValues(nodeId, listener);
                }

                @Override
                public void onNetworkFailure(Exception exception) {
                    Log.e(TAG, "Error : " + exception.getMessage());
                    Log.e(TAG, "Removing Node id : " + nodeId);
                    espApp.localDeviceMap.remove(nodeId);
                    espApp.nodeMap.get(nodeId).setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
                    getParamsValues(nodeId, listener);
                }
            });

        } else {
            apiManager.getNodeDetails(nodeId, listener);
        }
    }

    /**
     * Applies params just written over BLE to the cached node.
     * <p>
     * ParamAdapter updates its own copy of a param optimistically so the control responds
     * at once, but nothing wrote the value back to espApp.nodeMap - so the two disagreed
     * until the next 5s poll happened to read it back. Any updateUi() landing in that
     * window rebuilds the row from the stale nodeMap and reverts the control, which is why
     * the same sequence worked or failed depending on timing.
     */
    private void applySetParamToLocalNodes(String nodeId, JsonObject parameter) {

        EspNode node = espApp.nodeMap.get(nodeId);
        if (node == null || node.getDevices() == null) {
            return;
        }
        for (com.espressif.ui.models.Device device : node.getDevices()) {
            applySetParam(parameter, device);
        }
    }

    private void applySetParam(JsonObject parameter, com.espressif.ui.models.Device device) {

        String deviceName = device.getDeviceName();
        if (deviceName == null || !parameter.has(deviceName)
                || !parameter.get(deviceName).isJsonObject()) {
            return;
        }

        JsonObject attributes = parameter.getAsJsonObject(deviceName);
        java.util.ArrayList<com.espressif.ui.models.Param> params = device.getParams();
        if (params == null) {
            return;
        }

        for (com.espressif.ui.models.Param param : params) {
            String paramName = param.getName();
            if (paramName == null || !attributes.has(paramName)
                    || !attributes.get(paramName).isJsonPrimitive()) {
                continue;
            }
            com.google.gson.JsonPrimitive value = attributes.getAsJsonPrimitive(paramName);
            if (value.isBoolean()) {
                param.setSwitchStatus(value.getAsBoolean());
            } else if (value.isNumber()) {
                param.setValue(value.getAsDouble());
                param.setLabelValue(value.getAsString());
            } else if (value.isString()) {
                param.setLabelValue(value.getAsString());
            }
        }
    }

    /**
     * Schedules a proxy report after a successful BLE param update, replacing any report
     * already pending for this node.
     * <p>
     * A slider sends an update roughly every 100ms, and each report is a multi-chunk BLE
     * read. Firing one per update interleaves reads with the next write on a single
     * Security 2 session and breaks its AES-GCM counter, so reports are collapsed into
     * one a short time after the last change.
     */
    private void reportParamsToProxy(final String nodeId) {

        synchronized (bleProxyReportWork) {
            Runnable pending = bleProxyReportWork.remove(nodeId);
            if (pending != null) {
                bleProxyReportHandler.removeCallbacks(pending);
            }
            Runnable work = new Runnable() {
                @Override
                public void run() {
                    synchronized (bleProxyReportWork) {
                        bleProxyReportWork.remove(nodeId);
                    }
                    performReportParamsToProxy(nodeId);
                }
            };
            bleProxyReportWork.put(nodeId, work);
            bleProxyReportHandler.postDelayed(work, BLE_PROXY_REPORT_DELAY_MS);
        }
    }

    /**
     * Gets params with timestamp from the device and reports the signed payload to the
     * cloud via the proxy/params API.
     * This is fire-and-forget: failures are logged but do not affect the caller.
     */
    private void performReportParamsToProxy(final String nodeId) {

        if (!EspNetworkMonitor.isConnectedToNetwork()) {
            // No cloud to report to, and the BLE read would cost a round trip on the
            // shared session for nothing.
            Log.d(TAG, "Skipping proxy report, no internet (node: " + nodeId + ")");
            return;
        }

        BleLocalControlManager bleManager = BleLocalControlManager.getInstance(context);
        bleManager.getParamsWithTimestamp(nodeId, json -> {
            if (json == null) {
                Log.e(TAG, "Failed to get timestamped params for proxy report (node: " + nodeId + ")");
                return kotlin.Unit.INSTANCE;
            }

            try {
                String signature = json.optString("signature", "");
                org.json.JSONObject nodePayloadObj = json.optJSONObject("node_payload");

                if (android.text.TextUtils.isEmpty(signature) || nodePayloadObj == null) {
                    Log.e(TAG, "Missing signature or node_payload in device response for proxy report");
                    return kotlin.Unit.INSTANCE;
                }

                String nodePayloadStr = nodePayloadObj.toString().replace("\\/", "/");

                com.google.gson.JsonObject proxyPayload = new com.google.gson.JsonObject();
                proxyPayload.addProperty("node_payload", nodePayloadStr);
                proxyPayload.addProperty("signature", signature);

                Log.d(TAG, "Reporting params to proxy for node: " + nodeId);
                apiManager.reportProxyParams(nodeId, proxyPayload, new ApiResponseListener() {
                    @Override
                    public void onSuccess(Bundle data) {
                        Log.d(TAG, "Proxy params reported successfully for node: " + nodeId);
                    }

                    @Override
                    public void onResponseFailure(Exception exception) {
                        Log.e(TAG, "Failed to report proxy params: " + exception.getMessage());
                    }

                    @Override
                    public void onNetworkFailure(Exception exception) {
                        Log.e(TAG, "Network failure reporting proxy params: " + exception.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing proxy report: " + e.getMessage());
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Decides what to do after a BLE param op failed.
     * <p>
     * A BLE-only node has no cloud param path, so tearing down its BLE session for a
     * cloud call that cannot work would just leave it uncontrollable until the next
     * discovery scan. In that case the BLE state is kept as-is.
     *
     * @return true if the caller should retry the operation on the cloud.
     */
    private boolean handleBleFailure(String nodeId) {

        if (ParamTransportResolver.isBleLocalControlNode(espApp, nodeId)
                && !ParamTransportResolver.isCloudAvailable(espApp, nodeId)) {
            Log.d(TAG, "BLE failed and no cloud path for node: " + nodeId + ", keeping BLE state");
            return false;
        }

        clearBleAndFallbackToCloud(nodeId);
        return true;
    }

    /**
     * Clears BLE connection state for a node after a BLE failure,
     * updates the node status based on cloud connectivity, and
     * posts a UI update event so the "Reachable on BLE" label is removed.
     */
    private void clearBleAndFallbackToCloud(String nodeId) {
        BleLocalControlManager bleManager = BleLocalControlManager.getInstance(context);
        bleManager.disconnectDevice(nodeId);

        EspNode node = espApp.nodeMap.get(nodeId);
        if (node != null) {
            if (node.isOnline()) {
                node.setNodeStatus(AppConstants.NODE_STATUS_ONLINE);
            } else {
                node.setNodeStatus(AppConstants.NODE_STATUS_OFFLINE);
            }
        }
        EventBus.getDefault().post(new UpdateEvent(UpdateEventType.EVENT_DEVICE_STATUS_UPDATE));
    }
}
