package com.dsu.xyg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ROM 卡刷包打包工具：默认读取当前 PDNA 工程目录下的 ROM 镜像分区（.img / .img.zst），
 * 与内置的 TWRP 刷机脚本、bin/android 工具一起打包为可卡刷的 zip，输出到当前工程目录。
 */
public class RomPackActivity extends Activity {

    // 卡刷包刷写脚本里引用的底层分区顺序（按一加13全底层包脚本）
    private static final String[] PARTITIONS = {
            "abl", "aop", "aop_config", "bluetooth", "boot", "cpucp", "cpucp_dtb",
            "devcfg", "dsp", "dtbo", "engineering_cdt", "featenabler", "hyp",
            "imagefv", "keymaster", "modem", "oplus_sec", "oplusstanvbk", "pdp",
            "pdp_cdb", "pvmfw", "qupfw", "recovery", "shrm", "soccp_dcd",
            "soccp_debug", "splash", "spuservice", "tz", "uefi", "uefisecapp",
            "vbmeta", "vbmeta_system", "vbmeta_vendor", "vendor_boot",
            "xbl", "xbl_config", "xbl_ramdump"
    };

    private String currentProject = null;
    private final List<ImgFile> pickedImages = new ArrayList<>();

    private TextView statusText;
    private TextView partitionSummary;
    private TextView projectName;
    private TextView projectOut;
    private ProgressBar progress;
    private Button startButton;
    private EditText deviceInput;
    private EditText authorInput;
    private EditText zipNameInput;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    private static class ImgFile {
        final String name;     // 不含扩展名，如 boot / super / init_boot / root_init_boot
        final String fileName; // 完整文件名，如 boot.img / super.img.zst
        final File file;
        final long size;

        ImgFile(String name, String fileName, File file, long size) {
            this.name = name; this.fileName = fileName; this.file = file; this.size = size;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 先取当前 PDNA 工程
        currentProject = DnaTools.INSTANCE.currentProject(this);
        buildUi();
        // 自动扫描当前工程
        ui.post(this::scanCurrentProject);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台后重新扫描（用户可能在外部修改了文件）
        if (currentProject != null) scanCurrentProject();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private int getStatusBarHeight() {
        int r = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return r > 0 ? getResources().getDimensionPixelSize(r) : dp(24);
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void promptStorageAccess() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("打包需要读取/写入 PDNA 工程目录 (/sdcard/PDNA/)。\n请授予本应用“所有文件访问”权限。")
                .setCancelable(false)
                .setPositiveButton("去授权", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            Intent it = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            it.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(it);
                        } catch (Exception fallback) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1A2E);
        root.setPadding(0, getStatusBarHeight() + dp(8), 0, 0);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(28));

        // 标题栏
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("<");
        back.setTextColor(0xFFFFFFFF);
        back.setTextSize(22);
        back.setBackground(makeGlass());
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setMinimumWidth(0); back.setMinimumHeight(0); back.setIncludeFontPadding(false);
        back.setOnClickListener(v -> finish());
        titleBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setText("ROM 卡刷包打包");
        title.setTextSize(19);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2);
        tlp.weight = 1; tlp.leftMargin = dp(12);
        titleBar.addView(title, tlp);
        content.addView(titleBar, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView subtitle = new TextView(this);
        subtitle.setText("读取 PDNA 工程 · 自动打包为 TWRP 卡刷 zip");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFFB9C8DA);
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, lp(-1, -2, 0, dp(8), 0, 0));

        // 说明卡片
        content.addView(makeStepsCard());

        // 工程（来源目录）
        content.addView(label("📂 读取工程（点击切换 · 镜像读取自工程目录，zip 输出到 PDNA 根目录）"),
                lp(-1, -2, 0, dp(14), 0, 0));
        LinearLayout projCard = new LinearLayout(this);
        projCard.setOrientation(LinearLayout.VERTICAL);
        projCard.setPadding(dp(14), dp(10), dp(14), dp(12));
        projCard.setBackground(makeGlass());
        projectName = new TextView(this);
        projectName.setTextSize(15.5f);
        projectName.setTypeface(null, 1);
        projectName.setTextColor(0xFFFFFFFF);
        projectName.setSingleLine(true);
        projectName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        projCard.addView(projectName, new LinearLayout.LayoutParams(-1, -2));
        projectOut = new TextView(this);
        projectOut.setTextSize(12f);
        projectOut.setTextColor(0xFF8FA6C2);
        projectOut.setSingleLine(true);
        projectOut.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(-1, -2);
        outLp.topMargin = dp(4);
        projCard.addView(projectOut, outLp);
        projCard.setOnClickListener(v -> showProjectPicker());
        content.addView(projCard, lp(-1, -2, 0, dp(6), 0, dp(8)));

        // 已识别分区摘要
        partitionSummary = new TextView(this);
        partitionSummary.setText("正在扫描当前工程…");
        partitionSummary.setTextSize(13.5f);
        partitionSummary.setTextColor(0xFFCBD9EA);
        partitionSummary.setLineSpacing(dp(3), 1f);
        content.addView(partitionSummary, lp(-1, -2, 0, dp(6), 0, dp(6)));

        // 包名（输出 zip 文件名）
        content.addView(label("🏷 卡刷包文件名（输出到工程目录）"), lp(-1, -2, 0, dp(12), 0, 0));
        zipNameInput = mkInput("包名（不含扩展名）", "");
        zipNameInput.setHint("如 OnePlus13_FullFlash_20261011");
        content.addView(zipNameInput, lp(-1, dp(46), 0, dp(6), 0, 0));

        // 机型名 / 作者
        deviceInput = mkInput("适配机型（如 一加13）", "一加13");
        content.addView(label("适配机型"), lp(-1, -2, 0, dp(12), 0, 0));
        content.addView(deviceInput, lp(-1, dp(46), 0, dp(6), 0, 0));
        authorInput = mkInput("制作作者", "星落人间");
        content.addView(label("制作作者"), lp(-1, -2, 0, dp(12), 0, 0));
        content.addView(authorInput, lp(-1, dp(46), 0, dp(6), 0, 0));

        // 开始打包
        startButton = new Button(this);
        startButton.setText("🚀 开始打包");
        startButton.setTextColor(0xFFFFFFFF);
        startButton.setAllCaps(false);
        startButton.setTextSize(16);
        startButton.setBackground(makeGradient(0xFF2E9E5B, 0xFF176C3E, dp(14)));
        startButton.setMinimumWidth(0); startButton.setMinimumHeight(0); startButton.setIncludeFontPadding(false);
        startButton.setStateListAnimator(null);
        startButton.setEnabled(false);
        startButton.setAlpha(.5f);
        startButton.setOnClickListener(v -> startPack());
        content.addView(startButton, lp(-1, dp(54), 0, dp(18), 0, 0));

        // 进度
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        content.addView(progress, lp(-1, dp(10), 0, dp(8), 0, 0));
        statusText = new TextView(this);
        statusText.setText("等待开始");
        statusText.setTextSize(13.5f);
        statusText.setTextColor(0xFFAEBFD3);
        statusText.setVisibility(View.GONE);
        content.addView(statusText, lp(-1, -2, 0, 0, 0, 0));

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private View makeStepsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(makeGlass());
        TextView t = new TextView(this);
        t.setText("📋 使用步骤");
        t.setTextSize(15); t.setTextColor(0xFFFFFFFF); t.setTypeface(null, 1);
        card.addView(t);
        TextView c = new TextView(this);
        c.setText("1. 镜像请放在当前工程目录（/sdcard/PDNA/<工程名>/）下\n" +
                "2. 自动识别 boot / modem / super 等分区\n" +
                "3. 若 super.img 未压缩，打包时自动 zstd 压缩为 super.img.zst\n" +
                "4. 若同时有 root_init_boot 与 init_boot，打包时启用音量键选择 Root\n" +
                "5. 打包完成的 zip 输出到 /sdcard/PDNA/ 根目录，可在 TWRP 中直接卡刷");
        c.setTextSize(13); c.setTextColor(0xFFC8D6E8); c.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        card.addView(c, clp);
        return card;
    }

    private EditText mkInput(String hint, String def) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(def);
        et.setTextSize(14);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF6E8099);
        et.setBackground(makeGlass());
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        et.setSingleLine(true);
        return et;
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(0xFFAEBFD3);
        return tv;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private android.graphics.drawable.Drawable makeGlass() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        return g;
    }

    private android.graphics.drawable.Drawable makeGradient(int c1, int c2, float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(radius);
        return g;
    }

    /** 扫描当前 PDNA 工程目录下的 .img / .img.zst 文件 */
    private void scanCurrentProject() {
        pickedImages.clear();
        if (!hasStorageAccess()) {
            projectName.setText("无存储权限");
            projectOut.setText("点击下方按钮去授权");
            partitionSummary.setText("未授予“所有文件访问”权限，无法读取工程目录");
            startButton.setEnabled(false);
            startButton.setAlpha(.5f);
            // 直接弹授权
            if (!isFinishing()) promptStorageAccess();
            return;
        }
        if (currentProject == null || currentProject.isEmpty()) {
            projectName.setText("未选择工程");
            projectOut.setText("点击此处选择要打包的工程");
            partitionSummary.setText("当前没有 PDNA 工程，请先在 DNA 工具箱创建工程");
            startButton.setEnabled(false);
            startButton.setAlpha(.5f);
            return;
        }
        File projDir = DnaTools.INSTANCE.projectDir(currentProject);
        projectName.setText(currentProject);
        projectOut.setText("➜ " + projDir.getAbsolutePath());
        // 扫描工程目录及其下的 images/ 子目录
        List<File> scanRoots = new ArrayList<>();
        scanRoots.add(projDir);
        File imagesSub = new File(projDir, "images");
        if (imagesSub.isDirectory()) scanRoots.add(imagesSub);
        File outSub = new File(projDir, "out");
        if (outSub.isDirectory()) scanRoots.add(outSub);

        for (File rootDir : scanRoots) {
            File[] files = rootDir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                // 跳过自身输出 zip
                if (lower.endsWith(".zip")) continue;
                if (lower.endsWith(".img.zst")) {
                    String base = name.substring(0, name.length() - ".img.zst".length());
                    addUnique(new ImgFile(base, name, f, f.length()));
                } else if (lower.endsWith(".img")) {
                    String base = name.substring(0, name.length() - ".img".length());
                    addUnique(new ImgFile(base, name, f, f.length()));
                }
            }
        }
        renderSummary();
    }

    private void addUnique(ImgFile im) {
        for (ImgFile e : pickedImages) {
            if (e.name.equals(im.name)) {
                // 优先保留 size 较大的版本
                if (im.size > e.size) {
                    pickedImages.remove(e);
                    pickedImages.add(im);
                }
                return;
            }
        }
        pickedImages.add(im);
    }

    private void renderSummary() {
        if (pickedImages.isEmpty()) {
            partitionSummary.setText("当前工程目录下未识别到任何 .img / .img.zst 镜像文件\n请把各分区镜像放入工程目录后重试");
            startButton.setEnabled(false);
            startButton.setAlpha(.5f);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已识别 ").append(pickedImages.size()).append(" 个镜像：\n");
        List<ImgFile> ordered = orderedImages();
        for (ImgFile im : ordered) {
            sb.append("  · ").append(im.fileName);
            if (im.size > 0) sb.append("  (").append(humanSize(im.size)).append(")");
            sb.append("\n");
        }
        boolean hasRoot = find("root_init_boot") != null;
        boolean hasInit = find("init_boot") != null;
        boolean hasSuper = find("super") != null;
        sb.append("\n选项：");
        sb.append(hasRoot && hasInit ? "init_boot 音量键选择Root" : hasRoot ? "仅 root_init_boot" : hasInit ? "原版 init_boot" : "无 init_boot");
        sb.append(" ｜ ").append(hasSuper ? "含 super" : "无 super");
        partitionSummary.setText(sb.toString().trim());
        startButton.setEnabled(true);
        startButton.setAlpha(1f);
    }

    private List<ImgFile> orderedImages() {
        List<ImgFile> out = new ArrayList<>();
        for (String p : PARTITIONS) {
            ImgFile im = find(p);
            if (im != null) out.add(im);
        }
        // 附加 super / init_boot / root_init_boot
        ImgFile sup = find("super");
        if (sup != null) out.add(sup);
        ImgFile init = find("init_boot");
        if (init != null) out.add(init);
        ImgFile root = find("root_init_boot");
        if (root != null) out.add(root);
        // 其他未匹配的也列出
        for (ImgFile im : pickedImages) {
            if (!out.contains(im)) out.add(im);
        }
        return out;
    }

    private ImgFile find(String name) {
        for (ImgFile im : pickedImages) if (im.name.equals(name)) return im;
        return null;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2fGB", bytes / 1073741824.0);
    }

    /** 切换 PDNA 工程选择对话框 */
    private void showProjectPicker() {
        if (!hasStorageAccess()) { promptStorageAccess(); return; }
        List<String> projects = DnaTools.INSTANCE.listProjects();
        if (projects.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("选择工程")
                    .setMessage("暂无 PDNA 工程，请先在 DNA 工具箱创建工程。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final String[] arr = projects.toArray(new String[0]);
        int checked = currentProject != null ? Collections.binarySearch(projects, currentProject) : -1;
        int checkedIdx = checked >= 0 ? checked : -1;
        new AlertDialog.Builder(this)
                .setTitle("选择工程")
                .setSingleChoiceItems(arr, checkedIdx, (d, which) -> {
                    currentProject = arr[which];
                    DnaTools.INSTANCE.setCurrentProject(this, currentProject);
                    d.dismiss();
                    scanCurrentProject();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startPack() {
        if (running) return;
        if (!hasStorageAccess()) { promptStorageAccess(); return; }
        if (currentProject == null || currentProject.isEmpty()) { toast("请先选择工程"); return; }
        if (pickedImages.isEmpty()) { toast("当前工程目录下没有可打包的镜像"); return; }

        String zipName = zipNameInput.getText().toString().trim();
        if (zipName.isEmpty()) {
            toast("请输入卡刷包文件名");
            zipNameInput.requestFocus();
            return;
        }
        // 简单清洗文件名，去除非法字符
        zipName = zipName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (zipName.isEmpty()) { toast("包名不合法"); return; }

        running = true;
        startButton.setEnabled(false);
        startButton.setAlpha(.5f);
        progress.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        statusText.setText("准备打包…");

        final String device = deviceInput.getText().toString().trim();
        final String author = authorInput.getText().toString().trim();
        final String outName = zipName + ".zip";
        // 输出到 PDNA 根目录（/sdcard/PDNA/）
        final File outDir = new File(DnaTools.WORK_ROOT);

        new Thread(() -> {
            Exception err = null;
            File outFile = null;
            try {
                if (!outDir.exists()) outDir.mkdirs();
                outFile = new File(outDir, outName);
                err = packInternal(outFile, device, author);
            } catch (Exception e) {
                err = e;
            }
            final Exception finalErr = err;
            final File finalOutFile = outFile;
            ui.post(() -> {
                running = false;
                startButton.setEnabled(true);
                startButton.setAlpha(1f);
                if (finalErr != null) {
                    statusText.setText("打包失败：" + finalErr.getMessage());
                    toast("打包失败：" + finalErr.getMessage());
                } else {
                    progress.setProgress(100);
                    String path = finalOutFile != null ? finalOutFile.getAbsolutePath() : outName;
                    statusText.setText("打包完成：" + path);
                    toast("打包完成，已输出到 PDNA 目录");
                }
            });
        }).start();
    }

    private Exception packInternal(File outFile, String device, String author) {
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(outFile), 256 * 1024));
            zos.setLevel(0); // 不压缩，提速；img 已是二进制

            // 判断 super 是否需要压缩：若 super.img 未压缩，打包时压成 super.img.zst
            ImgFile sup = find("super");
            boolean superNeedsCompress = (sup != null)
                    && !sup.fileName.toLowerCase(Locale.ROOT).endsWith(".zst");
            // 计算总步骤数（super 压缩多一步）
            int totalSteps = pickedImages.size() + 3 + (superNeedsCompress ? 1 : 0);
            int done = 0;

            // 1) 写刷机脚本（super 若压缩则脚本里强制走 zstd 解压）
            updateProgress("写入刷机脚本", done, totalSteps);
            byte[] script = buildUpdateBinary(device, author, superNeedsCompress).getBytes("UTF-8");
            zos.putNextEntry(new ZipEntry("META-INF/com/google/android/update-binary"));
            zos.write(script);
            zos.closeEntry();
            done++;

            // 2) 写 bin/android 工具
            String[] tools = {"7zzs", "zstd", "toolbox"};
            for (String t : tools) {
                updateProgress("写入工具 bin/android/" + t, done, totalSteps);
                byte[] data = readAsset("rom_pack/" + t);
                if (data != null) {
                    zos.putNextEntry(new ZipEntry("bin/android/" + t));
                    zos.write(data);
                    zos.closeEntry();
                }
            }
            done++;

            // 3) 写 images/* —— 先写非 super 分区，super 最后压缩写入
            for (ImgFile im : pickedImages) {
                if ("super".equals(im.name)) continue; // super 最后处理
                updateProgress("打包镜像 " + im.fileName + " (" + humanSize(im.size) + ")", done, totalSteps);
                zos.putNextEntry(new ZipEntry("images/" + im.fileName));
                FileInputStream fis = new FileInputStream(im.file);
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
                done++;
            }
            // super 最后处理
            if (sup != null && superNeedsCompress) {
                updateProgress("压缩并打包 super.img → super.img.zst (" + humanSize(sup.size) + ")", done, totalSteps);
                zos.putNextEntry(new ZipEntry("images/super.img.zst"));
                // 多线程分块压缩，直接写入 zip entry 流
                compressSuperZstd(sup.file, zos);
                zos.closeEntry();
                done++;
            } else if (sup != null) {
                updateProgress("打包镜像 " + sup.fileName + " (" + humanSize(sup.size) + ")", done, totalSteps);
                zos.putNextEntry(new ZipEntry("images/" + sup.fileName));
                FileInputStream fis2 = new FileInputStream(sup.file);
                byte[] buf2 = new byte[1024 * 1024];
                int n2;
                while ((n2 = fis2.read(buf2)) > 0) zos.write(buf2, 0, n2);
                fis2.close();
                zos.closeEntry();
                done++;
            }
            updateProgress("收尾中…", done, totalSteps);
            zos.finish();
            return null;
        } catch (Exception e) {
            return e;
        } finally {
            try { if (zos != null) zos.close(); } catch (Exception ignored) { }
        }
    }

    /** 单个数据块的压缩任务结果。 */
    private static class ChunkResult {
        final int index;       // 原始块序号，用于排序
        final byte[] compressed;
        ChunkResult(int index, byte[] compressed) { this.index = index; this.compressed = compressed; }
    }

    /**
     * 用 zstd-jni 多线程分块压缩 super.img 写入 zip。
     * 原理：把 super.img 切成多个小块，用线程池并行压缩每块为独立 zstd frame，
     * 再按原顺序拼接到输出流。多个 zstd frame 拼接的文件可被标准 zstd 解压器正确解压。
     * 采用分批提交（每批 = 线程数×2 个块），避免所有压缩结果堆积在内存导致 OOM。
     */
    private void compressSuperZstd(File src, java.io.OutputStream out) throws Exception {
        final int CHUNK = 8 * 1024 * 1024;  // 8MB 一块（控制单块内存占用，防 OOM）
        final int LEVEL = 3;                // 级别 3（速度优先，super 内部通常已压缩）
        final long totalLen = src.length();
        final int chunkCount = (int) ((totalLen + CHUNK - 1) / CHUNK);
        // 线程数：CPU 核数，上限 8
        final int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        // 每批提交的任务数（= 线程数，避免内存堆积导致 OOM）
        final int batchSize = threads;
        // GC 一次释放可能残留的内存
        System.gc();
        // 已完成块数（用于进度）
        final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.ExecutorService pool = null;
        try {
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            int submitted = 0;
            while (submitted < chunkCount) {
                // 计算本批任务数
                int thisBatch = Math.min(batchSize, chunkCount - submitted);
                @SuppressWarnings("unchecked")
                java.util.concurrent.Future<ChunkResult>[] batchFutures = new java.util.concurrent.Future[thisBatch];
                // 提交本批任务
                for (int j = 0; j < thisBatch; j++) {
                    final int idx = submitted + j;
                    final long offset = (long) idx * CHUNK;
                    final int len = (int) Math.min(CHUNK, totalLen - offset);
                    batchFutures[j] = pool.submit(() -> {
                        byte[] data = new byte[len];
                        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(src, "r")) {
                            r.seek(offset);
                            int read = 0;
                            while (read < len) {
                                int n = r.read(data, read, len - read);
                                if (n <= 0) break;
                                read += n;
                            }
                        }
                        byte[] compressed = com.github.luben.zstd.Zstd.compress(data, LEVEL);
                        int done = completed.incrementAndGet();
                        updateProgress("多线程压缩 super (" + threads + "线程) " + done + "/" + chunkCount, done, chunkCount);
                        return new ChunkResult(idx, compressed);
                    });
                }
                // 按顺序收集本批并写入（写入后释放内存）
                for (int j = 0; j < thisBatch; j++) {
                    ChunkResult cr = batchFutures[j].get();
                    out.write(cr.compressed, 0, cr.compressed.length);
                    batchFutures[j] = null; // 帮助 GC
                }
                submitted += thisBatch;
                System.gc(); // 每批结束回收内存，避免堆积
            }
        } finally {
            if (pool != null) {
                pool.shutdown();
                try { pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
            }
        }
    }

    private void updateProgress(final String msg, final int done, final int total) {
        ui.post(() -> {
            int p = total > 0 ? (int) (done * 100L / total) : 0;
            progress.setProgress(Math.min(99, p));
            statusText.setText(msg);
        });
    }

    private byte[] readAsset(String path) {
        try (InputStream is = getAssets().open(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 基于内置模板动态生成 update-binary 脚本，仅刷写实际存在的分区。 */
    private String buildUpdateBinary(String device, String author, boolean superCompressed) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/sbin/bash\n\n");
        sb.append("OUTFD=/proc/self/fd/$2\n");
        sb.append("ZIPFILE=\"$3\"\n");
        sb.append("ZIPNAME=$(basename \"$ZIPFILE\" .zip)\n\n");
        sb.append("ui_print() {\n");
        sb.append("    echo -e \"ui_print $1\\nui_print\" >>$OUTFD\n");
        sb.append("}\n\n");
        sb.append("package_extract_file() {\n");
        sb.append("    ui_print \"- 正在刷写分区 $(basename $2)……\"\n");
        sb.append("    /tmp/bin/android/7zzs x \"$ZIPFILE\" \"$1\" -so >\"$2_a\"\n");
        sb.append("    /tmp/bin/android/7zzs x \"$ZIPFILE\" \"$1\" -so >\"$2_b\"\n");
        sb.append("}\n\n");
        sb.append("package_extract_super() {\n");
        sb.append("    ui_print \"- 正在刷写分区 $(basename $2)……\"\n");
        sb.append("    /tmp/bin/android/7zzs x \"$ZIPFILE\" \"$1\" -so | /tmp/bin/android/zstd -c -d > \"$2\"\n");
        sb.append("}\n\n");
        sb.append("GetVolumeKey() {\n");
        sb.append("    while true; do\n");
        sb.append("        key=$(getevent -qlc 1)\n");
        sb.append("        if echo \"$key\" | grep -q \"KEY_VOLUMEUP\"; then\n");
        sb.append("            return 0\n");
        sb.append("        elif echo \"$key\" | grep -q \"KEY_VOLUMEDOWN\"; then\n");
        sb.append("            return 1\n");
        sb.append("        fi\n");
        sb.append("    done\n");
        sb.append("}\n\n");
        sb.append("# 解压工具\n");
        sb.append("[ -d /tmp ] && rm -rf /tmp\n");
        sb.append("mkdir -p /tmp\n");
        sb.append("unzip -q \"$ZIPFILE\" bin/android/7zzs -d /tmp\n");
        sb.append("unzip -q \"$ZIPFILE\" bin/android/zstd -d /tmp\n");
        sb.append("chmod -R 0755 /tmp\n\n");
        sb.append("PartitionPath=/dev/block/bootdevice/by-name\n\n");

        // 构建实际存在的底层分区列表（用于 for 循环，排除 super 和 init_boot）
        StringBuilder partList = new StringBuilder();
        for (String p : PARTITIONS) {
            if (p.equals("super") || p.equals("init_boot") || p.equals("root_init_boot")) continue;
            if (find(p) != null) partList.append(p).append(" ");
        }

        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"          ").append(device.isEmpty() ? "一键刷机包" : device + "全底层一键刷机包").append("          \"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"固件名称：$ZIPNAME\"\n");
        sb.append("ui_print \"制作作者：").append(author.isEmpty() ? "DNA管理器" : author).append("\"\n");
        sb.append("ui_print \"适配机型：").append(device.isEmpty() ? "通用" : device).append(" \"\n");
        sb.append("ui_print \"运行环境：TWRP第三方Recovery\"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"温馨提示：\"\n");
        sb.append("ui_print \"1.刷机前请备份重要数据，避免数据丢失\"\n");
        sb.append("ui_print \"2.红色挂载报错属于正常现象，无需处理\"\n");
        sb.append("ui_print \"3.全程自动执行，请勿手动中断刷机进程\"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \" \"\n");

        // init_boot 选项块（与上传脚本一致：root + init 都存在时才弹出音量键选择）
        ImgFile root = find("root_init_boot");
        ImgFile init = find("init_boot");
        if (root != null && init != null) {
            sb.append("ui_print \"=========== 优先选择ROOT权限 ============\"\n");
            sb.append("ui_print \" \"\n");
            sb.append("ui_print \"- 请选择 init_boot 刷入方式\"\n");
            sb.append("ui_print \"- 音量【+】按钮：刷入 Root 版 init_boot\"\n");
            sb.append("ui_print \"- 音量【-】按钮：刷入 原版 init_boot\"\n");
            sb.append("ui_print \"- ==========================================\"\n");
            sb.append("if GetVolumeKey; then\n");
            sb.append("    ui_print \"- 用户选择：刷入 Root 权限\"\n");
            sb.append("    package_extract_file \"images/root_init_boot.img\" \"/dev/block/bootdevice/by-name/init_boot\"\n");
            sb.append("    ui_print \"- Root 版 root_init_boot 刷入完成！\"\n");
            sb.append("else\n");
            sb.append("    ui_print \"- 用户选择：刷入原版 init_boot\"\n");
            sb.append("    package_extract_file \"images/init_boot.img\" \"/dev/block/bootdevice/by-name/init_boot\"\n");
            sb.append("    ui_print \"- 原版 init_boot 刷入完成！\"\n");
            sb.append("fi\n");
            sb.append("ui_print \"- ==========================================\"\n");
        } else if (root != null) {
            sb.append("package_extract_file \"images/root_init_boot.img\" \"/dev/block/bootdevice/by-name/init_boot\"\n");
            sb.append("ui_print \"- Root 版 init_boot 刷入完成！\"\n");
            sb.append("ui_print \"- ==========================================\"\n");
        } else if (init != null) {
            sb.append("package_extract_file \"images/init_boot.img\" \"/dev/block/bootdevice/by-name/init_boot\"\n");
            sb.append("ui_print \"- 原版 init_boot 刷入完成！\"\n");
            sb.append("ui_print \"- ==========================================\"\n");
        }

        sb.append("\n");
        sb.append("ui_print \"=========== 正式开始底层刷写 ============\"\n");
        sb.append("ui_print \" \"\n");
        sb.append("\n");
        sb.append("# 刷入全部底层分区\n");
        // 仅遍历实际存在的分区
        for (String p : PARTITIONS) {
            if (p.equals("super") || p.equals("init_boot") || p.equals("root_init_boot")) continue;
            ImgFile im = find(p);
            if (im == null) continue;
            sb.append("package_extract_file \"images/").append(im.fileName).append("\" \"${PartitionPath}/").append(p).append("\"\n");
        }
        sb.append("\n");

        // super（打包时已统一压缩为 super.img.zst）
        ImgFile sup = find("super");
        if (sup != null) {
            sb.append("# 刷 super\n");
            sb.append("ui_print \"=========== 开始刷入Super分区 ============\"\n");
            sb.append("package_extract_super \"images/super.img.zst\" \"${PartitionPath}/super\"\n");
            sb.append("ui_print \"Super分区刷入完毕\"\n");
            sb.append("\n");
        }

        // 切换到 A 槽
        sb.append("# 切换到 A 槽\n");
        sb.append("ui_print \"- 开始切换卡槽……\"\n");
        sb.append("bootctl set-active-boot-slot 0\n");
        sb.append("ui_print \"- 切换A卡槽成功\"\n");
        sb.append("ui_print \"- ==========================================\"\n");
        sb.append("\n");

        // 清缓存
        sb.append("# 清缓存\n");
        sb.append("ui_print \" \"\n");
        sb.append("ui_print \"=========== 深度清理系统缓存 ============\"\n");
        sb.append("rm -rf /data/cache/\n");
        sb.append("rm -rf /data/dalvik-cache/\n");
        sb.append("rm -rf /data/system/package_cache/\n");
        sb.append("ui_print \"系统缓存清理完成\"\n");
        sb.append("\n");

        sb.append("ui_print \" \"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"            全套底层刷写完毕              \"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"✅ 所有底层分区刷入成功\"\n");
        sb.append("ui_print \"✅ 已锁定A系统启动槽位\"\n");
        sb.append("ui_print \"✅ 谷歌防盗锁已清空\"\n");
        sb.append("ui_print \"✅ 系统冗余缓存全部清理\"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"操作指引：\"\n");
        sb.append("ui_print \"直接重启设备即可正常开机进系统\"\n");
        sb.append("ui_print \"遇到异常卡顿可再次双清数据使用\"\n");
        sb.append("ui_print \"==========================================\"\n");
        sb.append("ui_print \"- 红色挂载报错请直接忽略\"\n");
        sb.append("ui_print \"- 红色挂载报错请直接忽略\"\n");
        sb.append("ui_print \"- 红色挂载报错请直接忽略\"\n");
        sb.append("ui_print \"- ==========================================\"\n");
        sb.append("\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
