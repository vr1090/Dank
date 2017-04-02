package me.saket.dank.widgets;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.support.v4.view.animation.FastOutSlowInInterpolator;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;

/**
 * A ProgressBar that uses height animation for hiding/showing + animates changes in determinate progress
 * (because {@link ProgressBar#setProgress(int, boolean)} is API 24+ only).
 */
public class SubmissionAnimatedProgressBar extends ProgressBar {

    private ObjectAnimator progressAnimator;
    private boolean visibilityAnimationOngoing;
    private boolean syncScrollEnabled;

    public SubmissionAnimatedProgressBar(Context context, AttributeSet attrs) {
        super(context, attrs);


//        TypedArray attributes = context.obtainStyledAttributes(attrs, R.styleable.SubmissionAnimatedProgressBar);
//        if (attributes.hasValue(R.styleable.SubmissionAnimatedProgressBar_animationPivotY)) {
//            float animationPivot = attributes.getFloat(R.styleable.SubmissionAnimatedProgressBar_animationPivotY, -1);
//        }
//        attributes.recycle();
    }

    /**
     * Tracks <var>sheet</var>'s top offset and keeps this View always on top of it.
     * Since {@link ScrollingRecyclerViewSheet} uses translationY changes to scroll, this
     */
    public void syncPositionWithSheet(ScrollingRecyclerViewSheet sheet) {
        sheet.addOnSheetScrollChangeListener((newTranslationY) -> {
            if (syncScrollEnabled && getTranslationY() != newTranslationY) {
                setTranslationY(newTranslationY);
            }
        });
    }

    /**
     * When disabled, this View stops scrolling with the sheet passed in {@link
     * #syncPositionWithSheet(ScrollingRecyclerViewSheet)} and stays fixed below the toolbar.
     */
    public void setSyncScrollEnabled(boolean enabled) {
        syncScrollEnabled = enabled;
    }

    public void setProgressWithAnimation(int toProgress) {
        cancelProgressAnimation();

        progressAnimator = ObjectAnimator.ofInt(this, "progress", getProgress(), toProgress);
        progressAnimator.setInterpolator(new FastOutSlowInInterpolator());
        progressAnimator.setDuration(400);
        progressAnimator.start();
    }

    private void cancelProgressAnimation() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
    }

    @Override
    public synchronized void setIndeterminate(boolean indeterminate) {
        cancelProgressAnimation();
        super.setIndeterminate(indeterminate);
    }

    @Override
    public void setVisibility(int visibility) {
        setVisible(visibility == VISIBLE);
    }

    public void show() {
        setVisible(true);
    }

    public void hide() {
        setVisible(false);
    }

    private void setVisible(boolean visible) {
        ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) getLayoutParams();
        boolean isTopAligned = marginLayoutParams.topMargin < 0;

        if (getHeight() > 0) {
            // Since we apply negative margins to negate ProgressView's extra vertical spacing,
            // set a pivot that ensures the gravity of the bar while animating in/out.
            float pivotYFactor = (float) Math.abs(isTopAligned ? marginLayoutParams.topMargin : 2 * marginLayoutParams.bottomMargin) / getHeight();
            setPivotY(getHeight() * pivotYFactor);
        }

        animate().cancel();
        animate()
                .scaleY(visible ? 1f : 0f)
                .setStartDelay(visibilityAnimationOngoing ? 100 : 0)
                .setInterpolator(new DecelerateInterpolator())
                .setDuration(400)
                .withStartAction(() -> visibilityAnimationOngoing = true)
                .withEndAction(() -> visibilityAnimationOngoing = false)
                .start();
    }

}