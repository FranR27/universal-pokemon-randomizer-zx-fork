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

    // isTraversable is asked for every folder in the listing, so remember recent answers (cheap, 3 s)
    private final java.util.Map<String, long[]> recentAnswers = new java.util.HashMap<>();

    private boolean isGameFolder(File f) {
        String key = f.getAbsolutePath();
        long now = System.currentTimeMillis();
        long[] cached = recentAnswers.get(key);
        if (cached != null && now - cached[0] < 3000) {
            return cached[1] == 1;
        }
        boolean result = f.isDirectory() && FolderNCCH.looksLikeGameFolder(f);
        recentAnswers.put(key, new long[]{now, result ? 1 : 0});
        return result;
    }

    // Swing (notably the Windows look and feel) enters a folder when it is chosen and Open is pressed, so a game
    // folder can never be returned. Saying it is not traversable makes Swing accept it as the selection instead.
    @Override
    public boolean isTraversable(File f) {
        if (f != null && getFileSelectionMode() == FILES_AND_DIRECTORIES && isGameFolder(f)) {
            return false;
        }
        return super.isTraversable(f);
    }

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
