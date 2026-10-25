package org.offblink.fisheye;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相册：只列 Pictures/FishEye。
 *
 * <p>单击看大图（从格子位置放大进入）；长按进选择模式，选中后按住拖动＝按序列涂抹连选。
 * 删除确认框与提示条都是自绘的，不用系统的 AlertDialog / Toast。
 */
public class GalleryActivity extends AppCompatActivity {

    /** 从相机页缩略图位置放大进来的源矩形（屏幕坐标） */
    public static final String EXTRA_ZOOM_FROM = "zoom_from";

    private RecyclerView grid;
    private int[] zoomFrom;
    private boolean closing;
    private TextView title;
    private TextView action;
    private TextView empty;
    private PhotoAdapter adapter;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AlbumStore.Item> items = new ArrayList<>();
    private boolean selectionMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_gallery);

        // 从相机页进来：从缩略图位置放大到全屏（和照片预览同一套缩放切页）
        zoomFrom = getIntent().getIntArrayExtra(EXTRA_ZOOM_FROM);
        if (zoomFrom != null) {
            Ui.zoomIn(this, findViewById(R.id.gal_root), zoomFrom);
        }

        grid = findViewById(R.id.gal_grid);
        title = findViewById(R.id.gal_title);
        action = findViewById(R.id.gal_count);
        empty = findViewById(R.id.gal_empty);

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-thumbs");
            t.setDaemon(true);
            return t;
        });

        applyInsets();
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new PhotoAdapter();
        adapter.setListener(new PhotoAdapter.Listener() {
            @Override
            public void onOpen(Uri uri, int[] rect) {
                if (selectionMode) {
                    adapter.toggle(uri);
                    refreshChrome();
                } else if (adapter.isVideo(uri)) {
                    openVideo(rect, uri);
                } else {
                    openPreview(rect, uri);
                }
            }

            @Override
            public void onPick(Uri uri) {
                if (!selectionMode) {
                    selectionMode = true;
                    adapter.setPaintEnabled(true);
                }
                adapter.toggle(uri);
                refreshChrome();
            }

            @Override
            public void onSelectionChanged() {
                refreshChrome();
            }
        });
        grid.setAdapter(adapter);
        refreshChrome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) {
            adapter.shutdown();
        }
        if (io != null) {
            io.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelection();
        } else {
            closePage();
        }
    }

    /** 返回：缩放过进来的缩回缩略图位置，否则走系统默认 */
    private void closePage() {
        if (closing) {
            return;
        }
        if (zoomFrom != null) {
            closing = true;
            Ui.shrinkFinish(this, findViewById(R.id.gal_root), zoomFrom);
        } else {
            super.onBackPressed();
        }
    }

    /** 全局铺到状态栏/导航栏下面，两条栏自己让位 */
    private void applyInsets() {
        View top = findViewById(R.id.gal_top);
        final int topPad = top.getPaddingTop();
        final int botPad = Ui.dp(this, 12);
        findViewById(R.id.gal_top).setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in =
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            v.setPadding(v.getPaddingLeft(),
                    topPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top,
                    v.getPaddingRight(), v.getPaddingBottom());
            grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(), grid.getPaddingRight(),
                    botPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;
        });
        action.setOnClickListener(v -> {
            if (selectionMode) {
                confirmDelete();
            }
        });
    }

    private void reload() {
        io.execute(() -> {
            final List<AlbumStore.Item> list = AlbumStore.list(this);
            main.post(() -> {
                items.clear();
                items.addAll(list);
                adapter.submit(list);
                if (selectionMode && list.isEmpty()) {
                    selectionMode = false;
                    adapter.setPaintEnabled(false);
                }
                empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                refreshChrome();
            });
        });
    }

    /** 顶部两条：普通态显示相册名与张数，选择态显示已选数与删除入口 */
    private void refreshChrome() {
        int n = adapter == null ? 0 : adapter.selectedCount();
        if (selectionMode) {
            if (n == 0) {
                // 涂抹里把最后一张也取消了：退回普通态，别卡在空的选择模式
                selectionMode = false;
                adapter.setPaintEnabled(false);
            } else {
                title.setText("已选 " + n + " 项");
                action.setText("删除");
                action.setTextColor(getColor(R.color.accent));
                return;
            }
        }
        title.setText("PICTURES / FISHEYE");
        action.setText(items.size() + " 项");
        action.setTextColor(getColor(R.color.fg_dim));
    }

    private void exitSelection() {
        selectionMode = false;
        adapter.setPaintEnabled(false);
        adapter.clearSelection();
        refreshChrome();
    }

    /** 从被点的格子位置放大进入预览；返回时缩回同一位置 */
    private void openPreview(int[] rect, Uri uri) {
        Ui.snapshot(this, () -> {
            startActivity(new Intent(this, PhotoActivity.class)
                    .setData(uri)
                    .putExtra(PhotoActivity.EXTRA_ZOOM_FROM, rect));
            overridePendingTransition(0, 0);
        });
    }

    /** 录像进自己的播放器：同样从格子位置放大进去，返回时缩回同一格 */
    private void openVideo(int[] rect, Uri uri) {
        Ui.snapshot(this, () -> {
            startActivity(new Intent(this, VideoActivity.class)
                    .setData(uri)
                    .putExtra(VideoActivity.EXTRA_ZOOM_FROM, rect));
            overridePendingTransition(0, 0);
        });
    }

    private void confirmDelete() {
        final List<Uri> targets = adapter.selectedUris();
        if (targets.isEmpty()) {
            return;
        }
        Ui.confirm(this, "删除 " + targets.size() + " 项？", "删掉就找不回来了。",
                "删除", Color.parseColor("#FFFF6B6B"), () -> io.execute(() -> {
                    for (Uri u : targets) {
                        AlbumStore.delete(this, u);
                    }
                    main.post(() -> {
                        selectionMode = false;
                        adapter.setPaintEnabled(false);
                        reload();
                    });
                }));
    }
}
