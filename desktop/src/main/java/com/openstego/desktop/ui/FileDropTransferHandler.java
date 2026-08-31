/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.openstego.desktop.ui;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.List;
import javax.swing.JTextField;
import javax.swing.TransferHandler;

/**
 * Transfer handler that lets the user drag-and-drop files onto a text field. Dropped file paths are
 * placed into the field; when multiple selection is enabled, paths are joined with a semicolon (the
 * separator used elsewhere for multi-file inputs). Plain text paste is still supported.
 */
class FileDropTransferHandler extends TransferHandler {
    private static final long serialVersionUID = 1L;

    private final JTextField target;
    private final boolean multiSelect;

    FileDropTransferHandler(JTextField target, boolean multiSelect) {
        this.target = target;
        this.multiSelect = multiSelect;
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean importData(TransferSupport support) {
        if (!canImport(support)) {
            return false;
        }
        try {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                List<File> files =
                        (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (files == null || files.isEmpty()) {
                    return false;
                }
                if (this.multiSelect) {
                    this.target.setText(String.join(";", files.stream().map(File::getPath).toList()));
                } else {
                    this.target.setText(files.get(0).getPath());
                }
                return true;
            }

            // Fall back to plain text paste/drop
            String text = (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            this.target.replaceSelection(text);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
