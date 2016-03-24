/*
 * Sakuli - Testing and Monitoring-Tool for Websites and common UIs.
 *
 * Copyright 2013 - 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakuli.services.forwarder.gearman;

import org.apache.commons.codec.binary.Base64;
import org.gearman.client.*;
import org.gearman.common.GearmanJobServerConnection;
import org.gearman.common.GearmanNIOJobServerConnection;
import org.sakuli.exceptions.SakuliRuntimeException;
import org.sakuli.services.common.AbstractResultService;
import org.sakuli.services.forwarder.ScreenshotDivConverter;
import org.sakuli.services.forwarder.gearman.model.NagiosCheckResult;
import org.sakuli.services.forwarder.gearman.model.builder.NagiosCheckResultBuilder;
import org.sakuli.services.forwarder.gearman.model.builder.NagiosExceptionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * @author tschneck
 *         Date: 23.05.14
 */
@ProfileGearman
@Component
public class GearmanResultServiceImpl extends AbstractResultService {
    private static final Logger logger = LoggerFactory.getLogger(GearmanResultServiceImpl.class);
    @Autowired
    private GearmanProperties properties;
    @Autowired
    private NagiosCheckResultBuilder nagiosCheckResultBuilder;
    @Autowired
    private GearmanCacheService cacheService;

    @Override
    public int getServicePriority() {
        return 10;
    }

    @Override
    public void saveAllResults() {
        logger.info("======= SEND RESULTS TO GEARMAN SERVER ======");
        String hostname = properties.getServerHost();
        int port = properties.getServerPort();
        GearmanClient gearmanClient = getGearmanClient();
        GearmanJobServerConnection connection = getGearmanConnection(hostname, port);

        testSuite.refreshState();
        List<NagiosCheckResult> results = new ArrayList<>();
        results.add(nagiosCheckResultBuilder.build());

        if (properties.isCacheEnabled()) {
            results.addAll(cacheService.getCachedResults());

            if (results.size() > 1) {
                logger.info(String.format("Processing %s cached results first", results.size() - 1));
            }
        }

        try {
            if (!gearmanClient.addJobServer(connection)) {
                throw new SakuliRuntimeException("Failed to connect to Gearman server");
            }

            while (!results.isEmpty()) {
                NagiosCheckResult checkResult = results.get(results.size() - 1);
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("Sending result to Gearman server %s:%s", checkResult.getQueueName(), checkResult.getUuid()));
                }

                logGearmanMessage(checkResult.getPayloadString());
                GearmanJob job = creatJob(checkResult);

                //send results to gearman
                Future<GearmanJobResult> future = gearmanClient.submit(job);
                GearmanJobResult result = future.get();
                results.remove(results.size() - 1);
                if (result.jobSucceeded()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(String.format("Successfully sent result to Gearman server %s:%s", checkResult.getQueueName(), checkResult.getUuid()));
                    }

                    if (!results.isEmpty()) {
                        while (gearmanClient.getJobStatus(job).isRunning()) {
                            logger.debug("Waiting for result job to finish");
                            Thread.sleep(properties.getJobInterval());
                        }

                        Thread.sleep(properties.getJobInterval());
                    }
                } else {
                    exceptionHandler.handleException(
                            NagiosExceptionBuilder.buildTransferException(hostname, port, result)
                    );
                }
            }
        } catch (Throwable e) {
            logger.error(e.getMessage());
            exceptionHandler.handleException(
                    NagiosExceptionBuilder.buildUnexpectedErrorException(e, hostname, port),
                    true);
        } finally {
            if (properties.isCacheEnabled()) {
                cacheService.cacheResults(results);
            }

            gearmanClient.shutdown();
            logger.info("======= FINISHED: SEND RESULTS TO GEARMAN SERVER ======");
        }
    }

    /**
     * Logs the assigned Gearman message as follow:
     * <ul>
     * <li>DEBUG: complete message with screenshot as BASE64 String</li>
     * <li>INFO: message without screenshot to shrink the log entry</li>
     * </ul>
     *
     * @param message as {@link String}
     */
    private void logGearmanMessage(String message) {
        if (logger.isDebugEnabled()) {
            logger.debug("MESSAGE for GEARMAN:\n{}", message);
        } else {
            logger.info("MESSAGE for GEARMAN:\n{}", ScreenshotDivConverter.removeBase64ImageDataString(message));
        }
    }

    protected GearmanJob creatJob(NagiosCheckResult checkResult) {
        byte[] bytesBase64 = Base64.encodeBase64(checkResult.getPayloadString().getBytes());
        return GearmanJobImpl.createBackgroundJob(checkResult.getQueueName(), bytesBase64, checkResult.getUuid());
    }

    protected GearmanJobServerConnection getGearmanConnection(String hostname, int port) {
        return new GearmanNIOJobServerConnection(hostname, port);
    }


    protected GearmanClient getGearmanClient() {
        return new GearmanClientImpl();
    }
}
