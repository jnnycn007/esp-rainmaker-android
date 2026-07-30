package com.espressif.ui.webrtc

import android.util.Log
import com.espressif.AppConstants
import com.espressif.local_control.EspLocalDevice
import com.espressif.provisioning.listeners.ResponseListener
import com.espressif.webrtc.proto.WebrtcSignalProto.*
import com.google.protobuf.ByteString
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Signaling client that exchanges WebRTC SDP/ICE messages over the local network
 * using the device's HTTP-based local control transport with protobuf encoding.
 *
 * Replaces [com.espressif.webrtc.SignalingServiceWebSocketClient] when the device
 * is reachable on the local network.
 */
class LocalSignalingClient(
    private val localDevice: EspLocalDevice,
    private val peerId: String,
    private val onSdpAnswer: (sdp: String) -> Unit,
    private val onIceCandidate: (candidateJson: String) -> Unit,
    private val onError: (message: String) -> Unit
) {
    /** Java-friendly callback interface for string-based signaling events. */
    fun interface StringCallback {
        fun invoke(value: String)
    }

    /**
     * Java-friendly constructor that accepts [StringCallback] instead of Kotlin function types.
     */
    constructor(
        localDevice: EspLocalDevice,
        peerId: String,
        onSdpAnswer: StringCallback,
        onIceCandidate: StringCallback,
        onError: StringCallback
    ) : this(
        localDevice,
        peerId,
        onSdpAnswer = { sdp -> onSdpAnswer.invoke(sdp) },
        onIceCandidate = { json -> onIceCandidate.invoke(json) },
        onError = { msg -> onError.invoke(msg) }
    )

    companion object {
        private const val TAG = "LocalSignalingClient"
        private const val FRAGMENT_SIZE = 2048
        private const val PROTOCOL_VERSION = 1
        private const val FAST_POLL_INTERVAL_MS = 100L
        private const val POLL_INTERVAL_MS = 200L
        private const val ANSWER_TIMEOUT_MS = 30_000L
        private const val MAX_CONSECUTIVE_POLL_FAILURES = 5

        /**
         * Parse a standard ICE candidate JSON string into a WebRTC [IceCandidate].
         * JSON format: {"candidate":"...","sdpMid":"...","sdpMLineIndex":N}
         */
        @JvmStatic
        fun parseIceCandidateJson(json: String): IceCandidate? {
            return try {
                val obj = JSONObject(json)
                IceCandidate(
                    obj.getString("sdpMid"),
                    obj.getInt("sdpMLineIndex"),
                    obj.getString("candidate")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ICE candidate JSON: ${e.message}", e)
                null
            }
        }

        /**
         * Serialize a WebRTC [IceCandidate] to standard JSON string.
         */
        @JvmStatic
        fun iceCandidateToJson(candidate: IceCandidate): String {
            return JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }.toString()
        }
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var pollFuture: ScheduledFuture<*>? = null
    private val connected = AtomicBoolean(false)
    private val answerReceived = AtomicBoolean(false)
    private val offerSending = AtomicBoolean(false)
    private val iceCandidateQueue: ConcurrentLinkedQueue<IceCandidate> = ConcurrentLinkedQueue()
    private var pollStartTimeMs = 0L
    private var consecutivePollFailures = 0

    /** Pre-built poll payload bytes — identical every poll, so build once. */
    private val pollPayloadBytes: ByteArray by lazy {
        WebrtcSignalPayload.newBuilder()
            .setMsg(WebrtcSignalMsgType.TypeCmdPoll)
            .setVersion(PROTOCOL_VERSION)
            .setCmdPoll(CmdPoll.newBuilder().setPeerId(peerId))
            .build()
            .toByteArray()
    }

    fun isOpen(): Boolean = connected.get()

    /**
     * Send an SDP offer to the device. On success, starts the polling loop.
     */
    fun sendSdpOffer(sdp: String) {
        offerSending.set(true)
        executor.execute { doSendSdpOffer(sdp) }
    }

    private fun doSendSdpOffer(sdp: String) {
        val payload = WebrtcSignalPayload.newBuilder()
            .setMsg(WebrtcSignalMsgType.TypeCmdOffer)
            .setVersion(PROTOCOL_VERSION)
            .setCmdOffer(
                CmdOffer.newBuilder()
                    .setPeerId(peerId)
                    .setOffer(
                        SessionDescription.newBuilder()
                            .setSdp(sdp)
                            .setType("offer")
                    )
            )
            .build()

        val data = payload.toByteArray()
        Log.d(TAG, "Sending SDP offer (${data.size} bytes) for peer $peerId")

        val responseBytes = sendFragmentedBlocking(data)
        if (responseBytes == null) {
            offerSending.set(false)
            onError("Empty response when sending offer")
            return
        }

        try {
            val response = reassembleAndParse(responseBytes)
            if (response == null) {
                offerSending.set(false)
                onError("Failed to reassemble offer response")
                return
            }

            val respOffer = response.respOffer
            if (respOffer.status == WebrtcSignalStatus.Success) {
                Log.d(TAG, "Offer accepted by device, peer_id=${respOffer.peerId}")
                connected.set(true)
                // Process any piggybacked messages (answer, ICE) in the offer response
                processMessages(respOffer.messagesList)
                // Submit the first poll via execute() — this uses the same FIFO
                // path as ICE candidate tasks (also submitted via execute()),
                // guaranteeing it runs before them.  scheduleWithFixedDelay(delay=0)
                // does NOT have the same FIFO guarantee with execute() tasks.
                executor.execute { doPoll() }
                startPolling()
                offerSending.set(false)
                flushIceCandidateQueue()
            } else {
                Log.e(TAG, "Offer rejected: status=${respOffer.status}")
                offerSending.set(false)
                onError("Offer rejected by device: ${respOffer.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing offer response: ${e.message}", e)
            offerSending.set(false)
            onError("Error parsing offer response: ${e.message}")
        }
    }

    /**
     * Send an ICE candidate to the device. If the offer is still being sent,
     * the candidate is queued and flushed after the offer completes.
     *
     * This method is thread-safe — it dispatches to the internal executor to
     * serialize with other signaling operations (polls, offer), preventing
     * concurrent encrypt/decrypt calls on the local security session.
     */
    fun sendIceCandidate(candidate: IceCandidate) {
        // Always queue candidates that arrive while offer is still sending
        if (offerSending.get()) {
            Log.d(TAG, "Queueing ICE candidate (offer still sending)")
            iceCandidateQueue.add(candidate)
            return
        }

        // Dispatch to executor to serialize with poll and other send operations
        executor.execute { doSendIceCandidate(candidate) }
    }

    private fun doSendIceCandidate(candidate: IceCandidate) {
        val json = iceCandidateToJson(candidate)

        val payload = WebrtcSignalPayload.newBuilder()
            .setMsg(WebrtcSignalMsgType.TypeCmdIceCandidate)
            .setVersion(PROTOCOL_VERSION)
            .setCmdIceCandidate(
                CmdIceCandidate.newBuilder()
                    .setPeerId(peerId)
                    .setPayload(json)
            )
            .build()

        val responseBytes = sendBlocking(payload.toByteArray())
        if (responseBytes == null) {
            Log.w(TAG, "Empty response when sending ICE candidate")
            return
        }
        try {
            val response = reassembleAndParse(responseBytes)
            if (response != null) {
                val resp = response.respIceCandidate
                if (resp.status != WebrtcSignalStatus.Success) {
                    Log.w(TAG, "ICE candidate send status: ${resp.status}")
                }
                // Process any piggybacked messages
                processMessages(resp.messagesList)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing ICE candidate response: ${e.message}")
        }
    }

    /**
     * Stop polling and release resources.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting local signaling client")
        connected.set(false)
        pollFuture?.cancel(false)
        pollFuture = null
        // Non-blocking shutdown — in-flight HTTP requests will complete naturally.
        // Avoids blocking the calling thread (which may be the main thread) for
        // up to the sendBlocking timeout.
        executor.shutdownNow()
    }

    // --- Polling ---

    /**
     * Stop the poll loop.  Called by the manager when the peer connection
     * reaches CONNECTED — there is nothing left to poll for.
     */
    fun stopPolling() {
        Log.d(TAG, "Stopping poll loop")
        pollFuture?.cancel(false)
        pollFuture = null
    }

    private fun startPolling() {
        pollStartTimeMs = System.currentTimeMillis()
        Log.d(TAG, "Starting poll loop (interval=${FAST_POLL_INTERVAL_MS}ms)")
        schedulePollLoop(FAST_POLL_INTERVAL_MS)
    }

    private fun schedulePollLoop(intervalMs: Long) {
        pollFuture?.cancel(false)
        // Use intervalMs as initial delay — the first poll is submitted
        // explicitly via executor.execute { doPoll() } in doSendSdpOffer.
        pollFuture = executor.scheduleWithFixedDelay({
            if (!connected.get()) {
                pollFuture?.cancel(false)
                return@scheduleWithFixedDelay
            }

            // Check answer timeout
            if (!answerReceived.get()) {
                val elapsed = System.currentTimeMillis() - pollStartTimeMs
                if (elapsed > ANSWER_TIMEOUT_MS) {
                    Log.e(TAG, "Poll timeout: no answer received within ${ANSWER_TIMEOUT_MS}ms")
                    pollFuture?.cancel(false)
                    onError("Local signaling timeout: no answer received")
                    return@scheduleWithFixedDelay
                }
            }

            doPoll()
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Process piggybacked signaling messages from any response.
     * Returns true if any messages were processed.
     */
    private fun processMessages(messages: List<SignalingMessage>): Boolean {
        if (messages.isEmpty()) return false
        for (msg in messages) {
            when (msg.type) {
                WebrtcSignalMsgType.TypeAnswer -> {
                    Log.d(TAG, "Received SDP answer from device")
                    answerReceived.set(true)
                    schedulePollLoop(POLL_INTERVAL_MS)
                    onSdpAnswer(msg.sessionDesc.sdp)
                }
                WebrtcSignalMsgType.TypeIceCandidateMsg -> {
                    Log.d(TAG, "Received ICE candidate from device")
                    onIceCandidate(msg.iceCandidateJson)
                }
                else -> {
                    Log.w(TAG, "Unknown message type in poll response: ${msg.type}")
                }
            }
        }
        return true
    }

    /**
     * Poll once and process messages.  Returns true if messages were received
     * (caller should poll again immediately to drain), false otherwise.
     */
    private fun doPollOnce(): Boolean {
        val responseBytes = sendBlocking(pollPayloadBytes) ?: run {
            consecutivePollFailures++
            Log.w(TAG, "Poll request failed or timed out (failure $consecutivePollFailures/$MAX_CONSECUTIVE_POLL_FAILURES)")
            if (consecutivePollFailures >= MAX_CONSECUTIVE_POLL_FAILURES && !answerReceived.get()) {
                connected.set(false)
                pollFuture?.cancel(false)
                onError("Local signaling transport error during poll ($consecutivePollFailures consecutive failures)")
            }
            return false
        }

        consecutivePollFailures = 0
        try {
            val response = reassembleAndParse(responseBytes) ?: return false
            val respPoll = response.respPoll

            return when (respPoll.status) {
                WebrtcSignalStatus.Pending -> false
                WebrtcSignalStatus.Success -> processMessages(respPoll.messagesList)
                WebrtcSignalStatus.Fail -> {
                    Log.e(TAG, "Poll returned Fail status")
                    if (!answerReceived.get()) {
                        connected.set(false)
                        pollFuture?.cancel(false)
                        onError("Device returned failure status during polling")
                    }
                    false
                }
                else -> {
                    Log.w(TAG, "Unexpected poll status: ${respPoll.status}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing poll response: ${e.message}", e)
            return false
        }
    }

    /**
     * Drain all pending messages from the device back-to-back.
     * Keeps polling in a tight loop until the device returns Pending (empty),
     * then yields back to the executor for ICE sends / scheduled polls.
     */
    private fun doPoll() {
        while (connected.get() && doPollOnce()) {
            // Device had messages — poll again immediately for more.
        }
    }

    // --- Transport ---

    /**
     * Synchronously send a single request to the device and return the response.
     * Must be called from the executor thread (all public send methods dispatch to it).
     * Uses CountDownLatch to bridge the async ResponseListener callback.
     */
    private fun sendBlocking(data: ByteArray): ByteArray? {
        val latch = CountDownLatch(1)
        var responseBytes: ByteArray? = null
        var sendError: Exception? = null

        localDevice.sendData(AppConstants.WEBRTC_SIGNAL_ENDPOINT, data,
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray?) {
                    responseBytes = returnData
                    latch.countDown()
                }

                override fun onFailure(e: Exception?) {
                    sendError = e
                    latch.countDown()
                }
            })

        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "sendBlocking timed out")
            return null
        }

        if (sendError != null) {
            Log.e(TAG, "sendBlocking failed: ${sendError?.message}")
            return null
        }

        return responseBytes
    }

    /**
     * Send data with fragmentation support, synchronously.
     * If data > FRAGMENT_SIZE, splits into chunks and sends each sequentially.
     * The last chunk's response is the actual reply from the device.
     * Must be called from the executor thread.
     *
     * Holds the device's request lock across ALL fragments so that param
     * requests cannot interleave between them.
     */
    private fun sendFragmentedBlocking(data: ByteArray): ByteArray? {
        if (data.size <= FRAGMENT_SIZE) {
            return sendBlocking(data)
        }

        Log.d(TAG, "Fragmenting ${data.size} bytes into chunks of $FRAGMENT_SIZE")

        // Hold the session lock across all fragments to prevent param requests
        // from interleaving (which would add ~200ms+ per interleaved request).
        val lockAcquired: Boolean
        try {
            lockAcquired = localDevice.acquireRequestLock()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted acquiring request lock for fragmented send")
            return null
        }

        try {
            var offset = 0
            while (offset < data.size) {
                val chunkSize = minOf(data.size - offset, FRAGMENT_SIZE)
                val isLast = (offset + chunkSize) >= data.size

                val fragPayload = WebrtcSignalPayload.newBuilder()
                    .setVersion(PROTOCOL_VERSION)
                    .setFragment(
                        FragmentInfo.newBuilder()
                            .setOffset(offset)
                            .setTotalLen(data.size)
                            .setData(ByteString.copyFrom(data, offset, chunkSize))
                    )
                    .build()

                Log.d(TAG, "Sending fragment: offset=$offset, chunkSize=$chunkSize, totalLen=${data.size}, isLast=$isLast")

                val response = sendBlocking(fragPayload.toByteArray())
                if (response == null) {
                    Log.e(TAG, "Fragment send failed at offset $offset")
                    return null
                }

                if (isLast) {
                    return response
                }

                offset += chunkSize
            }

            return null // unreachable
        } finally {
            if (lockAcquired) {
                localDevice.releaseRequestLock()
            }
        }
    }

    /**
     * Check if a response needs reassembly (fragmented response from device).
     * If not fragmented, parse directly. If fragmented, reassemble all chunks.
     */
    private fun reassembleAndParse(responseBytes: ByteArray): WebrtcSignalPayload? {
        val initial = WebrtcSignalPayload.parseFrom(responseBytes)

        // Check if response is fragmented
        if (!initial.hasFragment() || initial.fragment.totalLen == 0) {
            return initial
        }

        val totalLen = initial.fragment.totalLen
        val firstOffset = initial.fragment.offset
        val firstData = initial.fragment.data.toByteArray()

        Log.d(TAG, "Reassembling fragmented response: totalLen=$totalLen, firstOffset=$firstOffset, firstChunkSize=${firstData.size}")

        val buffer = ByteArray(totalLen)
        System.arraycopy(firstData, 0, buffer, firstOffset, firstData.size)
        var received = firstData.size

        // Fetch remaining chunks synchronously via sendBlocking
        while (received < totalLen) {
            val fragReq = WebrtcSignalPayload.newBuilder()
                .setFragment(
                    FragmentInfo.newBuilder()
                        .setOffset(received)
                        .setTotalLen(totalLen)
                )
                .build()

            val chunkResponse = sendBlocking(fragReq.toByteArray())
            if (chunkResponse == null) {
                Log.e(TAG, "Error fetching fragment at offset $received")
                return null
            }

            val fragResp = WebrtcSignalPayload.parseFrom(chunkResponse)
            if (!fragResp.hasFragment()) {
                Log.e(TAG, "Expected fragment response at offset $received, got non-fragment")
                return null
            }

            val chunkData = fragResp.fragment.data.toByteArray()
            val chunkOffset = fragResp.fragment.offset
            System.arraycopy(chunkData, 0, buffer, chunkOffset, chunkData.size)
            received += chunkData.size

            Log.d(TAG, "Reassembled fragment: offset=$chunkOffset, chunkSize=${chunkData.size}, received=$received/$totalLen")
        }

        return WebrtcSignalPayload.parseFrom(buffer)
    }

    // --- ICE queue ---

    private fun flushIceCandidateQueue() {
        val count = iceCandidateQueue.size
        Log.d(TAG, "Dispatching $count queued ICE candidates to executor")
        var candidate = iceCandidateQueue.poll()
        while (candidate != null) {
            // Submit each as a separate executor task so they interleave with
            // polls rather than blocking all polls until the flush completes.
            val c = candidate
            executor.execute { doSendIceCandidate(c) }
            candidate = iceCandidateQueue.poll()
        }
    }
}
