// Copyright 2021 Espressif Systems (Shanghai) PTE LTD
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

package com.espressif.local_control;

import com.espressif.AppConstants;
import com.espressif.provisioning.listeners.ResponseListener;
import com.espressif.provisioning.security.Security;
import com.espressif.provisioning.transport.Transport;

import java.util.concurrent.Semaphore;

/**
 * Session object encapsulates the Transport and Security
 * protocol implementations and is responsible for performing
 * initial handshake with the device to establish a secure session.
 */
public class EspLocalSession {

    private static final String TAG = EspLocalSession.class.getSimpleName();
    private Transport transport;
    private Security security;
    private boolean isSessionEstablished;
    // Serializes the full encrypt → send → receive → decrypt cycle.
    // Security1/2 AES-CTR uses a shared nonce for both directions, so
    // decrypt(response_A) must complete before encrypt(request_B) starts.
    // A Semaphore (vs synchronized) allows acquire on the calling thread
    // and release on the transport callback thread.
    private final Semaphore requestLock = new Semaphore(1);

    /**
     * Initialize Session object with Transport and Security interface implementations
     *
     * @param transport
     * @param security
     */
    public EspLocalSession(Transport transport, Security security) {
        this.transport = transport;
        this.security = security;
    }

    /**
     * Get the Security implementation object
     *
     * @return
     */
    public Security getSecurity() {
        return security;
    }

    /**
     * Get the Transport implementation object.
     *
     * @return
     */
    public Transport getTransport() {
        return transport;
    }

    /**
     * Get whether a secure Session has been established.
     *
     * @return
     */
    public boolean isEstablished() {
        return isSessionEstablished;
    }

    /**
     * Establish the session by performing handshake with the device
     * based on the Security implementation.
     * Communication with the device will happen over the Transport interface.
     *
     * @throws RuntimeException
     */
    public void init(byte[] response, final SessionListener sessionListener) throws RuntimeException {

        try {

            byte[] request = security.getNextRequestInSession(response);

            if (request == null) {

                isSessionEstablished = true;
                if (sessionListener != null) {
                    sessionListener.OnSessionEstablished();
                }
            } else {

                transport.sendConfigData(AppConstants.LOCAL_SESSION_ENDPOINT, request, new ResponseListener() {

                    @Override
                    public void onSuccess(byte[] returnData) {
                        if (returnData == null) {
                            if (sessionListener != null) {
                                sessionListener.OnSessionEstablishFailed(new RuntimeException("Session could not be established"));
                            }
                        } else {
                            init(returnData, sessionListener);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (sessionListener != null) {
                            sessionListener.OnSessionEstablishFailed(e);
                        }
                    }
                });
            }
        } catch (RuntimeException e) {
            if (response == null && sessionListener != null) {
                sessionListener.OnSessionEstablishFailed(new RuntimeException("Session could not be established"));
            }
        }
    }

    /**
     * Acquire the request lock externally.  Use this to hold the lock across
     * multiple {@link #sendDataToDevice} calls (e.g. fragmented sends) so that
     * other callers cannot interleave between them.
     * <p>
     * When held externally, {@code sendDataToDevice} calls made <b>on the same
     * thread</b> skip their own acquire/release. Calls from any other thread still
     * acquire (and therefore block) so concurrent crypto cannot interleave.
     * The caller MUST call {@link #releaseRequestLock()} when done.
     */
    public void acquireRequestLock() throws InterruptedException {
        requestLock.acquire();
        requestLockOwner = Thread.currentThread();
    }

    public void releaseRequestLock() {
        requestLockOwner = null;
        requestLock.release();
    }

    // Thread that currently holds requestLock via acquireRequestLock(), or null.
    // Must be thread-scoped (not a plain boolean): a process-wide "held" flag let
    // any concurrent caller skip the lock and race encrypt/decrypt, corrupting the
    // Security1/2 AES-CTR keystream shared across request/response.
    private volatile Thread requestLockOwner = null;

    public void sendDataToDevice(final String path, byte[] data, final ResponseListener listener) {

        if (isSessionEstablished) {

            // Only the thread that holds the lock externally (e.g. a fragmented send in
            // progress) may skip the acquire; every other caller MUST acquire so concurrent
            // encrypt/decrypt cannot interleave and corrupt the AES-CTR keystream.
            final boolean ownLock = (Thread.currentThread() != requestLockOwner);
            if (ownLock) {
                // Acquire the request lock to serialize the full
                // encrypt → HTTP POST → decrypt cycle across concurrent callers.
                try {
                    requestLock.acquire();
                } catch (InterruptedException e) {
                    if (listener != null) {
                        listener.onFailure(e);
                    }
                    return;
                }
            }

            final byte[] encryptedData = security.encrypt(data);

            transport.sendConfigData(path, encryptedData, new ResponseListener() {

                @Override
                public void onSuccess(byte[] returnData) {
                    byte[] decryptedData;
                    try {
                        decryptedData = security.decrypt(returnData);
                    } finally {
                        if (ownLock) {
                            requestLock.release();
                        }
                    }
                    if (listener != null) {
                        listener.onSuccess(decryptedData);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    if (ownLock) {
                        requestLock.release();
                    }
                    isSessionEstablished = false;
                    if (listener != null) {
                        listener.onFailure(e);
                    }
                }
            });

        } else {

            init(null, new SessionListener() {

                @Override
                public void OnSessionEstablished() {
                    // Retry now that session is established
                    sendDataToDevice(path, data, listener);
                }

                @Override
                public void OnSessionEstablishFailed(Exception e) {
                    if (listener != null) {
                        listener.onFailure(e);
                    }
                }
            });
        }
    }

    /**
     * Callback interface for listening to Session
     * establish events.
     */
    public interface SessionListener {

        /**
         * Called when session is established.
         * Further communication with the device can only
         * occur after this callback is called.
         */
        void OnSessionEstablished();

        /**
         * Called when session establish fails.
         *
         * @param e Exception
         */
        void OnSessionEstablishFailed(Exception e);
    }
}
