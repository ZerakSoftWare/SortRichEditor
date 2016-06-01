package com.hitomi.sortricheditor.view;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;

import com.hitomi.sortricheditor.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 富文本编辑器
 * 1、支持图片文字混排和编辑
 * 2、支持文字中间插入图片
 * 3、支持图片文字排序
 */
public class SortRichEditor extends ScrollView {

    /**
     * ImageView在排序状态下的高度
     */
    public final int DEFAULT_IMAGE_HEIGHT = dip2px(250);

    /**
     * EditText在排序状态下的高度
     */
    public final int SIZE_REDUCE_VIEW = dip2px(75);

    /**
     * 默认view之间的竖直间距为5dp
     */
    private final int DEFAULT_VERTICAL_SPACEING = dip2px(5);

    /**
     * 默认水平Padding为10dp
     */
    private final int DEFAULT_HORIZONTAL_PADDING = dip2px(10);

    /**
     * 因为排序状态下会修改EditText的Background，所以这里保存默认EditText
     * 的Background, 当排序完成后用于还原EditText默认的Background
     */
    private final Drawable editTextBackground;

    /**
     * 每创建一个child，为该child赋一个ID，该ID保存在view的tag属性中
     */
    private int viewTagID = 1;

    /**
     * 因为ScrollView的子view只能有一个，并且是ViewGroup
     * 所以这里指定为所有子view的容器为containerLayout(LinearLayout)
     * 即：布局层次为：
     * ScrollView{
     * containerLayout：{
     * child1,
     * child2,
     * child3,
     * ...
     * }
     * }
     */
    private LinearLayout containerLayout;

    /**
     * 布局填充器
     */
    private LayoutInflater inflater;

    /**
     * EditText的软键盘监听器
     */
    private OnKeyListener editTextKeyListener;

    /**
     * 图片右上角删除按钮监听器
     */
    private OnClickListener deleteListener;

    /**
     * EditText的焦点监听listener
     */
    private OnFocusChangeListener editTextFocusListener;

    /**
     * 最近获取焦点的一个EditText
     */
    private EditText lastFocusEdit;

    /**
     * 添加或者删除图片View时的Transition动画
     */
    private LayoutTransition mTransitioner;

    private int disappearingImageIndex = 0;

    private ViewDragHelper viewDragHelper;

    /**
     * 因为文字长短不一（过长换行让EditText高度增大），导致EditText高度不一，
     * 所以需要一个集合存储排序之前未缩小/放大的EditText高度
     */
    private SparseArray<Integer> editTextHeightArray;

    /**
     * 准备排序时，缩小各个child，并存放缩小的child的top作为该child的position值
     */
    private SparseArray<Integer> preSortPositionArray;

    /**
     * 排序完成后，子child位置下标
     */
    private SparseIntArray indexArray = new SparseIntArray();

    /**
     * 当前是否为排序状态
     */
    private boolean isSort;

    public SortRichEditor(Context context) {
        this(context, null);
    }

    public SortRichEditor(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SortRichEditor(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflater = LayoutInflater.from(context);

        initContainerLayout();

        initListener();

        editTextHeightArray = new SparseArray<>();

        // 初始化ViewDragHelper
        viewDragHelper = ViewDragHelper.create(containerLayout, 1.f, new ViewDragHelperCallBack());

        LinearLayout.LayoutParams firstEditParam = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        firstEditParam.bottomMargin = DEFAULT_VERTICAL_SPACEING;
        EditText firstEdit = createEditText("在此输入帖子内容");
        editTextHeightArray.put(Integer.parseInt(firstEdit.getTag().toString()), ViewGroup.LayoutParams.WRAP_CONTENT);
        editTextBackground = firstEdit.getBackground();
        containerLayout.addView(firstEdit, firstEditParam);
        lastFocusEdit = firstEdit;
    }

    private void initListener() {
        // 初始化键盘退格监听
        // 主要用来处理点击回删按钮时，view的一些列合并操作
        editTextKeyListener = new OnKeyListener() {

            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                    EditText edit = (EditText) v;
                    onBackspacePress(edit);
                }
                return false;
            }
        };

        // 3. 图片叉掉处理
        deleteListener = new OnClickListener() {

            @Override
            public void onClick(View v) {
                RelativeLayout parentView = (RelativeLayout) v.getParent();
                onImageCloseClick(parentView);
            }
        };

        editTextFocusListener = new OnFocusChangeListener() {

            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    lastFocusEdit = (EditText) v;
                }
            }
        };

    }

    private void initContainerLayout() {
        // 初始化ContainerLayout父容器
        containerLayout = createContaniner();
        addView(containerLayout);
    }

    @NonNull
    private LinearLayout createContaniner() {
        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        final LinearLayout containerLayout = new LinearLayout(getContext()) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                return viewDragHelper.shouldInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                viewDragHelper.processTouchEvent(event);
                int action = event.getAction();
                switch (action) {
                    case MotionEvent.ACTION_MOVE:
                        if (event.getRawY() > SortRichEditor.this.getBottom()) {
                            SortRichEditor.this.scrollBy(0, 5);
                        }
                        if (event.getRawY() < SIZE_REDUCE_VIEW * .6) {
                            SortRichEditor.this.scrollBy(0, -5);
                        }
                        System.out.println(event.getRawY());
                        break;
                }
                return true;
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (isSort) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return super.dispatchTouchEvent(ev);
            }


        };
        containerLayout.setPadding(DEFAULT_HORIZONTAL_PADDING
                , DEFAULT_VERTICAL_SPACEING
                , DEFAULT_HORIZONTAL_PADDING
                , DEFAULT_VERTICAL_SPACEING);
        containerLayout.setOrientation(LinearLayout.VERTICAL);
        containerLayout.setBackgroundColor(Color.WHITE);
        containerLayout.setLayoutParams(layoutParams);
        setupLayoutTransitions(containerLayout);
        return containerLayout;
    }

    private void endSortUI() {
        int childCount = containerLayout.getChildCount();
        View child;

        if (indexArray.size() == childCount) { // 重新排列过
            int sortIndex;
            View[] childArray = new View[childCount];
            for (int i = 0; i < childCount; i++) {
                if (indexArray.size() != childCount) break;
                // 代表原先在i的位置上的view，换到了sortIndex位置上
                sortIndex = indexArray.get(i);
                child = containerLayout.getChildAt(i);
                childArray[sortIndex] = child;
            }

            containerLayout.removeAllViews();
            for (int i = 0; i < childCount; i++) {
                child = childArray[i];
                child.setLayoutParams(resetChildLayoutParams(child));
                containerLayout.addView(child);
            }
        } else { // 没有重新排列
            for (int i = 0; i < childCount; i++) {
                child = containerLayout.getChildAt(i);
                child.setLayoutParams(resetChildLayoutParams(child));
            }
        }
    }

    private ViewGroup.LayoutParams resetChildLayoutParams(View child) {
        ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
        if (child instanceof RelativeLayout) { // 图片
            layoutParams.height = DEFAULT_IMAGE_HEIGHT;
        }
        if (child instanceof EditText) { // 文本编辑框
            child.setFocusable(true);
            child.setFocusableInTouchMode(true);
            child.requestFocus();
            child.setBackgroundDrawable(editTextBackground);
            layoutParams.height = editTextHeightArray.get(Integer.parseInt(child.getTag().toString()));
        }
        return layoutParams;
    }

    private void prepareSortUI() {
        int childCount = containerLayout.getChildCount();

        if (childCount != 0) {
            if (preSortPositionArray == null) {
                preSortPositionArray = new SparseArray<>();
            } else {
                preSortPositionArray.clear();
            }
        }

        View child;
        int pos, preIndex = 0;
        for (int i = 0; i < childCount; i++) {
            child = containerLayout.getChildAt(i);
            int tagID = Integer.parseInt(child.getTag().toString());
            ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
            if (child instanceof EditText) { // 文本编辑框
                editTextHeightArray.put(tagID, layoutParams.height);
                child.setFocusable(false);
                child.setBackgroundDrawable(getResources().getDrawable(R.drawable.shape_dash_edit));
            }
            layoutParams.height = SIZE_REDUCE_VIEW;
            child.setLayoutParams(layoutParams);
            if (i == 0) {
                preIndex = tagID;
                pos = DEFAULT_VERTICAL_SPACEING;
            } else {
                pos = SIZE_REDUCE_VIEW + DEFAULT_VERTICAL_SPACEING + preSortPositionArray.get(preIndex);

                preIndex = tagID;
            }
            preSortPositionArray.put(tagID, pos);
        }
    }

    /**
     * 处理软键盘backSpace回退事件
     *
     * @param editTxt 光标所在的文本输入框
     */
    private void onBackspacePress(EditText editTxt) {
        int startSelection = editTxt.getSelectionStart();
        // 只有在光标已经顶到文本输入框的最前方，在判定是否删除之前的图片，或两个View合并
        if (startSelection == 0) {
            int editIndex = containerLayout.indexOfChild(editTxt);
            View preView = containerLayout.getChildAt(editIndex - 1); // 如果editIndex-1<0,
            // 则返回的是null
            if (null != preView) {
                if (preView instanceof RelativeLayout) {
                    // 光标EditText的上一个view对应的是图片
                    onImageCloseClick(preView);
                } else if (preView instanceof EditText) {
                    // 光标EditText的上一个view对应的还是文本框EditText
                    String str1 = editTxt.getText().toString();
                    EditText preEdit = (EditText) preView;
                    String str2 = preEdit.getText().toString();

                    // 合并文本view时，不需要transition动画
                    containerLayout.setLayoutTransition(null);
                    containerLayout.removeView(editTxt);
                    containerLayout.setLayoutTransition(mTransitioner); // 恢复transition动画

                    // 文本合并
                    preEdit.setText(str2 + str1);
                    preEdit.requestFocus();
                    preEdit.setSelection(str2.length(), str2.length());
                    lastFocusEdit = preEdit;
                }
            }
        }
    }

    /**
     * 处理图片叉掉的点击事件
     *
     * @param view 整个image对应的relativeLayout view
     * @type 删除类型 0代表backspace删除 1代表按红叉按钮删除
     */
    private void onImageCloseClick(View view) {
        if (!mTransitioner.isRunning()) {
            disappearingImageIndex = containerLayout.indexOfChild(view);
            containerLayout.removeView(view);
        }
    }

    /**
     * 生成文本输入框
     */
    private EditText createEditText(String hint) {
        EditText editText = (EditText) inflater.inflate(R.layout.layout_edittext, null);
        editText.setOnKeyListener(editTextKeyListener);
        editText.setTag(viewTagID++);
        editText.setHint(hint);
        editText.setOnFocusChangeListener(editTextFocusListener);
        return editText;
    }

    /**
     * 生成图片View
     */
    private RelativeLayout createImageLayout() {
        RelativeLayout layout = (RelativeLayout) inflater.inflate(R.layout.layout_imageview, null);
        layout.setTag(viewTagID++);
        View closeView = layout.findViewById(R.id.image_close);
        closeView.setTag(layout.getTag());
        closeView.setOnClickListener(deleteListener);
        return layout;
    }

    /**
     * 根据绝对路径添加view
     *
     * @param imagePath
     */
    public void insertImage(String imagePath) {
        Bitmap bmp = getScaledBitmap(imagePath, getWidth());
        insertImage(bmp, imagePath);
    }

    /**
     * 插入一张图片
     */
    private void insertImage(Bitmap bitmap, String imagePath) {
        String lastEditStr = lastFocusEdit.getText().toString();
        int cursorIndex = lastFocusEdit.getSelectionStart();
        String editStr1 = lastEditStr.substring(0, cursorIndex).trim();
        int lastEditIndex = containerLayout.indexOfChild(lastFocusEdit);

        if (lastEditStr.length() == 0 || editStr1.length() == 0) {
            // 如果EditText为空，或者光标已经顶在了editText的最前面，则直接插入图片，并且EditText下移即可
            addImageViewAtIndex(lastEditIndex, bitmap, imagePath);
        } else {
            // 如果EditText非空且光标不在最顶端，则需要添加新的imageView和EditText
            lastFocusEdit.setText(editStr1);
            String editStr2 = lastEditStr.substring(cursorIndex).trim();
            if (containerLayout.getChildCount() - 1 == lastEditIndex
                    || editStr2.length() > 0) {
                addEditTextAtIndex(lastEditIndex + 1, editStr2);
            }

            addImageViewAtIndex(lastEditIndex + 1, bitmap, imagePath);
            lastFocusEdit.requestFocus();
            lastFocusEdit.setSelection(editStr1.length(), editStr1.length());
        }
        hideKeyBoard();
    }

    /**
     * 隐藏小键盘
     */
    public void hideKeyBoard() {
        InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(lastFocusEdit.getWindowToken(), 0);
    }

    /**
     * 在特定位置插入EditText
     *
     * @param index   位置
     * @param editStr EditText显示的文字
     */
    private void addEditTextAtIndex(final int index, String editStr) {
        EditText editText = createEditText("");
        editText.setText(editStr);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = DEFAULT_VERTICAL_SPACEING;
        editText.setLayoutParams(lp);

        // 请注意此处，EditText添加、或删除不触动Transition动画
        containerLayout.setLayoutTransition(null);
        containerLayout.addView(editText, index);
        containerLayout.setLayoutTransition(mTransitioner); // add之后恢复transition动画
    }

    /**
     * 在特定位置添加ImageView
     */
    private void addImageViewAtIndex(final int index, Bitmap bmp, String imagePath) {
        final RelativeLayout imageLayout = createImageLayout();
        DataImageView imageView = (DataImageView) imageLayout.findViewById(R.id.edit_imageView);
        imageView.setImageBitmap(bmp);
        imageView.setBitmap(bmp);
        imageView.setAbsolutePath(imagePath);

        // 调整imageView的高度
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, DEFAULT_IMAGE_HEIGHT);
        lp.bottomMargin = DEFAULT_VERTICAL_SPACEING;
        imageLayout.setLayoutParams(lp);


        // onActivityResult无法触发动画，此处post处理
        containerLayout.postDelayed(new Runnable() {
            @Override
            public void run() {
                containerLayout.addView(imageLayout, index);
            }
        }, 200);
    }

    /**
     * 根据view的宽度，动态缩放bitmap尺寸
     *
     * @param width view的宽度
     */
    private Bitmap getScaledBitmap(String filePath, int width) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(filePath, options);
        int sampleSize = options.outWidth > width ? options.outWidth / width
                + 1 : 1;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(filePath, options);
    }

    /**
     * 初始化transition动画
     */
    private void setupLayoutTransitions(LinearLayout containerLayout) {
        mTransitioner = new LayoutTransition();
        containerLayout.setLayoutTransition(mTransitioner);
        mTransitioner.addTransitionListener(new TransitionListener() {

            @Override
            public void startTransition(LayoutTransition transition,
                                        ViewGroup container, View view, int transitionType) {

            }

            @Override
            public void endTransition(LayoutTransition transition,
                                      ViewGroup container, View view, int transitionType) {
                if (!transition.isRunning()
                        && transitionType == LayoutTransition.CHANGE_DISAPPEARING) {
                    // transition动画结束，合并EditText
                    // mergeEditText();
                }
            }
        });
        mTransitioner.setDuration(300);
    }

    /**
     * 图片删除的时候，如果上下方都是EditText，则合并处理
     */
    private void mergeEditText() {
        View preView = containerLayout.getChildAt(disappearingImageIndex - 1);
        View nextView = containerLayout.getChildAt(disappearingImageIndex);
        if (preView != null && preView instanceof EditText && null != nextView && nextView instanceof EditText) {
            EditText preEdit = (EditText) preView;
            EditText nextEdit = (EditText) nextView;
            String str1 = preEdit.getText().toString();
            String str2 = nextEdit.getText().toString();
            String mergeText = "";
            if (str2.length() > 0) {
                mergeText = str1 + "\n" + str2;
            } else {
                mergeText = str1;
            }

            containerLayout.setLayoutTransition(null);
            containerLayout.removeView(nextEdit);
            preEdit.setText(mergeText);
            preEdit.requestFocus();
            preEdit.setSelection(str1.length(), str1.length());
            containerLayout.setLayoutTransition(mTransitioner);
        }
    }

    /**
     * dp和pixel转换
     *
     * @param dipValue dp值
     * @return 像素值
     */
    public int dip2px(float dipValue) {
        float m = getContext().getResources().getDisplayMetrics().density;
        return (int) (dipValue * m + 0.5f);
    }

    public void sort() {
        isSort = !isSort;
        if (isSort) {
            prepareSortUI();
            indexArray.clear();
        } else {
            endSortUI();
        }
    }

    /**
     * 对外提供的接口, 生成编辑数据上传
     */
    public List<EditData> buildEditData() {
        List<EditData> dataList = new ArrayList<EditData>();
        int num = containerLayout.getChildCount();
        for (int index = 0; index < num; index++) {
            View itemView = containerLayout.getChildAt(index);
            EditData itemData = new EditData();
            if (itemView instanceof EditText) {
                EditText item = (EditText) itemView;
                itemData.inputStr = item.getText().toString();
            } else if (itemView instanceof RelativeLayout) {
                DataImageView item = (DataImageView) itemView
                        .findViewById(R.id.edit_imageView);
                itemData.imagePath = item.getAbsolutePath();
                itemData.bitmap = item.getBitmap();
            }
            dataList.add(itemData);
        }

        return dataList;
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (viewDragHelper.continueSettling(true)) {
            invalidate();
        }
    }

    private void resetChildPostion() {
        indexArray.clear();
        View child;
        int tagID, sortIndex;
        int childCount = containerLayout.getChildCount();
        for (int i = 0; i < childCount; i++) {
            child = containerLayout.getChildAt(i);
            tagID = Integer.parseInt(child.getTag().toString());
            sortIndex = (preSortPositionArray.get(tagID) - DEFAULT_VERTICAL_SPACEING) / (SIZE_REDUCE_VIEW + DEFAULT_VERTICAL_SPACEING);
            indexArray.put(i, sortIndex);
        }
    }

    private class ViewDragHelperCallBack extends ViewDragHelper.Callback {

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            return (child instanceof RelativeLayout) && isSort;
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            final int leftBound = getPaddingLeft() + DEFAULT_HORIZONTAL_PADDING;
            final int rightBound = getWidth() - child.getWidth() - leftBound;
            final int newLeft = Math.min(Math.max(left, leftBound), rightBound);
            return newLeft;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            return top;
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            return 0;
        }

        @Override
        public int getViewHorizontalDragRange(View child) {
            return 0;
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            int releasedViewID = Integer.parseInt(releasedChild.getTag().toString());
            int releasedViewPos = preSortPositionArray.get(releasedViewID);
            viewDragHelper.settleCapturedViewAt(releasedChild.getLeft(), releasedViewPos);
            invalidate();
        }

        @Override
        public void onViewCaptured(View capturedChild, int activePointerId) {

        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            int reduceChildCount = containerLayout.getChildCount();

            View sortChild;
            int changeViewTagID, sortViewTagID, changeViewPosition, sortViewPosition;
            for (int i = 0; i < reduceChildCount; i++) {
                sortChild = containerLayout.getChildAt(i);
                if (sortChild != changedView) {
                    changeViewTagID = Integer.parseInt(changedView.getTag().toString());
                    sortViewTagID = Integer.parseInt(sortChild.getTag().toString());

                    changeViewPosition = preSortPositionArray.get(changeViewTagID);
                    sortViewPosition = preSortPositionArray.get(sortViewTagID);

                    if (changedView.getTop() > sortChild.getTop() && changeViewPosition < sortViewPosition) {

                        sortChild.setTop(changeViewPosition);
                        sortChild.setBottom(changeViewPosition + SIZE_REDUCE_VIEW);

                        preSortPositionArray.put(sortViewTagID, changeViewPosition);
                        preSortPositionArray.put(changeViewTagID, sortViewPosition);

                        resetChildPostion();
                        break;
                    } else if (changedView.getTop() < sortChild.getTop() && changeViewPosition > sortViewPosition) {

                        sortChild.setTop(changeViewPosition);
                        sortChild.setBottom(changeViewPosition + SIZE_REDUCE_VIEW);

                        preSortPositionArray.put(sortViewTagID, changeViewPosition);
                        preSortPositionArray.put(changeViewTagID, sortViewPosition);
                        resetChildPostion();
                        break;
                    }
                }
            }
        }

        @Override
        public void onViewDragStateChanged(int state) {
            super.onViewDragStateChanged(state);
        }
    }

    public class EditData {
        public String inputStr;
        public String imagePath;
        public Bitmap bitmap;
    }
}