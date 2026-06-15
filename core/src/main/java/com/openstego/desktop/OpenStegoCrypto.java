/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 */

package com.openstego.desktop;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.AlgorithmParameters;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * This is the class for providing cryptography support to OpenStego.
 * <p>
 * Two on-the-wire formats are supported:
 * <ul>
 *   <li><b>v2 (legacy)</b> - the original password-based encryption (PBE with a fixed salt and a low
 *       iteration count). This format is still <em>read</em> so that data created by older versions of
 *       OpenStego remains decryptable, and is still written when strong encryption is disabled.</li>
 *   <li><b>v3 (strong)</b> - modern key derivation (PBKDF2-HMAC-SHA256 with a random salt and a high
 *       iteration count) combined with AES-GCM authenticated encryption. This is the default for newly
 *       encrypted data. The v3 payload is self-describing and is distinguished from v2 by a leading
 *       marker byte (a v2 payload always starts with a non-zero parameter length).</li>
 * </ul>
 */
public class OpenStegoCrypto {
    /**
     * Logger instance
     */
    private static final Logger logger = Logger.getLogger(OpenStegoCrypto.class.getName());

    /**
     * Constant for algorithm - DES
     */
    public static final String ALGO_DES = "DES";
    /**
     * Constant for algorithm - AES128
     */
    public static final String ALGO_AES128 = "AES128";
    /**
     * Constant for algorithm - AES256
     */
    public static final String ALGO_AES256 = "AES256";

    // ------------- v2 (legacy) parameters - MUST NOT change (backward compatibility) -------------

    /**
     * 8-byte Salt for legacy password-based cryptography
     */
    private final byte[] SALT = {(byte) 0x28, (byte) 0x5F, (byte) 0x71, (byte) 0xC9, (byte) 0x1E, (byte) 0x35, (byte) 0x0A, (byte) 0x62};

    /**
     * Iteration count for legacy password-based cryptography
     */
    private final int ITER_COUNT = 7;

    /**
     * JCE algorithm name for the legacy (v2) PBE scheme
     */
    private final String pbeAlgorithm;

    /**
     * Derived key length in bits for the legacy (v2) scheme (0 = let the provider decide)
     */
    private final int v2KeyBits;

    // ------------- v3 (strong) parameters -------------

    /**
     * Leading marker byte for the v3 format. A v2 payload always begins with a non-zero parameter
     * length, so a leading zero unambiguously identifies a v3 payload.
     */
    private static final byte V3_MARKER = (byte) 0x00;

    /**
     * Magic bytes following the marker for the v3 format
     */
    private static final byte[] V3_MAGIC = {(byte) 'S', (byte) '3'};

    /**
     * v3 format version
     */
    private static final byte V3_FORMAT_VERSION = (byte) 1;

    /**
     * PBKDF2 iteration count for v3 (OWASP-recommended order of magnitude for PBKDF2-HMAC-SHA256)
     */
    private static final int V3_ITERATIONS = 210000;

    /**
     * Salt length in bytes for v3
     */
    private static final int V3_SALT_LEN = 16;

    /**
     * IV length in bytes for AES-GCM
     */
    private static final int V3_IV_LEN = 12;

    /**
     * Authentication tag length in bits for AES-GCM
     */
    private static final int V3_TAG_BITS = 128;

    private final String password;
    private final int v3KeyBytes;
    private final boolean v3Capable;
    private final boolean strongEncryption;
    private final SecureRandom random = new SecureRandom();

    /**
     * Constructor using strong encryption by default for newly encrypted data.
     *
     * @param password  Password to use for encryption
     * @param algorithm Cryptography algorithm to use. If null or blank value is provided, then it defaults to AES128
     * @throws OpenStegoException Processing issues
     */
    public OpenStegoCrypto(String password, String algorithm) throws OpenStegoException {
        this(password, algorithm, true);
    }

    /**
     * Default constructor
     *
     * @param password         Password to use for encryption
     * @param algorithm        Cryptography algorithm to use. If null or blank value is provided, then it defaults to AES128
     * @param strongEncryption Whether to use the modern (v3) format for newly encrypted data
     * @throws OpenStegoException Processing issues
     */
    public OpenStegoCrypto(String password, String algorithm, boolean strongEncryption) throws OpenStegoException {
        this.password = (password == null) ? "" : password;
        this.strongEncryption = strongEncryption;

        String origAlgo = (algorithm == null) ? "" : algorithm.trim().toUpperCase();
        // v3 (AES-GCM) is available for the AES algorithms; DES stays on the legacy path
        if (origAlgo.equals("") || ALGO_AES128.equals(origAlgo)) {
            this.v3KeyBytes = 16;
            this.v3Capable = true;
            this.pbeAlgorithm = "PBEWithHmacSHA256AndAES_128";
            this.v2KeyBits = 128;
        } else if (ALGO_AES256.equals(origAlgo)) {
            this.v3KeyBytes = 32;
            this.v3Capable = true;
            this.pbeAlgorithm = "PBEWithHmacSHA256AndAES_256";
            this.v2KeyBits = 256;
        } else if (ALGO_DES.equals(origAlgo)) {
            logger.warning("Using insecure algorithm: " + ALGO_DES);
            this.v3KeyBytes = 0;
            this.v3Capable = false;
            this.pbeAlgorithm = "PBEWithMD5AndDES";
            this.v2KeyBits = 0; // let the provider derive the DES key length
        } else {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.INVALID_CRYPT_ALGO, algorithm);
        }
    }

    /**
     * Lazily derives the legacy (v2) secret key. Done on demand (rather than in the constructor) so
     * that the v3 path never needs the legacy PBE algorithm, which is not available on all platforms.
     */
    private SecretKey getV2SecretKey() throws Exception {
        PBEKeySpec keySpec = (this.v2KeyBits > 0)
                ? new PBEKeySpec(this.password.toCharArray(), this.SALT, this.ITER_COUNT, this.v2KeyBits)
                : new PBEKeySpec(this.password.toCharArray(), this.SALT, this.ITER_COUNT);
        try {
            return SecretKeyFactory.getInstance(this.pbeAlgorithm).generateSecret(keySpec);
        } finally {
            keySpec.clearPassword();
        }
    }

    /**
     * Method to encrypt the data
     *
     * @param input Data to be encrypted
     * @return Encrypted data
     * @throws OpenStegoException Processing issues
     */
    public byte[] encrypt(byte[] input) throws OpenStegoException {
        if (!this.v3Capable) {
            // DES is cryptographically broken; it remains supported only for reading legacy files
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.INVALID_CRYPT_ALGO, ALGO_DES);
        }
        if (this.strongEncryption) {
            return encryptV3(input);
        }
        return encryptV2(input);
    }

    /**
     * Method to decrypt the data. The format (v2 or v3) is auto-detected.
     *
     * @param input Data to be decrypted
     * @return Decrypted data
     * @throws OpenStegoException Processing issues
     */
    public byte[] decrypt(byte[] input) throws OpenStegoException {
        if (isV3(input)) {
            return decryptV3(input);
        }
        return decryptV2(input);
    }

    private static boolean isV3(byte[] input) {
        return input != null && input.length >= 3 && input[0] == V3_MARKER
                && input[1] == V3_MAGIC[0] && input[2] == V3_MAGIC[1];
    }

    // ------------- v2 (legacy) implementation - unchanged on the wire -------------

    private byte[] encryptV2(byte[] input) throws OpenStegoException {
        try {
            SecretKey secretKey = getV2SecretKey();
            Cipher encryptCipher = Cipher.getInstance(this.pbeAlgorithm);
            AlgorithmParameterSpec algoParamSpec = new PBEParameterSpec(this.SALT, this.ITER_COUNT);
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, algoParamSpec);

            byte[] algoParams = encryptCipher.getParameters().getEncoded();
            byte[] msg = encryptCipher.doFinal(input);
            byte paramLen = Byte.parseByte(Integer.toString(algoParams.length));

            byte[] out = new byte[1 + paramLen + msg.length];
            // First byte = length of algo params
            out[0] = paramLen;
            // Next is algorithm params
            System.arraycopy(algoParams, 0, out, 1, paramLen);
            // Next is encrypted message
            System.arraycopy(msg, 0, out, paramLen + 1, msg.length);

            return out;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    private byte[] decryptV2(byte[] input) throws OpenStegoException {
        try {
            // First byte is algo params length
            byte paramLen = input[0];
            // Copy algorithm params
            byte[] algoParamData = new byte[paramLen];
            System.arraycopy(input, 1, algoParamData, 0, paramLen);
            // Copy encrypted message
            byte[] msg = new byte[input.length - paramLen - 1];
            System.arraycopy(input, paramLen + 1, msg, 0, msg.length);

            if (this.v3Capable) {
                // AES (PBES2) legacy format. Decrypt using portable primitives (PBKDF2-HMAC-SHA256 +
                // AES/CBC/PKCS5Padding) rather than the "PBEWithHmacSHA256AndAES_*" algorithm name,
                // which is not available on all platforms (e.g. Android). The salt and iteration
                // count are the fixed legacy values used by every v2 file; the 16-byte AES-CBC IV is
                // the trailing octet string of the stored PBES2 parameters.
                byte[] iv = Arrays.copyOfRange(algoParamData, algoParamData.length - 16, algoParamData.length);
                SecretKey key = deriveAesKeyPbkdf2(this.SALT, this.ITER_COUNT, this.v2KeyBits);
                Cipher decryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                decryptCipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
                return decryptCipher.doFinal(msg);
            }

            // Legacy DES (PBES1) - use the JCE algorithm name directly
            SecretKey secretKey = getV2SecretKey();
            AlgorithmParameters algoParams = AlgorithmParameters.getInstance(this.pbeAlgorithm);
            algoParams.init(algoParamData);
            Cipher decryptCipher = Cipher.getInstance(this.pbeAlgorithm);
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, algoParams);
            return decryptCipher.doFinal(msg);
        } catch (BadPaddingException bpEx) {
            throw new OpenStegoException(bpEx, OpenStego.NAMESPACE, OpenStegoErrors.INVALID_PASSWORD);
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    /**
     * Derives an AES key from the password using PBKDF2-HMAC-SHA256 (portable across JVM and Android),
     * wiping the transient key spec afterwards.
     */
    private SecretKey deriveAesKeyPbkdf2(byte[] salt, int iterations, int keyBits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(this.password.toCharArray(), salt, iterations, keyBits);
        byte[] keyBytes;
        try {
            keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        try {
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    // ------------- v3 (strong) implementation -------------

    private byte[] encryptV3(byte[] input) throws OpenStegoException {
        try {
            byte[] salt = new byte[V3_SALT_LEN];
            this.random.nextBytes(salt);
            byte[] iv = new byte[V3_IV_LEN];
            this.random.nextBytes(iv);

            SecretKey key = deriveV3Key(this.password, salt, V3_ITERATIONS, this.v3KeyBytes);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(V3_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(input);

            // Layout: marker, magic(2), version(1), iterations(4, big-endian), keyBytes(1),
            //         saltLen(1), salt, ivLen(1), iv, cipherText (includes GCM tag)
            int headerLen = 1 + V3_MAGIC.length + 1 + 4 + 1 + 1 + salt.length + 1 + iv.length;
            byte[] out = new byte[headerLen + cipherText.length];
            int idx = 0;
            out[idx++] = V3_MARKER;
            System.arraycopy(V3_MAGIC, 0, out, idx, V3_MAGIC.length);
            idx += V3_MAGIC.length;
            out[idx++] = V3_FORMAT_VERSION;
            out[idx++] = (byte) ((V3_ITERATIONS >> 24) & 0xFF);
            out[idx++] = (byte) ((V3_ITERATIONS >> 16) & 0xFF);
            out[idx++] = (byte) ((V3_ITERATIONS >> 8) & 0xFF);
            out[idx++] = (byte) (V3_ITERATIONS & 0xFF);
            out[idx++] = (byte) this.v3KeyBytes;
            out[idx++] = (byte) salt.length;
            System.arraycopy(salt, 0, out, idx, salt.length);
            idx += salt.length;
            out[idx++] = (byte) iv.length;
            System.arraycopy(iv, 0, out, idx, iv.length);
            idx += iv.length;
            System.arraycopy(cipherText, 0, out, idx, cipherText.length);

            return out;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    private byte[] decryptV3(byte[] input) throws OpenStegoException {
        try {
            int idx = 1 + V3_MAGIC.length; // skip marker + magic
            byte version = input[idx++];
            if (version != V3_FORMAT_VERSION) {
                throw new OpenStegoException(new IllegalArgumentException("Unsupported v3 crypto format version: " + version));
            }
            int iterations = ((input[idx++] & 0xFF) << 24) | ((input[idx++] & 0xFF) << 16)
                    | ((input[idx++] & 0xFF) << 8) | (input[idx++] & 0xFF);
            int keyBytes = input[idx++] & 0xFF;
            int saltLen = input[idx++] & 0xFF;
            byte[] salt = new byte[saltLen];
            System.arraycopy(input, idx, salt, 0, saltLen);
            idx += saltLen;
            int ivLen = input[idx++] & 0xFF;
            byte[] iv = new byte[ivLen];
            System.arraycopy(input, idx, iv, 0, ivLen);
            idx += ivLen;
            byte[] cipherText = new byte[input.length - idx];
            System.arraycopy(input, idx, cipherText, 0, cipherText.length);

            SecretKey key = deriveV3Key(this.password, salt, iterations, keyBytes);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(V3_TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (AEADBadTagException tagEx) {
            // Authentication failure - almost always an incorrect password
            throw new OpenStegoException(tagEx, OpenStego.NAMESPACE, OpenStegoErrors.INVALID_PASSWORD);
        } catch (OpenStegoException osEx) {
            throw osEx;
        } catch (Exception ex) {
            throw new OpenStegoException(ex);
        }
    }

    private static SecretKey deriveV3Key(String password, byte[] salt, int iterations, int keyBytes) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBytes * 8);
        byte[] keyData;
        try {
            keyData = factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        try {
            return new SecretKeySpec(keyData, "AES");
        } finally {
            Arrays.fill(keyData, (byte) 0);
        }
    }
}
