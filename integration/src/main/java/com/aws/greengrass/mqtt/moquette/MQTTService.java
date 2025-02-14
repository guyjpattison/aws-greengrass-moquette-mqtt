/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqtt.moquette;

import com.aws.greengrass.clientdevices.auth.api.CertificateUpdateEvent;
import com.aws.greengrass.clientdevices.auth.api.ClientDevicesAuthServiceApi;
import com.aws.greengrass.clientdevices.auth.api.GetCertificateRequest;
import com.aws.greengrass.clientdevices.auth.api.GetCertificateRequestOptions;
import com.aws.greengrass.clientdevices.auth.exception.CertificateGenerationException;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.PluginService;
import com.aws.greengrass.util.Coerce;
import io.moquette.BrokerConstants;
import io.moquette.broker.ISslContextCreator;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.interception.InterceptHandler;

import java.io.IOException;
import java.security.KeyStoreException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;

@ImplementsService(name = MQTTService.SERVICE_NAME, autostart = true)
public class MQTTService extends PluginService {
    public static final String SERVICE_NAME = "MistralData.greengrass.clientdevices.mqtt.Moquette";

    private static BrokerKeyStore brokerKeyStore;
    private final Server mqttBroker = new Server();
    private final Kernel kernel;
    private final ClientDeviceTrustManager clientDeviceTrustManager;
    private final ClientDeviceAuthorizer clientDeviceAuthorizer;
    private final List<InterceptHandler> interceptHandlers;
    private final ClientDevicesAuthServiceApi clientDevicesAuthServiceApi;
    private final GetCertificateRequest serverCertificateRequest;

    private boolean serverRunning = false;
    private Properties runningProperties = null;

    /**
     * Constructor for GreengrassService.
     *
     * @param topics                   Root Configuration topic for this service
     * @param kernel                   Greengrass Nucleus
     * @param clientDevicesAuthService Client devices auth service handle
     */
    @Inject
    public MQTTService(Topics topics, Kernel kernel, ClientDevicesAuthServiceApi clientDevicesAuthService) {
        super(topics);
        this.kernel = kernel;
        this.clientDeviceTrustManager = new ClientDeviceTrustManager(clientDevicesAuthService);
        this.clientDeviceAuthorizer = new ClientDeviceAuthorizer(clientDevicesAuthService);
        this.interceptHandlers = Collections.singletonList(clientDeviceAuthorizer.new ConnectionTerminationListener());
        this.clientDevicesAuthServiceApi = clientDevicesAuthService;

        GetCertificateRequestOptions options = new GetCertificateRequestOptions();
        options.setCertificateType(GetCertificateRequestOptions.CertificateType.SERVER);
        serverCertificateRequest = new GetCertificateRequest(SERVICE_NAME, options, this::updateServerCertificate);
    }

    @Override
    protected void install() {
        try {
            brokerKeyStore = new BrokerKeyStore(kernel.getNucleusPaths().workPath(SERVICE_NAME));
            brokerKeyStore.initialize();

            this.config.lookupTopics(CONFIGURATION_CONFIG_KEY).subscribe(this::processConfigUpdate);
        } catch (IOException | KeyStoreException e) {
            serviceErrored(e);
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private synchronized void processConfigUpdate(WhatHappened whatHappened, Node node) {
        if (inState(State.RUNNING)) {
            startWithProperties(getProperties());
        }
    }

    private synchronized void updateServerCertificate(CertificateUpdateEvent certificateUpdate) {
        try {
            brokerKeyStore.updateServerCertificate(certificateUpdate);
        } catch (KeyStoreException e) {
            logger.atError().cause(e).log("Failed to update MQTT broker certificate");
        }
        startWithProperties(getProperties(), true);
    }

    @Override
    public synchronized void startup() {
        // Subscribe to client devices auth certificate updates
        try {
            clientDevicesAuthServiceApi.subscribeToCertificateUpdates(serverCertificateRequest);
        } catch (CertificateGenerationException e) {
            logger.atError().log("Unable to generate MQTT broker certificate");
            serviceErrored(e);
            return;
        }

        Properties p = getProperties();
        startWithProperties(p);
        reportState(State.RUNNING);
    }

    @Override
    public synchronized void shutdown() {
        clientDevicesAuthServiceApi.unsubscribeFromCertificateUpdates(serverCertificateRequest);
        if (serverRunning) {
            mqttBroker.stopServer();
            serverRunning = false;
        }
    }

    private synchronized void startWithProperties(Properties properties) {
        startWithProperties(properties, false);
    }

    private synchronized void startWithProperties(Properties properties, boolean forceRestart) {
        if (runningProperties != null && runningProperties.equals(properties) && !forceRestart && serverRunning) {
            // Nothing to do
            // Only do nothing if the server is currently running. If we aren't running, then we need to startup
            return;
        }

        if (serverRunning) {
            mqttBroker.stopServer();
            serverRunning = false;
        }

        IConfig config = new MemoryConfig(properties);
        ISslContextCreator sslContextCreator =
            new GreengrassMoquetteSslContextCreator(config, clientDeviceTrustManager);
        mqttBroker.startServer(config, interceptHandlers, sslContextCreator, clientDeviceAuthorizer,
            clientDeviceAuthorizer);
        serverRunning = true;
        runningProperties = properties;
    }

    private Properties getProperties() {
        Properties p = new Properties();

        Topics rootConfig = config.lookupTopics(CONFIGURATION_CONFIG_KEY);
        Topics moquetteTopics = config.lookupTopics(CONFIGURATION_CONFIG_KEY, "moquette");

        String password = brokerKeyStore.getJksPassword();
        p.setProperty(BrokerConstants.HOST_PROPERTY_NAME,
            Coerce.toString(moquetteTopics.lookup(BrokerConstants.HOST_PROPERTY_NAME).dflt(BrokerConstants.HOST)));
        // Due to a deserialization bug in GSON during group deployments, this value can become a floating point
        // instead of an int. As a workaround, coerce to an int before converting back to a string
        p.setProperty(BrokerConstants.SSL_PORT_PROPERTY_NAME,
            String.valueOf(Coerce.toInt(moquetteTopics.lookup(BrokerConstants.SSL_PORT_PROPERTY_NAME).dflt("8883"))));
        p.setProperty(BrokerConstants.JKS_PATH_PROPERTY_NAME, brokerKeyStore.getJksPath());
        p.setProperty(BrokerConstants.KEY_STORE_PASSWORD_PROPERTY_NAME, password);
        p.setProperty(BrokerConstants.KEY_MANAGER_PASSWORD_PROPERTY_NAME, password);
        //Guy May 2023 allow anon connections
        //p.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "false");
        p.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");        
        p.setProperty(BrokerConstants.PEER_CERTIFICATE_AS_USERNAME, "true");
        //Guy May 2023 allow anon connections      
        //p.setProperty(BrokerConstants.NEED_CLIENT_AUTH, "true");          
        p.setProperty(BrokerConstants.NEED_CLIENT_AUTH, "false");
        p.setProperty(BrokerConstants.IMMEDIATE_BUFFER_FLUSH_PROPERTY_NAME, "true");
        p.setProperty(BrokerConstants.NETTY_MAX_BYTES_PROPERTY_NAME, String.valueOf(Coerce
            .toInt(moquetteTopics.lookup(BrokerConstants.NETTY_MAX_BYTES_PROPERTY_NAME).dflt("131072")))); // 128KiB
        p.setProperty(BrokerConstants.NETTY_ENABLED_TLS_PROTOCOLS_PROPERTY_NAME, "TLSv1.2");
        p.setProperty(BrokerConstants.NETTY_CHANNEL_WRITE_LIMIT_PROPERTY_NAME, Coerce.toString(
            rootConfig.lookup(BrokerConstants.NETTY_CHANNEL_WRITE_LIMIT_PROPERTY_NAME)
                .dflt(BrokerConstants.DEFAULT_NETTY_CHANNEL_WRITE_LIMIT_BYTES)));
        p.setProperty(BrokerConstants.NETTY_CHANNEL_READ_LIMIT_PROPERTY_NAME, Coerce.toString(
            rootConfig.lookup(BrokerConstants.NETTY_CHANNEL_READ_LIMIT_PROPERTY_NAME)
                .dflt(BrokerConstants.DEFAULT_NETTY_CHANNEL_READ_LIMIT_BYTES)));

        //Guy May 2023 comment out disabling plain TCP port
        ////Disable plain TCP port
        //p.setProperty(BrokerConstants.PORT_PROPERTY_NAME, BrokerConstants.DISABLED_PORT_BIND);

        return p;
    }
}
