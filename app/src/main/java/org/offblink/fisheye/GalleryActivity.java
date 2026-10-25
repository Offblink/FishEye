package org.offblink.fisheye;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 相册：只列 Pictures/FishEye。点开进调色，长按删除。 */
public class GalleryActivity extends AppCompatActivity {

    private RecyclerView grid;
    private TextView empty;
    private TextView count;
    private final List<Uri> items = new ArrayList<>();
    private Adapter adapter;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_gallery);
        grid = findViewById(R.id.gal_grid);
        empty = findViewById(R.id.gal_empty);
        count = findViewById(R.id.gal_count);

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-thumbs");
            t.setDaemon(true);
            return t;
        });

        applyInsets();
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new Adapter();
        grid.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
    }

    /** 全局铺到状态栏/导航栏下面，两条栏自己让位 */
    private void applyInsets() {
        View top = findViewById(R.id.gal_top);
        View bottom = grid;
        final int topPad = top.getPaddingTop();
        final int botPad = Ui.dp(this, 12);
        findViewById(R.id.gal_top).setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in =
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            v.setPadding(v.getPaddingLeft(),
                    topPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top,
                    v.getPaddingRight(), v.getPaddingBottom());
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(),
                    botPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;
        });
    }

    private void reload() {
        io.execute(() -> {
            final List<Uri> list = AlbumStore.list(this);
            main.post(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                count.setText(list.size() + " 张");
                empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void open(Uri uri) {
        startActivity(new Intent(this, EditorActivity.class)
                .setData(uri)
                .putExtra(EditorActivity.EXTRA_ALLOW_OVERWRITE, true));
    }

    private void confirmDelete(Uri uri) {
        new AlertDialog.Builder(this)
                .setMessage("删除这张照片？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> io.execute(() -> {
                    AlbumStore.delete(this, uri);
                    main.post(this::reload);
                }))
                .show();
    }

    private final class Adapter extends RecyclerView.Adapter<VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_photo, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final Uri uri = items.get(position);
            // 正方形格：宽高按三列算，避免图片比例把行高撑开
            int side = grid.getWidth() > 0
                    ? grid.getWidth() / 3
                    : getResources().getDisplayMetrics().widthPixels / 3;
            ViewGroup.LayoutParams lp = h.image.getLayoutParams();
            lp.height = side;
            h.image.setLayoutParams(lp);
            h.image.setImageDrawable(null);
            h.image.setTag(uri);
            h.itemView.setOnClickListener(v -> open(uri));
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(uri);
                return true;
            });
            io.execute(() -> {
                Bitmap bmp = AlbumStore.thumbnail(getContentResolver(), uri, side);
                main.post(() -> {
                    if (bmp != null) {
                        if (uri.equals(h.image.getTag())) {
                            h.image.setImageBitmap(bmp);
                        }
                        return;
                    }
                    // 读不出来：多半是 MediaStore 里残留了文件已不存在的条目，
                    // 直接从本次列表里剔掉，免得留一个空格子
                    int idx = items.indexOf(uri);
                    if (idx >= 0) {
                        items.remove(idx);
                        notifyItemRemoved(idx);
                        count.setText(items.size() + " 张");
                        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView image;

        VH(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.item_image);
        }
    }
}
