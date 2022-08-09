/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.fuelgauge.batteryusage;

import static com.android.settings.Utils.formatPercentage;

import static java.lang.Math.round;
import static java.util.Objects.requireNonNull;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.widget.AppCompatImageView;

import com.android.settings.R;
import com.android.settings.overlay.FeatureFactory;
import com.android.settingslib.Utils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A widget component to draw chart graph. */
public class BatteryChartViewV2 extends AppCompatImageView implements View.OnClickListener,
        AccessibilityManager.AccessibilityStateChangeListener {
    private static final String TAG = "BatteryChartViewV2";
    private static final List<String> ACCESSIBILITY_SERVICE_NAMES =
            Arrays.asList("SwitchAccessService", "TalkBackService", "JustSpeakService");

    private static final int DIVIDER_COLOR = Color.parseColor("#CDCCC5");
    private static final long UPDATE_STATE_DELAYED_TIME = 500L;
    private static final Map<Integer, Integer[]> MODEL_SIZE_TO_LABEL_INDEXES_MAP =
            buildModelSizeToLabelIndexesMap();

    /** A callback listener for selected group index is updated. */
    public interface OnSelectListener {
        /** The callback function for selected group index is updated. */
        void onSelect(int trapezoidIndex);
    }

    private BatteryChartViewModel mViewModel;

    private int mDividerWidth;
    private int mDividerHeight;
    private float mTrapezoidVOffset;
    private float mTrapezoidHOffset;
    private boolean mIsSlotsClickabled;
    private String[] mPercentages = getPercentages();
    private Integer[] mLabelsIndexes;

    @VisibleForTesting
    int mHoveredIndex = BatteryChartViewModel.SELECTED_INDEX_INVALID;

    // Colors for drawing the trapezoid shape and dividers.
    private int mTrapezoidColor;
    private int mTrapezoidSolidColor;
    private int mTrapezoidHoverColor;
    // For drawing the percentage information.
    private int mTextPadding;
    private final Rect mIndent = new Rect();
    private final Rect[] mPercentageBounds =
            new Rect[]{new Rect(), new Rect(), new Rect()};
    // For drawing the axis label information.
    private final Rect[] mAxisLabelsBounds = initializeAxisLabelsBounds();

    @VisibleForTesting
    Handler mHandler = new Handler();
    @VisibleForTesting
    final Runnable mUpdateClickableStateRun = () -> updateClickableState();

    private Paint mTextPaint;
    private Paint mDividerPaint;
    private Paint mTrapezoidPaint;

    @VisibleForTesting
    Paint mTrapezoidCurvePaint = null;
    @VisibleForTesting
    TrapezoidSlot[] mTrapezoidSlots;
    // Records the location to calculate selected index.
    @VisibleForTesting
    float mTouchUpEventX = Float.MIN_VALUE;
    private BatteryChartViewV2.OnSelectListener mOnSelectListener;

    public BatteryChartViewV2(Context context) {
        super(context, null);
    }

    public BatteryChartViewV2(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeColors(context);
        // Registers the click event listener.
        setOnClickListener(this);
        setClickable(false);
        requestLayout();
    }

    /** Sets the data model of this view. */
    public void setViewModel(BatteryChartViewModel viewModel) {
        if (viewModel == null) {
            mViewModel = null;
            invalidate();
            return;
        }

        Log.d(TAG, String.format("setViewModel(): size: %d, selectedIndex: %d.",
                viewModel.size(), viewModel.selectedIndex()));
        mViewModel = viewModel;
        mLabelsIndexes = MODEL_SIZE_TO_LABEL_INDEXES_MAP.get(mViewModel.size());
        initializeTrapezoidSlots(viewModel.size() - 1);
        setClickable(hasAnyValidTrapezoid(viewModel));
        requestLayout();
    }

    /** Sets the callback to monitor the selected group index. */
    public void setOnSelectListener(BatteryChartViewV2.OnSelectListener listener) {
        mOnSelectListener = listener;
    }

    /** Sets the companion {@link TextView} for percentage information. */
    public void setCompanionTextView(TextView textView) {
        if (textView != null) {
            // Pre-draws the view first to load style atttributions into paint.
            textView.draw(new Canvas());
            mTextPaint = textView.getPaint();
        } else {
            mTextPaint = null;
        }
        requestLayout();
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Measures text bounds and updates indent configuration.
        if (mTextPaint != null) {
            for (int index = 0; index < mPercentages.length; index++) {
                mTextPaint.getTextBounds(
                        mPercentages[index], 0, mPercentages[index].length(),
                        mPercentageBounds[index]);
            }
            // Updates the indent configurations.
            mIndent.top = mPercentageBounds[0].height();
            mIndent.right = mPercentageBounds[0].width() + mTextPadding;

            if (mViewModel != null) {
                int maxHeight = 0;
                for (int index = 0; index < mLabelsIndexes.length; index++) {
                    final String text = getAxisLabelText(index);
                    mTextPaint.getTextBounds(text, 0, text.length(), mAxisLabelsBounds[index]);
                    maxHeight = Math.max(maxHeight, mAxisLabelsBounds[index].height());
                }
                mIndent.bottom = maxHeight + round(mTextPadding * 1.5f);
            }
            Log.d(TAG, "setIndent:" + mPercentageBounds[0]);
        } else {
            mIndent.set(0, 0, 0, 0);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        // Before mLevels initialized, the count of trapezoids is unknown. Only draws the
        // horizontal percentages and dividers.
        drawHorizontalDividers(canvas);
        if (mViewModel == null) {
            return;
        }
        drawVerticalDividers(canvas);
        drawTrapezoids(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Caches the location to calculate selected trapezoid index.
        final int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_UP:
                mTouchUpEventX = event.getX();
                break;
            case MotionEvent.ACTION_CANCEL:
                mTouchUpEventX = Float.MIN_VALUE; // reset
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        final int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                final int trapezoidIndex = getTrapezoidIndex(event.getX());
                if (mHoveredIndex != trapezoidIndex) {
                    mHoveredIndex = trapezoidIndex;
                    invalidate();
                }
                break;
        }
        return super.onHoverEvent(event);
    }

    @Override
    public void onHoverChanged(boolean hovered) {
        super.onHoverChanged(hovered);
        if (!hovered) {
            mHoveredIndex = BatteryChartViewModel.SELECTED_INDEX_INVALID; // reset
            invalidate();
        }
    }

    @Override
    public void onClick(View view) {
        if (mTouchUpEventX == Float.MIN_VALUE) {
            Log.w(TAG, "invalid motion event for onClick() callback");
            return;
        }
        final int trapezoidIndex = getTrapezoidIndex(mTouchUpEventX);
        // Ignores the click event if the level is zero.
        if (trapezoidIndex == BatteryChartViewModel.SELECTED_INDEX_INVALID
                || !isValidToDraw(mViewModel, trapezoidIndex)) {
            return;
        }
        if (mOnSelectListener != null) {
            // Selects all if users click the same trapezoid item two times.
            mOnSelectListener.onSelect(
                    trapezoidIndex == mViewModel.selectedIndex()
                            ? BatteryChartViewModel.SELECTED_INDEX_ALL : trapezoidIndex);
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateClickableState();
        mContext.getSystemService(AccessibilityManager.class)
                .addAccessibilityStateChangeListener(/*listener=*/ this);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.getSystemService(AccessibilityManager.class)
                .removeAccessibilityStateChangeListener(/*listener=*/ this);
        mHandler.removeCallbacks(mUpdateClickableStateRun);
    }

    @Override
    public void onAccessibilityStateChanged(boolean enabled) {
        Log.d(TAG, "onAccessibilityStateChanged:" + enabled);
        mHandler.removeCallbacks(mUpdateClickableStateRun);
        // We should delay it a while since accessibility manager will spend
        // some times to bind with new enabled accessibility services.
        mHandler.postDelayed(
                mUpdateClickableStateRun, UPDATE_STATE_DELAYED_TIME);
    }

    private void updateClickableState() {
        final Context context = mContext;
        mIsSlotsClickabled =
                FeatureFactory.getFactory(context)
                        .getPowerUsageFeatureProvider(context)
                        .isChartGraphSlotsEnabled(context)
                        && !isAccessibilityEnabled(context);
        Log.d(TAG, "isChartGraphSlotsEnabled:" + mIsSlotsClickabled);
        setClickable(isClickable());
        // Initializes the trapezoid curve paint for non-clickable case.
        if (!mIsSlotsClickabled && mTrapezoidCurvePaint == null) {
            mTrapezoidCurvePaint = new Paint();
            mTrapezoidCurvePaint.setAntiAlias(true);
            mTrapezoidCurvePaint.setColor(mTrapezoidSolidColor);
            mTrapezoidCurvePaint.setStyle(Paint.Style.STROKE);
            mTrapezoidCurvePaint.setStrokeWidth(mDividerWidth * 2);
        } else if (mIsSlotsClickabled) {
            mTrapezoidCurvePaint = null;
            // Sets view model again to force update the click state.
            setViewModel(mViewModel);
        }
        invalidate();
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(mIsSlotsClickabled && clickable);
    }

    @VisibleForTesting
    void setClickableForce(boolean clickable) {
        super.setClickable(clickable);
    }

    private void initializeTrapezoidSlots(int count) {
        mTrapezoidSlots = new TrapezoidSlot[count];
        for (int index = 0; index < mTrapezoidSlots.length; index++) {
            mTrapezoidSlots[index] = new TrapezoidSlot();
        }
    }

    private void initializeColors(Context context) {
        setBackgroundColor(Color.TRANSPARENT);
        mTrapezoidSolidColor = Utils.getColorAccentDefaultColor(context);
        mTrapezoidColor = Utils.getDisabled(context, mTrapezoidSolidColor);
        mTrapezoidHoverColor = Utils.getColorAttrDefaultColor(context,
                com.android.internal.R.attr.colorAccentSecondaryVariant);
        // Initializes the divider line paint.
        final Resources resources = getContext().getResources();
        mDividerWidth = resources.getDimensionPixelSize(R.dimen.chartview_divider_width);
        mDividerHeight = resources.getDimensionPixelSize(R.dimen.chartview_divider_height);
        mDividerPaint = new Paint();
        mDividerPaint.setAntiAlias(true);
        mDividerPaint.setColor(DIVIDER_COLOR);
        mDividerPaint.setStyle(Paint.Style.STROKE);
        mDividerPaint.setStrokeWidth(mDividerWidth);
        Log.i(TAG, "mDividerWidth:" + mDividerWidth);
        Log.i(TAG, "mDividerHeight:" + mDividerHeight);
        // Initializes the trapezoid paint.
        mTrapezoidHOffset = resources.getDimension(R.dimen.chartview_trapezoid_margin_start);
        mTrapezoidVOffset = resources.getDimension(R.dimen.chartview_trapezoid_margin_bottom);
        mTrapezoidPaint = new Paint();
        mTrapezoidPaint.setAntiAlias(true);
        mTrapezoidPaint.setColor(mTrapezoidSolidColor);
        mTrapezoidPaint.setStyle(Paint.Style.FILL);
        mTrapezoidPaint.setPathEffect(
                new CornerPathEffect(
                        resources.getDimensionPixelSize(R.dimen.chartview_trapezoid_radius)));
        // Initializes for drawing text information.
        mTextPadding = resources.getDimensionPixelSize(R.dimen.chartview_text_padding);
    }

    private void drawHorizontalDividers(Canvas canvas) {
        final int width = getWidth() - mIndent.right;
        final int height = getHeight() - mIndent.top - mIndent.bottom;
        // Draws the top divider line for 100% curve.
        float offsetY = mIndent.top + mDividerWidth * .5f;
        canvas.drawLine(0, offsetY, width, offsetY, mDividerPaint);
        drawPercentage(canvas, /*index=*/ 0, offsetY);

        // Draws the center divider line for 50% curve.
        final float availableSpace =
                height - mDividerWidth * 2 - mTrapezoidVOffset - mDividerHeight;
        offsetY = mIndent.top + mDividerWidth + availableSpace * .5f;
        canvas.drawLine(0, offsetY, width, offsetY, mDividerPaint);
        drawPercentage(canvas, /*index=*/ 1, offsetY);

        // Draws the bottom divider line for 0% curve.
        offsetY = mIndent.top + (height - mDividerHeight - mDividerWidth * .5f);
        canvas.drawLine(0, offsetY, width, offsetY, mDividerPaint);
        drawPercentage(canvas, /*index=*/ 2, offsetY);
    }

    private void drawPercentage(Canvas canvas, int index, float offsetY) {
        if (mTextPaint != null) {
            canvas.drawText(
                    mPercentages[index],
                    getWidth() - mPercentageBounds[index].width()
                            - mPercentageBounds[index].left,
                    offsetY + mPercentageBounds[index].height() * .5f,
                    mTextPaint);
        }
    }

    private void drawVerticalDividers(Canvas canvas) {
        final int width = getWidth() - mIndent.right;
        final int dividerCount = mTrapezoidSlots.length + 1;
        final float dividerSpace = dividerCount * mDividerWidth;
        final float unitWidth = (width - dividerSpace) / (float) mTrapezoidSlots.length;
        final float bottomY = getHeight() - mIndent.bottom;
        final float startY = bottomY - mDividerHeight;
        final float trapezoidSlotOffset = mTrapezoidHOffset + mDividerWidth * .5f;
        // Draws each vertical dividers.
        float startX = mDividerWidth * .5f;
        for (int index = 0; index < dividerCount; index++) {
            canvas.drawLine(startX, startY, startX, bottomY, mDividerPaint);
            final float nextX = startX + mDividerWidth + unitWidth;
            // Updates the trapezoid slots for drawing.
            if (index < mTrapezoidSlots.length) {
                mTrapezoidSlots[index].mLeft = round(startX + trapezoidSlotOffset);
                mTrapezoidSlots[index].mRight = round(nextX - trapezoidSlotOffset);
            }
            startX = nextX;
        }
        // Draws the axis label slot information.
        if (mViewModel != null) {
            final float[] xOffsets = new float[mLabelsIndexes.length];
            final float baselineX = mDividerWidth * .5f;
            final float offsetX = mDividerWidth + unitWidth;
            for (int index = 0; index < mLabelsIndexes.length; index++) {
                xOffsets[index] = baselineX + mLabelsIndexes[index] * offsetX;
            }
            switch (mViewModel.axisLabelPosition()) {
                case CENTER_OF_TRAPEZOIDS:
                    drawAxisLabelsCenterOfTrapezoids(canvas, xOffsets, unitWidth);
                    break;
                case BETWEEN_TRAPEZOIDS:
                default:
                    drawAxisLabelsBetweenTrapezoids(canvas, xOffsets);
                    break;
            }
        }
    }

    private void drawAxisLabelsBetweenTrapezoids(Canvas canvas, float[] xOffsets) {
        // Draws the 1st axis label info.
        canvas.drawText(
                getAxisLabelText(0), xOffsets[0] - mAxisLabelsBounds[0].left, getAxisLabelY(0),
                mTextPaint);
        final int latestIndex = mLabelsIndexes.length - 1;
        // Draws the last axis label info.
        canvas.drawText(
                getAxisLabelText(latestIndex),
                xOffsets[latestIndex]
                        - mAxisLabelsBounds[latestIndex].width()
                        - mAxisLabelsBounds[latestIndex].left,
                getAxisLabelY(latestIndex),
                mTextPaint);
        // Draws the rest of axis label info since it is located in the center.
        for (int index = 1; index <= mLabelsIndexes.length - 2; index++) {
            canvas.drawText(
                    getAxisLabelText(index),
                    xOffsets[index]
                            - (mAxisLabelsBounds[index].width() - mAxisLabelsBounds[index].left)
                            * .5f,
                    getAxisLabelY(index),
                    mTextPaint);
        }
    }

    private void drawAxisLabelsCenterOfTrapezoids(
            Canvas canvas, float[] xOffsets, float unitWidth) {
        for (int index = 0; index < mLabelsIndexes.length - 1; index++) {
            canvas.drawText(
                    getAxisLabelText(index),
                    xOffsets[index] + (unitWidth - (mAxisLabelsBounds[index].width()
                            - mAxisLabelsBounds[index].left)) * .5f,
                    getAxisLabelY(index),
                    mTextPaint);
        }
    }

    private int getAxisLabelY(int index) {
        return getHeight()
                - mAxisLabelsBounds[index].height()
                + (mAxisLabelsBounds[index].height() + mAxisLabelsBounds[index].top)
                + round(mTextPadding * 1.5f);
    }

    private void drawTrapezoids(Canvas canvas) {
        // Ignores invalid trapezoid data.
        if (mViewModel == null) {
            return;
        }
        final float trapezoidBottom =
                getHeight() - mIndent.bottom - mDividerHeight - mDividerWidth
                        - mTrapezoidVOffset;
        final float availableSpace = trapezoidBottom - mDividerWidth * .5f - mIndent.top;
        final float unitHeight = availableSpace / 100f;
        // Draws all trapezoid shapes into the canvas.
        final Path trapezoidPath = new Path();
        Path trapezoidCurvePath = null;
        for (int index = 0; index < mTrapezoidSlots.length; index++) {
            // Not draws the trapezoid for corner or not initialization cases.
            if (!isValidToDraw(mViewModel, index)) {
                if (mTrapezoidCurvePaint != null && trapezoidCurvePath != null) {
                    canvas.drawPath(trapezoidCurvePath, mTrapezoidCurvePaint);
                    trapezoidCurvePath = null;
                }
                continue;
            }
            // Configures the trapezoid paint color.
            final int trapezoidColor = mIsSlotsClickabled && (mViewModel.selectedIndex() == index
                    || mViewModel.selectedIndex() == BatteryChartViewModel.SELECTED_INDEX_ALL)
                    ? mTrapezoidSolidColor : mTrapezoidColor;
            final boolean isHoverState =
                    mIsSlotsClickabled && mHoveredIndex == index
                            && isValidToDraw(mViewModel, mHoveredIndex);
            mTrapezoidPaint.setColor(isHoverState ? mTrapezoidHoverColor : trapezoidColor);

            final float leftTop = round(
                    trapezoidBottom - requireNonNull(mViewModel.levels().get(index)) * unitHeight);
            final float rightTop = round(trapezoidBottom
                    - requireNonNull(mViewModel.levels().get(index + 1)) * unitHeight);
            trapezoidPath.reset();
            trapezoidPath.moveTo(mTrapezoidSlots[index].mLeft, trapezoidBottom);
            trapezoidPath.lineTo(mTrapezoidSlots[index].mLeft, leftTop);
            trapezoidPath.lineTo(mTrapezoidSlots[index].mRight, rightTop);
            trapezoidPath.lineTo(mTrapezoidSlots[index].mRight, trapezoidBottom);
            // A tricky way to make the trapezoid shape drawing the rounded corner.
            trapezoidPath.lineTo(mTrapezoidSlots[index].mLeft, trapezoidBottom);
            trapezoidPath.lineTo(mTrapezoidSlots[index].mLeft, leftTop);
            // Draws the trapezoid shape into canvas.
            canvas.drawPath(trapezoidPath, mTrapezoidPaint);

            // Generates path for non-clickable trapezoid curve.
            if (mTrapezoidCurvePaint != null) {
                if (trapezoidCurvePath == null) {
                    trapezoidCurvePath = new Path();
                    trapezoidCurvePath.moveTo(mTrapezoidSlots[index].mLeft, leftTop);
                } else {
                    trapezoidCurvePath.lineTo(mTrapezoidSlots[index].mLeft, leftTop);
                }
                trapezoidCurvePath.lineTo(mTrapezoidSlots[index].mRight, rightTop);
            }
        }
        // Draws the trapezoid curve for non-clickable case.
        if (mTrapezoidCurvePaint != null && trapezoidCurvePath != null) {
            canvas.drawPath(trapezoidCurvePath, mTrapezoidCurvePaint);
            trapezoidCurvePath = null;
        }
    }

    // Searches the corresponding trapezoid index from x location.
    private int getTrapezoidIndex(float x) {
        for (int index = 0; index < mTrapezoidSlots.length; index++) {
            final TrapezoidSlot slot = mTrapezoidSlots[index];
            if (x >= slot.mLeft - mTrapezoidHOffset
                    && x <= slot.mRight + mTrapezoidHOffset) {
                return index;
            }
        }
        return BatteryChartViewModel.SELECTED_INDEX_INVALID;
    }

    private String getAxisLabelText(int labelIndex) {
        return mViewModel.texts().get(mLabelsIndexes[labelIndex]);
    }

    private static boolean isTrapezoidValid(
            @NonNull BatteryChartViewModel viewModel, int trapezoidIndex) {
        return viewModel.levels().get(trapezoidIndex) != null
                && viewModel.levels().get(trapezoidIndex + 1) != null;
    }

    private static boolean isValidToDraw(BatteryChartViewModel viewModel, int trapezoidIndex) {
        return viewModel != null
                && trapezoidIndex >= 0
                && trapezoidIndex < viewModel.size() - 1
                && isTrapezoidValid(viewModel, trapezoidIndex);
    }

    private static boolean hasAnyValidTrapezoid(@NonNull BatteryChartViewModel viewModel) {
        // Sets the chart is clickable if there is at least one valid item in it.
        for (int trapezoidIndex = 0; trapezoidIndex < viewModel.size() - 1; trapezoidIndex++) {
            if (isTrapezoidValid(viewModel, trapezoidIndex)) {
                return true;
            }
        }
        return false;
    }

    private static String[] getPercentages() {
        return new String[]{
                formatPercentage(/*percentage=*/ 100, /*round=*/ true),
                formatPercentage(/*percentage=*/ 50, /*round=*/ true),
                formatPercentage(/*percentage=*/ 0, /*round=*/ true)};
    }

    @VisibleForTesting
    static boolean isAccessibilityEnabled(Context context) {
        final AccessibilityManager accessibilityManager =
                context.getSystemService(AccessibilityManager.class);
        if (!accessibilityManager.isEnabled()) {
            return false;
        }
        final List<AccessibilityServiceInfo> serviceInfoList =
                accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_SPOKEN
                                | AccessibilityServiceInfo.FEEDBACK_GENERIC);
        for (AccessibilityServiceInfo info : serviceInfoList) {
            for (String serviceName : ACCESSIBILITY_SERVICE_NAMES) {
                final String serviceId = info.getId();
                if (serviceId != null && serviceId.contains(serviceName)) {
                    Log.d(TAG, "acccessibilityEnabled:" + serviceId);
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<Integer, Integer[]> buildModelSizeToLabelIndexesMap() {
        final Map<Integer, Integer[]> result = new HashMap<>();
        result.put(2, new Integer[]{0, 1});
        result.put(3, new Integer[]{0, 1, 2});
        result.put(4, new Integer[]{0, 1, 2, 3});
        result.put(5, new Integer[]{0, 1, 2, 3, 4});
        result.put(6, new Integer[]{0, 1, 2, 3, 4, 5});
        result.put(7, new Integer[]{0, 1, 2, 3, 4, 5, 6});
        result.put(8, new Integer[]{0, 1, 2, 3, 4, 5, 6, 7});
        result.put(9, new Integer[]{0, 2, 4, 6, 8});
        result.put(10, new Integer[]{0, 3, 6, 9});
        result.put(11, new Integer[]{0, 5, 10});
        result.put(12, new Integer[]{0, 4, 7, 11});
        result.put(13, new Integer[]{0, 4, 8, 12});
        return result;
    }

    private static Rect[] initializeAxisLabelsBounds() {
        final int maxLabelsLength = MODEL_SIZE_TO_LABEL_INDEXES_MAP.values().stream().max(
                Comparator.comparingInt(indexes -> indexes.length)).get().length;
        final Rect[] bounds = new Rect[maxLabelsLength];
        for (int i = 0; i < maxLabelsLength; i++) {
            bounds[i] = new Rect();
        }
        return bounds;
    }

    // A container class for each trapezoid left and right location.
    @VisibleForTesting
    static final class TrapezoidSlot {
        public float mLeft;
        public float mRight;

        @Override
        public String toString() {
            return String.format(Locale.US, "TrapezoidSlot[%f,%f]", mLeft, mRight);
        }
    }
}
