/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import com.openstego.desktop.util.LabelUtil;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Abstract class for stego plugins for OpenStego. Abstract methods need to be implemented to add support for more
 * steganographic algorithms
 *
 * @param <C> Config class for the plugin
 */
public abstract class OpenStegoPlugin<C extends OpenStegoConfig> {
    /**
     * Enumeration of plugin purposes
     */
    public enum Purpose {
        /**
         * Purpose - data hiding
         */
        DATA_HIDING,

        /**
         * Purpose - watermarking
         */
        WATERMARKING
    }

    /**
     * Configuration data to be used while embedding / extracting data
     */
    protected C config = null;

    /**
     * Optional listener notified of completion progress during long-running operations. May be
     * {@code null} (the default), in which case no progress is reported.
     */
    protected ProgressListener progressListener = null;

    /**
     * Registers a listener to receive completion progress during embedding / extraction /
     * watermarking. Pass {@code null} to disable reporting.
     *
     * @param listener progress listener, or {@code null}
     */
    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Reports a completion fraction to the registered {@link ProgressListener}, if any. The fraction
     * is clamped to {@code [0.0, 1.0]}. Safe to call when no listener is set (no-op).
     *
     * @param fraction completion ratio (will be clamped to {@code [0.0, 1.0]})
     */
    protected void reportProgress(double fraction) {
        ProgressListener listener = this.progressListener;
        if (listener != null) {
            double f = fraction < 0.0 ? 0.0 : (fraction > 1.0 ? 1.0 : fraction);
            listener.onProgress(f);
        }
    }

    // ------------- Metadata Methods -------------

    /**
     * Gives the name of the plugin
     *
     * @return Name of the plugin
     */
    public abstract String getName();

    /**
     * Gives the purpose(s) of the plugin
     *
     * @return Purpose(s) of the plugin
     */
    public abstract List<Purpose> getPurposes();

    /**
     * Gives a short description of the plugin
     *
     * @return Short description of the plugin
     */
    public abstract String getDescription();

    /**
     * Gives the display label for purpose(s) of the plugin
     *
     * @return Display lable for purpose(s) of the plugin
     */
    public final String getPurposesLabel() {
        StringBuilder sbf = new StringBuilder();
        LabelUtil labelUtil = LabelUtil.getInstance(OpenStego.NAMESPACE);
        List<Purpose> purposes = getPurposes();

        if (purposes == null || purposes.size() == 0) {
            return "";
        }

        sbf.append("(").append(labelUtil.getString("cmd.label.purpose.caption")).append(" ");
        for (int i = 0; i < purposes.size(); i++) {
            if (i > 0) {
                sbf.append(", ");
            }
            sbf.append(labelUtil.getString("cmd.label.purpose." + purposes.get(i)));
        }
        sbf.append(")");

        return sbf.toString();
    }

    // ------------- Core Stego Methods -------------

    /**
     * Method to embed the message into the cover data
     *
     * @param msg           Message to be embedded
     * @param msgFileName   Name of the message file. If this value is provided, then the filename should be embedded in
     *                      the cover data
     * @param cover         Cover data into which message needs to be embedded
     * @param coverFileName Name of the cover file
     * @param stegoFileName Name of the output stego file
     * @return Stego data containing the message
     * @throws OpenStegoException Processing issues
     */
    public abstract byte[] embedData(
            byte[] msg, String msgFileName, byte[] cover, String coverFileName, String stegoFileName)
            throws OpenStegoException;

    /**
     * Method to extract the message file name from the stego data
     *
     * @param stegoData     Stego data containing the message
     * @param stegoFileName Name of the stego file
     * @return Message file name
     * @throws OpenStegoException Processing issues
     */
    public abstract String extractMsgFileName(byte[] stegoData, String stegoFileName) throws OpenStegoException;

    /**
     * Method to extract the message from the stego data
     *
     * @param stegoData     Stego data containing the message
     * @param stegoFileName Name of the stego file
     * @param origSigData   Optional signature data file for watermark
     * @return Extracted message
     * @throws OpenStegoException Processing issues
     */
    public abstract byte[] extractData(byte[] stegoData, String stegoFileName, byte[] origSigData)
            throws OpenStegoException;

    /**
     * Whether this plugin could plausibly decode the given stego bytes, judged solely by the container
     * magic bytes (not by the embedded payload, which may be password-protected). Auto-detecting
     * extraction uses this to skip plugins that physically cannot read the container - e.g. the WAV
     * plugin should not be tried against a PNG - so the error that surfaces on failure reflects the
     * right format family rather than whichever plugin happened to be tried last.
     *
     * <p>The default accepts everything; format-specific plugins override it to gate on
     * {@link com.openstego.desktop.util.ContainerType}.
     *
     * @param stegoData Stego file bytes
     * @return {@code true} if this plugin should be attempted for the given bytes
     */
    public boolean canExtractFrom(byte[] stegoData) {
        return true;
    }

    /**
     * Method to generate the signature data. This method needs to be implemented only if the purpose of the plugin is
     * Watermarking
     *
     * @return Signature data
     * @throws OpenStegoException Processing issues
     */
    public abstract byte[] generateSignature() throws OpenStegoException;

    /**
     * Method to check the correlation for the given image and the original signature
     *
     * @param stegoData     Stego data containing the watermark
     * @param stegoFileName Name of the stego file
     * @param origSigData   Original signature data
     * @return Correlation
     * @throws OpenStegoException Processing issues
     */
    public double checkMark(byte[] stegoData, String stegoFileName, byte[] origSigData) throws OpenStegoException {
        return getWatermarkCorrelation(origSigData, extractData(stegoData, stegoFileName, origSigData));
    }

    /**
     * Method to check the correlation between original signature and the extracted watermark
     *
     * @param origSigData   Original signature data
     * @param watermarkData Extracted watermark data
     * @return Correlation
     * @throws OpenStegoException Processing issues
     */
    public abstract double getWatermarkCorrelation(byte[] origSigData, byte[] watermarkData) throws OpenStegoException;

    /**
     * Method to get correlation value which above which it can be considered that watermark strength is high
     *
     * @return High watermark
     * @throws OpenStegoException Processing issues
     */
    public abstract double getHighWatermarkLevel() throws OpenStegoException;

    /**
     * Method to get correlation value which below which it can be considered that watermark strength is low
     *
     * @return Low watermark
     * @throws OpenStegoException Processing issues
     */
    public abstract double getLowWatermarkLevel() throws OpenStegoException;

    /**
     * Method to get difference between original cover file and the stegged file
     *
     * @param stegoData     Stego data containing the embedded data
     * @param stegoFileName Name of the stego file
     * @param coverData     Original cover data
     * @param coverFileName Name of the cover file
     * @param diffFileName  Name of the output difference file
     * @return Difference data
     * @throws OpenStegoException Processing issues
     */
    public abstract byte[] getDiff(
            byte[] stegoData, String stegoFileName, byte[] coverData, String coverFileName, String diffFileName)
            throws OpenStegoException;

    /**
     * Method to get the list of supported file extensions for reading
     *
     * @return List of supported file extensions for reading
     * @throws OpenStegoException Processing issues
     */
    public abstract List<String> getReadableFileExtensions() throws OpenStegoException;

    /**
     * Method to get the list of supported file extensions for writing
     *
     * @return List of supported file extensions for writing
     * @throws OpenStegoException Processing issues
     */
    public abstract List<String> getWritableFileExtensions() throws OpenStegoException;

    // ------------- Command-line Related Methods -------------

    /**
     * Method to declare the plugin-specific command-line options. The command-line layer uses these
     * neutral descriptors to build its parser, keeping any specific parsing library out of the plugin SPI.
     * Plugins with no extra options need not override this.
     *
     * @return List of plugin-specific command-line option descriptors (empty by default)
     */
    public List<PluginCmdLineOption> getPluginCmdLineOptions() {
        return Collections.emptyList();
    }

    /**
     * Method to translate parsed values of the plugin-specific command-line options into configuration
     * items. Implementations should add typed entries to {@code configMap} for the values they recognize.
     * Plugins with no extra options need not override this.
     *
     * @param configMap    Configuration map to populate (consumed by {@link OpenStegoConfig#initialize(Map)})
     * @param parsedValues Parsed command-line values keyed by option name (e.g. "-b")
     * @throws OpenStegoException Processing issues
     */
    public void addPluginConfigValues(Map<String, Object> configMap, Map<String, String> parsedValues)
            throws OpenStegoException {
        // No plugin-specific options by default
    }

    /**
     * Method to get the usage details of the plugin
     *
     * @return Usage details of the plugin
     * @throws OpenStegoException Processing issues
     */
    public abstract String getUsage() throws OpenStegoException;

    // ------------- Config Related Methods -------------

    /**
     * Method to get current configuration data
     *
     * @return Configuration data
     */
    public C getConfig() {
        return this.config;
    }

    /**
     * Method to reset configuration data to default
     *
     * @throws OpenStegoException Processing issues
     */
    public void resetConfig() throws OpenStegoException {
        this.config = createConfig();
    }

    /**
     * Method to create default configuration data (specific to this plugin)
     *
     * @return Configuration data
     * @throws OpenStegoException Processing issues
     */
    protected abstract C createConfig() throws OpenStegoException;
}
