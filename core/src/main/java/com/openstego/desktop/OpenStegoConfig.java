/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
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
     * Key string for configuration item - useStrongEncryption
     * <p>
     * Flag to indicate whether the modern (v3) key-derivation should be used for new encrypted data.
     * Reading older data is unaffected; this only controls how new data is written.
     */
    public static final String USE_STRONG_ENCRYPTION = "useStrongEncryption";

    /**
     * Key string for configuration item - embedFileName
     * <p>
     * Flag to indicate whether the original file name should be embedded. The name is stored
     * unencrypted, so omitting it avoids leaking it to anyone who extracts the file.
     */
    public static final String EMBED_FILE_NAME = "embedFileName";

    /**
     * Flag to indicate whether compression should be used or not
     */
    private boolean useCompression = true;

    /**
     * Flag to indicate whether encryption should be used or not
     */
    private boolean useEncryption = false;

    /**
     * Password for encryption in case "useEncryption" is set to true. Held as a char[] (rather than a
     * String) so it can be wiped from memory after use.
     */
    private char[] password = null;

    /**
     * Algorithm to be used for encryption in case "useEncryption" is set to true
     */
    private String encryptionAlgorithm = OpenStegoCrypto.ALGO_AES128;

    /**
     * Flag to indicate whether the modern (v3) key-derivation should be used for new encrypted data
     */
    private boolean useStrongEncryption = true;

    /**
     * Flag to indicate whether the original file name should be embedded in the stego data
     */
    private boolean embedFileName = true;

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
                if (value instanceof char[]) {
                    this.password = (char[]) value;
                } else {
                    this.password = (value == null) ? null : ((String) value).toCharArray();
                }
                break;
            case ENCRYPTION_ALGORITHM:
                // Only known algorithm names may be stored: the name is written into a fixed-size
                // field of the stego header, and an unknown one would fail late (or overflow it)
                String algo = (value == null) ? null : ((String) value).trim().toUpperCase();
                if (algo != null
                        && !algo.isEmpty()
                        && !OpenStegoCrypto.ALGO_AES128.equals(algo)
                        && !OpenStegoCrypto.ALGO_AES256.equals(algo)
                        && !OpenStegoCrypto.ALGO_DES.equals(algo)) {
                    throw new OpenStegoException(
                            null, OpenStego.NAMESPACE, OpenStegoErrors.INVALID_CRYPT_ALGO, value);
                }
                this.encryptionAlgorithm = algo;
                break;
            case USE_STRONG_ENCRYPTION:
                if (value != null) {
                    assert value instanceof Boolean;
                    this.useStrongEncryption = (boolean) value;
                }
                break;
            case EMBED_FILE_NAME:
                if (value != null) {
                    assert value instanceof Boolean;
                    this.embedFileName = (boolean) value;
                }
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
     * @return password (the live char[]; do not mutate)
     */
    public char[] getPassword() {
        return this.password;
    }

    /**
     * Set Method for password
     *
     * @param password Value to be set
     */
    public void setPassword(char[] password) {
        this.password = password;
    }

    /**
     * Convenience setter that accepts a String. Note that the String itself cannot be wiped; prefer
     * {@link #setPassword(char[])} for sensitive input.
     *
     * @param password Value to be set
     */
    public void setPassword(String password) {
        this.password = (password == null) ? null : password.toCharArray();
    }

    /**
     * @return true if a non-blank password has been set
     */
    public boolean isPasswordSet() {
        if (this.password == null) {
            return false;
        }
        for (char c : this.password) {
            if (!Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wipes the password from memory.
     */
    public void clearPassword() {
        if (this.password != null) {
            java.util.Arrays.fill(this.password, '\0');
            this.password = null;
        }
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

    /**
     * Get Method for useStrongEncryption
     *
     * @return useStrongEncryption
     */
    public boolean isUseStrongEncryption() {
        return this.useStrongEncryption;
    }

    /**
     * Set Method for useStrongEncryption
     *
     * @param useStrongEncryption Value to be set
     */
    public void setUseStrongEncryption(boolean useStrongEncryption) {
        this.useStrongEncryption = useStrongEncryption;
    }

    /**
     * Get Method for embedFileName
     *
     * @return embedFileName
     */
    public boolean isEmbedFileName() {
        return this.embedFileName;
    }

    /**
     * Set Method for embedFileName
     *
     * @param embedFileName Value to be set
     */
    public void setEmbedFileName(boolean embedFileName) {
        this.embedFileName = embedFileName;
    }
}
