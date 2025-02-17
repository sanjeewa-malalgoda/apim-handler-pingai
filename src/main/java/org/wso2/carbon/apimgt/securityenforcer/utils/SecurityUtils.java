/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.apimgt.securityenforcer.utils;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.axis2.Constants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.synapse.MessageContext;
import org.apache.synapse.transport.passthru.ServerWorker;
import org.apache.synapse.transport.passthru.SourceRequest;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.wso2.carbon.apimgt.securityenforcer.dto.AISecurityHandlerConfig;
import org.wso2.carbon.apimgt.securityenforcer.internal.ServiceReferenceHolder;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

public class SecurityUtils {

    private static final Log log = LogFactory.getLog(SecurityUtils.class);

    private static final String STRICT = "Strict";
    private static final String ALLOW_ALL = "AllowAll";
    private static final String HOST_NAME_VERIFIER = "httpclient.hostnameVerifier";

    /**
     * Return a json array with with transport headers
     *
     * @param axis2MessageContext- synapse variables
     * @param sideBandCallType - request or response message
     * @return transportHeaderArray - JSON array with all the transport headers
     */
    public static JSONArray getTransportHeaders(org.apache.axis2.context.MessageContext axis2MessageContext,
            String sideBandCallType, String correlationID) throws AISecurityException {

        TreeMap<String, String> transportHeadersMap = (TreeMap<String, String>) axis2MessageContext
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);

        if (transportHeadersMap != null) {
            JSONArray transportHeadersArray = new JSONArray();
            Set<String> headerKeysSet = new HashSet<String>(transportHeadersMap.keySet());

            if (log.isDebugEnabled()) {
                log.debug("Transport headers found for the request " + correlationID + " are " + headerKeysSet);
            }
            if (ServiceReferenceHolder.getInstance().getSecurityHandlerConfig().getLimitTransportHeaders().isEnable()) {
                headerKeysSet.retainAll(
                        ServiceReferenceHolder.getInstance().getSecurityHandlerConfig().getLimitTransportHeaders()
                                .getHeaderSet());
            }

            if (AISecurityHandlerConstants.ASE_RESOURCE_REQUEST.equals(sideBandCallType)) {
                String hostValue = transportHeadersMap.get(AISecurityHandlerConstants.TRANSPORT_HEADER_HOST_NAME);
                if (hostValue != null) {
                    transportHeadersArray.add(addObj(AISecurityHandlerConstants.TRANSPORT_HEADER_HOST_NAME, hostValue));
                    headerKeysSet.remove(AISecurityHandlerConstants.TRANSPORT_HEADER_HOST_NAME);
                } else {
                    log.error("Host not found in the transport headers for the request " + correlationID);
                    throw new AISecurityException(AISecurityException.CLIENT_REQUEST_ERROR,
                            AISecurityException.CLIENT_REQUEST_ERROR_MESSAGE);
                }
            }

            for (String headerKey : headerKeysSet) {
                String headerValue = transportHeadersMap.get(headerKey);
                transportHeadersArray.add(addObj(headerKey, headerValue));
            }
            return transportHeadersArray;
        } else {
            log.error("No Transport headers found for the request " + correlationID);
            throw new AISecurityException(AISecurityException.CLIENT_REQUEST_ERROR,
                    AISecurityException.CLIENT_REQUEST_ERROR_MESSAGE);
        }
    }

    public static JSONObject addObj(String key, Object value) {
        JSONObject obj = new JSONObject();
        obj.put(key, value);
        return obj;
    }

    /**
     * Return a CloseableHttpClient instance
     *
     * @param protocol- service endpoint protocol. It can be http/https
     * @param dataPublisherConfiguration - DataPublisher Configurations
     *          maxPerRoute- maximum number of HTTP connections allowed across all routes.
     *          maxOpenConnections- maximum number of HTTP connections allowed for a route.
     *          connectionTimeout- the time to establish the connection with the remote host
     *
     * @return CloseableHttpClient
     */
    public static CloseableHttpClient getHttpClient(String protocol,
            AISecurityHandlerConfig.DataPublisherConfig dataPublisherConfiguration) throws AISecurityException {

        PoolingHttpClientConnectionManager pool;
        try {
            pool = SecurityUtils.getPoolingHttpClientConnectionManager(protocol);
        } catch (Exception e) {
            throw new AISecurityException(e);
        }

        pool.setMaxTotal(dataPublisherConfiguration.getMaxOpenConnections());
        pool.setDefaultMaxPerRoute(dataPublisherConfiguration.getMaxPerRoute());

        //Socket timeout is set to 10 seconds addition to connection timeout.
        RequestConfig params = RequestConfig.custom()
                .setConnectTimeout(dataPublisherConfiguration.getConnectionTimeout() * 1000)
                .setSocketTimeout((dataPublisherConfiguration.getConnectionTimeout() + 10) * 10000).build();

        return HttpClients.custom().setConnectionManager(pool).setDefaultRequestConfig(params).build();
    }

    /**
     * Return a PoolingHttpClientConnectionManager instance
     *
     * @param protocol- service endpoint protocol. It can be http/https
     * @return PoolManager
     */
    private static PoolingHttpClientConnectionManager getPoolingHttpClientConnectionManager(String protocol)
            throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException, IOException,
            CertificateException {

        PoolingHttpClientConnectionManager poolManager;
        if ("https".equals(protocol)) {

            String keyStorePath = CarbonUtils.getServerConfiguration().getFirstProperty("Security.TrustStore.Location");
            String keyStorePassword = CarbonUtils.getServerConfiguration()
                    .getFirstProperty("Security.TrustStore.Password");
            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(new FileInputStream(keyStorePath), keyStorePassword.toCharArray());

            SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(trustStore).build();

            X509HostnameVerifier hostnameVerifier;
            String hostnameVerifierOption = System.getProperty(HOST_NAME_VERIFIER);

            if (ALLOW_ALL.equalsIgnoreCase(hostnameVerifierOption)) {
                hostnameVerifier = SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER;
            } else if (STRICT.equalsIgnoreCase(hostnameVerifierOption)) {
                hostnameVerifier = SSLConnectionSocketFactory.STRICT_HOSTNAME_VERIFIER;
            } else {
                hostnameVerifier = SSLConnectionSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;
            }

            SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(sslContext, hostnameVerifier);

            Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("https", sslsf).build();
            poolManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
        } else {
            poolManager = new PoolingHttpClientConnectionManager();
        }
        return poolManager;
    }

    /**
     * Extracts the IP from Message Context.
     *
     * @param axis2MessageContext Axis2 Message Context.
     * @return IP as a String.
     */
    public static String getIp(org.apache.axis2.context.MessageContext axis2MessageContext) {

        //Set transport headers of the message
        TreeMap<String, String> transportHeaderMap = (TreeMap<String, String>) axis2MessageContext
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        // Assigning an Empty String so that when doing comparisons, .equals method can be used without explicitly
        // checking for nullity.
        String remoteIP = "";
        //Check whether headers map is null and x forwarded for header is present
        if (transportHeaderMap != null) {
            remoteIP = transportHeaderMap.get("X-Forwarded-For");
        }

        //Setting IP of the client by looking at x forded for header and  if it's empty get remote address
        if (remoteIP != null && !remoteIP.isEmpty()) {
            if (remoteIP.indexOf(",") > 0) {
                remoteIP = remoteIP.substring(0, remoteIP.indexOf(","));
            }
        } else {
            remoteIP = (String) axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.REMOTE_ADDR);
        }
        return remoteIP;
    }

    /**
     * return existing correlation ID in the message context or set new correlation ID to the message context.
     *
     * @param messageContext synapse message context
     * @return correlation ID
     */
    public static String getAndSetCorrelationID(MessageContext messageContext) {
        Object correlationObj = messageContext.getProperty("am.correlationID");
        String correlationID;
        if (correlationObj != null) {
            correlationID = (String) correlationObj;
            if (log.isDebugEnabled()) {
                log.debug("Correlation ID is available in the message context." + correlationID);
            }
        } else {
            correlationID = UUID.randomUUID().toString();
            messageContext.setProperty("am.correlationID", correlationID);
            if (log.isDebugEnabled()) {
                log.debug("Correlation ID is not available in the message context. Setting a new ID to message context."
                        + correlationID);
            }
        }
        return correlationID;
    }

    /**
     * Return the httpVersion of the request
     *
     * @param axis2MessageContext - synapse variables
     * @return payload - HTTP version of the request
     */
    public static String getHttpVersion(org.apache.axis2.context.MessageContext axis2MessageContext) {

        ServerWorker worker = (ServerWorker) axis2MessageContext.getProperty(Constants.OUT_TRANSPORT_INFO);
        SourceRequest sourceRequest = worker.getSourceRequest();
        ProtocolVersion httpProtocolVersion = sourceRequest.getVersion();

        return httpProtocolVersion.getMajor() + AISecurityHandlerConstants.HTTP_VERSION_CONNECTOR + httpProtocolVersion
                .getMinor();
    }

    public static OMElement getFaultPayload(AISecurityException e) {
        OMFactory fac = OMAbstractFactory.getOMFactory();
        OMNamespace ns = fac.createOMNamespace(AISecurityHandlerConstants.API_SECURITY_NS,
                AISecurityHandlerConstants.API_SECURITY_NS_PREFIX);
        OMElement payload = fac.createOMElement("fault", ns);
        OMElement errorCode = fac.createOMElement("code", ns);
        errorCode.setText(String.valueOf(e.getErrorCode()));
        OMElement errorMessage = fac.createOMElement("message", ns);
        errorMessage.setText(e.getMessage());
        OMElement errorDetail = fac.createOMElement("description", ns);
        errorDetail.setText(AISecurityException.getAuthenticationFailureMessage(e.getErrorCode()));

        payload.addChild(errorCode);
        payload.addChild(errorMessage);
        payload.addChild(errorDetail);
        return payload;
    }

    public static void updateLatency(Long latency, MessageContext messageContext) {
        Object otherLatency = messageContext.getProperty("other_latency");
        if (otherLatency == null) {
            messageContext.setProperty("other_latency", TimeUnit.NANOSECONDS.toMillis(latency));
        } else {
            messageContext.setProperty("other_latency", TimeUnit.NANOSECONDS.toMillis((long) otherLatency + latency));
        }

    }
}
