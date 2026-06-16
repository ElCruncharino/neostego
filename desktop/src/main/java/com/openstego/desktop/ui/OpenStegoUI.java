/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */
package com.openstego.desktop.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.openstego.desktop.*;
import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.ImageUtil;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.PluginManager;
import com.openstego.desktop.util.UserPreferences;
import com.openstego.desktop.util.ui.WorkerTask;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the main class for OpenStego GUI and it implements the action and window listeners.
 */
public class OpenStegoUI extends OpenStegoFrame {
    private static final long serialVersionUID = -7485426167074985636L;

    /**
     * Logger instance
     */
    private static final Logger logger = Logger.getLogger(OpenStegoUI.class.getName());

    private static final int READ_EXTENSIONS = 1;
    private static final int WRITE_EXTENSIONS = 2;
    private static final String SIG_FILE_EXTENSION = ".sig";

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(OpenStego.NAMESPACE);

    /**
     * Static variable to holds path to last selected folder
     */
    private static String lastFolder = null;

    /**
     * All available data-hiding plugins, populating the Algorithm dropdown
     */
    private final List<OpenStegoPlugin<?>> dhPlugins;

    /**
     * The currently selected data-hiding plugin (driven by the Algorithm dropdown)
     */
    private OpenStegoPlugin<?> dhPlugin;
    private final OpenStegoPlugin<?> wmPlugin;

    /**
     * Guards programmatic updates of the output-stego extension chip so that repopulating it does
     * not fire the user-driven change listener.
     */
    private boolean syncingStegoExt = false;

    /**
     * Default constructor
     */
    public OpenStegoUI(OpenStegoPlugin<?> dhPlugin, OpenStegoPlugin<?> wmPlugin) {
        super(dhPlugin, wmPlugin);
        this.dhPlugin = dhPlugin;
        this.wmPlugin = wmPlugin;
        this.dhPlugins = PluginManager.getDataHidingPlugins();
        populateAlgorithmComboBox();
        resetGUI();

        // Render the brand mark from SVG at several sizes so the title-bar / taskbar icon stays crisp
        // on HiDPI displays (the OS picks the best-matching size).
        try {
            java.util.List<Image> icons = new ArrayList<>();
            for (int size : new int[]{16, 24, 32, 48, 64, 128}) {
                icons.add(new FlatSVGIcon("images/NeoStego.svg", size, size).getImage());
            }
            setIconImages(icons);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Could not load application icon", ex);
        }

        Listener listener = new Listener();
        addWindowListener(listener);

        getFileExitMenuItem().addActionListener(listener);
        getHelpAboutMenuItem().addActionListener(listener);

        getEmbedButton().addActionListener(listener);
        getExtractButton().addActionListener(listener);
        getGenSigButton().addActionListener(listener);
        getSignWmButton().addActionListener(listener);
        getVerifyWmButton().addActionListener(listener);

        getEmbedPanel().getMsgFileButton().addActionListener(listener);
        getEmbedPanel().getCoverFileButton().addActionListener(listener);
        getEmbedPanel().getStegoFileButton().addActionListener(listener);
        getEmbedPanel().getRunEmbedButton().addActionListener(listener);

        getExtractPanel().getInputStegoFileButton().addActionListener(listener);
        getExtractPanel().getOutputFolderButton().addActionListener(listener);
        getExtractPanel().getRunExtractButton().addActionListener(listener);

        getGenSigPanel().getSignatureFileButton().addActionListener(listener);
        getGenSigPanel().getRunGenSigButton().addActionListener(listener);

        getEmbedWmPanel().getFileForWmButton().addActionListener(listener);
        getEmbedWmPanel().getSignatureFileButton().addActionListener(listener);
        getEmbedWmPanel().getOutputWmFileButton().addActionListener(listener);
        getEmbedWmPanel().getRunEmbedWmButton().addActionListener(listener);

        getVerifyWmPanel().getInputFileButton().addActionListener(listener);
        getVerifyWmPanel().getSignatureFileButton().addActionListener(listener);
        getVerifyWmPanel().getRunVerifyWmButton().addActionListener(listener);

        // Enable drag-and-drop of files onto the file/folder fields
        installFileDrop(getEmbedPanel().getMsgFileTextField(), false);
        installFileDrop(getEmbedPanel().getCoverFileTextField(), true);
        installFileDrop(getEmbedPanel().getStegoFileTextField(), false);
        installFileDrop(getExtractPanel().getInputStegoFileTextField(), false);
        installFileDrop(getExtractPanel().getOutputFolderTextField(), false);
        installFileDrop(getGenSigPanel().getSignatureFileTextField(), false);
        installFileDrop(getEmbedWmPanel().getFileForWmTextField(), true);
        installFileDrop(getEmbedWmPanel().getSignatureFileTextField(), false);
        installFileDrop(getEmbedWmPanel().getOutputWmFileTextField(), false);
        installFileDrop(getVerifyWmPanel().getInputFileTextField(), true);
        installFileDrop(getVerifyWmPanel().getSignatureFileTextField(), false);

        // Auto-suggest an output file name from the chosen cover/input (browse, drop or typing),
        // only filling the field while it is still empty so the user's own input is never clobbered.
        getEmbedPanel().getCoverFileTextField().getDocument().addDocumentListener(onDocumentChange(this::maybeSuggestStegoOutput));
        getEmbedWmPanel().getFileForWmTextField().getDocument().addDocumentListener(onDocumentChange(this::maybeSuggestWmOutput));

        loadSettings();

        pack();
        // Make the embed action the default button so Enter triggers it on the initial panel
        getRootPane().setDefaultButton(getEmbedPanel().getRunEmbedButton());
        restoreWindowBounds();
    }

    /**
     * Populates the Algorithm dropdown with the available data-hiding plugins (friendly names),
     * selects the current default and wires the listener that swaps the per-plugin options panel
     * when the selection changes.
     */
    private void populateAlgorithmComboBox() {
        JComboBox<String> combo = getEmbedPanel().getAlgorithmComboBox();
        combo.removeAllItems();
        int selectedIndex = 0;
        for (int i = 0; i < this.dhPlugins.size(); i++) {
            OpenStegoPlugin<?> plugin = this.dhPlugins.get(i);
            combo.addItem(getAlgorithmDisplayName(plugin));
            if (plugin.getName().equals(this.dhPlugin.getName())) {
                selectedIndex = i;
            }
        }

        // Per-item hover tooltips: each row in the open dropdown shows that algorithm's detailed
        // description, so the trade-offs are visible before selecting.
        ListCellRenderer<? super String> base = combo.getRenderer();
        combo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            Component c = base.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (c instanceof JComponent && index >= 0 && index < this.dhPlugins.size()) {
                ((JComponent) c).setToolTipText(getAlgorithmDescription(this.dhPlugins.get(index)));
            }
            return c;
        });

        combo.setSelectedIndex(selectedIndex);
        updateAlgorithmTooltip();
        // Show the options panel for the initially selected plugin
        getEmbedPanel().setPluginOptionPanel(EmbedOptionsUIFactory.create(this.dhPlugin.getName(), this));

        // Populate the output-format chip and keep the output extension in sync when the user changes
        // the format (only meaningful for algorithms with more than one writable extension).
        refreshStegoExtensions();
        getEmbedPanel().getStegoExtComboBox().addActionListener(e -> {
            if (syncingStegoExt) {
                return;
            }
            String fileName = getEmbedPanel().getStegoFileTextField().getText();
            if (fileName == null || fileName.trim().isEmpty()) {
                return;
            }
            try {
                getEmbedPanel().getStegoFileTextField().setText(applyWritableExtension(fileName, this.dhPlugin, selectedStegoExt()));
            } catch (OpenStegoException ex) {
                handleException(ex);
            }
        });

        combo.addActionListener(e -> {
            int idx = combo.getSelectedIndex();
            if (idx < 0 || idx >= this.dhPlugins.size()) {
                return;
            }
            OpenStegoPlugin<?> selected = this.dhPlugins.get(idx);
            if (selected.getName().equals(this.dhPlugin.getName())) {
                return;
            }
            this.dhPlugin = selected;
            updateAlgorithmTooltip();
            getEmbedPanel().setPluginOptionPanel(EmbedOptionsUIFactory.create(selected.getName(), this));
            // Sync the new plugin's default config into its options panel, but preserve the user's
            // file selections; the output extension is rewritten to match the new algorithm.
            try {
                this.dhPlugin.resetConfig();
                if (getEmbedPanel().getPluginOptionPanel() != null) {
                    getEmbedPanel().getPluginOptionPanel().setGUIFromConfig(this.dhPlugin.getConfig());
                }
            } catch (OpenStegoException ex) {
                handleException(ex);
            }
            refreshStegoExtensions();
            pack();
        });
    }

    /**
     * Sets the Algorithm dropdown's own tooltip to the currently selected algorithm's description,
     * so hovering the closed combo box explains the active choice.
     */
    private void updateAlgorithmTooltip() {
        getEmbedPanel().getAlgorithmComboBox().setToolTipText(getAlgorithmDescription(this.dhPlugin));
    }

    /**
     * Returns the friendly, localized display name for a data-hiding plugin, falling back to the
     * plugin's internal name when no label is defined.
     *
     * @param plugin Plugin
     * @return Display name for the Algorithm dropdown
     */
    private String getAlgorithmDisplayName(OpenStegoPlugin<?> plugin) {
        try {
            String name = labelUtil.getString("gui.label.dhEmbed.algo." + plugin.getName());
            if (name != null && !name.trim().isEmpty() && !name.startsWith("!")) {
                return name;
            }
        } catch (Exception ignored) {
            // No label defined for this plugin; fall through to the internal name
        }
        return plugin.getName();
    }

    /**
     * Returns the detailed, localized HTML description for a data-hiding plugin (shown as a hover
     * tooltip on the Algorithm dropdown), or {@code null} when no description is defined.
     *
     * @param plugin Plugin
     * @return HTML description, or {@code null}
     */
    private String getAlgorithmDescription(OpenStegoPlugin<?> plugin) {
        try {
            String desc = labelUtil.getString("gui.tooltip.dhEmbed.algo." + plugin.getName());
            if (desc != null && !desc.trim().isEmpty() && !desc.startsWith("!")) {
                return desc;
            }
        } catch (Exception ignored) {
            // No description defined for this plugin
        }
        return null;
    }

    /**
     * Returns the lowercase extension of a file name (without the dot), or an empty string if it has
     * none. Only the last path segment is considered.
     */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotPos = fileName.lastIndexOf('.');
        int sepPos = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return (dotPos > sepPos && dotPos >= 0) ? fileName.substring(dotPos + 1).toLowerCase() : "";
    }

    /**
     * Ensures {@code fileName} ends with an output extension that is valid for {@code plugin}. When
     * {@code chosenExt} is supplied and supported it is forced; otherwise an already-valid extension
     * is kept and anything else is replaced with the plugin's default (first) writable extension, so
     * e.g. {@code photo.txt} becomes {@code photo.png} rather than {@code photo.txt.png}.
     *
     * @param fileName  Candidate output file name (may be {@code null}/blank)
     * @param plugin    Plugin whose writable extensions apply
     * @param chosenExt Preferred extension (without dot), or {@code null} to auto-select
     * @return File name with a valid output extension
     * @throws OpenStegoException Plugin error while querying writable extensions
     */
    private static String applyWritableExtension(String fileName, OpenStegoPlugin<?> plugin, String chosenExt) throws OpenStegoException {
        if (fileName == null || fileName.trim().isEmpty()) {
            return fileName;
        }
        List<String> exts = plugin.getWritableFileExtensions();
        if (exts.isEmpty()) {
            return fileName;
        }
        String current = extensionOf(fileName);
        String target;
        if (chosenExt != null && exts.contains(chosenExt.toLowerCase())) {
            target = chosenExt.toLowerCase();
        } else if (exts.contains(current)) {
            return fileName;
        } else {
            target = exts.get(0);
        }
        int dotPos = fileName.lastIndexOf('.');
        int sepPos = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = (dotPos > sepPos && dotPos >= 0) ? fileName.substring(0, dotPos) : fileName;
        return base + "." + target;
    }

    /**
     * Returns the extension (without dot) currently selected in the output-stego chip, falling back
     * to the data-hiding plugin's default writable extension.
     */
    private String selectedStegoExt() throws OpenStegoException {
        Object sel = getEmbedPanel().getStegoExtComboBox().getSelectedItem();
        if (sel != null) {
            String s = sel.toString();
            return s.startsWith(".") ? s.substring(1).toLowerCase() : s.toLowerCase();
        }
        List<String> exts = dhPlugin.getWritableFileExtensions();
        return exts.isEmpty() ? null : exts.get(0);
    }

    /**
     * Repopulates the output-stego extension chip from the current algorithm's writable extensions,
     * selecting the one matching the current output field (or the default), and rewrites the output
     * field's extension to stay consistent with the algorithm. The chip is enabled only when the
     * algorithm offers more than one output format.
     */
    private void refreshStegoExtensions() {
        JComboBox<String> extCombo = getEmbedPanel().getStegoExtComboBox();
        List<String> exts;
        try {
            exts = dhPlugin.getWritableFileExtensions();
        } catch (OpenStegoException e) {
            handleException(e);
            return;
        }

        syncingStegoExt = true;
        extCombo.removeAllItems();
        for (String ext : exts) {
            extCombo.addItem("." + ext);
        }
        String fileName = getEmbedPanel().getStegoFileTextField().getText();
        int sel = exts.indexOf(extensionOf(fileName));
        if (sel < 0) {
            sel = 0;
        }
        if (extCombo.getItemCount() > 0) {
            extCombo.setSelectedIndex(sel);
        }
        extCombo.setEnabled(exts.size() > 1);
        syncingStegoExt = false;

        // Keep an existing output name consistent with the selected algorithm/format
        if (fileName != null && !fileName.trim().isEmpty()) {
            try {
                getEmbedPanel().getStegoFileTextField().setText(applyWritableExtension(fileName, dhPlugin, selectedStegoExt()));
            } catch (OpenStegoException e) {
                handleException(e);
            }
        }
    }

    /**
     * Suggests an output-stego file name derived from a single chosen cover file
     * (e.g. {@code photo.png} &rarr; {@code photo-stego.png}) when the output field is still empty.
     * No-op for multi-cover selections (where the output is a directory) or when the user has
     * already entered an output name.
     */
    private void maybeSuggestStegoOutput() {
        JTextField stegoField = getEmbedPanel().getStegoFileTextField();
        if (!stegoField.getText().trim().isEmpty()) {
            return;
        }
        String cover = getEmbedPanel().getCoverFileTextField().getText().trim();
        if (cover.isEmpty()) {
            return;
        }
        List<File> covers = CommonUtil.parseFileList(cover, ";");
        if (covers.size() != 1) {
            return;
        }
        File c = covers.get(0);
        if (c.isDirectory()) {
            return;
        }
        try {
            stegoField.setText(deriveOutputName(c, "-stego", selectedStegoExt()));
        } catch (Exception ignored) {
            // Non-fatal: skip the suggestion
        }
    }

    /**
     * Builds an output file path next to {@code source}, appending {@code suffix} to its base name
     * and using {@code ext} as the extension.
     */
    private static String deriveOutputName(File source, String suffix, String ext) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String baseNoExt = (dot > 0) ? name.substring(0, dot) : name;
        String fileName = baseNoExt + suffix + (ext != null && !ext.isEmpty() ? "." + ext : "");
        File parent = source.getAbsoluteFile().getParentFile();
        return (parent != null) ? new File(parent, fileName).getAbsolutePath() : fileName;
    }

    /**
     * Suggests an output watermarked-file name derived from a single chosen input file
     * (e.g. {@code photo.png} &rarr; {@code photo-wm.png}) when the output field is still empty.
     */
    private void maybeSuggestWmOutput() {
        JTextField outField = getEmbedWmPanel().getOutputWmFileTextField();
        if (!outField.getText().trim().isEmpty()) {
            return;
        }
        String input = getEmbedWmPanel().getFileForWmTextField().getText().trim();
        if (input.isEmpty()) {
            return;
        }
        List<File> inputs = CommonUtil.parseFileList(input, ";");
        if (inputs.size() != 1) {
            return;
        }
        File f = inputs.get(0);
        if (f.isDirectory()) {
            return;
        }
        try {
            String ext = wmPlugin.getWritableFileExtensions().isEmpty() ? extensionOf(f.getName())
                    : wmPlugin.getWritableFileExtensions().get(0);
            outField.setText(deriveOutputName(f, "-wm", ext));
        } catch (Exception ignored) {
            // Non-fatal: skip the suggestion
        }
    }

    /**
     * Creates a {@link javax.swing.event.DocumentListener} that runs the given action on any change.
     */
    private static javax.swing.event.DocumentListener onDocumentChange(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }
        };
    }

    /**
     * Loads persisted settings (last folder and encryption algorithm) from user preferences.
     */
    private void loadSettings() {
        String folder = UserPreferences.getString("gui.lastFolder");
        if (folder != null && !folder.isEmpty()) {
            lastFolder = folder;
        }
        String algo = UserPreferences.getString("gui.encryptionAlgorithm");
        if (algo != null && !algo.isEmpty()) {
            getEmbedPanel().getEncryptionAlgoComboBox().setSelectedItem(algo);
        }
    }

    /**
     * Restores the saved window size and position (if any), keeping the window on-screen and not
     * smaller than its packed size. Falls back to centering on screen.
     */
    private void restoreWindowBounds() {
        Dimension packed = getSize();
        setMinimumSize(packed);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        try {
            Integer w = UserPreferences.getInteger("gui.window.width");
            Integer h = UserPreferences.getInteger("gui.window.height");
            Integer x = UserPreferences.getInteger("gui.window.x");
            Integer y = UserPreferences.getInteger("gui.window.y");
            if (w != null && h != null) {
                setSize(Math.max(w, packed.width), Math.max(h, packed.height));
            }
            if (x != null && y != null && x > -50 && y > -50 && x < screen.width - 50 && y < screen.height - 50) {
                setLocation(x, y);
                return;
            }
        } catch (OpenStegoException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
        setLocation(screen.width / 2 - (getWidth() / 2), screen.height / 2 - (getHeight() / 2));
    }

    /**
     * Persists settings (last folder, encryption algorithm and window geometry) to user preferences.
     */
    private void saveSettings() {
        try {
            if (lastFolder != null) {
                UserPreferences.put("gui.lastFolder", lastFolder);
            }
            Object algo = getEmbedPanel().getEncryptionAlgoComboBox().getSelectedItem();
            if (algo != null) {
                UserPreferences.put("gui.encryptionAlgorithm", algo.toString());
            }
            UserPreferences.put("gui.window.width", Integer.toString(getWidth()));
            UserPreferences.put("gui.window.height", Integer.toString(getHeight()));
            UserPreferences.put("gui.window.x", Integer.toString(getX()));
            UserPreferences.put("gui.window.y", Integer.toString(getY()));
            UserPreferences.save();
        } catch (OpenStegoException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    /**
     * Installs a file drag-and-drop handler on the given text field.
     *
     * @param field       Target text field
     * @param multiSelect Whether multiple dropped files should be accepted (joined with ';')
     */
    private void installFileDrop(JTextField field, boolean multiSelect) {
        field.setTransferHandler(new FileDropTransferHandler(field, multiSelect));
    }

    /**
     * Method to reset the GUI components from scratch
     */
    protected void resetGUI() {
        pack();

        getEmbedPanel().getMsgFileTextField().setText("");
        getEmbedPanel().getCoverFileTextField().setText("");
        getEmbedPanel().getStegoFileTextField().setText("");
        getEmbedPanel().getPasswordTextField().setText("");
        getEmbedPanel().getConfPasswordTextField().setText("");
        getEmbedPanel().getSplitCheckBox().setSelected(false);
        getEmbedPanel().getMsgFileTextField().requestFocus();

        try {
            dhPlugin.resetConfig();
            if (getEmbedPanel().getPluginOptionPanel() != null) {
                getEmbedPanel().getPluginOptionPanel().setGUIFromConfig(dhPlugin.getConfig());
            }
        } catch (OpenStegoException e) {
            handleException(e);
        }
    }

    /**
     * This method embeds the selected data file into selected file
     */
    private void embedData() {
        String outputFileName;
        char[] password;
        char[] confPassword;
        File outputFile;
        List<File> coverFileList;

        outputFileName = getEmbedPanel().getStegoFileTextField().getText();
        outputFile = new File(outputFileName);
        coverFileList = CommonUtil.parseFileList(getEmbedPanel().getCoverFileTextField().getText(), ";");
        password = getEmbedPanel().getPasswordTextField().getPassword();
        confPassword = getEmbedPanel().getConfPasswordTextField().getPassword();

        // START: Input Validations
        if (!checkMandatory(getEmbedPanel().getMsgFileTextField(), labelUtil.getString("gui.label.dhEmbed.msgFile"))) {
            return;
        }
        if (!checkMandatory(getEmbedPanel().getCoverFileTextField(), labelUtil.getString("gui.label.dhEmbed.coverFile"))) {
            return;
        }
        if (!checkMandatory(getEmbedPanel().getStegoFileTextField(), labelUtil.getString("gui.label.dhEmbed.stegoFile"))) {
            return;
        }

        boolean splitMode = getEmbedPanel().getSplitCheckBox().isSelected();
        if (splitMode) {
            // Split mode (upstream issue #67): need at least two covers and a directory output.
            if (coverFileList.size() < 2) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.dhEmbed.splitNeedsCovers"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedPanel().getCoverFileTextField().requestFocus();
                return;
            }
            if (!outputFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.dhEmbed.outputShouldBeDir"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedPanel().getStegoFileTextField().requestFocus();
                return;
            }
        } else if (coverFileList.size() <= 1) {
            // If user has provided a wildcard for cover file name, and parser returns zero length, then it means that
            // there are no matching files with that wildcard
            if (coverFileList.size() == 0 && !getEmbedPanel().getCoverFileTextField().getText().trim().equals("")) {
                JOptionPane.showMessageDialog(this,
                        labelUtil.getString("gui.msg.err.dhEmbed.coverFileNotFound", getEmbedPanel().getCoverFileTextField().getText()),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedPanel().getCoverFileTextField().requestFocus();
                return;
            }
            // If single cover file is given, then output stego file must not be a directory
            if (outputFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.dhEmbed.outputShouldBeFile"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedPanel().getStegoFileTextField().requestFocus();
                return;
            }
        } else {
            // If multiple cover files are given, then output stego file must be a directory
            if (!outputFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.dhEmbed.outputShouldBeDir"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedPanel().getStegoFileTextField().requestFocus();
                return;
            }
        }

        boolean passwordMismatch = !java.util.Arrays.equals(password, confPassword);
        java.util.Arrays.fill(password, '\0');
        java.util.Arrays.fill(confPassword, '\0');
        if (passwordMismatch) {
            JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.dhEmbed.passwordMismatch"), labelUtil.getString("gui.msg.title.err"),
                    JOptionPane.ERROR_MESSAGE);
            getEmbedPanel().getConfPasswordTextField().requestFocus();
            return;
        }
        // END: Input Validations

        // Plugin specific validations
        if (getEmbedPanel().getPluginOptionPanel() != null &&
                !getEmbedPanel().getPluginOptionPanel().validateEmbedAction()) {
            return;
        }

        WorkerTask task = new WorkerTask(this, coverFileList, coverFileList.size() > 1) {
            @Override
            protected Object doInBackground() throws Exception {
                OpenStego openStego;
                OpenStegoConfig config;
                String outputFileName;
                String dataFileName;
                String cryptAlgo;
                char[] password;
                File outputFile;
                File cvrFile;
                int processCount = 0;
                int skipCount = 0;
                byte[] stegoData;

                @SuppressWarnings("unchecked")
                List<File> coverFileList = (List<File>) this.data;

                cryptAlgo = (String) getEmbedPanel().getEncryptionAlgoComboBox().getSelectedItem();
                password = getEmbedPanel().getPasswordTextField().getPassword();
                dataFileName = getEmbedPanel().getMsgFileTextField().getText();
                outputFileName = getEmbedPanel().getStegoFileTextField().getText();
                outputFile = new File(outputFileName);

                dhPlugin.resetConfig();
                config = dhPlugin.getConfig();
                config.setUseCompression(true);
                config.setUseEncryption(true);
                config.setEncryptionAlgorithm(cryptAlgo);
                config.setPassword(password);
                if (getEmbedPanel().getPluginOptionPanel() != null) {
                    getEmbedPanel().getPluginOptionPanel().setConfigFromGUI(config);
                }
                openStego = new OpenStego(dhPlugin, config);

                // Split one payload across all covers (upstream issue #67), one stego image per cover.
                if (getEmbedPanel().getSplitCheckBox().isSelected()) {
                    if (!(dhPlugin instanceof DHImagePluginTemplate)) {
                        throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_NOT_SUPPORTED);
                    }
                    DHImagePluginTemplate<?> dhTemplate = (DHImagePluginTemplate<?>) dhPlugin;

                    byte[] msg = CommonUtil.fileToBytes(new File(dataFileName));
                    String msgName = new File(dataFileName).getName();
                    List<byte[]> covers = new ArrayList<>(coverFileList.size());
                    List<String> coverNames = new ArrayList<>(coverFileList.size());
                    List<String> outPaths = new ArrayList<>(coverFileList.size());
                    for (File cf : coverFileList) {
                        covers.add(CommonUtil.fileToBytes(cf));
                        coverNames.add(cf.getName());
                        outPaths.add(outputFile.getPath() + File.separator + cf.getName());
                    }

                    for (String p : outPaths) {
                        if (new File(p).exists() && JOptionPane.showConfirmDialog(this.parent,
                                labelUtil.getString("gui.msg.warn.fileExists", p), labelUtil.getString("gui.msg.title.warn"),
                                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.NO_OPTION) {
                            this.cancel(true);
                            return null;
                        }
                    }

                    List<byte[]> stegoImages = MultiCoverPayloadSplitter.embedSplit(msg, msgName, covers, coverNames,
                            outPaths, config, dhTemplate);
                    for (int i = 0; i < stegoImages.size(); i++) {
                        setProgress(i * 100 / stegoImages.size());
                        CommonUtil.writeFile(stegoImages.get(i), outPaths.get(i));
                    }
                    java.util.Arrays.fill(password, '\0');
                    config.clearPassword();
                    return new Integer[]{stegoImages.size(), 0};
                }

                // Add null entry for coverfile if not provided
                if (coverFileList.isEmpty()) {
                    coverFileList.add(null);
                }

                OpenStegoBulkException bulkException = new OpenStegoBulkException();
                for (int i = 0; i < coverFileList.size(); i++) {
                    setProgress(i * 100 / coverFileList.size());
                    cvrFile = coverFileList.get(i);

                    if (outputFile.isDirectory()) {
                        // Use cover file name as the output file name. Change the folder to given output folder
                        outputFileName = outputFile.getPath() + File.separator + (cvrFile == null ? "Output" : cvrFile.getName());
                    }

                    // If the output filename extension is not supported for writing, change it to the
                    // format chosen in the extension chip (falling back to the plugin's default)
                    outputFileName = applyWritableExtension(outputFileName, dhPlugin, selectedStegoExt());

                    if ((new File(outputFileName)).exists()) {
                        if (JOptionPane.showConfirmDialog(this.parent, labelUtil.getString("gui.msg.warn.fileExists", outputFileName),
                                labelUtil.getString("gui.msg.title.warn"), JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE) == JOptionPane.NO_OPTION) {
                            if (coverFileList.size() == 1) {
                                this.cancel(true);
                                return null;
                            }
                            skipCount++;
                            continue;
                        }
                    }

                    processCount++;
                    try {
                        stegoData = openStego.embedData(
                                dataFileName == null || dataFileName.equals("") ? null : new File(dataFileName),
                                cvrFile, outputFileName);
                        CommonUtil.writeFile(stegoData, outputFileName);
                    } catch (OpenStegoException e) {
                        bulkException.add(cvrFile == null ? "-" : cvrFile.getName(), e);
                    }
                }
                // Wipe the password from memory once all files are processed
                java.util.Arrays.fill(password, '\0');
                config.clearPassword();
                bulkException.throwIfRequired();

                return new Integer[]{processCount, skipCount};
            }

            @Override
            public void done() {
                super.done();
                if (isCancelled()) {
                    return;
                }

                Integer[] val;
                try {
                    val = (Integer[]) get();
                } catch (InterruptedException exc) {
                    logger.log(Level.SEVERE, exc.getMessage(), exc);
                    return;
                } catch (ExecutionException exc) {
                    handleException(exc);
                    return;
                }

                JOptionPane.showMessageDialog(this.parent, labelUtil.getString("gui.msg.success.dhEmbed", val[0], val[1]),
                        labelUtil.getString("gui.msg.title.success"), JOptionPane.INFORMATION_MESSAGE);

                // Reset configuration
                ((OpenStegoUI) this.parent).resetGUI();
            }
        };
        task.start();
    }

    /**
     * This method extracts data from the selected file
     */
    private void extractData() {
        // START: Input Validations
        if (!checkMandatory(getExtractPanel().getInputStegoFileTextField(), labelUtil.getString("gui.label.dhExtract.stegoFile"))) {
            return;
        }
        if (!checkMandatory(getExtractPanel().getOutputFolderTextField(), labelUtil.getString("gui.label.dhExtract.outputDir"))) {
            return;
        }
        // END: Input Validations

        WorkerTask task = new WorkerTask(this, null, false) {
            @Override
            protected Object doInBackground() throws Exception {
                String stegoFileName;
                String outputFolder;
                String outputFileName;
                File file;
                List<?> stegoOutput;

                stegoFileName = getExtractPanel().getInputStegoFileTextField().getText();
                outputFolder = getExtractPanel().getOutputFolderTextField().getText();

                // Extraction is algorithm-agnostic: the stego file carries no record of which plugin
                // wrote it, so we try the data-hiding plugins in turn until one decodes successfully.
                // This keeps old (Adaptive/LSB) files decodable and adds the new JPEG plugin.
                char[] password = getExtractPanel().getExtractPwdTextField().getPassword();
                try {
                    if (getExtractPanel().getSplitCheckBox().isSelected()) {
                        stegoOutput = extractSplitWithAutoDetect(stegoFileName, password);
                    } else {
                        stegoOutput = extractWithAutoDetect(new File(stegoFileName), password);
                    }
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
                outputFileName = (String) stegoOutput.get(0);
                file = new File(outputFolder + File.separator + outputFileName);
                if (file.exists()) {
                    if (JOptionPane.showConfirmDialog(this.parent, labelUtil.getString("gui.msg.warn.fileExists", outputFileName),
                            labelUtil.getString("gui.msg.title.warn"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.NO_OPTION) {
                        this.cancel(true);
                    }
                }

                byte[] extracted = (byte[]) stegoOutput.get(1);
                CommonUtil.writeFile(extracted, outputFolder + File.separator + outputFileName);
                java.util.Arrays.fill(extracted, (byte) 0); // wipe the decrypted plaintext from the heap once written
                return outputFileName;
            }

            @Override
            public void done() {
                super.done();
                if (isCancelled()) {
                    return;
                }

                String outputFileName;
                try {
                    outputFileName = (String) get();
                } catch (InterruptedException exc) {
                    logger.log(Level.SEVERE, exc.getMessage(), exc);
                    return;
                } catch (ExecutionException exc) {
                    handleException(exc);
                    return;
                }

                JOptionPane.showMessageDialog(this.parent, labelUtil.getString("gui.msg.success.dhExtract", outputFileName),
                        labelUtil.getString("gui.msg.title.success"), JOptionPane.INFORMATION_MESSAGE);

                // Reset GUI
                getExtractPanel().getInputStegoFileTextField().setText("");
                getExtractPanel().getOutputFolderTextField().setText("");
                getExtractPanel().getExtractPwdTextField().setText("");
                getExtractPanel().getSplitCheckBox().setSelected(false);
                getExtractPanel().getInputStegoFileTextField().requestFocus();
            }
        };
        task.start();
    }

    /**
     * Attempts to extract hidden data from the given stego file by trying each data-hiding plugin in
     * turn until one succeeds. The plugins are ordered by the stego file's extension (JPEG plugin
     * first for {@code .jpg}/{@code .jpeg}); a fresh password copy is supplied to each attempt and
     * wiped afterward.
     *
     * @param stegoFile Stego file to extract from
     * @param password  Extraction password (may be {@code null}/empty); never mutated by this method
     * @return Extracted output (element 0 is the file name, element 1 is the message bytes)
     * @throws OpenStegoException If no plugin could decode the file
     */
    private List<?> extractWithAutoDetect(File stegoFile, char[] password) throws OpenStegoException {
        byte[] stegoData = CommonUtil.fileToBytes(stegoFile);
        String stegoFileName = stegoFile.getName();

        OpenStegoException last = null;
        for (OpenStegoPlugin<?> plugin : orderPluginsForExtract(stegoFileName)) {
            plugin.resetConfig();
            OpenStegoConfig config = plugin.getConfig();
            config.setPassword(password == null ? null : password.clone());
            try {
                return new OpenStego(plugin, config).extractData(stegoData, stegoFileName);
            } catch (OpenStegoException e) {
                last = e;
            } finally {
                config.clearPassword();
            }
        }
        if (last != null) {
            throw last;
        }
        throw new OpenStegoException(new RuntimeException("No data-hiding plugin available for extraction"));
    }

    /**
     * Reassembles a payload that was split across multiple covers (upstream issue #67). The parts are
     * given as a {@code ;}-separated list of stego files. As with single-file extraction the algorithm
     * is auto-detected: each image data-hiding plugin is tried until one decodes the parts. An invalid
     * password (the right plugin matched but the password is wrong) is surfaced immediately.
     *
     * @param stegoFileNames {@code ;}-separated list of the split parts
     * @param password       Extraction password (may be {@code null}/empty); never mutated by this method
     * @return Extracted output (element 0 is the file name, element 1 is the message bytes)
     * @throws OpenStegoException If the parts are invalid or no plugin could decode them
     */
    private List<?> extractSplitWithAutoDetect(String stegoFileNames, char[] password) throws OpenStegoException {
        List<File> parts = CommonUtil.parseFileList(stegoFileNames, ";");
        if (parts.size() < 2) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_REQUIRES_MULTIPLE_PARTS, parts.size());
        }

        List<byte[]> images = new ArrayList<>(parts.size());
        List<String> names = new ArrayList<>(parts.size());
        for (File part : parts) {
            images.add(CommonUtil.fileToBytes(part));
            names.add(part.getName());
        }

        OpenStegoException last = null;
        for (OpenStegoPlugin<?> plugin : orderPluginsForExtract(names.get(0))) {
            if (!(plugin instanceof DHImagePluginTemplate)) {
                continue;
            }
            plugin.resetConfig();
            OpenStegoConfig config = plugin.getConfig();
            config.setPassword(password == null ? null : password.clone());
            try {
                return MultiCoverPayloadSplitter.extractSplit(images, names, config, (DHImagePluginTemplate<?>) plugin);
            } catch (OpenStegoException e) {
                if (e.getErrorCode() == OpenStegoErrors.INVALID_PASSWORD) {
                    throw e; // right plugin, wrong password - no point trying the others
                }
                last = e;
            } finally {
                config.clearPassword();
            }
        }
        if (last != null) {
            throw last;
        }
        throw new OpenStegoException(new RuntimeException("No data-hiding plugin available for extraction"));
    }

    /**
     * Returns the data-hiding plugins ordered for an extraction attempt. JPEG stego files are tried
     * with the JPEG plugin first; for all other inputs the (mutually exclusive) spatial plugins are
     * tried in their registered order. JPEG and spatial plugins reject each other's files cleanly, so
     * the ordering only affects which error surfaces if every plugin fails.
     *
     * @param stegoFileName Name of the stego file (used only for its extension)
     * @return Ordered list of candidate plugins
     */
    private List<OpenStegoPlugin<?>> orderPluginsForExtract(String stegoFileName) {
        String lower = stegoFileName == null ? "" : stegoFileName.toLowerCase();
        boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        List<OpenStegoPlugin<?>> ordered = new ArrayList<>();
        List<OpenStegoPlugin<?>> deferred = new ArrayList<>();
        for (OpenStegoPlugin<?> plugin : this.dhPlugins) {
            boolean jpegPlugin = "JpegUniward".equals(plugin.getName());
            if (jpeg == jpegPlugin) {
                ordered.add(plugin);
            } else {
                deferred.add(plugin);
            }
        }
        ordered.addAll(deferred);
        return ordered;
    }

    /**
     * This method generates signature for watermarking
     *
     * @throws OpenStegoException Processing issues
     */
    private void generateSignature() throws OpenStegoException {
        OpenStego openStego;
        byte[] sigData;
        String inputKey;
        String sigFileName;
        File sigFile;
        OpenStegoConfig config;

        wmPlugin.resetConfig();
        config = wmPlugin.getConfig();

        inputKey = getGenSigPanel().getInputKeyTextField().getText();
        sigFileName = getGenSigPanel().getSignatureFileTextField().getText();
        sigFile = new File(sigFileName);

        // START: Input Validations
        if (!checkMandatory(getGenSigPanel().getInputKeyTextField(), labelUtil.getString("gui.label.wmGenSig.inputKey"))) {
            return;
        }
        if (!checkMandatory(getGenSigPanel().getSignatureFileTextField(), labelUtil.getString("gui.label.wmGenSig.sigFile"))) {
            return;
        }
        // END: Input Validations

        config.setPassword(inputKey);
        openStego = new OpenStego(wmPlugin, config);
        if (sigFile.exists()) {
            if (JOptionPane.showConfirmDialog(this, labelUtil.getString("gui.msg.warn.fileExists", sigFileName),
                    labelUtil.getString("gui.msg.title.warn"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.NO_OPTION) {
                return;
            }
        }

        sigData = openStego.generateSignature();
        CommonUtil.writeFile(sigData, sigFile);

        JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.success.wmGenSig"), labelUtil.getString("gui.msg.title.success"),
                JOptionPane.INFORMATION_MESSAGE);

        // Reset GUI
        getGenSigPanel().getInputKeyTextField().setText("");
        getGenSigPanel().getSignatureFileTextField().setText("");
        getGenSigPanel().getInputKeyTextField().requestFocus();
    }

    /**
     * This method embeds the watermark into selected file
     */
    private void embedMark() {
        List<File> inputFileList;
        File outputFile;

        inputFileList = CommonUtil.parseFileList(getEmbedWmPanel().getFileForWmTextField().getText(), ";");
        outputFile = new File(getEmbedWmPanel().getOutputWmFileTextField().getText());

        // START: Input Validations
        if (!checkMandatory(getEmbedWmPanel().getFileForWmTextField(), labelUtil.getString("gui.label.wmEmbed.fileForWm"))) {
            return;
        }
        if (!checkMandatory(getEmbedWmPanel().getSignatureFileTextField(), labelUtil.getString("gui.label.wmEmbed.sigFile"))) {
            return;
        }
        if (!checkMandatory(getEmbedWmPanel().getOutputWmFileTextField(), labelUtil.getString("gui.label.wmEmbed.outputWmFile"))) {
            return;
        }

        // Check if single or multiple input files are selected
        if (inputFileList.size() <= 1) {
            // If user has provided a wildcard for file name, and parser returns zero length, then it means that
            // there are no matching files with that wildcard
            if (inputFileList.size() == 0 && !getEmbedWmPanel().getFileForWmTextField().getText().trim().equals("")) {
                JOptionPane.showMessageDialog(this,
                        labelUtil.getString("gui.msg.err.wmEmbed.inputFileNotFound", getEmbedWmPanel().getFileForWmTextField().getText()),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedWmPanel().getFileForWmTextField().requestFocus();
                return;
            }
            // If single input file is given, then output file must not be a directory
            if (outputFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.wmEmbed.outputShouldBeFile"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedWmPanel().getOutputWmFileTextField().requestFocus();
                return;
            }
        } else {
            // If multiple input files are given, then output file must be a directory
            if (!outputFile.isDirectory()) {
                JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.wmEmbed.outputShouldBeDir"),
                        labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
                getEmbedWmPanel().getOutputWmFileTextField().requestFocus();
                return;
            }
        }
        // END: Input Validations

        WorkerTask task = new WorkerTask(this, inputFileList, inputFileList.size() > 1) {
            @Override
            protected Object doInBackground() throws Exception {
                OpenStego openStego;
                byte[] wmData;
                String sigFileName;
                String outputFileName;
                File inputFile;
                File outputFile;
                int processCount = 0;
                int skipCount = 0;

                @SuppressWarnings("unchecked")
                List<File> inputFileList = (List<File>) this.data;

                wmPlugin.resetConfig();
                openStego = new OpenStego(wmPlugin, wmPlugin.getConfig());

                // Apply the chosen JPEG output quality; only takes effect when the output is a JPEG (issue #24).
                ImageUtil.setJpegQuality(getEmbedWmPanel().getJpegQuality());

                sigFileName = getEmbedWmPanel().getSignatureFileTextField().getText();
                outputFileName = getEmbedWmPanel().getOutputWmFileTextField().getText();
                outputFile = new File(outputFileName);

                OpenStegoBulkException bulkException = new OpenStegoBulkException();
                for (int i = 0; i < inputFileList.size(); i++) {
                    setProgress(i * 100 / inputFileList.size());
                    inputFile = inputFileList.get(i);

                    if (outputFile.isDirectory()) {
                        // Use input file name as the output file name. Change the folder to given output folder
                        outputFileName = outputFile.getPath() + File.separator + inputFile.getName();
                    }

                    // If the output filename extension is not supported for writing, then change the same
                    if (!wmPlugin.getWritableFileExtensions().contains(outputFileName.substring(outputFileName.lastIndexOf('.') + 1).toLowerCase())) {
                        outputFileName = outputFileName + "." + wmPlugin.getWritableFileExtensions().get(0);
                    }

                    if ((new File(outputFileName)).exists()) {
                        if (JOptionPane.showConfirmDialog(this.parent, labelUtil.getString("gui.msg.warn.fileExists", outputFileName),
                                labelUtil.getString("gui.msg.title.warn"), JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE) == JOptionPane.NO_OPTION) {
                            if (inputFileList.size() == 1) {
                                this.cancel(true);
                                return null;
                            }
                            skipCount++;
                            continue;
                        }
                    }

                    processCount++;
                    try {
                        wmData = openStego.embedMark(sigFileName == null || sigFileName.equals("") ? null : new File(sigFileName), inputFile,
                                outputFileName);
                        CommonUtil.writeFile(wmData, outputFileName);
                    } catch (OpenStegoException e) {
                        bulkException.add(inputFile.getName(), e);
                    }
                }
                bulkException.throwIfRequired();

                return new Integer[]{processCount, skipCount};
            }

            @Override
            public void done() {
                super.done();
                if (isCancelled()) {
                    return;
                }

                Integer[] val;
                try {
                    val = (Integer[]) get();
                } catch (InterruptedException exc) {
                    logger.log(Level.SEVERE, exc.getMessage(), exc);
                    return;
                } catch (ExecutionException exc) {
                    handleException(exc);
                    return;
                }

                JOptionPane.showMessageDialog(this.parent, labelUtil.getString("gui.msg.success.wmEmbed", val[0], val[1]),
                        labelUtil.getString("gui.msg.title.success"), JOptionPane.INFORMATION_MESSAGE);

                // Reset GUI
                getEmbedWmPanel().getFileForWmTextField().setText("");
                getEmbedWmPanel().getSignatureFileTextField().setText("");
                getEmbedWmPanel().getOutputWmFileTextField().setText("");
                getEmbedWmPanel().getFileForWmTextField().requestFocus();
            }
        };
        task.start();
    }

    /**
     * This method checks for watermark in the selected file
     */
    private void checkMark() {
        List<File> inputFileList;

        // START: Input Validations
        if (!checkMandatory(getVerifyWmPanel().getInputFileTextField(), labelUtil.getString("gui.label.wmVerify.inputWmFile"))) {
            return;
        }
        if (!checkMandatory(getVerifyWmPanel().getSignatureFileTextField(), labelUtil.getString("gui.label.wmVerify.sigFile"))) {
            return;
        }

        // If user has provided a wildcard for file name, and parser returns zero length, then it means that
        // there are no matching files with that wildcard
        inputFileList = CommonUtil.parseFileList(getVerifyWmPanel().getInputFileTextField().getText(), ";");
        if (inputFileList.size() == 0 && !getVerifyWmPanel().getInputFileTextField().getText().trim().equals("")) {
            JOptionPane.showMessageDialog(this,
                    labelUtil.getString("gui.msg.err.wmVerify.inputFileNotFound", getVerifyWmPanel().getInputFileTextField().getText()),
                    labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
            getVerifyWmPanel().getInputFileTextField().requestFocus();
            return;
        }
        // END: Input Validations

        WorkerTask task = new WorkerTask(this, inputFileList, inputFileList.size() > 1) {
            @Override
            protected Object doInBackground() throws Exception {
                File sigFile;
                OpenStego openStego;
                NumberFormat formatter = NumberFormat.getPercentInstance();
                double correlation;

                @SuppressWarnings("unchecked")
                List<File> inputFileList = (List<File>) this.data;

                wmPlugin.resetConfig();
                openStego = new OpenStego(wmPlugin, wmPlugin.getConfig());
                sigFile = new File(getVerifyWmPanel().getSignatureFileTextField().getText());

                Object[][] tblData = new Object[inputFileList.size()][2];
                for (int i = 0; i < inputFileList.size(); i++) {
                    setProgress(i * 100 / inputFileList.size());
                    File inputFile = inputFileList.get(i);
                    correlation = openStego.checkMark(inputFile, sigFile);
                    tblData[i][0] = inputFile.getName();
                    String color;
                    if (correlation > wmPlugin.getHighWatermarkLevel()) {
                        color = "green";
                    } else if (correlation > wmPlugin.getLowWatermarkLevel()) {
                        color = "#FFBF00";
                    } else {
                        color = "red";
                    }
                    tblData[i][1] = "<html><span style='color:" + color + "'>\u25cf " + formatter.format(correlation) + "</span></html>";
                }
                setProgress(100);

                return tblData;
            }

            @Override
            public void done() {
                super.done();
                if (isCancelled()) {
                    return;
                }

                Object[][] tblData;
                try {
                    tblData = (Object[][]) get();
                } catch (InterruptedException exc) {
                    logger.log(Level.SEVERE, exc.getMessage(), exc);
                    return;
                } catch (ExecutionException exc) {
                    handleException(exc);
                    return;
                }

                JTable table = new JTable(tblData, new Object[]{labelUtil.getString("gui.label.wmVerify.result.header.fileName"),
                        labelUtil.getString("gui.label.wmVerify.result.header.strength")}) {
                    /**
                     *
                     */
                    private static final long serialVersionUID = 2555408155856491941L;

                    @Override
                    public boolean isCellEditable(int rowIndex, int colIndex) {
                        return false;
                    }
                };
                JScrollPane pane = new JScrollPane(table);
                table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
                table.setDragEnabled(false);
                table.setCellSelectionEnabled(false);
                table.setRowSelectionAllowed(false);
                table.setPreferredScrollableViewportSize(new Dimension(400, 150));

                JPanel panel = new JPanel(new BorderLayout());
                JLabel header = new JLabel(labelUtil.getString("gui.msg.success.wmVerify"));
                header.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
                panel.add(header, BorderLayout.NORTH);
                panel.add(pane, BorderLayout.CENTER);

                JOptionPane.showMessageDialog(this.parent, panel, labelUtil.getString("gui.msg.title.results"), JOptionPane.INFORMATION_MESSAGE);

                // Reset GUI
                getVerifyWmPanel().getInputFileTextField().setText("");
                getVerifyWmPanel().getSignatureFileTextField().setText("");
                getVerifyWmPanel().getInputFileTextField().requestFocus();
            }
        };
        task.start();
    }

    /**
     * This method shows the file chooser and updates the text field based on the selection
     *
     * @throws OpenStegoException Processing issues
     */
    private void selectFile(String action) throws OpenStegoException {
        FileBrowser browser = new FileBrowser();
        String fileName;
        String title;
        String filterDesc = null;
        List<String> allowedExts = null;
        int allowFileDir = FileBrowser.ALLOW_FILE;
        boolean multiSelect = false;
        int coverFileListSize;
        int wmInputFileListSize;
        JTextField textField;
        OpenStegoPlugin<?> plugin;

        plugin = action.startsWith("BROWSE_DH_") ? dhPlugin : wmPlugin;

        coverFileListSize = CommonUtil.parseFileList(getEmbedPanel().getCoverFileTextField().getText(), ";").size();
        wmInputFileListSize = CommonUtil.parseFileList(getEmbedWmPanel().getFileForWmTextField().getText(), ";").size();

        switch (action) {
            case ActionCommands.BROWSE_DH_EMB_MSGFILE:
                title = labelUtil.getString("gui.filer.title.dhEmbed.msgFile");
                textField = getEmbedPanel().getMsgFileTextField();
                break;
            case ActionCommands.BROWSE_DH_EMB_CVRFILE:
                title = labelUtil.getString("gui.filer.title.dhEmbed.coverFile");
                filterDesc = labelUtil.getString("gui.filer.filter.coverFiles", getExtensionsString(plugin, READ_EXTENSIONS));
                allowedExts = getExtensionsList(plugin, READ_EXTENSIONS);
                textField = getEmbedPanel().getCoverFileTextField();
                multiSelect = true;
                break;
            case ActionCommands.BROWSE_DH_EMB_STGFILE:
                title = labelUtil.getString("gui.filer.title.dhEmbed.stegoFile");
                if (coverFileListSize > 1) {
                    allowFileDir = FileBrowser.ALLOW_DIRECTORY;
                } else {
                    filterDesc = labelUtil.getString("gui.filer.filter.stegoFiles", getExtensionsString(plugin, WRITE_EXTENSIONS));
                    allowedExts = getExtensionsList(plugin, WRITE_EXTENSIONS);
                }
                textField = getEmbedPanel().getStegoFileTextField();
                break;
            case ActionCommands.BROWSE_DH_EXT_STGFILE:
                title = labelUtil.getString("gui.filer.title.dhExtract.stegoFile");
                // Extraction auto-detects the plugin (see extractWithAutoDetect), so the filter must
                // accept every stego format any data-hiding plugin can produce, not just the one
                // currently selected on the embed tab.
                filterDesc = labelUtil.getString("gui.filer.filter.stegoFiles", allStegoWritableExtensionsString());
                allowedExts = allStegoWritableExtensions();
                textField = getExtractPanel().getInputStegoFileTextField();
                // In split mode the user picks several parts at once (upstream issue #67)
                multiSelect = getExtractPanel().getSplitCheckBox().isSelected();
                break;
            case ActionCommands.BROWSE_DH_EXT_OUTDIR:
                title = labelUtil.getString("gui.filer.title.dhExtract.outputDir");
                allowFileDir = FileBrowser.ALLOW_DIRECTORY;
                textField = getExtractPanel().getOutputFolderTextField();
                break;
            case ActionCommands.BROWSE_WM_GSG_SIGFILE:
                title = labelUtil.getString("gui.filer.title.wmGenSig.sigFile");
                filterDesc = labelUtil.getString("gui.filer.filter.sigFiles", "*" + SIG_FILE_EXTENSION);
                allowedExts = Collections.singletonList(SIG_FILE_EXTENSION);
                textField = getGenSigPanel().getSignatureFileTextField();
                break;
            case ActionCommands.BROWSE_WM_EMB_INPFILE:
                title = labelUtil.getString("gui.filer.title.wmEmbed.fileForWm");
                filterDesc = labelUtil.getString("gui.filer.filter.filesForWm", getExtensionsString(plugin, READ_EXTENSIONS));
                allowedExts = getExtensionsList(plugin, READ_EXTENSIONS);
                textField = getEmbedWmPanel().getFileForWmTextField();
                multiSelect = true;
                break;
            case ActionCommands.BROWSE_WM_EMB_SIGFILE:
                title = labelUtil.getString("gui.filer.title.wmEmbed.sigFile");
                filterDesc = labelUtil.getString("gui.filer.filter.sigFiles", "*" + SIG_FILE_EXTENSION);
                allowedExts = Collections.singletonList(SIG_FILE_EXTENSION);
                textField = getEmbedWmPanel().getSignatureFileTextField();
                break;
            case ActionCommands.BROWSE_WM_EMB_OUTFILE:
                title = labelUtil.getString("gui.filer.title.wmEmbed.outputWmFile");
                if (wmInputFileListSize > 1) {
                    allowFileDir = FileBrowser.ALLOW_DIRECTORY;
                } else {
                    filterDesc = labelUtil.getString("gui.filer.filter.wmFiles", getExtensionsString(plugin, WRITE_EXTENSIONS));
                    allowedExts = getExtensionsList(plugin, WRITE_EXTENSIONS);
                }
                textField = getEmbedWmPanel().getOutputWmFileTextField();
                break;
            case ActionCommands.BROWSE_WM_VER_INPFILE:
                title = labelUtil.getString("gui.filer.title.wmExtract.inputWmFile");
                filterDesc = labelUtil.getString("gui.filer.filter.wmFiles", getExtensionsString(plugin, WRITE_EXTENSIONS));
                allowedExts = getExtensionsList(plugin, WRITE_EXTENSIONS);
                textField = getVerifyWmPanel().getInputFileTextField();
                multiSelect = true;
                break;
            case ActionCommands.BROWSE_WM_VER_SIGFILE:
                title = labelUtil.getString("gui.filer.title.wmExtract.sigFile");
                filterDesc = labelUtil.getString("gui.filer.filter.sigFiles", "*" + SIG_FILE_EXTENSION);
                allowedExts = Collections.singletonList(SIG_FILE_EXTENSION);
                textField = getVerifyWmPanel().getSignatureFileTextField();
                break;
            default:
                throw new OpenStegoException(new RuntimeException("Unknown action: " + action));
        }

        boolean saveDialog = action.equals(ActionCommands.BROWSE_DH_EMB_STGFILE)
                || action.equals(ActionCommands.BROWSE_WM_EMB_OUTFILE)
                || action.equals(ActionCommands.BROWSE_WM_GSG_SIGFILE);
        fileName = browser.getFileName(this, title, filterDesc, allowedExts, allowFileDir, multiSelect, saveDialog);
        if (fileName != null) {
            // Check for valid extension for output file. For the data-hiding stego file, honor the
            // format currently chosen in the extension chip; otherwise apply the plugin's default.
            if (action.equals(OpenStegoFrame.ActionCommands.BROWSE_DH_EMB_STGFILE) && (coverFileListSize <= 1)) {
                fileName = applyWritableExtension(fileName, plugin, selectedStegoExt());
            } else if (action.equals(OpenStegoFrame.ActionCommands.BROWSE_WM_EMB_OUTFILE) && (wmInputFileListSize <= 1)) {
                fileName = applyWritableExtension(fileName, plugin, null);
            }
            // Check for valid extension for signature file
            if (action.equals(OpenStegoFrame.ActionCommands.BROWSE_WM_GSG_SIGFILE)) {
                if (!fileName.toLowerCase().endsWith(SIG_FILE_EXTENSION)) {
                    fileName = fileName + SIG_FILE_EXTENSION;
                }
            }
            textField.setText(fileName);
        }
    }

    /**
     * This method exits the application.
     */
    private void close() {
        saveSettings();
        System.exit(0);
    }

    /**
     * This method displays the About dialog box
     */
    private void showHelpAbout() {
        HelpAboutDialog aboutDialog = new HelpAboutDialog(this);
        aboutDialog.setVisible(true);
    }

    /**
     * This method handles all the exceptions in the GUI
     *
     * @param ex Exception to be handled
     */
    private void handleException(Throwable ex) {
        Object msg;

        if (ex instanceof OutOfMemoryError) {
            msg = labelUtil.getString("err.memory.full");
        } else if (ex instanceof OpenStegoException) {
            msg = ex.getMessage();
        } else if (ex instanceof OpenStegoBulkException) {
            msg = getBulkMessage((OpenStegoBulkException) ex);
        } else {
            Throwable cause = ex.getCause();
            if (cause instanceof OpenStegoException) {
                msg = cause.getMessage();
            } else if (cause instanceof OpenStegoBulkException) {
                msg = getBulkMessage((OpenStegoBulkException) cause);
            } else {
                msg = ex.getMessage();
            }
        }

        if (msg == null || (msg instanceof String && ((String) msg).trim().equals(""))) {
            StringWriter writer = new StringWriter();
            ex.printStackTrace(new PrintWriter(writer));
            msg = writer.toString();
        }

        logger.log(Level.SEVERE, ex.getMessage(), ex);
        JOptionPane.showMessageDialog(this, msg, labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Helper method to build single output component out of all exceptions within a Bulk Exception
     *
     * @param ex Bulk Exception
     * @return Output message component
     */
    private Object getBulkMessage(OpenStegoBulkException ex) {
        int len = ex.getExceptions().size();
        if (len == 1) {
            return ex.getExceptions().get(0);
        }

        String prefix = "<html><head><style type='text/css'>" +
                "table, th, td { border: 1px solid #333; }" +
                "table { border-width: 0 0 1px 1px }" +
                "th, td { border-width: 1px 1px 0 0 }" +
                "td { background-color: white }" +
                "</style></head><body><table border='0' cellspacing='0' cellpadding='5'>";
        StringBuilder sb = new StringBuilder(prefix).append("<tr><th>")
                .append(labelUtil.getString("gui.msg.err.header.file")).append("</th><th>")
                .append(labelUtil.getString("gui.msg.err.header.error")).append("</th></tr>");
        for (int i = 0; i < ex.getKeys().size(); i++) {
            sb.append("<tr><td>").append(ex.getKeys().get(i)).append("</td><td>")
                    .append(ex.getExceptions().get(i).getMessage()).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return new JLabel(sb.toString());
    }

    /**
     * Method to check whether value is provided or not; and display message box in case it is not provided
     *
     * @param textField Text field to be checked for value
     * @param fieldName Name of the field
     * @return Flag whether value exists or not
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkMandatory(JTextField textField, String fieldName) {
        if (!textField.isEnabled()) {
            return true;
        }

        String value = textField.getText();
        if (value == null || value.trim().equals("")) {
            JOptionPane.showMessageDialog(this, labelUtil.getString("gui.msg.err.mandatoryCheck", fieldName),
                    labelUtil.getString("gui.msg.title.err"), JOptionPane.ERROR_MESSAGE);

            textField.requestFocus();
            return false;
        }

        return true;
    }

    /**
     * Method to get the list of extensions as a single string
     *
     * @param plugin Plugin
     * @param flag   Flag to indicate whether readable (READ_EXTENSIONS) or writeable (WRITE_EXTENSIONS) extensions are
     *               required
     * @return List of extensions (as string)
     * @throws OpenStegoException Processing issues
     */
    private String getExtensionsString(OpenStegoPlugin<?> plugin, int flag) throws OpenStegoException {
        List<String> list;
        StringBuilder output = new StringBuilder();

        list = getExtensionsList(plugin, flag);
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                output.append(", ");
            }
            output.append("*").append(list.get(i));
        }
        return output.toString();
    }

    /**
     * Method to get the list of extensions as a list
     *
     * @param plugin Plugin
     * @param flag   Flag to indicate whether readable (READ_EXTENSIONS) or writeable (WRITE_EXTENSIONS) extensions are
     *               required
     * @return List of extensions (as list)
     * @throws OpenStegoException Processing issues
     */
    private List<String> getExtensionsList(OpenStegoPlugin<?> plugin, int flag) throws OpenStegoException {
        List<String> list;
        List<String> output = new ArrayList<>();

        if (flag == READ_EXTENSIONS) {
            list = plugin.getReadableFileExtensions();
        } else if (flag == WRITE_EXTENSIONS) {
            list = plugin.getWritableFileExtensions();
        } else {
            throw new OpenStegoException(new RuntimeException("Unknown flag: " + flag));
        }

        for (String s : list) {
            output.add("." + s);
        }
        return output;
    }

    /**
     * Returns the union of every data-hiding plugin's writable extensions (dot-prefixed, de-duplicated,
     * insertion order preserved). Used by the extract-tab file filter, since extraction auto-detects
     * the plugin and so must accept any stego format produced by any plugin.
     *
     * @return combined list of stego extensions (e.g. {@code .png}, {@code .bmp}, {@code .jpg}, {@code .jpeg})
     * @throws OpenStegoException Processing issues
     */
    private List<String> allStegoWritableExtensions() throws OpenStegoException {
        List<String> output = new ArrayList<>();
        for (OpenStegoPlugin<?> p : this.dhPlugins) {
            for (String ext : getExtensionsList(p, WRITE_EXTENSIONS)) {
                if (!output.contains(ext)) {
                    output.add(ext);
                }
            }
        }
        return output;
    }

    /**
     * Comma-separated, {@code *}-globbed form of {@link #allStegoWritableExtensions()} for filter labels.
     *
     * @return e.g. {@code *.png, *.bmp, *.jpg, *.jpeg}
     * @throws OpenStegoException Processing issues
     */
    private String allStegoWritableExtensionsString() throws OpenStegoException {
        StringBuilder output = new StringBuilder();
        List<String> list = allStegoWritableExtensions();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                output.append(", ");
            }
            output.append("*").append(list.get(i));
        }
        return output.toString();
    }

    /**
     * Common listener class to handlw action and window events
     */
    class Listener implements ActionListener, WindowListener {
        @Override
        public void actionPerformed(ActionEvent ev) {
            try {
                String action = ev.getActionCommand();

                if (action.startsWith("MENU_")) {
                    if (action.equals(OpenStegoFrame.ActionCommands.MENU_FILE_EXIT)) {
                        close();
                    } else if (action.equals(OpenStegoFrame.ActionCommands.MENU_HELP_ABOUT)) {
                        showHelpAbout();
                    }
                } else if (action.startsWith("BROWSE_")) {
                    selectFile(action);
                } else if (action.startsWith("SWITCH_")) {
                    getMainPanel().removeAll();
                    switch (action) {
                        case ActionCommands.SWITCH_DH_EMBED:
                            getMainPanel().add(getEmbedPanel());
                            getHeader().setText(labelUtil.getString("gui.label.panelHeader.dhEmbed"));
                            getRootPane().setDefaultButton(getEmbedPanel().getRunEmbedButton());
                            break;
                        case ActionCommands.SWITCH_DH_EXTRACT:
                            getMainPanel().add(getExtractPanel());
                            getHeader().setText(labelUtil.getString("gui.label.panelHeader.dhExtract"));
                            getRootPane().setDefaultButton(getExtractPanel().getRunExtractButton());
                            break;
                        case ActionCommands.SWITCH_WM_GENSIG:
                            getMainPanel().add(getGenSigPanel());
                            getHeader().setText(labelUtil.getString("gui.label.panelHeader.wmGenSig"));
                            getRootPane().setDefaultButton(getGenSigPanel().getRunGenSigButton());
                            break;
                        case ActionCommands.SWITCH_WM_EMBED:
                            getMainPanel().add(getEmbedWmPanel());
                            getHeader().setText(labelUtil.getString("gui.label.panelHeader.wmEmbed"));
                            getRootPane().setDefaultButton(getEmbedWmPanel().getRunEmbedWmButton());
                            break;
                        case ActionCommands.SWITCH_WM_VERIFY:
                            getMainPanel().add(getVerifyWmPanel());
                            getHeader().setText(labelUtil.getString("gui.label.panelHeader.wmVerify"));
                            getRootPane().setDefaultButton(getVerifyWmPanel().getRunVerifyWmButton());
                            break;
                    }
                    getMainPanel().revalidate();
                    getMainPanel().repaint();
                } else if (action.startsWith("RUN_")) {
                    try {
                        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                        switch (action) {
                            case ActionCommands.RUN_DH_EMBED:
                                embedData();
                                break;
                            case ActionCommands.RUN_DH_EXTRACT:
                                extractData();
                                break;
                            case ActionCommands.RUN_WM_GENSIG:
                                generateSignature();
                                break;
                            case ActionCommands.RUN_WM_EMBED:
                                embedMark();
                                break;
                            case ActionCommands.RUN_WM_VERIFY:
                                checkMark();
                                break;
                        }
                    } finally {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
            } catch (Throwable ex) {
                handleException(ex);
            }
        }

        @Override
        public void windowClosing(WindowEvent ev) {
            close();
        }

        @Override
        public void windowActivated(WindowEvent ev) {
        }

        @Override
        public void windowClosed(WindowEvent ev) {
        }

        @Override
        public void windowDeactivated(WindowEvent ev) {
        }

        @Override
        public void windowDeiconified(WindowEvent ev) {
        }

        @Override
        public void windowIconified(WindowEvent ev) {
        }

        @Override
        public void windowOpened(WindowEvent ev) {
        }
    }

    /**
     * Class to implement File Chooser
     */
    static class FileBrowser {
        public static final int ALLOW_FILE = 1;
        public static final int ALLOW_DIRECTORY = 2;
        public static final int ALLOW_FILE_AND_DIR = 3;

        /**
         * Method to display a file chooser and return the selected file name.
         * <p>
         * For plain file open/save this uses the native OS file dialog ({@link FileDialog}) so that the
         * application uses Windows Explorer / the native desktop file picker. Directory selection is not
         * supported by the native dialog (especially on Windows), so {@link JFileChooser} is used for
         * those cases.
         *
         * @param parent       Parent frame to own the dialog
         * @param dialogTitle  Title for the file chooser dialog box
         * @param filterDesc   Description to be displayed for the filter in file chooser
         * @param allowedExts  Allowed file extensions for the filter
         * @param allowFileDir Type of objects allowed to be selected (FileBrowser.ALLOW_FILE,
         *                     FileBrowser.ALLOW_DIRECTORY or FileBrowser.ALLOW_FILE_AND_DIR)
         * @param multiSelect  Flag to indicate whether multiple file selection is allowed or not
         * @param saveDialog   Flag to indicate whether the dialog is for saving (output) a file
         * @return Name of the selected file (null if no file was selected)
         */
        public String getFileName(Frame parent, String dialogTitle, String filterDesc, List<String> allowedExts, int allowFileDir,
                                  boolean multiSelect, boolean saveDialog) {
            // The native AWT file dialog can only select files, so fall back to the Swing chooser when a
            // directory may be selected.
            if (allowFileDir != ALLOW_FILE) {
                return getFileNameUsingChooser(dialogTitle, filterDesc, allowedExts, allowFileDir, multiSelect);
            }
            return getFileNameUsingNativeDialog(parent, dialogTitle, allowedExts, multiSelect, saveDialog);
        }

        /**
         * Shows the native OS file dialog for selecting (or saving) one or more files.
         */
        private String getFileNameUsingNativeDialog(Frame parent, String dialogTitle, List<String> allowedExts, boolean multiSelect,
                                                    boolean saveDialog) {
            FileDialog dialog = new FileDialog(parent, dialogTitle, saveDialog ? FileDialog.SAVE : FileDialog.LOAD);
            if (lastFolder != null) {
                dialog.setDirectory(lastFolder);
            }
            dialog.setMultipleMode(multiSelect);

            if (allowedExts != null && !allowedExts.isEmpty()) {
                // Linux/macOS honor the FilenameFilter; Windows honors a wildcard pattern set via setFile().
                dialog.setFilenameFilter((dir, name) -> {
                    String lower = name.toLowerCase();
                    for (String ext : allowedExts) {
                        if (lower.endsWith(ext.toLowerCase())) {
                            return true;
                        }
                    }
                    return false;
                });
                if (!saveDialog) {
                    StringBuilder pattern = new StringBuilder();
                    for (String ext : allowedExts) {
                        if (pattern.length() > 0) {
                            pattern.append(";");
                        }
                        pattern.append("*").append(ext.startsWith(".") ? ext : "." + ext);
                    }
                    dialog.setFile(pattern.toString());
                }
            }

            dialog.setVisible(true);

            File[] files = dialog.getFiles();
            if (files == null || files.length == 0) {
                return null;
            }
            lastFolder = dialog.getDirectory();

            if (multiSelect) {
                StringBuilder fileList = new StringBuilder();
                for (int i = 0; i < files.length; i++) {
                    if (i != 0) {
                        fileList.append(";");
                    }
                    fileList.append(files[i].getPath());
                }
                return fileList.toString();
            }
            return files[0].getPath();
        }

        /**
         * Shows the Swing file chooser, used when a directory may be selected.
         */
        private String getFileNameUsingChooser(String dialogTitle, String filterDesc, List<String> allowedExts, int allowFileDir,
                                               boolean multiSelect) {
            int retVal;
            String fileName = null;
            File[] files;

            JFileChooser chooser = new JFileChooser(lastFolder);
            chooser.setMultiSelectionEnabled(multiSelect);
            switch (allowFileDir) {
                case ALLOW_FILE:
                    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                    break;
                case ALLOW_DIRECTORY:
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    break;
                case ALLOW_FILE_AND_DIR:
                    chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                    break;
            }

            if (filterDesc != null) {
                chooser.setFileFilter(new FileBrowserFilter(filterDesc, allowedExts));
            }
            chooser.setDialogTitle(dialogTitle);
            retVal = chooser.showOpenDialog(null);

            if (retVal == JFileChooser.APPROVE_OPTION) {
                if (multiSelect) {
                    StringBuilder fileList = new StringBuilder();
                    files = chooser.getSelectedFiles();
                    for (int i = 0; i < files.length; i++) {
                        if (i != 0) {
                            fileList.append(";");
                        }
                        fileList.append(files[i].getPath());
                    }
                    fileName = fileList.toString();
                } else {
                    fileName = chooser.getSelectedFile().getPath();
                }
                lastFolder = chooser.getSelectedFile().getParent();
            }

            return fileName;
        }

        /**
         * Class to implement filter for file chooser
         */
        static class FileBrowserFilter extends FileFilter {
            /**
             * Description of the filter
             */
            private final String filterDesc;

            /**
             * List of allowed file extensions
             */
            private final List<String> allowedExts;

            /**
             * Default constructor
             *
             * @param filterDesc  Description of the filter
             * @param allowedExts List of allowed file extensions
             */
            public FileBrowserFilter(String filterDesc, List<String> allowedExts) {
                this.filterDesc = filterDesc;
                this.allowedExts = allowedExts;
            }

            /**
             * Implementation of <code>accept</accept> method of <code>FileFilter</code> class
             *
             * @param file File to check whether it is acceptable by this filter or not
             * @return Flag to indicate whether file is acceptable or not
             */
            @Override
            public boolean accept(File file) {
                if (file != null) {
                    if (this.allowedExts == null || this.allowedExts.size() == 0 || file.isDirectory()) {
                        return true;
                    }

                    for (String allowedExt : this.allowedExts) {
                        if (file.getName().toLowerCase().endsWith(allowedExt)) {
                            return true;
                        }
                    }
                }

                return false;
            }

            /**
             * Implementation of <code>getDescription</accept> method of <code>FileFilter</code> class
             *
             * @return Description of the filter
             */
            @Override
            public String getDescription() {
                return this.filterDesc;
            }
        }
    }
}
