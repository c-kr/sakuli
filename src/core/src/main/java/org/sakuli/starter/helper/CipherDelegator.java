/*
 * Sakuli - Testing and Monitoring-Tool for Websites and common UIs.
 *
 * Copyright 2013 - 2017 the original author or authors.
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

package org.sakuli.starter.helper;

import org.sakuli.datamodel.properties.CipherProperties;
import org.sakuli.exceptions.SakuliCipherException;
import org.sakuli.services.cipher.EnvironmentCipher;
import org.sakuli.services.cipher.NetworkInterfaceCipher;
import org.sakuli.utils.SakuliPropertyPlaceholderConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Properties;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Helper class to delegate which cipher implementation should be used, if the cipher get called from the
 * {@link org.sakuli.starter.SakuliStarter} directly without Spring context.
 *
 * @author tschneck
 *         Date: 6/28/17
 */
public class CipherDelegator {
    private static final Logger LOGGER = LoggerFactory.getLogger(CipherDelegator.class);

    /**
     * Delegation class to encrypt a secret without starting the whole Spring context.
     *
     * @param strToEncrypt
     * @return a entry with "secret, generation-info"
     * @throws SakuliCipherException
     */
    public static Map.Entry<String, String> encrypt(String strToEncrypt) throws SakuliCipherException {
        loadEnvironmentVariables();
        Properties props = new Properties();
        SakuliPropertyPlaceholderConfigurer.assignEncryptionProperties(props);
        CipherProperties cipherProps = CipherProperties.load(props);

        switch (cipherProps.getEncryptionMode()) {
            case CipherProperties.ENCRYPTION_MODE_ENVIRONMENT:
                return new AbstractMap.SimpleEntry<>("environment masterkey", new EnvironmentCipher(cipherProps).encrypt(strToEncrypt));
            case CipherProperties.ENCRYPTION_MODE_INTERFACE:
                NetworkInterfaceCipher cipher = new NetworkInterfaceCipher(cipherProps);
                cipher.scanNetworkInterfaces();
                return new AbstractMap.SimpleEntry<>("interface " + cipher.getInterfaceName(), cipher.encrypt(strToEncrypt));
            default:
                throw new SakuliCipherException("unexpected error during encryption");
        }
    }

    /**
     * Loads the environment value of {@link CipherProperties#ENCRYPTION_KEY_ENV} if no CLI option value is parsed.
     */
    static void loadEnvironmentVariables() {
        //CLI argument wins against environment var
        if (isBlank(SakuliPropertyPlaceholderConfigurer.ENCRYPTION_KEY_VALUE)) {
            final String envKey = System.getenv(CipherProperties.ENCRYPTION_KEY_ENV);
            if (isNotBlank(envKey)) {
                SakuliPropertyPlaceholderConfigurer.ENCRYPTION_KEY_VALUE = envKey;
                LOGGER.info("use environment var '{}' for encryption", CipherProperties.ENCRYPTION_KEY_ENV);
            }
        }
    }
}
