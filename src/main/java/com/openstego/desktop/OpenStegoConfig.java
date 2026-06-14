/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

import java.util.Map;

/**
 * Class to store configuration data for OpenStego
 */
public class OpenStegoConfig {
    /**
     * Key string for configuration item - useCompression
     * <p>
     * Flag to indicate whether compression should be used or not
     */
    public static final String USE_COMPRESSION = "useCompression";

    /**
     * Key string for configuration item - useEncryption
     * <p>
     * Flag to indicate whether encryption should be used or not
     */
    public static final String USE_ENCRYPTION = "useEncryption";

    /**
     * Key string for configuration item - password
     * <p>
     * Password for encryption in case "useEncryption" is set to true
     */
    public static final String PASSWORD = "password";

    /**
     * Key string for configuration item - encryptionAlgorithm
     * <p>
     * Algorithm to be used for encryption
     */
    public static final String ENCRYPTION_ALGORITHM = "encryptionAlgorithm";

    /**
     * Flag to indicate whether compression should be used or not
     */
    private boolean useCompression = true;

    /**
     * Flag to indicate whether encryption should be used or not
     */
    private boolean useEncryption = false;

    /**
     * Password for encryption in case "useEncryption" is set to true
     */
    private String password = null;

    /**
     * Algorithm to be used for encryption in case "useEncryption" is set to true
     */
    private String encryptionAlgorithm = OpenStegoCrypto.ALGO_AES128;

    /**
     * Initialize the configuration with map data. Please make sure that only valid keys for configuration items are
     * provided, and the values for those items are also valid.
     *
     * @param propMap Map containing the configuration data
     * @throws OpenStegoException Processing issues
     */
    public final void initialize(Map<String, Object> propMap) throws OpenStegoException {
        addProperties(propMap);
    }

    /**
     * Processes a configuration item.
     *
     * @param key   Configuration item key
     * @param value Configuration item value
     * @throws OpenStegoException Processing issues
     */
    protected void processConfigItem(String key, Object value) throws OpenStegoException {
        switch (key) {
            case USE_COMPRESSION:
                if (value != null) {
                    assert value instanceof Boolean;
                    this.useCompression = (boolean) value;
                }
                break;
            case USE_ENCRYPTION:
                if (value != null) {
                    assert value instanceof Boolean;
                    this.useEncryption = (boolean) value;
                }
                break;
            case PASSWORD:
                assert value instanceof String;
                this.password = (String) value;
                break;
            case ENCRYPTION_ALGORITHM:
                assert value instanceof String;
                this.encryptionAlgorithm = (String) value;
                break;
        }
    }

    /**
     * Method to add properties from the map to this configuration data
     *
     * @param propMap Map containing the configuration data
     * @throws OpenStegoException Processing issues
     */
    private void addProperties(Map<String, Object> propMap) throws OpenStegoException {
        for (Map.Entry<String, Object> entry : propMap.entrySet()) {
            processConfigItem(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Get method for configuration item - useCompression
     *
     * @return useCompression
     */
    public boolean isUseCompression() {
        return this.useCompression;
    }

    /**
     * Set method for configuration item - useCompression
     *
     * @param useCompression Value to be set
     */
    public void setUseCompression(boolean useCompression) {
        this.useCompression = useCompression;
    }

    /**
     * Get Method for useEncryption
     *
     * @return useEncryption
     */
    public boolean isUseEncryption() {
        return this.useEncryption;
    }

    /**
     * Set Method for useEncryption
     *
     * @param useEncryption Value to be set
     */
    public void setUseEncryption(boolean useEncryption) {
        this.useEncryption = useEncryption;
    }

    /**
     * Get Method for password
     *
     * @return password
     */
    public String getPassword() {
        return this.password;
    }

    /**
     * Set Method for password
     *
     * @param password Value to be set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Get Method for encryptionAlgorithm
     *
     * @return encryptionAlgorithm
     */
    public String getEncryptionAlgorithm() {
        return this.encryptionAlgorithm;
    }

    /**
     * Set Method for encryptionAlgorithm
     *
     * @param encryptionAlgorithm Value to be set
     */
    public void setEncryptionAlgorithm(String encryptionAlgorithm) {
        this.encryptionAlgorithm = encryptionAlgorithm;
    }
}
