/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.jpeguniward;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.ui.OpenStegoFrame;
import com.openstego.desktop.ui.PluginEmbedOptionsUI;
import com.openstego.desktop.util.LabelUtil;
import java.awt.*;
import javax.swing.*;

/**
 * GUI options panel for the SI-UNIWARD (JpegUniward) plugin.
 * <p>
 * Exposes the JPEG output quality, which is the one meaningful knob for this
 * algorithm: it controls the quantization tables used during compression and
 * thus the capacity/quality trade-off of the produced stego JPEG.
 */
@SuppressWarnings("unused")
public class JpegUniwardEmbedOptionsUI extends PluginEmbedOptionsUI {
    private static final long serialVersionUID = 4521964214875126093L;

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(JpegUniwardPlugin.NAMESPACE);

    /**
     * Slider for the JPEG output quality (50-100)
     */
    private final JSlider qualitySlider;

    /**
     * Label that mirrors the current slider value
     */
    private final JLabel qualityValueLabel;

    /**
     * Reference to the parent UI object
     */
    private final OpenStegoFrame stegoUI;

    /**
     * Default constructor
     *
     * @param stegoUI Reference to the parent UI object
     */
    public JpegUniwardEmbedOptionsUI(OpenStegoFrame stegoUI) {
        this.stegoUI = stegoUI;

        setLayout(new GridBagLayout());

        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.weightx = 0.5;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.insets = new Insets(5, 5, 5, 5);
        add(new JLabel(labelUtil.getString("gui.label.option.quality")), gridBagConstraints);

        this.qualitySlider = new JSlider(50, 100, 90);
        this.qualitySlider.setMajorTickSpacing(10);
        this.qualitySlider.setPaintTicks(true);
        this.qualityValueLabel = new JLabel(String.valueOf(this.qualitySlider.getValue()));
        this.qualitySlider.addChangeListener(
                e -> this.qualityValueLabel.setText(String.valueOf(this.qualitySlider.getValue())));

        gridBagConstraints.gridx = 1;
        gridBagConstraints.weightx = 1.0;
        add(this.qualitySlider, gridBagConstraints);

        gridBagConstraints.gridx = 2;
        gridBagConstraints.weightx = 0.0;
        add(this.qualityValueLabel, gridBagConstraints);
    }

    /**
     * Initialize the UI
     */
    @Override
    public void initialize() {
        // Nothing extra to initialize
    }

    /**
     * Method to validate plugin options for "Embed" action
     *
     * @return Boolean indicating whether validation was successful or not
     */
    @Override
    public boolean validateEmbedAction() {
        return true;
    }

    /**
     * Method to populate the plugin GUI options based on the config data
     *
     * @param config OpenStego configuration data
     */
    @Override
    public void setGUIFromConfig(OpenStegoConfig config) {
        this.qualitySlider.setValue(((JpegUniwardConfig) config).getQuality());
    }

    /**
     * Method to populate the config object based on the GUI data
     *
     * @param config OpenStego configuration data
     */
    @Override
    public void setConfigFromGUI(OpenStegoConfig config) {
        ((JpegUniwardConfig) config).setQuality(this.qualitySlider.getValue());
    }
}
