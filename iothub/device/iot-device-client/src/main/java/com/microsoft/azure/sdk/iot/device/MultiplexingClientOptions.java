// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.azure.sdk.iot.device;

import lombok.Builder;
import lombok.Getter;

import javax.net.ssl.SSLContext;

import static com.microsoft.azure.sdk.iot.device.ClientConfiguration.DEFAULT_KEEP_ALIVE_INTERVAL_IN_SECONDS;
import static com.microsoft.azure.sdk.iot.device.MultiplexingClient.*;

/**
 * The optional settings for creating a {@link MultiplexingClient}. If set, these values will supersede any device client
 * level settings on these parameters.
 */
@Builder
public final class MultiplexingClientOptions
{
    /**
     * The details of the proxy to connect to, if set. If not set, the multiplexing client will not connect through a proxy.
     */
    @Getter
    private final ProxySettings proxySettings;

    /**
     * The SSLContext to use during SSL handshake with the server. If not set, a default SSLContext will be generated for you.
     */
    @Getter
    private final SSLContext sslContext;

    /**
     * The period, in seconds, for how often a thread will spawn to handle queued outgoing messages. For instance, if this
     * is set to 10, then a thread will spawn every 10 seconds. If unset, this will default to {@link MultiplexingClient#DEFAULT_SEND_PERIOD_MILLIS}.
     */
    @Getter
    @Builder.Default
    private final long sendInterval = DEFAULT_SEND_PERIOD_MILLIS;

    /**
     * The period, in seconds, for how often a thread will spawn to handle queued incoming messages. For instance, if this
     * is set to 10, then a thread will spawn every 10 seconds. If unset, this will default to {@link MultiplexingClient#DEFAULT_RECEIVE_PERIOD_MILLIS}.
     */
    @Getter
    @Builder.Default
    private final long receiveInterval = DEFAULT_RECEIVE_PERIOD_MILLIS;

    /**
     * This option specifies how many messages a given send thread should attempt to send before exiting.
     * This option can be used in conjunction with the "sendInterval" option to control the how frequently and in what
     * batch size messages are sent. If unset, this will default to {@link MultiplexingClient#DEFAULT_MAX_MESSAGES_TO_SEND_PER_THREAD}.
     */
    @Getter
    @Builder.Default
    private final int maxMessagesSentPerSendInterval = DEFAULT_MAX_MESSAGES_TO_SEND_PER_THREAD;

    /**
     * Gets the keep alive interval in seconds. This value defines the
     * maximum time interval between messages sent or received. It enables the
     * client to detect if the server is no longer available, without having to wait
     * for the TCP/IP timeout. The client will ensure that at least one message
     * travels across the network within each keep alive period. In the absence of a
     * data-related message during the time period, the client sends a very small
     * "ping" message, which the server will acknowledge. The default value is 230 seconds.
     */
    @Getter
    @Builder.Default
    public final int keepAliveInterval = DEFAULT_KEEP_ALIVE_INTERVAL_IN_SECONDS;

    /**
     * The prefix that will be applied to the names of all threads created by this client. If
     * {@link #useIdentifiableThreadNames} is set to true, then this value is ignored and this client will create the
     * prefix for you.
     */
    @Getter
    @Builder.Default
    private final String threadNamePrefix = null;

    /**
     * The suffix that will be applied to the names of all threads created by this client. If
     * {@link #useIdentifiableThreadNames} is set to true, then this value is ignored and this client will create the
     * suffix for you.
     */
    @Getter
    @Builder.Default
    private final String threadNameSuffix = null;

    /**
     * If true, all threads created by this client will use names that are unique. This is useful in applications that manage
     * multiple device/module clients and want to be able to correlate logs to a particular client. In addition,
     * the {@link #threadNamePrefix} and {@link #threadNameSuffix} values will be ignored.
     *
     * If false, all threads created by this client will use simple names that describe the thread's purpose, but are
     * indistinguishable from the same threads created by a different client instance. However, users may still alter
     * these thread names by providing values for the {@link #threadNamePrefix} and {@link #threadNameSuffix}.
     */
    @Builder.Default
    private final boolean useIdentifiableThreadNames = true;

    public boolean isUsingIdentifiableThreadNames()
    {
        // Using a manually written method here to override the name that Lombok would have given it
        return this.useIdentifiableThreadNames;
    }
}
