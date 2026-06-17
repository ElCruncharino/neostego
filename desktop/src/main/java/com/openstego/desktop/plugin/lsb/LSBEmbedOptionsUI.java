/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.plugin.lsb;

import com.openstego.desktop.OpenStegoConfig;
import com.openstego.desktop.ui.OpenStegoFrame;
import com.openstego.desktop.ui.PluginEmbedOptionsUI;
import com.openstego.desktop.util.LabelUtil;
import java.awt.*;
import javax.swing.*;
import javax.swing.event.ChangeListener;

/**
 * GUI class for the LSB Plugin
 */
@SuppressWarnings("unused")
public class LSBEmbedOptionsUI extends PluginEmbedOptionsUI {
    private static final long serialVersionUID = 6168148599483165215L;

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(LSBPlugin.NAMESPACE);

    /**
     * "Random Image as Source" checkbox
     */
    private final JCheckBox randomImgCheckBox = new JCheckBox();

    /**
     * Toggle that shows/hides the advanced options
     */
    private final JToggleButton advancedToggle;

    /**
     * Label for "Max Bits Per Color Channel" (hidden until Advanced is expanded)
     */
    private final JLabel maxBitsLabel;

    /**
     * Combobox for "Max Bits Per Color Channel"
     */
    private final JComboBox<Integer> maxBitsComboBox;

    /**
     * Reference to the parent OpenStegoUI object
     */
    private final OpenStegoFrame stegoUI;

    /**
     * Default constructor
     *
     * @param stegoUI Reference to the parent UI object
     */
    public LSBEmbedOptionsUI(OpenStegoFrame stegoUI) {
        this.stegoUI = stegoUI;

        GridBagConstraints gridBagConstraints;
        JLabel label;
        Integer[] maxBitsList = new Integer[8];

        setLayout(new GridBagLayout());

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.weightx = 0.5;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.insets = new Insets(5, 5, 5, 5);

        gridBagConstraints.gridy = 0;
        label = new JLabel(labelUtil.getString("gui.label.option.useRandomImage"));
        add(label, gridBagConstraints);

        // Advanced toggle on its own row; the bits/channel control below it stays hidden until expanded
        gridBagConstraints.gridy = 1;
        this.advancedToggle = new JToggleButton(labelUtil.getString("gui.label.option.advanced"));
        add(this.advancedToggle, gridBagConstraints);

        gridBagConstraints.gridy = 2;
        this.maxBitsLabel = new JLabel(labelUtil.getString("gui.label.option.maxBitsPerChannel"));
        add(this.maxBitsLabel, gridBagConstraints);

        gridBagConstraints.gridx = 1;
        gridBagConstraints.weightx = 1.0;

        gridBagConstraints.gridy = 0;
        add(this.randomImgCheckBox, gridBagConstraints);

        gridBagConstraints.gridy = 2;
        for (int i = 0; i < 8; i++) {
            maxBitsList[i] = i + 1;
        }
        this.maxBitsComboBox = new JComboBox<>(maxBitsList);
        this.maxBitsComboBox.setPreferredSize(new Dimension(40, 20));
        add(this.maxBitsComboBox, gridBagConstraints);

        ChangeListener changeListener = changeEvent -> useRandomImgChanged();
        this.randomImgCheckBox.addChangeListener(changeListener);
        this.advancedToggle.addActionListener(e -> advancedChanged());
    }

    /**
     * Initialize the UI
     */
    @Override
    public void initialize() {
        useRandomImgChanged();
        advancedChanged();
    }

    /**
     * Shows or hides the advanced bits/channel control based on the toggle state.
     */
    private void advancedChanged() {
        boolean shown = this.advancedToggle.isSelected();
        this.maxBitsLabel.setVisible(shown);
        this.maxBitsComboBox.setVisible(shown);
        revalidate();
        repaint();
    }

    /**
     * Method to handle change event for 'randomImage'
     */
    private void useRandomImgChanged() {
        JTextField coverFileTextField = this.stegoUI.getEmbedPanel().getCoverFileTextField();
        JButton coverFileButton = this.stegoUI.getEmbedPanel().getCoverFileButton();

        if (this.randomImgCheckBox.isSelected()) {
            setEnabled(coverFileTextField, false);
            coverFileTextField.setText("");
            coverFileButton.setEnabled(false);
        } else {
            setEnabled(coverFileTextField, true);
            coverFileButton.setEnabled(true);
            coverFileTextField.requestFocus();
        }
    }

    /**
     * Method to enable/disable a Swing JTextField object
     *
     * @param textField Swing JTextField object
     * @param enabled   Flag to indicate whether to enable or disable the object
     */
    private static void setEnabled(JTextField textField, boolean enabled) {
        textField.setEnabled(enabled);
        textField.setBackground(enabled ? java.awt.Color.WHITE : javax.swing.UIManager.getColor("Panel.background"));
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
        this.maxBitsComboBox.setSelectedItem(((LSBConfig) config).getMaxBitsUsedPerChannel());
    }

    /**
     * Method to populate the config object based on the GUI data
     *
     * @param config OpenStego configuration data
     */
    @Override
    public void setConfigFromGUI(OpenStegoConfig config) {
        Integer maxBits = (Integer) this.maxBitsComboBox.getSelectedItem();
        if (maxBits != null) {
            ((LSBConfig) config).setMaxBitsUsedPerChannel(maxBits);
        }
    }
}
