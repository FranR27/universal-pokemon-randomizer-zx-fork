package com.dabomstew.pkrandom.newgui;

import com.dabomstew.pkrandom.ctr.FolderNCCH;

import javax.swing.*;
import java.io.File;

/**
 * A file chooser that treats an extracted 3DS game folder (RomFS + ExeFS) as a single choice. Java's chooser
 * enters a folder instead of selecting it; here, as soon as the chooser is about to enter a game folder
 * (double-click, or a folder selected and Open pressed) that folder is selected and the dialog is accepted.
 */
public class FolderAwareFileChooser extends JFileChooser {

    @Override
    public void setCurrentDirectory(File dir) {
        if (dir != null && isShowing() && FolderNCCH.looksLikeGameFolder(dir)) {
            final File gameFolder = dir;
            SwingUtilities.invokeLater(() -> {
                setSelectedFile(gameFolder);
                approveSelection();
            });
            return;
        }
        super.setCurrentDirectory(dir);
    }
}
