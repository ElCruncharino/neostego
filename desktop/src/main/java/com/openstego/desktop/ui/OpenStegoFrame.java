/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.openstego.desktop.OpenStego;
import com.openstego.desktop.OpenStegoPlugin;
import com.openstego.desktop.util.LabelUtil;
import java.awt.*;
import java.awt.event.KeyEvent;
import javax.swing.*;

/**
 * Frame class to build the Swing UI for OpenStego. This class includes only graphics rendering
 * code. Listeners are implemented in {@link com.openstego.desktop.ui.OpenStegoUI} class.
 */
public class OpenStegoFrame extends JFrame {
    private static final long serialVersionUID = -880718904125121559L;

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(OpenStego.NAMESPACE);

    /**
     * Number of columns for text fields
     */
    public static final int TEXTFIELD_SIZE = 30;
    /**
     * Number of columns for password fields
     */
    public static final int PWD_FIELD_SIZE = 15;

    private JMenuBar topMenuBar;
    private JMenu fileMenu;
    private JMenuItem fileExitMenuItem;
    private JMenu viewMenu;
    private JRadioButtonMenuItem themeLightMenuItem;
    private JRadioButtonMenuItem themeDarkMenuItem;
    private JMenu helpMenu;
    private JMenuItem helpAboutMenuItem;

    private JPanel mainContentPane;

    private JScrollPane accordionPane;
    private JPanel accordion;
    private final ButtonGroup actionButtonGroup = new ButtonGroup();
    private JToggleButton embedButton;
    private JToggleButton extractButton;
    private JToggleButton genSigButton;
    private JToggleButton signWmButton;
    private JToggleButton verifyWmButton;

    private JPanel headerPanel;
    private JLabel header;

    private JPanel mainPanel;
    private EmbedPanel embedPanel;
    private ExtractPanel extractPanel;
    private GenerateSignaturePanel genSigPanel;
    private EmbedWatermarkPanel embedWmPanel;
    private VerifyWatermarkPanel verifyWmPanel;

    private final OpenStegoPlugin<?> dhPlugin;

    /**
     * Default constructor
     */
    @SuppressWarnings("unused")
    public OpenStegoFrame(OpenStegoPlugin<?> dhPlugin, OpenStegoPlugin<?> wmPlugin) {
        super();
        this.dhPlugin = dhPlugin;
        initialize();
        setActionCommands();
    }

    /**
     * Getter method for topMenuBar
     *
     * @return topMenuBar
     */
    public JMenuBar getTopMenuBar() {
        if (this.topMenuBar == null) {
            this.topMenuBar = new JMenuBar();
            this.topMenuBar.add(getFileMenu());
            this.topMenuBar.add(getViewMenu());
            this.topMenuBar.add(getHelpMenu());
        }
        return this.topMenuBar;
    }

    /**
     * Getter method for fileMenu
     *
     * @return fileMenu
     */
    public JMenu getFileMenu() {
        if (this.fileMenu == null) {
            this.fileMenu = new JMenu(labelUtil.getString("gui.menu.file"));
            this.fileMenu.setMnemonic(KeyEvent.VK_F);
            this.fileMenu.add(getFileExitMenuItem());
        }
        return this.fileMenu;
    }

    /**
     * Getter method for fileExitMenuItem
     *
     * @return fileExitMenuItem
     */
    public JMenuItem getFileExitMenuItem() {
        if (this.fileExitMenuItem == null) {
            this.fileExitMenuItem = new JMenuItem(labelUtil.getString("gui.menu.file.exit"));
            this.fileExitMenuItem.setMnemonic(KeyEvent.VK_X);
        }
        return this.fileExitMenuItem;
    }

    /**
     * Getter method for viewMenu
     *
     * @return viewMenu
     */
    public JMenu getViewMenu() {
        if (this.viewMenu == null) {
            this.viewMenu = new JMenu(labelUtil.getString("gui.menu.view"));
            this.viewMenu.setMnemonic(KeyEvent.VK_V);

            JMenu themeMenu = new JMenu(labelUtil.getString("gui.menu.view.theme"));
            ButtonGroup themeGroup = new ButtonGroup();
            themeGroup.add(getThemeLightMenuItem());
            themeGroup.add(getThemeDarkMenuItem());
            themeMenu.add(getThemeLightMenuItem());
            themeMenu.add(getThemeDarkMenuItem());

            this.viewMenu.add(themeMenu);
        }
        return this.viewMenu;
    }

    /**
     * Getter method for themeLightMenuItem
     *
     * @return themeLightMenuItem
     */
    public JRadioButtonMenuItem getThemeLightMenuItem() {
        if (this.themeLightMenuItem == null) {
            this.themeLightMenuItem = new JRadioButtonMenuItem(labelUtil.getString("gui.menu.view.theme.light"));
            this.themeLightMenuItem.setSelected(UITheme.LIGHT.equals(UITheme.current()));
            this.themeLightMenuItem.addActionListener(e -> UITheme.switchTo(UITheme.LIGHT));
        }
        return this.themeLightMenuItem;
    }

    /**
     * Getter method for themeDarkMenuItem
     *
     * @return themeDarkMenuItem
     */
    public JRadioButtonMenuItem getThemeDarkMenuItem() {
        if (this.themeDarkMenuItem == null) {
            this.themeDarkMenuItem = new JRadioButtonMenuItem(labelUtil.getString("gui.menu.view.theme.dark"));
            this.themeDarkMenuItem.setSelected(UITheme.DARK.equals(UITheme.current()));
            this.themeDarkMenuItem.addActionListener(e -> UITheme.switchTo(UITheme.DARK));
        }
        return this.themeDarkMenuItem;
    }

    /**
     * Getter method for helpMenu
     *
     * @return helpMenu
     */
    public JMenu getHelpMenu() {
        if (this.helpMenu == null) {
            this.helpMenu = new JMenu(labelUtil.getString("gui.menu.help"));
            this.helpMenu.setMnemonic(KeyEvent.VK_H);
            this.helpMenu.add(getHelpAboutMenuItem());
        }
        return this.helpMenu;
    }

    /**
     * Getter method for helpAboutMenuItem
     *
     * @return helpAboutMenuItem
     */
    public JMenuItem getHelpAboutMenuItem() {
        if (this.helpAboutMenuItem == null) {
            this.helpAboutMenuItem = new JMenuItem(labelUtil.getString("gui.menu.help.about"));
            this.helpAboutMenuItem.setMnemonic(KeyEvent.VK_A);
        }
        return this.helpAboutMenuItem;
    }

    /**
     * Getter method for mainContentPane
     *
     * @return mainContentPane
     */
    public JPanel getMainContentPane() {
        if (this.mainContentPane == null) {
            this.mainContentPane = new JPanel();
            this.mainContentPane.setLayout(new BorderLayout());

            this.mainContentPane.add(getAccordionPane(), BorderLayout.LINE_START);

            JPanel rightPane = new JPanel();
            rightPane.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor()));
            rightPane.setLayout(new BorderLayout());
            this.mainContentPane.add(rightPane, BorderLayout.CENTER);

            rightPane.add(getHeaderPanel(), BorderLayout.PAGE_START);
            rightPane.add(getMainPanel(), BorderLayout.CENTER);
        }
        return this.mainContentPane;
    }

    /**
     * Getter method for accordionPane
     *
     * @return accordionPane
     */
    public JScrollPane getAccordionPane() {
        if (this.accordionPane == null) {
            this.accordionPane = new JScrollPane();
            this.accordionPane.setBorder(null);
            this.accordionPane.setViewportView(getAccordion());
        }
        return this.accordionPane;
    }

    /**
     * Getter method for accordion
     *
     * @return accordion
     */
    public JPanel getAccordion() {
        if (this.accordion == null) {
            this.accordion = new JPanel();
            this.accordion.setLayout(new GridBagLayout());

            int pad = 20;
            int gridy = 0;
            GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;

            c.gridy = gridy++;
            this.accordion.add(createAccordionHeader(labelUtil.getString("gui.label.tabHeader.dataHiding")), c);

            c.gridy = gridy++;
            c.insets = new Insets(0, pad, 0, pad);
            this.accordion.add(getEmbedButton(), c);

            c.gridy = gridy++;
            this.accordion.add(getExtractButton(), c);

            c.gridy = gridy++;
            this.accordion.add(Box.createVerticalStrut(20), c);

            c.gridy = gridy++;
            c.insets = new Insets(0, 0, 0, 0);
            this.accordion.add(createAccordionHeader(labelUtil.getString("gui.label.tabHeader.watermarking")), c);

            c.gridy = gridy++;
            c.insets = new Insets(0, pad, 0, pad);
            this.accordion.add(getGenSigButton(), c);

            c.gridy = gridy++;
            this.accordion.add(getSignWmButton(), c);

            c.gridy = gridy++;
            this.accordion.add(getVerifyWmButton(), c);

            c.gridy = gridy;
            c.weighty = 1.0;
            this.accordion.add(new JPanel(), c);
        }
        return this.accordion;
    }

    private Component createAccordionHeader(String name) {
        GradientPanel panel = new GradientPanel(
                (new JPanel()).getBackground(), (new JPanel()).getBackground().darker());
        panel.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor()));
        panel.setLayout(new GridLayout(1, 1));

        JButton button = new JButton(name);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setMargin(new Insets(3, 3, 3, 3));
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setFocusable(false);
        panel.add(button);

        return panel;
    }

    /**
     * Size (px) at which the navigation SVG icons are rendered. {@link FlatSVGIcon} re-rasterizes
     * crisply at the active display scale, so this stays sharp on HiDPI screens.
     */
    private static final int NAV_ICON_SIZE = 24;

    /**
     * Builds a navigation icon from an SVG resource that follows the current theme. The icon's
     * drawing color is mapped to the button foreground at paint time, so it re-tints automatically
     * when the light/dark theme is switched (FlatLaf invalidates the icon cache on LAF change).
     *
     * @param resource SVG resource path (relative to the classpath root)
     * @return Theme-adaptive icon
     */
    private static FlatSVGIcon navIcon(String resource) {
        FlatSVGIcon icon = new FlatSVGIcon(resource, NAV_ICON_SIZE, NAV_ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> {
            Color fg = UIManager.getColor("Button.foreground");
            return fg != null ? fg : color;
        }));
        return icon;
    }

    /**
     * Border color that follows the active theme, so separator borders keep adequate contrast in
     * both light and dark themes (the previous hardcoded {@code Color.DARK_GRAY} washed out / lost
     * contrast on dark backgrounds). Falls back to {@link Color#GRAY} if the LAF exposes no value.
     *
     * @return Theme-aware border color
     */
    private static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : Color.GRAY;
    }

    /**
     * Applies the shared look and accessibility wiring for a navigation toggle button: icon-over-text
     * layout, a keyboard mnemonic (Alt+key), and a screen-reader description taken from the matching
     * panel-header label. The buttons are intentionally left focusable (they form a
     * {@link ButtonGroup}, so arrow keys traverse the group) — this is the primary feature switcher
     * and must be reachable without a mouse.
     *
     * @param button   The toggle button to configure
     * @param descKey  Label key for the accessible description
     * @param mnemonic {@link KeyEvent} virtual-key code for the Alt mnemonic
     */
    private static void configureNavButton(AbstractButton button, String descKey, int mnemonic) {
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setMnemonic(mnemonic);
        button.getAccessibleContext().setAccessibleDescription(labelUtil.getString(descKey));
    }

    /**
     * Getter method for embedButton
     *
     * @return embedButton
     */
    public JToggleButton getEmbedButton() {
        if (this.embedButton == null) {
            this.embedButton =
                    new JToggleButton(labelUtil.getString("gui.label.tab.dhEmbed"), navIcon("images/embed.svg"), true);
            configureNavButton(this.embedButton, "gui.label.panelHeader.dhEmbed", KeyEvent.VK_D);
            this.actionButtonGroup.add(this.embedButton);
        }
        return this.embedButton;
    }

    /**
     * Getter method for extractButton
     *
     * @return extractButton
     */
    public JToggleButton getExtractButton() {
        if (this.extractButton == null) {
            this.extractButton =
                    new JToggleButton(labelUtil.getString("gui.label.tab.dhExtract"), navIcon("images/extract.svg"));
            configureNavButton(this.extractButton, "gui.label.panelHeader.dhExtract", KeyEvent.VK_T);
            this.actionButtonGroup.add(this.extractButton);
        }
        return this.extractButton;
    }

    /**
     * Getter method for genSigButton
     *
     * @return genSigButton
     */
    public JToggleButton getGenSigButton() {
        if (this.genSigButton == null) {
            this.genSigButton =
                    new JToggleButton(labelUtil.getString("gui.label.tab.wmGenSig"), navIcon("images/gensig.svg"));
            configureNavButton(this.genSigButton, "gui.label.panelHeader.wmGenSig", KeyEvent.VK_G);
            this.actionButtonGroup.add(this.genSigButton);
        }
        return this.genSigButton;
    }

    /**
     * Getter method for signWmButton
     *
     * @return signWmButton
     */
    public JToggleButton getSignWmButton() {
        if (this.signWmButton == null) {
            this.signWmButton = new JToggleButton(
                    labelUtil.getString("gui.label.tab.wmEmbed"), navIcon("images/watermark-embed.svg"));
            configureNavButton(this.signWmButton, "gui.label.panelHeader.wmEmbed", KeyEvent.VK_W);
            this.actionButtonGroup.add(this.signWmButton);
        }
        return this.signWmButton;
    }

    /**
     * Getter method for verifyWmButton
     *
     * @return verifyWmButton
     */
    public JToggleButton getVerifyWmButton() {
        if (this.verifyWmButton == null) {
            this.verifyWmButton = new JToggleButton(
                    labelUtil.getString("gui.label.tab.wmVerify"), navIcon("images/watermark-verify.svg"));
            configureNavButton(this.verifyWmButton, "gui.label.panelHeader.wmVerify", KeyEvent.VK_Y);
            this.actionButtonGroup.add(this.verifyWmButton);
        }
        return this.verifyWmButton;
    }

    /**
     * Getter method for headerPanel
     *
     * @return headerPanel
     */
    public JPanel getHeaderPanel() {
        if (this.headerPanel == null) {
            this.headerPanel = new JPanel();
            this.headerPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor()),
                    BorderFactory.createEmptyBorder(5, 5, 5, 5)));
            this.headerPanel.setLayout(new GridLayout());
            this.headerPanel.add(getHeader());
        }
        return this.headerPanel;
    }

    /**
     * Getter method for header
     *
     * @return header
     */
    public JLabel getHeader() {
        if (this.header == null) {
            this.header = new JLabel();
            this.header.setFont(this.header
                    .getFont()
                    .deriveFont(Font.BOLD, this.header.getFont().getSize2D() + 3f));
        }
        return this.header;
    }

    /**
     * Getter method for mainPanel
     *
     * @return mainPanel
     */
    public JPanel getMainPanel() {
        if (this.mainPanel == null) {
            this.mainPanel = new JPanel();
            this.mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            this.mainPanel.setLayout(new GridLayout());
        }
        return this.mainPanel;
    }

    /**
     * Getter method for embedPanel
     *
     * @return embedPanel
     */
    public EmbedPanel getEmbedPanel() {
        if (this.embedPanel == null) {
            // The plugin-specific options panel is swapped in by the controller based on the
            // selected algorithm (see OpenStegoUI)
            this.embedPanel = new EmbedPanel();
            this.embedPanel.initialize();
        }
        return this.embedPanel;
    }

    /**
     * Getter method for extractPanel
     *
     * @return extractPanel
     */
    public ExtractPanel getExtractPanel() {
        if (this.extractPanel == null) {
            this.extractPanel = new ExtractPanel();
        }
        return this.extractPanel;
    }

    /**
     * Getter method for genSigPanel
     *
     * @return genSigPanel
     */
    public GenerateSignaturePanel getGenSigPanel() {
        if (this.genSigPanel == null) {
            this.genSigPanel = new GenerateSignaturePanel();
        }
        return this.genSigPanel;
    }

    /**
     * Getter method for embedWmPanel
     *
     * @return embedWmPanel
     */
    public EmbedWatermarkPanel getEmbedWmPanel() {
        if (this.embedWmPanel == null) {
            this.embedWmPanel = new EmbedWatermarkPanel();
        }
        return this.embedWmPanel;
    }

    /**
     * Getter method for verifyWmPanel
     *
     * @return verifyWmPanel
     */
    public VerifyWatermarkPanel getVerifyWmPanel() {
        if (this.verifyWmPanel == null) {
            this.verifyWmPanel = new VerifyWatermarkPanel();
        }
        return this.verifyWmPanel;
    }

    /**
     * This method is called from within the constructor to
     * initialize the form.
     */
    private void initialize() {
        this.setContentPane(getMainContentPane());
        this.setTitle(labelUtil.getString("gui.window.title"));
        this.setJMenuBar(getTopMenuBar());

        getMainPanel().add(getEmbedPanel());
        getHeader().setText(labelUtil.getString("gui.label.panelHeader.dhEmbed"));
    }

    /**
     * Method to set the action commands for interactive UI items
     */
    private void setActionCommands() {
        getFileExitMenuItem().setActionCommand(ActionCommands.MENU_FILE_EXIT);
        getHelpAboutMenuItem().setActionCommand(ActionCommands.MENU_HELP_ABOUT);

        getEmbedButton().setActionCommand(ActionCommands.SWITCH_DH_EMBED);
        getExtractButton().setActionCommand(ActionCommands.SWITCH_DH_EXTRACT);
        getGenSigButton().setActionCommand(ActionCommands.SWITCH_WM_GENSIG);
        getSignWmButton().setActionCommand(ActionCommands.SWITCH_WM_EMBED);
        getVerifyWmButton().setActionCommand(ActionCommands.SWITCH_WM_VERIFY);

        getEmbedPanel().getMsgFileButton().setActionCommand(ActionCommands.BROWSE_DH_EMB_MSGFILE);
        getEmbedPanel().getCoverFileButton().setActionCommand(ActionCommands.BROWSE_DH_EMB_CVRFILE);
        getEmbedPanel().getStegoFileButton().setActionCommand(ActionCommands.BROWSE_DH_EMB_STGFILE);
        getEmbedPanel().getRunEmbedButton().setActionCommand(ActionCommands.RUN_DH_EMBED);

        getExtractPanel().getInputStegoFileButton().setActionCommand(ActionCommands.BROWSE_DH_EXT_STGFILE);
        getExtractPanel().getOutputFolderButton().setActionCommand(ActionCommands.BROWSE_DH_EXT_OUTDIR);
        getExtractPanel().getRunExtractButton().setActionCommand(ActionCommands.RUN_DH_EXTRACT);

        getGenSigPanel().getSignatureFileButton().setActionCommand(ActionCommands.BROWSE_WM_GSG_SIGFILE);
        getGenSigPanel().getRunGenSigButton().setActionCommand(ActionCommands.RUN_WM_GENSIG);

        getEmbedWmPanel().getFileForWmButton().setActionCommand(ActionCommands.BROWSE_WM_EMB_INPFILE);
        getEmbedWmPanel().getSignatureFileButton().setActionCommand(ActionCommands.BROWSE_WM_EMB_SIGFILE);
        getEmbedWmPanel().getOutputWmFileButton().setActionCommand(ActionCommands.BROWSE_WM_EMB_OUTFILE);
        getEmbedWmPanel().getRunEmbedWmButton().setActionCommand(ActionCommands.RUN_WM_EMBED);

        getVerifyWmPanel().getInputFileButton().setActionCommand(ActionCommands.BROWSE_WM_VER_INPFILE);
        getVerifyWmPanel().getSignatureFileButton().setActionCommand(ActionCommands.BROWSE_WM_VER_SIGFILE);
        getVerifyWmPanel().getRunVerifyWmButton().setActionCommand(ActionCommands.RUN_WM_VERIFY);
    }

    /**
     * Enumeration for button actions
     */
    public interface ActionCommands {
        /**
         * Menu - File - Exit
         */
        String MENU_FILE_EXIT = "MENU_FILE_EXIT";
        /**
         * Menu - Help - About
         */
        String MENU_HELP_ABOUT = "MENU_HELP_ABOUT";

        /**
         * Switch to Data Hiding - Embed panel
         */
        String SWITCH_DH_EMBED = "SWITCH_DH_EMBED";
        /**
         * Switch to Data Hiding - Extract panel
         */
        String SWITCH_DH_EXTRACT = "SWITCH_DH_EXTRACT";
        /**
         * Switch to Watermarking - GenSig panel
         */
        String SWITCH_WM_GENSIG = "SWITCH_WM_GENSIG";
        /**
         * Switch to Watermarking - Embed panel
         */
        String SWITCH_WM_EMBED = "SWITCH_WM_EMBED";
        /**
         * Switch to Watermarking - Verify panel
         */
        String SWITCH_WM_VERIFY = "SWITCH_WM_VERIFY";

        /**
         * Browse action for DH-Embed-MessageFile
         */
        String BROWSE_DH_EMB_MSGFILE = "BROWSE_DH_EMB_MSGFILE";
        /**
         * Browse action for DH-Embed-CoverFile
         */
        String BROWSE_DH_EMB_CVRFILE = "BROWSE_DH_EMB_CVRFILE";
        /**
         * Browse action for DH-Embed-StegoFile
         */
        String BROWSE_DH_EMB_STGFILE = "BROWSE_DH_EMB_STGFILE";
        /**
         * Execute DH-Embed
         */
        String RUN_DH_EMBED = "RUN_DH_EMBED";

        /**
         * Browse action for DH-Extract-StegoFile
         */
        String BROWSE_DH_EXT_STGFILE = "BROWSE_DH_EXT_STGFILE";
        /**
         * Browse action for DH-Extract-OutputFolder
         */
        String BROWSE_DH_EXT_OUTDIR = "BROWSE_DH_EXT_OUTDIR";
        /**
         * Execute DH-Extract
         */
        String RUN_DH_EXTRACT = "RUN_DH_EXTRACT";

        /**
         * Browse action for WM-GenSig-SigFile
         */
        String BROWSE_WM_GSG_SIGFILE = "BROWSE_WM_GSG_SIGFILE";
        /**
         * Execute WM-GenSig
         */
        String RUN_WM_GENSIG = "RUN_WM_GENSIG";

        /**
         * Browse action for WM-Embed-InputFile
         */
        String BROWSE_WM_EMB_INPFILE = "BROWSE_WM_EMB_INPFILE";
        /**
         * Browse action for WM-Embed-SignatureFile
         */
        String BROWSE_WM_EMB_SIGFILE = "BROWSE_WM_EMB_SIGFILE";
        /**
         * Browse action for WM-Embed-OutputFile
         */
        String BROWSE_WM_EMB_OUTFILE = "BROWSE_WM_EMB_OUTFILE";
        /**
         * Execute WM-Embed
         */
        String RUN_WM_EMBED = "RUN_WM_EMBED";

        /**
         * Browse action for WM-Verify-InputFile
         */
        String BROWSE_WM_VER_INPFILE = "BROWSE_WM_VER_INPFILE";
        /**
         * Browse action for WM-Verify-SignatureFile
         */
        String BROWSE_WM_VER_SIGFILE = "BROWSE_WM_VER_SIGFILE";
        /**
         * Execute WM-Verify
         */
        String RUN_WM_VERIFY = "RUN_WM_VERIFY";
    }

    static class GradientPanel extends JPanel {
        private static final long serialVersionUID = 3865918400221647086L;
        private final Color startColor;
        private final Color endColor;

        /**
         * Default constructor
         *
         * @param startColor Start of gradient
         * @param endColor   End of gradient
         */
        public GradientPanel(Color startColor, Color endColor) {
            this.startColor = startColor;
            this.endColor = endColor;
        }

        /*
         * (non-Javadoc)
         * @see javax.swing.JComponent#paintComponent(java.awt.Graphics)
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int panelHeight = getHeight();
            int panelWidth = getWidth();

            GradientPaint gradientPaint = new GradientPaint(0, 0, this.startColor, 0, panelHeight, this.endColor);
            if (g instanceof Graphics2D) {
                Graphics2D graphics2D = (Graphics2D) g;
                graphics2D.setPaint(gradientPaint);
                graphics2D.fillRect(0, 0, panelWidth, panelHeight);
            }
        }
    }
}
