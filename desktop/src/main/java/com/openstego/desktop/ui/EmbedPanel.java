/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */
package com.openstego.desktop.ui;

import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoCrypto;
import com.openstego.desktop.util.LabelUtil;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;

/**
 * Panel for "Embed"
 */
public class EmbedPanel extends JPanel {
    private static final long serialVersionUID = 5812035848879719995L;

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(OpenStego.NAMESPACE);

    private JPanel optionPanel;
    private JComboBox<String> algorithmComboBox;
    private JTextField msgFileTextField;
    private JButton msgFileButton;
    private JTextField coverFileTextField;
    private JButton coverFileButton;
    private JTextField stegoFileTextField;
    private JComboBox<String> stegoExtComboBox;
    private JButton stegoFileButton;
    private JComboBox<String> encryptionAlgoComboBox;
    private JPasswordField passwordTextField;
    private JPasswordField confPasswordTextField;
    private JCheckBox splitCheckBox;
    private JButton runEmbedButton;

    /**
     * Container holding the (swappable) plugin-specific options panel
     */
    private JPanel pluginOptionContainer;

    /**
     * The currently displayed plugin-specific options panel ({@code null} if the selected
     * algorithm exposes no options)
     */
    private PluginEmbedOptionsUI pluginOptionPanel;

    /**
     * Default constructor
     */
    public EmbedPanel() {
        super();
    }

    /**
     * Getter method for optionPanel
     *
     * @return optionPanel
     */
    public JPanel getOptionPanel() {
        if (this.optionPanel == null) {
            JLabel label;
            this.optionPanel = new JPanel();
            this.optionPanel.setBorder(new TitledBorder(
                    new CompoundBorder(new EmptyBorder(new java.awt.Insets(5, 5, 5, 5)), new EtchedBorder()),
                    " " + labelUtil.getString("gui.label.dhEmbed.option.title") + " "));
            this.optionPanel.setLayout(new GridBagLayout());

            GridBagConstraints gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 0;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            label = new JLabel(labelUtil.getString("gui.label.dhEmbed.option.algorithm"));
            label.setLabelFor(getAlgorithmComboBox());
            this.optionPanel.add(label, gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 0;
            gridBagConstraints.insets = new Insets(5, 5, 5, 30);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            this.optionPanel.add(getAlgorithmComboBox(), gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 1;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            label = new JLabel(labelUtil.getString("gui.label.dhEmbed.option.cryptalgo"));
            label.setLabelFor(getEncryptionAlgoComboBox());
            this.optionPanel.add(label, gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 1;
            gridBagConstraints.insets = new Insets(5, 5, 5, 30);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            this.optionPanel.add(getEncryptionAlgoComboBox(), gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 2;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            label = new JLabel(labelUtil.getString("gui.label.dhEmbed.option.password"));
            label.setLabelFor(getPasswordTextField());
            this.optionPanel.add(label, gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 2;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            this.optionPanel.add(getPasswordTextField(), gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 0;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            label = new JLabel(labelUtil.getString("gui.label.dhEmbed.option.confPassword"));
            label.setLabelFor(getConfPasswordTextField());
            this.optionPanel.add(label, gridBagConstraints);

            gridBagConstraints = new GridBagConstraints();
            gridBagConstraints.fill = GridBagConstraints.BOTH;
            gridBagConstraints.gridx = 1;
            gridBagConstraints.gridy = 3;
            gridBagConstraints.insets = new Insets(5, 5, 5, 5);
            gridBagConstraints.weightx = 1.0;
            gridBagConstraints.weighty = 0.0;
            this.optionPanel.add(getConfPasswordTextField(), gridBagConstraints);
        }
        return this.optionPanel;
    }

    /**
     * Get method for "Algorithm" combo box. The list of algorithms is populated by the controller
     * ({@link OpenStegoUI}); this panel only owns the widget.
     *
     * @return algorithmComboBox
     */
    public JComboBox<String> getAlgorithmComboBox() {
        if (this.algorithmComboBox == null) {
            this.algorithmComboBox = new JComboBox<>();
        }
        return this.algorithmComboBox;
    }

    /**
     * Get method for the container that holds the swappable plugin-specific options panel.
     *
     * @return pluginOptionContainer
     */
    public JPanel getPluginOptionContainer() {
        if (this.pluginOptionContainer == null) {
            this.pluginOptionContainer = new JPanel(new BorderLayout());
        }
        return this.pluginOptionContainer;
    }

    /**
     * Swaps the plugin-specific options panel shown below the common options. Passing {@code null}
     * clears the area (for algorithms that expose no options).
     *
     * @param panel The new options panel, or {@code null}
     */
    public void setPluginOptionPanel(PluginEmbedOptionsUI panel) {
        this.pluginOptionPanel = panel;
        getPluginOptionContainer().removeAll();
        if (panel != null) {
            panel.setBorder(new TitledBorder(
                    new CompoundBorder(new EmptyBorder(new Insets(5, 5, 5, 5)), new EtchedBorder()),
                    " " + labelUtil.getString("gui.label.dhEmbed.pluginOption.title") + " "));
            getPluginOptionContainer().add(panel, BorderLayout.CENTER);
            panel.initialize();
        }
        getPluginOptionContainer().revalidate();
        getPluginOptionContainer().repaint();
    }

    /**
     * Getter method for pluginOptionPanel
     *
     * @return pluginOptionPanel
     */
    public PluginEmbedOptionsUI getPluginOptionPanel() {
        return this.pluginOptionPanel;
    }

    /**
     * Get method for "Message File" text field
     *
     * @return msgFileTextField
     */
    public JTextField getMsgFileTextField() {
        if (this.msgFileTextField == null) {
            this.msgFileTextField = new JTextField();
            this.msgFileTextField.setColumns(OpenStegoFrame.TEXTFIELD_SIZE);
        }
        return this.msgFileTextField;
    }

    /**
     * Get method for "Message File" browse file button
     *
     * @return msgFileButton
     */
    public JButton getMsgFileButton() {
        if (this.msgFileButton == null) {
            this.msgFileButton = new JButton();
            this.msgFileButton.setText("...");
            String acc = labelUtil.getString("gui.acc.browse.msgFile");
            this.msgFileButton.setToolTipText(acc);
            this.msgFileButton.getAccessibleContext().setAccessibleName(acc);
        }
        return this.msgFileButton;
    }

    /**
     * Get method for "Cover File" text field
     *
     * @return coverFileTextField
     */
    public JTextField getCoverFileTextField() {
        if (this.coverFileTextField == null) {
            this.coverFileTextField = new JTextField();
            this.coverFileTextField.setColumns(OpenStegoFrame.TEXTFIELD_SIZE);
        }
        return this.coverFileTextField;
    }

    /**
     * Get method for "Cover File" browse file button
     *
     * @return coverFileButton
     */
    public JButton getCoverFileButton() {
        if (this.coverFileButton == null) {
            this.coverFileButton = new JButton();
            this.coverFileButton.setText("...");
            String acc = labelUtil.getString("gui.acc.browse.coverFile");
            this.coverFileButton.setToolTipText(acc);
            this.coverFileButton.getAccessibleContext().setAccessibleName(acc);
        }
        return this.coverFileButton;
    }

    /**
     * Get method for "Stego File" text field
     *
     * @return stegoFileTextField
     */
    public JTextField getStegoFileTextField() {
        if (this.stegoFileTextField == null) {
            this.stegoFileTextField = new JTextField();
            this.stegoFileTextField.setColumns(OpenStegoFrame.TEXTFIELD_SIZE);
        }
        return this.stegoFileTextField;
    }

    /**
     * Get method for the output-stego file-extension chip. Shows the extension implied by the
     * selected algorithm (e.g. {@code .png}); becomes a selectable dropdown when the algorithm
     * supports multiple output formats. Populated and driven by {@link OpenStegoUI}.
     *
     * @return stegoExtComboBox
     */
    public JComboBox<String> getStegoExtComboBox() {
        if (this.stegoExtComboBox == null) {
            this.stegoExtComboBox = new JComboBox<>();
            this.stegoExtComboBox.setToolTipText(labelUtil.getString("gui.tooltip.dhEmbed.stegoExt"));
        }
        return this.stegoExtComboBox;
    }

    /**
     * Get method for "Stego File" browse file button
     *
     * @return stegoFileButton
     */
    public JButton getStegoFileButton() {
        if (this.stegoFileButton == null) {
            this.stegoFileButton = new JButton();
            this.stegoFileButton.setText("...");
            String acc = labelUtil.getString("gui.acc.browse.stegoFile");
            this.stegoFileButton.setToolTipText(acc);
            this.stegoFileButton.getAccessibleContext().setAccessibleName(acc);
        }
        return this.stegoFileButton;
    }

    /**
     * Get method for "Encryption Algorithm" combo box
     *
     * @return encryptionAlgoComboBox
     */
    public JComboBox<String> getEncryptionAlgoComboBox() {
        if (this.encryptionAlgoComboBox == null) {
            this.encryptionAlgoComboBox =
                    new JComboBox<>(new String[] {OpenStegoCrypto.ALGO_AES128, OpenStegoCrypto.ALGO_AES256});
        }
        return this.encryptionAlgoComboBox;
    }

    /**
     * Get method for "Password" text field
     *
     * @return passwordTextField
     */
    public JPasswordField getPasswordTextField() {
        if (this.passwordTextField == null) {
            this.passwordTextField = new JPasswordField();
            this.passwordTextField.setColumns(OpenStegoFrame.PWD_FIELD_SIZE);
            this.passwordTextField.setToolTipText(labelUtil.getString("gui.tooltip.password"));
        }
        return this.passwordTextField;
    }

    /**
     * Get method for "Confirm Password" text field
     *
     * @return confPasswordTextField
     */
    public JPasswordField getConfPasswordTextField() {
        if (this.confPasswordTextField == null) {
            this.confPasswordTextField = new JPasswordField();
            this.confPasswordTextField.setColumns(OpenStegoFrame.PWD_FIELD_SIZE);
            this.confPasswordTextField.setToolTipText(labelUtil.getString("gui.tooltip.confPassword"));
        }
        return this.confPasswordTextField;
    }

    /**
     * Get method for the "Split across covers" check box. When selected, one message is spread across
     * all the given cover files (upstream issue #67) instead of being embedded into each separately.
     *
     * @return splitCheckBox
     */
    public JCheckBox getSplitCheckBox() {
        if (this.splitCheckBox == null) {
            this.splitCheckBox = new JCheckBox(labelUtil.getString("gui.label.dhEmbed.split"));
            this.splitCheckBox.setToolTipText(labelUtil.getString("gui.tooltip.dhEmbed.split"));
        }
        return this.splitCheckBox;
    }

    /**
     * Get method for Embed "OK" button
     *
     * @return runEmbedButton
     */
    public JButton getRunEmbedButton() {
        if (this.runEmbedButton == null) {
            this.runEmbedButton = new JButton();
            this.runEmbedButton.setText(labelUtil.getString("gui.button.dhEmbed.run"));
        }
        return this.runEmbedButton;
    }

    public void initialize() {
        JLabel label;
        setLayout(new GridBagLayout());

        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.insets = new Insets(5, 5, 0, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        label = new JLabel(labelUtil.getString("gui.label.dhEmbed.msgFile"));
        label.setLabelFor(getMsgFileTextField());
        add(label, gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.insets = new Insets(5, 5, 0, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        label = new JLabel(labelUtil.getString("gui.label.dhEmbed.coverFile"));
        label.setLabelFor(getCoverFileTextField());
        add(label, gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.insets = new Insets(5, 5, 0, 5);
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        label = new JLabel(labelUtil.getString("gui.label.dhEmbed.stegoFile"));
        label.setLabelFor(getStegoFileTextField());
        add(label, gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.insets = new Insets(0, 5, 0, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        label = new JLabel(labelUtil.getString("gui.label.dhEmbed.coverFileMsg"));
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        add(label, gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.insets = new Insets(0, 5, 5, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getMsgFileTextField(), gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.insets = new Insets(0, 5, 5, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getCoverFileTextField(), gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.insets = new Insets(0, 5, 5, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getStegoFileTextField(), gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.NONE;
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.insets = new Insets(0, 0, 5, 5);
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        add(getMsgFileButton(), gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.NONE;
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.insets = new Insets(0, 0, 5, 5);
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        add(getCoverFileButton(), gridBagConstraints);

        // "Split across covers" toggle, sat next to the cover-file label
        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.NONE;
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.insets = new Insets(5, 0, 0, 5);
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        add(getSplitCheckBox(), gridBagConstraints);

        // Trailing controls for the output-stego row: the extension chip/dropdown sits directly
        // before the browse button so the implied output format is always visible.
        JPanel stegoTrailing = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        stegoTrailing.add(getStegoExtComboBox());
        stegoTrailing.add(getStegoFileButton());
        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.NONE;
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.insets = new Insets(0, 0, 5, 5);
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        add(stegoTrailing, gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getOptionPanel(), gridBagConstraints);

        // Swappable plugin-specific options live in a container that is always present; its contents
        // are replaced when the algorithm selection changes (see setPluginOptionPanel)
        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getPluginOptionContainer(), gridBagConstraints);

        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.EAST;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 10;
        gridBagConstraints.insets = new Insets(5, 5, 5, 5);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        add(getRunEmbedButton(), gridBagConstraints);

        // Dummy padding
        gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.anchor = GridBagConstraints.WEST;
        gridBagConstraints.fill = GridBagConstraints.HORIZONTAL;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 11;
        gridBagConstraints.insets = new Insets(0, 0, 0, 0);
        gridBagConstraints.weightx = 0.01;
        gridBagConstraints.weighty = 1.0;
        add(new JLabel(" "), gridBagConstraints);
    }
}
