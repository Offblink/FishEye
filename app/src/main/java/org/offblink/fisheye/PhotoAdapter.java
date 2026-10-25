package org.offblink.fisheye;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相册网格：3 列方格，缩略图后台解、按 tag 对号入座（滚快了旧图不串位）。
 *
 * <p>手势分工：单击＝进预览；长按＝进选择模式；选择模式里单击＝勾选，按住拖动＝
 * <b>序列范围选择</b>——相册是一维照片序列，起笔那张是锚点，锚点到手指当前位置之间的
 * 所有照片按本笔方向翻转（斜着从左上拖到右下 = 起点到终点全选）；往回拖区间收缩，
 * 本笔涂过又甩出区间的自动撤销。之前就选好的不碰。选中态只画圈+压暗，不整格重绑。
 */
class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.VH> {

    interface Listener {
        /** 单击一格：选择模式里是勾选，否则进预览；rect = 这格的屏幕矩形（缩放切页用） */
        void onOpen(Uri uri, int[] rect);

        /** 长按一格：进选择模式并勾住它 */
        void onPick(Uri uri);

        /** 涂抹勾选有变化：让界面刷新计数 */
        void onSelectionChanged();
    }

    /** 只改选中态的局部刷新标记，避免重解缩略图 */
    private static final Object PAYLOAD_SELECTION = new Object();

    private final List<Uri> items = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private Listener listener;

    private boolean paintEnabled;
    private boolean painting;
    private boolean paintSelecting;
    private boolean paintModeDecided;
    private long anchorId = -1;
    private final Set<Long> strokeTouched = new HashSet<>();
    private final Set<Long> strokeApplied = new HashSet<>();
    private float downX;
    private float downY;
    private long lastPaintId = -1;
    private int touchSlop;
    private int cellSide;

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setPaintEnabled(boolean on) {
        paintEnabled = on;
        if (!on) {
            painting = false;
        }
    }

    void submit(List<Uri> data) {
        items.clear();
        items.addAll(data);
        selected.clear();
        notifyDataSetChanged();
    }

    void clearSelection() {
        if (selected.isEmpty()) {
            return;
        }
        selected.clear();
        notifyDataSetChanged();
    }

    void toggle(Uri uri) {
        long id = idOf(uri);
        if (!selected.remove(id)) {
            selected.add(id);
        }
        int idx = indexOf(id);
        if (idx >= 0) {
            notifyItemChanged(idx, PAYLOAD_SELECTION);
        }
    }

    boolean isSelected(Uri uri) {
        return selected.contains(idOf(uri));
    }

    int selectedCount() {
        return selected.size();
    }

    List<Uri> selectedUris() {
        List<Uri> out = new ArrayList<>();
        for (Uri u : items) {
            if (selected.contains(idOf(u))) {
                out.add(u);
            }
        }
        return out;
    }

    void shutdown() {
        thumbs.shutdownNow();
    }

    private static long idOf(Uri u) {
        return u.toString().hashCode();
    }

    private int indexOf(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (idOf(items.get(i)) == id) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        touchSlop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        final Uri uri = items.get(position);
        final long id = idOf(uri);
        holder.photoId = id;
        holder.ring.setVisibility(selected.contains(id) ? View.VISIBLE : View.GONE);

        ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
        lp.height = holder.itemView.getResources().getDisplayMetrics().widthPixels / 3;
        holder.image.setLayoutParams(lp);

        Bitmap cached = AlbumStore.cachedThumb(uri, lp.height);
        if (cached != null) {
            holder.image.setTag(id);
            holder.image.setImageBitmap(cached);
        } else {
            holder.image.setImageDrawable(null);
            holder.image.setTag(id);
            thumbs.execute(() -> {
                Bitmap bmp = AlbumStore.thumbnail(holder.image.getContext().getContentResolver(),
                        uri, lp.height);
                holder.image.post(() -> {
                    Object tag = holder.image.getTag();
                    if (tag instanceof Long && (Long) tag == id && bmp != null) {
                        holder.image.setImageBitmap(bmp);
                    }
                });
            });
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpen(uri, Ui.rectOnScreen(v));
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener == null) {
                return false;
            }
            listener.onPick(uri);
            return true;
        });
        bindPaint(holder);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // 只是选中态变了：画个圈就完事，别把缩略图重新解一遍
            Uri uri = items.get(position);
            holder.photoId = idOf(uri);
            holder.ring.setVisibility(selected.contains(holder.photoId)
                    ? View.VISIBLE : View.GONE);
            return;
        }
        onBindViewHolder(holder, position);
    }

    /**
     * 涂抹：手指按住滑过哪格就勾哪格。
     *
     * <p>事件始终发给按下的那一格（它才是 touch target），所以坐标要从该格的局部系
     * 换算回 RecyclerView 再 {@code findChildViewUnder}，才能找到手指正压着的兄弟格。
     */
    private void bindPaint(VH holder) {
        holder.itemView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    painting = false;
                    paintModeDecided = false;
                    downX = event.getX();
                    downY = event.getY();
                    lastPaintId = -1;
                    anchorId = holder.photoId;
                    strokeTouched.clear();
                    strokeApplied.clear();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!paintEnabled) {
                        break;
                    }
                    if (!painting) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                            painting = true;
                            // 起涂了就掐掉这一格挂起的长按，否则拖到一半它会当成
                            // 「长按进选择模式」把刚涂上的格子又翻回去
                            v.cancelLongPress();
                            // 起涂之后别让 RecyclerView 把手势抢去当滚动
                            if (v.getParent() != null) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    }
                    if (painting) {
                        paintAt(v, event.getX(), event.getY());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (painting) {
                        painting = false;
                        if (listener != null) {
                            listener.onSelectionChanged();
                        }
                        // 吞掉这次抬起：这是一次涂抹，不是单击
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * 一维范围涂抹：锚点（按下的那张）到当前手指所在照片，整段按本笔方向翻转。
     *
     * <p>二维网格只是排布，语义按序列走——斜着从左上拖到右下 = 锚点到终点之间
     * 全部选中，不管几何路径压没压到中间那些格子。往回拖区间收缩，本笔涂过又
     * 落回区间外的就地撤销。
     */
    private void paintAt(View target, float x, float y) {
        if (!(target.getParent() instanceof RecyclerView)) {
            return;
        }
        RecyclerView rv = (RecyclerView) target.getParent();
        View under = rv.findChildViewUnder(target.getLeft() + x, target.getTop() + y);
        if (under == null) {
            return;
        }
        RecyclerView.ViewHolder holder = rv.getChildViewHolder(under);
        if (!(holder instanceof VH)) {
            return;
        }
        long id = ((VH) holder).photoId;
        if (id == lastPaintId) {
            return;
        }
        lastPaintId = id;
        int anchorPos = indexOf(anchorId);
        int curPos = indexOf(id);
        if (anchorPos < 0 || curPos < 0) {
            return;
        }
        if (!paintModeDecided) {
            // 起笔那格决定整笔方向：落在已选图上 = 这笔取消，落在未选图上 = 这笔加选
            paintModeDecided = true;
            paintSelecting = !selected.contains(anchorId);
        }
        int lo = Math.min(anchorPos, curPos);
        int hi = Math.max(anchorPos, curPos);
        boolean changed = false;
        for (int p = lo; p <= hi; p++) {
            long cid = idOf(items.get(p));
            if (strokeApplied.contains(cid)) {
                continue;
            }
            boolean ok = paintSelecting ? selected.add(cid) : selected.remove(cid);
            if (ok) {
                strokeTouched.add(cid);
                strokeApplied.add(cid);
                notifyItemChanged(p, PAYLOAD_SELECTION);
                changed = true;
            }
        }
        if (!strokeApplied.isEmpty()) {
            Iterator<Long> it = strokeApplied.iterator();
            while (it.hasNext()) {
                long cid = it.next();
                int p = indexOf(cid);
                if (p >= lo && p <= hi) {
                    continue;
                }
                it.remove();
                boolean ok = paintSelecting ? selected.remove(cid) : selected.add(cid);
                if (ok) {
                    notifyItemChanged(p, PAYLOAD_SELECTION);
                    changed = true;
                }
            }
        }
        if (changed && listener != null) {
            listener.onSelectionChanged();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        final View ring;
        long photoId;

        VH(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.item_image);
            ring = itemView.findViewById(R.id.item_ring);
        }
    }
}
