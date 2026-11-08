package com.dabomstew.pkrandom.ctr;

/*----------------------------------------------------------------------------*/
/*--  FolderNCCH.java - an NCCH replacement that reads an already-extracted --*/
/*--                    RomFS/ExeFS from disk instead of a ROM image.       --*/
/*--                                                                        --*/
/*--  Part of "Universal Pokemon Randomizer ZX" by the UPR-ZX team          --*/
/*--  Pokemon and any associated names and the like are                     --*/
/*--  trademark and (C) Nintendo 1996-2020.                                 --*/
/*--                                                                        --*/
/*--  This program is free software: you can redistribute it and/or modify  --*/
/*--  it under the terms of the GNU General Public License as published by  --*/
/*--  the Free Software Foundation, either version 3 of the License, or     --*/
/*--  (at your option) any later version.                                   --*/
/*--                                                                        --*/
/*--  This program is distributed in the hope that it will be useful,       --*/
/*--  but WITHOUT ANY WARRANTY; without even the implied warranty of        --*/
/*--  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the          --*/
/*--  GNU General Public License for more details.                          --*/
/*--                                                                        --*/
/*--  You should have received a copy of the GNU General Public License     --*/
/*--  along with this program. If not, see <http://www.gnu.org/licenses/>.  --*/
/*----------------------------------------------------------------------------*/

import com.dabomstew.pkrandom.FileFunctions;
import com.dabomstew.pkrandom.SysConstants;
import cuecompressors.BLZCoder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Works on a game that has already been extracted into folders (for example with HackingToolkit,
 * 3dstool or pk3DS), including games that have had a mod merged into those folders. Nothing is
 * rebuilt into a ROM: unchanged files are never touched, and only the files the randomizer actually
 * changed are written back, either over the loaded folders (with a backup of the originals) or
 * into a separate LayeredFS-style output folder.
 *
 * Accepted layouts (the folder the user picks can be any of these):
 *   - a folder that contains a "romfs" and an "exefs" folder
 *   - a folder that contains "ExtractedRomFS" and "ExtractedExeFS" (HackingToolkit)
 *   - the romfs folder itself, with the exefs folder next to it
 *
 * The title ID and product code are needed to recognise the game. They are read, in this order, from:
 *   1. a "uvr_game.properties" file (productCode=..., titleId=...)
 *   2. an NCCH header dump (a 0x200-byte file with the NCCH magic, e.g. HeaderNCCH0.bin)
 *   3. an ExHeader dump (program ID), with the product code taken from the known title IDs below
 */
public class FolderNCCH extends NCCH {

    public static final String GAME_PROPERTIES_FILE = "uvr_game.properties";

    private static final String[] ROMFS_DIR_NAMES = {"romfs", "extractedromfs"};
    private static final String[] EXEFS_DIR_NAMES = {"exefs", "extractedexefs"};
    private static final String[] CODE_FILE_NAMES = {"code.bin", ".code.bin", ".code", "code"};
    private static final int NCCH_HEADER_SIZE = 0x200;
    private static final int NCCH_MAGIC = 0x4E434348; // "NCCH"

    // Product codes for the games the randomizer knows, used only when the folder has the title ID (from an
    // ExHeader dump) but no NCCH header to read the product code from.
    private static final Map<String, String> KNOWN_PRODUCT_CODES = new HashMap<>();
    static {
        KNOWN_PRODUCT_CODES.put("0004000000164800", "CTR-P-BNDA"); // Sun
        KNOWN_PRODUCT_CODES.put("0004000000175E00", "CTR-P-BNEA"); // Moon
        KNOWN_PRODUCT_CODES.put("00040000001B5000", "CTR-P-A2AA"); // Ultra Sun
        KNOWN_PRODUCT_CODES.put("00040000001B5100", "CTR-P-A2BA"); // Ultra Moon
    }

    private static class Layout {
        File root;
        File romfsDir;
        File exefsDir;   // may be null when code.bin sits next to the romfs folder
        File codeFile;
        File ncchHeader; // may be null
        File exHeader;   // may be null
        File gameProperties; // may be null
    }

    private final Layout layout;
    private final String productCode;
    private final String titleId;
    private final boolean codeCompressedOnDisk;

    private final Map<String, byte[]> changedFiles = new TreeMap<>();
    private byte[] codeCache;
    private byte[] codeOriginal;
    private boolean codeOpen, codeWritten;

    public FolderNCCH(File dir, String productCode, String titleId) throws IOException {
        super();
        Layout l = resolve(dir);
        if (l == null) {
            throw new IOException("No se encontró un RomFS válido en " + dir.getAbsolutePath()
                    + " (se espera una carpeta 'romfs' / 'ExtractedRomFS' con la carpeta 'a' dentro).");
        }
        if (l.codeFile == null) {
            throw new IOException("No se encontró el archivo code.bin (carpeta 'exefs' / 'ExtractedExeFS') junto a "
                    + l.romfsDir.getAbsolutePath());
        }
        this.layout = l;
        this.productCode = productCode;
        this.titleId = titleId;
        this.codeCompressedOnDisk = readCompressedFlag(l);
    }

    // ------------------------------------------------------------------ detection

    public static boolean isFolder(String filename) {
        return new File(filename).isDirectory();
    }

    public static String detectProductCode(File dir) {
        Layout l = resolve(dir);
        return l == null ? null : readIdentity(l)[0];
    }

    public static String detectTitleId(File dir) {
        Layout l = resolve(dir);
        return l == null ? null : readIdentity(l)[1];
    }

    private static Layout resolve(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        Layout l = new Layout();
        l.root = dir;
        File base;
        if (looksLikeRomfs(dir)) {
            l.romfsDir = dir;
            base = dir.getAbsoluteFile().getParentFile();
        } else {
            base = dir;
            l.romfsDir = findChildDir(dir, ROMFS_DIR_NAMES, true);
        }
        if (l.romfsDir == null) {
            return null;
        }

        if (base != null) {
            l.exefsDir = findChildDir(base, EXEFS_DIR_NAMES, false);
            if (l.exefsDir != null) {
                l.codeFile = findCodeFile(l.exefsDir);
            }
            if (l.codeFile == null) {
                l.codeFile = findCodeFile(base);
            }
        }

        List<File> searchDirs = new ArrayList<>();
        searchDirs.add(dir);
        if (base != null && !base.equals(dir)) {
            searchDirs.add(base);
        }
        for (File d : searchDirs) {
            File[] children = d.listFiles();
            if (children == null) continue;
            Arrays.sort(children);
            for (File f : children) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (l.gameProperties == null && name.equals(GAME_PROPERTIES_FILE)) {
                    l.gameProperties = f;
                } else if (l.exHeader == null && (name.equals("exheader.bin") || name.equals("exheader"))) {
                    l.exHeader = f;
                } else if (l.ncchHeader == null && f.length() == NCCH_HEADER_SIZE && hasNcchMagic(f)) {
                    l.ncchHeader = f;
                }
            }
        }
        return l;
    }

    private static boolean looksLikeRomfs(File d) {
        if (!d.isDirectory() || !new File(d, "a").isDirectory()) {
            return false;
        }
        return new File(d, "Battle.cro").isFile() || new File(d, "DllBattle.cro").isFile()
                || new File(d, "a" + File.separator + "0").isDirectory();
    }

    private static File findChildDir(File parent, String[] names, boolean romfs) {
        File[] children = parent.listFiles();
        if (children == null) return null;
        Arrays.sort(children);
        for (File c : children) {
            if (!c.isDirectory()) continue;
            String lower = c.getName().toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (lower.equals(n) && (romfs ? looksLikeRomfs(c) : findCodeFile(c) != null)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static File findCodeFile(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        Arrays.sort(children);
        for (String wanted : CODE_FILE_NAMES) {
            for (File c : children) {
                if (c.isFile() && c.getName().equalsIgnoreCase(wanted)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static boolean hasNcchMagic(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] header = new byte[NCCH_HEADER_SIZE];
            if (fis.read(header) < NCCH_HEADER_SIZE) return false;
            return FileFunctions.readFullIntBigEndian(header, 0x100) == NCCH_MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    // returns {productCode, titleId}; either may be null
    private static String[] readIdentity(Layout l) {
        String productCode = null, titleId = null;

        if (l.gameProperties != null) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(l.gameProperties)) {
                props.load(fis);
                if (props.getProperty("productCode") != null) productCode = props.getProperty("productCode").trim();
                if (props.getProperty("titleId") != null) titleId = props.getProperty("titleId").trim().toUpperCase(Locale.ROOT);
            } catch (IOException e) {
                System.err.println("FolderNCCH: could not read " + l.gameProperties + ": " + e.getMessage());
            }
        }

        if ((productCode == null || titleId == null) && l.ncchHeader != null) {
            try {
                byte[] header = Files.readAllBytes(l.ncchHeader.toPath());
                if (productCode == null) {
                    productCode = new String(header, 0x150, 0x10, StandardCharsets.UTF_8).trim();
                }
                if (titleId == null) {
                    titleId = programIdAt(header, 0x118);
                }
            } catch (IOException e) {
                System.err.println("FolderNCCH: could not read " + l.ncchHeader + ": " + e.getMessage());
            }
        }

        if (titleId == null && l.exHeader != null) {
            try {
                byte[] exheader = Files.readAllBytes(l.exHeader.toPath());
                if (exheader.length >= 0x208) {
                    titleId = programIdAt(exheader, 0x200);
                }
            } catch (IOException e) {
                System.err.println("FolderNCCH: could not read " + l.exHeader + ": " + e.getMessage());
            }
        }

        if (productCode == null && titleId != null) {
            productCode = KNOWN_PRODUCT_CODES.get(titleId);
        }
        return new String[]{productCode, titleId};
    }

    private static String programIdAt(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) {
            sb.append(String.format("%02X", data[offset + i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean readCompressedFlag(Layout l) throws IOException {
        if (l.exHeader == null) {
            return false;
        }
        byte[] exheader = Files.readAllBytes(l.exHeader.toPath());
        if (exheader.length <= 0x0D || (exheader[0x0D] & 0x01) == 0) {
            return false;
        }
        // Tools such as HackingToolkit decompress code.bin but may leave the flag set, so only treat the
        // file as compressed if it actually ends with a BLZ footer.
        return looksBlzCompressed(Files.readAllBytes(l.codeFile.toPath()));
    }

    private static boolean looksBlzCompressed(byte[] code) {
        if (code.length < 8) return false;
        int headerLen = code[code.length - 5] & 0xFF;
        int encLen = (code[code.length - 8] & 0xFF) | ((code[code.length - 7] & 0xFF) << 8)
                | ((code[code.length - 6] & 0xFF) << 16);
        long incLen = (code[code.length - 4] & 0xFFL) | ((code[code.length - 3] & 0xFFL) << 8)
                | ((code[code.length - 2] & 0xFFL) << 16) | ((code[code.length - 1] & 0xFFL) << 24);
        return headerLen >= 8 && headerLen <= 15 && encLen >= headerLen && encLen <= code.length
                && incLen > 0 && incLen < 0x1000000L;
    }

    // ------------------------------------------------------------------ NCCH API

    @Override
    public void reopenROM() { }

    @Override
    public void closeROM() { }

    @Override
    public boolean isDecrypted() {
        return true;
    }

    @Override
    public String getProductCode() {
        return productCode;
    }

    @Override
    public String getTitleId() {
        return titleId;
    }

    @Override
    public int getVersion() {
        return 0;
    }

    @Override
    public boolean isWritingEnabled() {
        return false;
    }

    @Override
    public String getTmpFolder() {
        return null;
    }

    @Override
    public java.io.RandomAccessFile getBaseRom() {
        return null;
    }

    public File getRootDir() {
        return layout.root;
    }

    @Override
    public byte[] getCode() throws IOException {
        if (!codeOpen) {
            byte[] code = Files.readAllBytes(layout.codeFile.toPath());
            originalCodeCRC = FileFunctions.getCRC32(code);
            if (codeCompressedOnDisk) {
                code = new BLZCoder(null).BLZ_DecodePub(code, ".code");
            }
            codeCache = code;
            codeOriginal = code.clone();
            codeOpen = true;
        }
        return codeCache.clone();
    }

    @Override
    public void writeCode(byte[] code) throws IOException {
        if (!codeOpen) {
            getCode();
        }
        codeCache = code.clone();
        codeWritten = true;
    }

    @Override
    public boolean hasFile(String filename) {
        return changedFiles.containsKey(filename) || romfsFile(filename).isFile();
    }

    // returns null if file doesn't exist
    @Override
    public byte[] getFile(String filename) throws IOException {
        byte[] changed = changedFiles.get(filename);
        if (changed != null) {
            return changed.clone();
        }
        File f = romfsFile(filename);
        if (!f.isFile()) {
            return null;
        }
        return Files.readAllBytes(f.toPath());
    }

    @Override
    public void writeFile(String filename, byte[] data) throws IOException {
        if (!hasFile(filename)) {
            // The base NCCH ignores writes to files that are not in the romfs; make that visible, because
            // it is a common reason for "my changes did not show up in the game".
            System.err.println("FolderNCCH: ignoring write to " + filename + " because it does not exist in "
                    + layout.romfsDir.getAbsolutePath());
            return;
        }
        changedFiles.put(filename, data.clone());
    }

    @Override
    public void printRomDiagnostics(PrintStream logStream, NCCH gameUpdate) {
        logStream.println("Folder: " + layout.root.getName());
        logStream.println(".code: " + String.format("%08X", this.originalCodeCRC));
    }

    @Override
    public Map<String, String> getRomfsFilesDiagnostics() {
        return new HashMap<>();
    }

    @Override
    public void saveAsNCCH(String filename, String gameAcronym, long seed) {
        throw new UnsupportedOperationException(
                "Un juego cargado desde carpetas no se puede guardar como ROM; guárdalo en carpetas.");
    }

    // ------------------------------------------------------------------ saving

    private File romfsFile(String path) {
        return new File(layout.romfsDir, path.replace('/', File.separatorChar));
    }

    private boolean differsFromDisk(String path, byte[] data) throws IOException {
        File f = romfsFile(path);
        return !f.isFile() || !Arrays.equals(Files.readAllBytes(f.toPath()), data);
    }

    private boolean codeDiffers() {
        return codeOpen && codeWritten && !Arrays.equals(codeOriginal, codeCache);
    }

    private byte[] codeBytesForDisk() throws IOException {
        if (codeCompressedOnDisk) {
            return new BLZCoder(null).BLZ_EncodePub(codeCache, false, true, ".code");
        }
        return codeCache;
    }

    private File backupRoot() {
        File parent = layout.root.getAbsoluteFile().getParentFile();
        if (parent == null) {
            parent = new File(SysConstants.ROOT_PATH);
        }
        return new File(parent, layout.root.getName() + "_uvr_backup");
    }

    // Keeps the FIRST original of each file; later saves never overwrite an existing backup.
    private void backUp(File original, String relativePath) throws IOException {
        if (!original.isFile()) return;
        File dest = new File(backupRoot(), relativePath.replace('/', File.separatorChar));
        if (dest.exists()) return;
        dest.getParentFile().mkdirs();
        Files.copy(original.toPath(), dest.toPath());
    }

    private static void writeFully(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tmp = new File(target.getPath() + ".uvr_tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Writes every changed file over the loaded folders. The first original of each file is copied to
     * "<folder name>_uvr_backup" next to the loaded folder before it is overwritten.
     */
    public void saveInPlace() throws IOException {
        int written = 0;
        for (Map.Entry<String, byte[]> entry : changedFiles.entrySet()) {
            String path = entry.getKey();
            byte[] data = entry.getValue();
            if (!differsFromDisk(path, data)) continue;
            File target = romfsFile(path);
            backUp(target, "romfs/" + path);
            writeFully(target, data);
            written++;
        }
        if (codeDiffers()) {
            backUp(layout.codeFile, "exefs/" + layout.codeFile.getName());
            writeFully(layout.codeFile, codeBytesForDisk());
            written++;
        }
        System.out.println("FolderNCCH: wrote " + written + " changed file(s) over " + layout.root.getAbsolutePath()
                + "; backups of the originals are in " + backupRoot().getAbsolutePath());
    }

    /**
     * Writes only the changed files into outputPath/titleId/ in the layout Citra/Azahar (load/mods) and
     * Luma3DS (luma/titles) expect: romfs/..., exefs/code.bin and code.bin. Unlike the ROM-based
     * LayeredFS export, this never deletes anything that is already in the output folder.
     */
    @Override
    public void saveAsLayeredFS(String outputPath) throws IOException {
        File titleRoot = new File(outputPath, titleId);
        File romfsOut = new File(titleRoot, "romfs");
        int written = 0;
        for (Map.Entry<String, byte[]> entry : changedFiles.entrySet()) {
            String path = entry.getKey();
            byte[] data = entry.getValue();
            if (!differsFromDisk(path, data)) continue;
            writeFully(new File(romfsOut, path.replace('/', File.separatorChar)), data);
            written++;
        }
        if (codeDiffers()) {
            writeFully(new File(titleRoot, "code.bin"), codeCache);
            writeFully(new File(new File(titleRoot, "exefs"), "code.bin"), codeCache);
            written++;
        }
        System.out.println("FolderNCCH: wrote " + written + " changed file(s) to " + titleRoot.getAbsolutePath());
    }
}
