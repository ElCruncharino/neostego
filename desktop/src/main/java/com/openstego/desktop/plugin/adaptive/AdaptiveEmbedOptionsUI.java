/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.adaptive;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.ui.OpenStegoFrame;
import com.openstego.desktop.ui.PluginEmbedOptionsUI;
import com.openstego.desktop.util.LabelUtil;
import java.awt.*;
import javax.swing.*;

/**
 * GUI options panel for the content-adaptive (HILL+STC) plugin.
 * <p>
 * The two knobs &mdash; CMD (Clustering of Modification Directions) and its
 * strength mu &mdash; are power-user tuning that the defaults already handle
 * well, so they live under a collapsible Advanced toggle.
 */
@SuppressWarnings("unused")
public class AdaptiveEmbedOptionsUI extends PluginEmbedOptionsUI {
    private static final long serialVersionUID = 8233954672143905181L;

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(AdaptiveImagePlugin.NAMESPACE);

    /**
     * Toggle that shows/hides the advanced options
     */
    private final JToggleButton advancedToggle;

    /**
     * "Cluster changes (CMD)" checkbox
     */
    private final JCheckBox cmdCheckBox = new JCheckBox();

    /**
     * Label for the CMD checkbox
     */
    private final JLabel cmdLabel;

    /**
     * Label for the mu spinner
     */
    private final JLabel cmdMuLabel;

    /**
     * Spinner for the CMD clustering strength (mu)
     */
    private final JSpinner cmdMuSpinner;

    /**
     * Reference to the parent UI object
     */
    private final OpenStegoFrame stegoUI;

    /**
     * Default constructor
     *
     * @param stegoUI Reference to the parent UI object
     */
    public AdaptiveEmbedOptionsUI(OpenStegoFrame stegoUI) {
        this.stegoUI = stegoUI;

        setLayout(new GridBagLayout());

        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.weightx = 0.5;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.insets = new Insets(5, 5, 5, 5);

        gridBagConstraints.gridy = 0;
        this.advancedToggle = new JToggleButton(labelUtil.getString("gui.label.option.advanced"));
        add(this.advancedToggle, gridBagConstraints);

        gridBagConstraints.gridy = 1;
        this.cmdLabel = new JLabel(labelUtil.getString("gui.label.option.cmd"));
        add(this.cmdLabel, gridBagConstraints);

        gridBagConstraints.gridy = 2;
        this.cmdMuLabel = new JLabel(labelUtil.getString("gui.label.option.cmdMu"));
        add(this.cmdMuLabel, gridBagConstraints);

        gridBagConstraints.gridx = 1;
        gridBagConstraints.weightx = 1.0;

        gridBagConstraints.gridy = 1;
        this.cmdCheckBox.setSelected(true);
        add(this.cmdCheckBox, gridBagConstraints);

        gridBagConstraints.gridy = 2;
        this.cmdMuSpinner = new JSpinner(new SpinnerNumberModel(3.0, 1.0, 9.0, 0.5));
        this.cmdMuSpinner.setPreferredSize(new Dimension(60, 20));
        add(this.cmdMuSpinner, gridBagConstraints);

        this.advancedToggle.addActionListener(e -> advancedChanged());
    }

    /**
     * Initialize the UI
     */
    @Override
    public void initialize() {
        advancedChanged();
    }

    /**
     * Shows or hides the advanced CMD controls based on the toggle state.
     */
    private void advancedChanged() {
        boolean shown = this.advancedToggle.isSelected();
        this.cmdLabel.setVisible(shown);
        this.cmdCheckBox.setVisible(shown);
        this.cmdMuLabel.setVisible(shown);
        this.cmdMuSpinner.setVisible(shown);
        revalidate();
        repaint();
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
        AdaptiveConfig adaptiveConfig = (AdaptiveConfig) config;
        this.cmdCheckBox.setSelected(adaptiveConfig.isCmd());
        this.cmdMuSpinner.setValue(adaptiveConfig.getCmdMu());
    }

    /**
     * Method to populate the config object based on the GUI data
     *
     * @param config OpenStego configuration data
     */
    @Override
    public void setConfigFromGUI(OpenStegoConfig config) {
        AdaptiveConfig adaptiveConfig = (AdaptiveConfig) config;
        adaptiveConfig.setCmd(this.cmdCheckBox.isSelected());
        adaptiveConfig.setCmdMu(((Number) this.cmdMuSpinner.getValue()).doubleValue());
    }
}
