/*
 * Steganography utility to hide messages into cover files
 * Author: Samir Vaidya (mailto:syvaidya@gmail.com)
 * Copyright (c) Samir Vaidya
 * Modifications copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop;

import com.openstego.desktop.plugin.lsb.MultiCoverPayloadSplitter;
import com.openstego.desktop.plugin.template.image.DHImagePluginTemplate;
import com.openstego.desktop.util.CommonUtil;
import com.openstego.desktop.util.LabelUtil;
import com.openstego.desktop.util.PluginManager;
import com.openstego.desktop.util.cmd.PasswordInput;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the main class for OpenStego command line. Command-line parsing is handled by picocli; the
 * plugin SPI stays free of any command-line library (plugins declare their options via neutral
 * {@link PluginCmdLineOption} descriptors).
 */
public class OpenStegoCmd {
    /**
     * Logger instance
     */
    private static final Logger logger = Logger.getLogger(OpenStegoCmd.class.getName());

    /**
     * LabelUtil instance to retrieve labels
     */
    private static final LabelUtil labelUtil = LabelUtil.getInstance(OpenStego.NAMESPACE);

    /**
     * Supported commands (the first command-line argument, optionally prefixed with "--")
     */
    private static final Set<String> COMMANDS = new HashSet<>(Arrays.asList(
            "embed", "extract", "gensig", "embedmark", "checkmark", "diff",
            "readformats", "writeformats", "algorithms", "help"));

    /**
     * Value-bearing standard options that the command handlers read
     */
    private static final String[] VALUE_OPTIONS = {"-a", "-mf", "-cf", "-sf", "-xf", "-xd", "-gf", "-p", "-A", "-q"};

    /**
     * Main method for processing command line
     *
     * @param args Command line arguments
     */
    public static int execute(String[] args) {
        OpenStego stego = null;
        int exitCode = 0;
        try {
            if (args.length == 0) {
                displayUsage();
                return 2;
            }

            // The first argument is the command (an optional "--" prefix is accepted)
            String command = args[0];
            if (command.startsWith("--")) {
                command = command.substring(2);
            }
            if (!COMMANDS.contains(command)) {
                displayUsage();
                return 2;
            }
            String[] optionArgs = Arrays.copyOfRange(args, 1, args.length);

            // Determine the plugin (explicit via -a, else auto-selected)
            OpenStegoPlugin<?> plugin = selectPlugin(findAlgorithm(optionArgs), command);

            // Parse the options using picocli
            ParseResult parseResult;
            try {
                parseResult = new CommandLine(buildSpec(plugin)).parseArgs(optionArgs);
            } catch (ParameterException pe) {
                System.err.println(pe.getMessage());
                displayUsage();
                return 2;
            }

            Map<String, String> opt = collectStringOptions(parseResult);
            boolean splitMode = parseResult.hasMatchedOption("-S");

            // Optional JPEG output quality (0-100), e.g. for watermarking out to JPEG (upstream issue #24).
            if (opt.containsKey("-q")) {
                try {
                    float pct = Float.parseFloat(opt.get("-q"));
                    if (pct < 0 || pct > 100) {
                        throw new NumberFormatException();
                    }
                    com.openstego.desktop.util.ImageUtil.setJpegQuality(pct / 100.0f);
                } catch (NumberFormatException nfe) {
                    System.err.println("Invalid --quality value (expected 0-100): " + opt.get("-q"));
                    return 2;
                }
            }

            if (!command.equals("help") && !command.equals("algorithms")) {
                if (plugin == null) {
                    throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.NO_PLUGIN_SPECIFIED);
                }
                plugin.resetConfig();
                plugin.getConfig().initialize(buildConfigMap(parseResult, plugin));
                stego = new OpenStego(plugin, plugin.getConfig());
            }

            switch (command) {
                case "embed":
                    executeEmbed(opt, stego, plugin, splitMode);
                    break;
                case "embedmark":
                    executeEmbedMark(opt, stego);
                    break;
                case "extract":
                    executeExtract(opt, stego, plugin, splitMode);
                    break;
                case "checkmark":
                    executeCheckMark(opt, stego);
                    break;
                case "gensig":
                    executeGenSig(opt, stego);
                    break;
                case "diff":
                    executeDiff(opt, stego);
                    break;
                case "readformats":
                    plugin.getReadableFileExtensions().forEach(System.out::println);
                    break;
                case "writeformats":
                    plugin.getWritableFileExtensions().forEach(System.out::println);
                    break;
                case "algorithms":
                    for (OpenStegoPlugin<?> osp : PluginManager.getPlugins()) {
                        System.out.println(osp.getName() + " " + osp.getPurposesLabel() + " - " + osp.getDescription());
                    }
                    break;
                case "help":
                    if (plugin == null) {
                        displayUsage();
                    } else { // Show plugin-specific help
                        System.err.println(plugin.getUsage());
                    }
                    break;
                default:
                    displayUsage();
            }
        } catch (OpenStegoException osEx) {
            if (osEx.getErrorCode() == OpenStegoException.UNHANDLED_EXCEPTION) {
                logger.log(Level.SEVERE, osEx.getMessage(), osEx);
            } else {
                System.err.println(osEx.getMessage());
            }
            exitCode = 1;
        } catch (OpenStegoBulkException bulkEx) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bulkEx.getExceptions().size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(bulkEx.getKeys().get(i)).append(": ")
                        .append(bulkEx.getExceptions().get(i).getMessage()).append("\n");
            }
            System.err.println();
            System.err.println(labelUtil.getString("cmd.label.bulkerror.header"));
            System.err.println(sb);
            exitCode = 1;
        } catch (OutOfMemoryError oome) {
            // A payload too large for the cover/heap manifests as an OOM; give the same actionable guidance
            // as the GUI rather than a raw stack trace (upstream issue #67).
            System.err.println(labelUtil.getString("err.memory.full"));
            exitCode = 1;
        } catch (Exception ex) {
            logger.log(Level.SEVERE, ex.getMessage(), ex);
            exitCode = 1;
        } finally {
            if (stego != null) {
                stego.getConfig().clearPassword();
            }
        }
        return exitCode;
    }

    /**
     * Determines the plugin to use, given the (possibly null) algorithm name and the command.
     */
    private static OpenStegoPlugin<?> selectPlugin(String pluginName, String command) throws OpenStegoException {
        if (pluginName != null && !pluginName.isEmpty()) {
            OpenStegoPlugin<?> plugin = PluginManager.getPluginByName(pluginName);
            if (plugin == null) {
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.PLUGIN_NOT_FOUND, pluginName);
            }
            return plugin;
        }

        // Try to auto-select the plugin
        List<OpenStegoPlugin<?>> plugins = PluginManager.getPlugins();
        if (plugins.size() == 1) {
            return plugins.get(0);
        } else if (plugins.size() > 1) {
            if (command.equals("embed") || command.equals("extract")) {
                List<OpenStegoPlugin<?>> dhPlugins = PluginManager.getDataHidingPlugins();
                if (dhPlugins.size() == 1) {
                    return dhPlugins.get(0);
                }
            } else if (command.equals("gensig") || command.equals("embedmark") || command.equals("checkmark")) {
                List<OpenStegoPlugin<?>> wmPlugins = PluginManager.getWatermarkingPlugins();
                if (wmPlugins.size() == 1) {
                    return wmPlugins.get(0);
                }
            }
        }
        return null;
    }

    /**
     * Scans the option arguments for the algorithm name (-a / --algorithm).
     */
    private static String findAlgorithm(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ((arg.equals("-a") || arg.equals("--algorithm")) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (arg.startsWith("-a=")) {
                return arg.substring("-a=".length());
            }
            if (arg.startsWith("--algorithm=")) {
                return arg.substring("--algorithm=".length());
            }
        }
        return null;
    }

    /**
     * Builds the picocli command specification, including any plugin-specific options.
     */
    private static CommandSpec buildSpec(OpenStegoPlugin<?> plugin) {
        CommandSpec spec = CommandSpec.create();

        // Plugin / file options (value-bearing)
        addOption(spec, true, "-a", "--algorithm");
        addOption(spec, true, "-mf", "--messagefile");
        addOption(spec, true, "-cf", "--coverfile");
        addOption(spec, true, "-sf", "--stegofile");
        addOption(spec, true, "-xf", "--extractfile");
        addOption(spec, true, "-xd", "--extractdir");
        addOption(spec, true, "-gf", "--sigfile");
        addOption(spec, true, "-p", "--password");
        addOption(spec, true, "-A", "--cryptalgo");
        addOption(spec, true, "-q", "--quality");

        // Command flags
        addOption(spec, false, "-c", "--compress");
        addOption(spec, false, "-C", "--nocompress");
        addOption(spec, false, "-e", "--encrypt");
        addOption(spec, false, "-E", "--noencrypt");
        addOption(spec, false, "-L", "--legacyencrypt");
        addOption(spec, false, "-nf", "--nofilename");
        addOption(spec, false, "-S", "--split");

        // Plugin-specific options
        if (plugin != null) {
            for (PluginCmdLineOption pluginOption : plugin.getPluginCmdLineOptions()) {
                addOption(spec, pluginOption.isTakesArg(), pluginOption.getName(), pluginOption.getAltName());
            }
        }

        return spec;
    }

    private static void addOption(CommandSpec spec, boolean takesArg, String name, String altName) {
        String[] names = (altName == null) ? new String[]{name} : new String[]{name, altName};
        OptionSpec.Builder builder = OptionSpec.builder(names);
        if (takesArg) {
            builder.arity("1").type(String.class).paramLabel("<value>");
        }
        spec.addOption(builder.build());
    }

    /**
     * Collects the value-bearing standard options into a simple name-&gt;value map for the handlers.
     */
    private static Map<String, String> collectStringOptions(ParseResult parseResult) {
        Map<String, String> opt = new HashMap<>();
        for (String name : VALUE_OPTIONS) {
            if (parseResult.hasMatchedOption(name)) {
                String value = parseResult.matchedOptionValue(name, (String) null);
                if (value != null) {
                    opt.put(name, value.trim());
                }
            }
        }
        return opt;
    }

    /**
     * Builds the configuration map from the parsed options (standard items plus any plugin-specific ones).
     */
    private static Map<String, Object> buildConfigMap(ParseResult parseResult, OpenStegoPlugin<?> plugin) throws OpenStegoException {
        Map<String, Object> map = new HashMap<>();

        if (parseResult.hasMatchedOption("-c")) {
            map.put(OpenStegoConfig.USE_COMPRESSION, true);
        }
        if (parseResult.hasMatchedOption("-C")) {
            map.put(OpenStegoConfig.USE_COMPRESSION, false);
        }
        if (parseResult.hasMatchedOption("-e")) {
            map.put(OpenStegoConfig.USE_ENCRYPTION, true);
        }
        if (parseResult.hasMatchedOption("-E")) {
            map.put(OpenStegoConfig.USE_ENCRYPTION, false);
        }
        if (parseResult.hasMatchedOption("-p")) {
            map.put(OpenStegoConfig.PASSWORD, parseResult.matchedOptionValue("-p", (String) null));
        }
        if (parseResult.hasMatchedOption("-A")) {
            map.put(OpenStegoConfig.ENCRYPTION_ALGORITHM, parseResult.matchedOptionValue("-A", (String) null));
        }
        if (parseResult.hasMatchedOption("-L")) { // legacy (v2) encryption for upstream compatibility
            map.put(OpenStegoConfig.USE_STRONG_ENCRYPTION, false);
        }
        if (parseResult.hasMatchedOption("-nf")) { // do not embed the (unencrypted) original file name
            map.put(OpenStegoConfig.EMBED_FILE_NAME, false);
        }

        // Plugin-specific options
        Map<String, String> pluginValues = new HashMap<>();
        for (PluginCmdLineOption pluginOption : plugin.getPluginCmdLineOptions()) {
            if (parseResult.hasMatchedOption(pluginOption.getName())) {
                pluginValues.put(pluginOption.getName(), parseResult.matchedOptionValue(pluginOption.getName(), (String) null));
            }
        }
        plugin.addPluginConfigValues(map, pluginValues);

        return map;
    }

    /**
     * Method to execute "embed" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException     Processing issues
     * @throws OpenStegoBulkException Errors for multiple files
     */
    private static void executeEmbed(Map<String, String> opt, OpenStego stego, OpenStegoPlugin<?> plugin, boolean splitMode)
            throws OpenStegoException, OpenStegoBulkException {
        String msgFileName = opt.get("-mf");
        String coverFileName = opt.get("-cf");
        String stegoFileName = opt.get("-sf");
        List<File> coverFileList;

        // Check if we need to prompt for password
        if (stego.getConfig().isUseEncryption() && stego.getConfig().getPassword() == null) {
            stego.getConfig().setPassword(PasswordInput.acquirePassword(labelUtil.getString("cmd.msg.enterPassword") + " "));
        }

        File msgFile = (msgFileName == null || msgFileName.equals("-")) ? null : new File(msgFileName);

        // Split mode (upstream issue #67): spread one payload across multiple covers, one stego image
        // per cover, into the given output directory. Opt-in only; the default multi-cover behaviour
        // below (same message into each cover) is unchanged.
        if (splitMode) {
            executeEmbedSplit(msgFile, msgFileName, coverFileName, stegoFileName, stego, plugin);
            return;
        }

        coverFileList = CommonUtil.parseFileList(coverFileName, ";");
        // If no coverfile or only one coverfile is provided then use stegofile name given by the user
        if (coverFileList.size() <= 1) {
            if (coverFileList.size() == 0 && coverFileName != null && !coverFileName.equals("-")) {
                System.err.println(labelUtil.getString("cmd.msg.coverFileNotFound", coverFileName));
                return;
            }

            String stegoFile = (stegoFileName == null || stegoFileName.equals("-")) ? null : stegoFileName;
            CommonUtil.writeFile(
                    stego.embedData(msgFile, coverFileList.size() == 0 ? null : coverFileList.get(0), stegoFile),
                    stegoFile);
        }
        // Else loop through all coverfiles and overwrite the same coverfiles with generated stegofiles
        else {
            // If stego file name is provided, then warn user that it will be ignored
            if (stegoFileName != null && !stegoFileName.equals("-")) {
                System.err.println(labelUtil.getString("cmd.warn.stegoFileIgnored"));
            }

            OpenStegoBulkException bulkException = new OpenStegoBulkException();
            // Loop through all cover files
            for (File file : coverFileList) {
                coverFileName = file.getName();
                try {
                    CommonUtil.writeFile(stego.embedData(msgFile, file, coverFileName), coverFileName);
                    System.err.println(labelUtil.getString("cmd.msg.coverProcessed", coverFileName));
                } catch (OpenStegoException e) {
                    bulkException.add(coverFileName, e);
                }
            }
            bulkException.throwIfRequired();
        }
    }

    /**
     * Splits one payload across multiple covers (upstream issue #67), writing one stego image per
     * cover into the output directory given by {@code -sf}.
     */
    private static void executeEmbedSplit(File msgFile, String msgFileName, String coverFileName, String stegoFileName,
                                          OpenStego stego, OpenStegoPlugin<?> plugin) throws OpenStegoException {
        if (!(plugin instanceof DHImagePluginTemplate)) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_NOT_SUPPORTED);
        }
        DHImagePluginTemplate<?> dhPlugin = (DHImagePluginTemplate<?>) plugin;

        List<File> coverFileList = CommonUtil.parseFileList(coverFileName, ";");
        if (coverFileList.size() < 2) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_REQUIRES_MULTIPLE_COVERS, coverFileList.size());
        }
        if (stegoFileName == null || stegoFileName.equals("-") || !new File(stegoFileName).isDirectory()) {
            throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_REQUIRES_OUTPUT_DIR);
        }
        File outDir = new File(stegoFileName);

        byte[] msg;
        String embeddedName;
        if (msgFile == null) {
            try (java.io.InputStream is = System.in) {
                msg = CommonUtil.streamToBytes(is);
            } catch (java.io.IOException ioEx) {
                throw new OpenStegoException(ioEx);
            }
            embeddedName = null;
        } else {
            msg = CommonUtil.fileToBytes(msgFile);
            embeddedName = msgFile.getName();
        }

        List<byte[]> covers = new ArrayList<>(coverFileList.size());
        List<String> coverNames = new ArrayList<>(coverFileList.size());
        List<String> stegoOutPaths = new ArrayList<>(coverFileList.size());
        for (File cover : coverFileList) {
            covers.add(CommonUtil.fileToBytes(cover));
            coverNames.add(cover.getName());
            stegoOutPaths.add(new File(outDir, cover.getName()).getPath());
        }

        List<byte[]> stegoImages = MultiCoverPayloadSplitter.embedSplit(msg, embeddedName, covers, coverNames,
                stegoOutPaths, stego.getConfig(), dhPlugin);
        for (int i = 0; i < stegoImages.size(); i++) {
            CommonUtil.writeFile(stegoImages.get(i), stegoOutPaths.get(i));
            System.err.println(labelUtil.getString("cmd.msg.coverProcessed", stegoOutPaths.get(i)));
        }
    }

    /**
     * Method to execute "embedmark" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException     Processing issues
     * @throws OpenStegoBulkException Errors for multiple files
     */
    private static void executeEmbedMark(Map<String, String> opt, OpenStego stego)
            throws OpenStegoException, OpenStegoBulkException {
        String sigFileName = opt.get("-gf");
        String coverFileName = opt.get("-cf");
        String stegoFileName = opt.get("-sf");

        File sigFile = (sigFileName == null || sigFileName.equals("-")) ? null : new File(sigFileName);
        List<File> coverFileList = CommonUtil.parseFileList(coverFileName, ";");
        // If no coverfile or only one coverfile is provided then use stegofile name given by the user
        if (coverFileList.size() <= 1) {
            if (coverFileList.size() == 0 && coverFileName != null && !coverFileName.equals("-")) {
                System.err.println(labelUtil.getString("cmd.msg.coverFileNotFound", coverFileName));
                return;
            }

            String stegoFile = (stegoFileName == null || stegoFileName.equals("-")) ? null : stegoFileName;
            CommonUtil.writeFile(
                    stego.embedMark(sigFile, coverFileList.size() == 0 ? null : coverFileList.get(0), stegoFile),
                    stegoFile);
        }
        // Else loop through all coverfiles and overwrite the same coverfiles with generated stegofiles
        else {
            // If stego file name is provided, then warn user that it will be ignored
            if (stegoFileName != null && !stegoFileName.equals("-")) {
                System.err.println(labelUtil.getString("cmd.warn.stegoFileIgnored"));
            }

            OpenStegoBulkException bulkException = new OpenStegoBulkException();
            // Loop through all cover files
            for (File file : coverFileList) {
                coverFileName = file.getName();
                try {
                    CommonUtil.writeFile(stego.embedMark(sigFile, file, coverFileName), coverFileName);
                    System.err.println(labelUtil.getString("cmd.msg.coverProcessed", coverFileName));
                } catch (OpenStegoException e) {
                    bulkException.add(coverFileName, e);
                }
            }
            bulkException.throwIfRequired();
        }
    }

    /**
     * Method to execute "extract" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException Processing issues
     */
    private static void executeExtract(Map<String, String> opt, OpenStego stego, OpenStegoPlugin<?> plugin, boolean splitMode)
            throws OpenStegoException {
        String stegoFileName = opt.get("-sf");
        String extractDir = opt.get("-xd");
        String extractFileName;
        List<?> msgData;

        if (stegoFileName == null) {
            displayUsage();
            return;
        }

        if (splitMode) {
            // Reassemble a payload that was split across multiple covers (upstream issue #67).
            if (!(plugin instanceof DHImagePluginTemplate)) {
                throw new OpenStegoException(null, OpenStego.NAMESPACE, OpenStegoErrors.SPLIT_NOT_SUPPORTED);
            }
            msgData = extractSplitWithPrompt(stegoFileName, stego, (DHImagePluginTemplate<?>) plugin);
            if (msgData == null) {
                return; // invalid password already reported
            }
        } else {
            // Read the stego image from stdin ("-sf -") or a file. Reading to bytes up front lets us
            // retry decryption with a password without having to re-read a (non-seekable) stdin stream.
            byte[] stegoBytes;
            String stegoName;
            if ("-".equals(stegoFileName)) {
                try (java.io.InputStream is = System.in) {
                    stegoBytes = CommonUtil.streamToBytes(is);
                } catch (java.io.IOException ioEx) {
                    throw new OpenStegoException(ioEx);
                }
                stegoName = "stdin";
            } else {
                File stegoFile = new File(stegoFileName);
                stegoBytes = CommonUtil.fileToBytes(stegoFile);
                stegoName = stegoFile.getName();
            }
            try {
                msgData = stego.extractData(stegoBytes, stegoName);
            } catch (OpenStegoException osEx) {
                if ((osEx.getErrorCode() == OpenStegoErrors.INVALID_PASSWORD || osEx.getErrorCode() == OpenStegoErrors.NO_VALID_PLUGIN)
                        && stego.getConfig().getPassword() == null) {
                    // Encrypted data with no password supplied yet: acquire one (env var or terminal)
                    // and retry once. A still-wrong password then propagates and exits non-zero.
                    stego.getConfig().setPassword(PasswordInput.acquirePassword(labelUtil.getString("cmd.msg.enterPassword") + " "));
                    msgData = stego.extractData(stegoBytes, stegoName);
                } else {
                    throw osEx;
                }
            }
        }

        extractFileName = opt.get("-xf");
        // "-xf -" streams the extracted payload to stdout (symmetric with "-sf -" on embed), so it
        // can be piped, e.g. neostego extract -sf img.png -p secret -xf - | tar xz
        if ("-".equals(extractFileName)) {
            CommonUtil.writeFile((byte[]) msgData.get(1), (String) null);
            return;
        }
        if (extractFileName == null) {
            extractFileName = (String) msgData.get(0);
            if (extractFileName == null || extractFileName.equals("")) {
                extractFileName = "untitled";
            }
        }

        if (extractDir != null) {
            extractFileName = extractDir + File.separator + extractFileName;
        }

        CommonUtil.writeFile((byte[]) msgData.get(1), extractFileName);
        System.err.println(labelUtil.getString("cmd.msg.fileExtracted", extractFileName));
    }

    /**
     * Reassembles a split payload from the {@code ;}-separated list of stego files, acquiring a
     * password (env var or terminal) once if the data turns out to be encrypted and none was supplied.
     *
     * @return The extracted [filename, bytes] list
     */
    private static List<?> extractSplitWithPrompt(String stegoFileNames, OpenStego stego, DHImagePluginTemplate<?> plugin)
            throws OpenStegoException {
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

        try {
            return MultiCoverPayloadSplitter.extractSplit(images, names, stego.getConfig(), plugin);
        } catch (OpenStegoException osEx) {
            if (osEx.getErrorCode() == OpenStegoErrors.INVALID_PASSWORD && stego.getConfig().getPassword() == null) {
                stego.getConfig().setPassword(PasswordInput.acquirePassword(labelUtil.getString("cmd.msg.enterPassword") + " "));
                return MultiCoverPayloadSplitter.extractSplit(images, names, stego.getConfig(), plugin);
            }
            throw osEx;
        }
    }

    /**
     * Method to execute "checkmark" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException Processing issues
     */
    private static void executeCheckMark(Map<String, String> opt, OpenStego stego) throws OpenStegoException {
        String stegoFileName = opt.get("-sf");
        String sigFileName = opt.get("-gf");
        List<File> stegoFileList;

        if (stegoFileName == null || sigFileName == null) {
            displayUsage();
            return;
        }

        stegoFileList = CommonUtil.parseFileList(stegoFileName, ";");
        // If only one stegofile is provided then use stegofile name given by the user
        if (stegoFileList.size() == 1) {
            System.out.println(stego.checkMark(stegoFileList.get(0), new File(sigFileName)));
        }
        // Else loop through all stegofiles and calculate correlation value for each
        else {
            for (File file : stegoFileList) {
                stegoFileName = file.getName();
                System.out.println(stegoFileName + "\t" + stego.checkMark(file, new File(sigFileName)));
            }
        }
    }

    /**
     * Method to execute "gensig" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException Processing issues
     */
    private static void executeGenSig(Map<String, String> opt, OpenStego stego) throws OpenStegoException {
        // Check if we need to prompt for password
        if (stego.getConfig().getPassword() == null) {
            stego.getConfig().setPassword(PasswordInput.acquirePassword(labelUtil.getString("cmd.msg.enterPassword") + " "));
        }

        String signatureFileName = opt.get("-gf");
        CommonUtil.writeFile(stego.generateSignature(), (signatureFileName == null || signatureFileName.equals("-")) ? null : signatureFileName);
    }

    /**
     * Method to execute "diff" command
     *
     * @param opt   Parsed command-line option values
     * @param stego {@link OpenStego} object
     * @throws OpenStegoException Processing issues
     */
    private static void executeDiff(Map<String, String> opt, OpenStego stego) throws OpenStegoException {
        String coverFileName = opt.get("-cf");
        String stegoFileName = opt.get("-sf");
        String extractDir = opt.get("-xd");
        String extractFileName = opt.get("-xf");

        if (extractDir != null) {
            extractFileName = extractDir + File.separator + extractFileName;
        }

        CommonUtil.writeFile(stego.getDiff(new File(stegoFileName), new File(coverFileName), extractFileName), extractFileName);
    }

    /**
     * Method to display usage for OpenStego
     *
     * @throws OpenStegoException Processing issues
     */
    private static void displayUsage() throws OpenStegoException {
        PluginManager.loadPlugins();

        System.err.print(labelUtil.getString("appName") + " " + labelUtil.getString("appVersion") + ". ");
        System.err.println(labelUtil.getString("copyright") + "\n");
        System.err.println(labelUtil.getString("cmd.usage", File.separator));
    }
}
