package com.dsu.xyg;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class UpdateFileProvider extends ContentProvider {
    private static final String PATH_PREFIX = "/updates/";
    // v3.55.59：APK 下载目录（与 UpdateCenter 下载路径一致）
    private static final String PDNA_UPDATE_DIR = "/sdcard/PDNA/update";

    public static Uri getUriForFile(Context context, File file) {
        return Uri.parse("content://" + context.getPackageName() + ".fileprovider" + PATH_PREFIX + file.getName());
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPath() == null || !uri.getPath().startsWith(PATH_PREFIX)) {
            throw new FileNotFoundException("Read-only update URI required");
        }
        String name = uri.getPath().substring(PATH_PREFIX.length());
        // v3.55.59：依次在 /sdcard/PDNA/update 和 cacheDir 中查找
        File[] candidates = {
                new File(PDNA_UPDATE_DIR, name),
                new File(requireContext().getCacheDir(), name),
        };
        File file = null;
        for (File c : candidates) {
            try {
                File canon = c.getCanonicalFile();
                if (canon.isFile()) { file = canon; break; }
            } catch (IOException ignored) { }
        }
        if (file == null) throw new FileNotFoundException("Update APK not found: " + name);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
}
