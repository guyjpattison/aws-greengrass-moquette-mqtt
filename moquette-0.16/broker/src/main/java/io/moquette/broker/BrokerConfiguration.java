/*
 * Copyright (c) 2012-2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.broker;

import io.moquette.BrokerConstants;
import io.moquette.broker.config.IConfig;

class BrokerConfiguration {

    private final boolean allowAnonymous;
    private final boolean allowZeroByteClientId;
    private final boolean reauthorizeSubscriptionsOnConnect;
    private final boolean immediateBufferFlush;
    private final boolean peerCertificateAsUsername;

    BrokerConfiguration(IConfig props) {
        allowAnonymous = props.boolProp(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, true);
        allowZeroByteClientId = props.boolProp(BrokerConstants.ALLOW_ZERO_BYTE_CLIENT_ID_PROPERTY_NAME, false);
        reauthorizeSubscriptionsOnConnect = props.boolProp(BrokerConstants.REAUTHORIZE_SUBSCRIPTIONS_ON_CONNECT, false);
        immediateBufferFlush = props.boolProp(BrokerConstants.IMMEDIATE_BUFFER_FLUSH_PROPERTY_NAME, false);
        peerCertificateAsUsername = props.boolProp(BrokerConstants.PEER_CERTIFICATE_AS_USERNAME, false);
    }

    public BrokerConfiguration(boolean allowAnonymous, boolean allowZeroByteClientId,
                               boolean reauthorizeSubscriptionsOnConnect, boolean immediateBufferFlush) {
        this(allowAnonymous, allowZeroByteClientId,
            reauthorizeSubscriptionsOnConnect, immediateBufferFlush, false);
    }

    public BrokerConfiguration(boolean allowAnonymous, boolean allowZeroByteClientId,
                               boolean reauthorizeSubscriptionsOnConnect, boolean immediateBufferFlush,
                               boolean peerCertificateAsUsername) {
        this.allowAnonymous = allowAnonymous;
        this.allowZeroByteClientId = allowZeroByteClientId;
        this.reauthorizeSubscriptionsOnConnect = reauthorizeSubscriptionsOnConnect;
        this.immediateBufferFlush = immediateBufferFlush;
        this.peerCertificateAsUsername = peerCertificateAsUsername;
    }

    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public boolean isAllowZeroByteClientId() {
        return allowZeroByteClientId;
    }

    public boolean isReauthorizeSubscriptionsOnConnect() {
        return reauthorizeSubscriptionsOnConnect;
    }

    public boolean isImmediateBufferFlush() {
        return immediateBufferFlush;
    }

    public boolean isPeerCertificateAsUsername() {
        return peerCertificateAsUsername;
    }
}
