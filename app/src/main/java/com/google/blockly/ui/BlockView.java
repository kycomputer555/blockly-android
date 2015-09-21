/*
* Copyright  2015 Google Inc. All Rights Reserved.
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.google.blockly.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Log;
import android.widget.FrameLayout;

import com.google.blockly.R;
import com.google.blockly.model.Block;
import com.google.blockly.model.Input;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a block and handles laying out all its inputs/fields.
 */
public class BlockView extends FrameLayout {
    private static final String TAG = "BlockView";

    // TODO: Replace these with dimens so they get scaled correctly
    // Minimum height of a block should be the same as an empty field.
    private static final int MIN_HEIGHT = InputView.MIN_HEIGHT;
    // Minimum width of a block should be the same as an empty field.
    private static final int MIN_WIDTH = InputView.MIN_WIDTH;

    // Color of block outline.
    private static final int OUTLINE_COLOR = Color.BLACK;

    private final WorkspaceHelper mHelper;
    private final Block mBlock;

    // Objects for drawing the block.
    private final Path mDrawPath = new Path();
    private final Paint mPaintArea = new Paint();
    private final Paint mPaintBorder = new Paint();
    private final ArrayList<InputView> mInputViews = new ArrayList<>();

    // Current measured size of this block view.
    private final ViewPoint mBlockViewSize = new ViewPoint();

    // Layout coordinates for inputs in this Block, so they don't have to be computed repeatedly.
    private ArrayList<ViewPoint> mInputLayoutOrigins = new ArrayList<>();
    private BlockWorkspaceParams mWorkspaceParams;

    // Offset of the block origin inside the view's measured area.
    private int mLayoutMarginLeft;
    private int mMaxInputFieldsWidth;
    private int mMaxStatementFieldsWidth;

    // Vertical offset for positioning the "Next" block (if one exists).
    private int mNextBlockVerticalOffset;

    // Width of the core "block", ie, rectangle box without connectors or inputs.
    private int mBlockWidth;

    /**
     * Create a new BlockView for the given block using the workspace's style.
     *
     * @param context The context for creating this view.
     * @param block   The block represented by this view.
     * @param helper  The helper for loading workspace configs and doing calculations.
     */
    public BlockView(Context context, Block block, WorkspaceHelper helper) {
        this(context, 0 /* default style */, block, helper);
    }

    /**
     * Create a new BlockView for the given block using the specified style. The style must extend
     * {@link R.style#DefaultBlockStyle}.
     *
     * @param context    The context for creating this view.
     * @param blockStyle The resource id for the style to use on this view.
     * @param block      The block represented by this view.
     * @param helper     The helper for loading workspace configs and doing calculations.
     */
    public BlockView(Context context, int blockStyle, Block block, WorkspaceHelper helper) {
        super(context, null, 0);

        mBlock = block;
        mHelper = helper;
        mWorkspaceParams = new BlockWorkspaceParams(mBlock, mHelper);

        setWillNotDraw(false);

        initViews(context, blockStyle);
        initDrawingObjects(context);
    }

    /**
     * @return The {@link InputView} for the {@link Input} at the given index.
     */
    public InputView getInputView(int index) {
        return mInputViews.get(index);
    }

    @Override
    public void onDraw(Canvas c) {
        c.drawPath(mDrawPath, mPaintArea);
        c.drawPath(mDrawPath, mPaintBorder);
    }

    /**
     * Measure all children (i.e., block inputs) and compute their sizes and relative positions
     * for use in {@link #onLayout}.
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        adjustInputLayoutOriginsListSize();

        if (getBlock().getInputsInline()) {
            onMeasureInlineInputs(widthMeasureSpec, heightMeasureSpec);
        } else {
            onMeasureExternalInputs(widthMeasureSpec, heightMeasureSpec);
        }

        mNextBlockVerticalOffset = mBlockViewSize.y;
        if (mBlock.getNextConnection() != null) {
            mBlockViewSize.y += ConnectorHelper.SIZE_PERPENDICULAR;
        }

        if (mBlock.getOutputConnection() != null) {
            mLayoutMarginLeft = ConnectorHelper.SIZE_PERPENDICULAR;
            mBlockViewSize.x += mLayoutMarginLeft;
        } else {
            mLayoutMarginLeft = 0;
        }

        setMeasuredDimension(mBlockViewSize.x, mBlockViewSize.y);
        mWorkspaceParams.setMeasuredDimensions(mBlockViewSize);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Note that layout must be done regardless of the value of the "changed" parameter.
        int xLeft = mLayoutMarginLeft;
        for (int i = 0; i < mInputViews.size(); i++) {
            int rowLeft = xLeft + mInputLayoutOrigins.get(i).x;
            int rowTop = mInputLayoutOrigins.get(i).y;
            InputView inputView = mInputViews.get(i);
            inputView.layout(rowLeft, rowTop, rowLeft + inputView.getMeasuredWidth(),
                    rowTop + inputView.getMeasuredHeight());
        }

        updateDrawPath();
    }

    /**
     * @return The block for this view.
     */
    public Block getBlock() {
        return mBlock;
    }

    /**
     * Measure view and its children with inline inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block.</li>
     * </ol>
     * </p>
     */
    private void onMeasureInlineInputs(int widthMeasureSpec, int heightMeasureSpec) {
        // First pass - measure all fields and inputs; compute maximum width of fields over all
        // Statement inputs.
        mMaxStatementFieldsWidth = 0;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                mMaxStatementFieldsWidth =
                        Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());

            }
        }

        // Second pass - compute layout positions and sizes of all inputs.
        int rowLeft = 0;
        int rowTop = 0;

        int rowHeight = 0;
        int maxRowWidth = 0;

        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);

            // If this is a Statement input, force its field width to be the maximum over all
            // Statements, and begin a new layout row.
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);

                // New row BEFORE each Statement input.
                rowTop += rowHeight;
                rowHeight = 0;
                rowLeft = 0;
            }

            mInputLayoutOrigins.get(i).set(rowLeft, rowTop);

            // Measure input view and update row height and width accordingly.
            inputView.measure(widthMeasureSpec, heightMeasureSpec);
            rowHeight = Math.max(rowHeight, inputView.getMeasuredHeight());
            rowLeft += inputView.getMeasuredWidth();

            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // The block width is that of the widest row, but for a Statement input there needs
                // to be added space for the connector.
                maxRowWidth = Math.max(
                        maxRowWidth, rowLeft + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH);

                // New row AFTER each Statement input.
                rowTop += rowHeight;
                rowLeft = 0;
                rowHeight = 0;
            } else {
                // For Dummy and Value inputs, block width is that of the widest row
                maxRowWidth = Math.max(maxRowWidth, rowLeft);
            }
        }

        // Add height of final row. This is non-zero with inline inputs if the final input in the
        // block is not a Statement input.
        rowTop += rowHeight;

        // Block width is the computed width of the widest input row, and at least MIN_WIDTH.
        mBlockViewSize.x = Math.max(MIN_WIDTH, maxRowWidth);
        mBlockWidth = mBlockViewSize.x;

        // Height is vertical position of next (non-existent) inputs row, and at least MIN_HEIGHT.
        mBlockViewSize.y = Math.max(MIN_HEIGHT, rowTop);
    }

    /**
     * Measure view and its children with external inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block (but note that for external inputs, only the y coordinate of each
     * position is later used for positioning.)</li>
     * </ol>
     * </p>
     */
    private void onMeasureExternalInputs(int widthMeasureSpec, int heightMeasureSpec) {
        mMaxInputFieldsWidth = MIN_WIDTH;
        mMaxStatementFieldsWidth = MIN_WIDTH;

        int maxInputChildWidth = 0;
        int maxStatementChildWidth = 0;

        // First pass - measure fields and children of all inputs.
        boolean hasValueInput = false;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);

            switch (inputView.getInput().getType()) {
                case Input.TYPE_VALUE: {
                    hasValueInput = true;
                    // fall through
                }
                default:
                case Input.TYPE_DUMMY: {
                    mMaxInputFieldsWidth =
                            Math.max(mMaxInputFieldsWidth, inputView.getTotalFieldWidth());
                    maxInputChildWidth =
                            Math.max(maxInputChildWidth, inputView.getTotalChildWidth());
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    mMaxStatementFieldsWidth =
                            Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());
                    maxStatementChildWidth =
                            Math.max(maxInputChildWidth, inputView.getTotalChildWidth());
                    break;
                }
            }
        }

        // If there was a statement, force all other input fields to be at least as wide as required
        // by the Statement field plus port width.
        if (mMaxStatementFieldsWidth > 0) {
            mMaxInputFieldsWidth = Math.max(mMaxInputFieldsWidth,
                    mMaxStatementFieldsWidth + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH);
        }

        // Second pass - force all inputs to render fields with the same width and compute positions
        // for all inputs.
        int rowTop = 0;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);
            } else {
                inputView.setFieldLayoutWidth(mMaxInputFieldsWidth);
            }
            inputView.measure(widthMeasureSpec, heightMeasureSpec);

            mInputLayoutOrigins.get(i).set(0, rowTop);

            // The block height is the sum of all the row heights.
            rowTop += inputView.getMeasuredHeight();
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                rowTop += ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT;
            }
        }

        // Block width is the width of the longest row. Add space for connector if there is at least
        // one Value input.
        mBlockWidth = Math.max(mMaxInputFieldsWidth, mMaxStatementFieldsWidth);
        if (hasValueInput) {
            mBlockWidth += ConnectorHelper.SIZE_PERPENDICULAR;
        }

        // The width of the block view is the width of the block plus the maximum width of any of
        // its children. If there are no children, make sure view is at least as wide as the Block,
        // which accounts for width of unconnected input puts.
        mBlockViewSize.x = Math.max(mBlockWidth,
                Math.max(mMaxInputFieldsWidth + maxInputChildWidth,
                         mMaxStatementFieldsWidth + maxStatementChildWidth));
        mBlockViewSize.y = Math.max(MIN_HEIGHT, rowTop);
    }

    /**
     * A block is responsible for initializing all of its fields. Sub-blocks must be added
     * elsewhere.
     */
    private void initViews(Context context, int blockStyle) {
        List<Input> inputs = mBlock.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            InputView inputView = new InputView(context, blockStyle, inputs.get(i), mHelper);
            mInputViews.add(inputView);
            addView(inputView);
        }
    }

    private void initDrawingObjects(Context context) {
        mPaintArea.setColor(mBlock.getColour());
        mPaintArea.setStyle(Paint.Style.FILL);
        mPaintArea.setStrokeJoin(Paint.Join.ROUND);

        mPaintBorder.setColor(OUTLINE_COLOR);
        mPaintBorder.setStyle(Paint.Style.STROKE);
        mPaintBorder.setStrokeWidth(1);
        mPaintBorder.setStrokeJoin(Paint.Join.ROUND);

        mDrawPath.setFillType(Path.FillType.EVEN_ODD);
    }

    /**
     * Adjust size of {@link #mInputLayoutOrigins} list to match the size of {@link #mInputViews}.
     */
    private void adjustInputLayoutOriginsListSize() {
        if (mInputLayoutOrigins.size() != mInputViews.size()) {
            mInputLayoutOrigins.ensureCapacity(mInputViews.size());
            if (mInputLayoutOrigins.size() < mInputViews.size()) {
                for (int i = mInputLayoutOrigins.size(); i < mInputViews.size(); i++) {
                    mInputLayoutOrigins.add(new ViewPoint());
                }
            } else {
                while (mInputLayoutOrigins.size() > mInputViews.size()) {
                    mInputLayoutOrigins.remove(mInputLayoutOrigins.size() - 1);
                }
            }
        }
    }

    /**
     * @return Vertical offset for positioning the "Next" block (if one exists). This is relative to
     * the top of this view's area.
     */
    int getNextBlockVerticalOffset() {
        return mNextBlockVerticalOffset;
    }

    /** Update path for drawing the block after view size or layout have changed. */
    private void updateDrawPath() {
        // TODO(rohlfingt): refactor path drawing code to be more readable. (Will likely be
        // superseded by TODO: implement pretty block rendering.)
        mDrawPath.reset();

        int xLeft = mLayoutMarginLeft;
        int xRight = mBlockWidth + mLayoutMarginLeft;

        int yTop = 0;
        int yBottom = mNextBlockVerticalOffset;

        // Top of the block, including "Previous" connector.
        mDrawPath.moveTo(xLeft, yTop);
        if (mBlock.getPreviousConnection() != null) {
            ConnectorHelper.addPreviousConnectorToPath(mDrawPath, xLeft, yTop);
        }
        mDrawPath.lineTo(xRight, yTop);

        // Right-hand side of the block, including "Input" connectors.
        // TODO(rohlfingt): draw this on the opposite side in RTL mode.
        for (int i = 0; i < mInputViews.size(); ++i) {
            InputView inputView = mInputViews.get(i);
            ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY: {
                    break;
                }
                case Input.TYPE_VALUE: {
                    if (!getBlock().getInputsInline()) {
                        ConnectorHelper.addValueInputConnectorToPath(
                                mDrawPath, xRight, inputLayoutOrigin.y);
                    }
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    int xOffset = xLeft + inputView.getFieldLayoutWidth();
                    int connectorHeight = inputView.getTotalChildHeight();
                    ConnectorHelper.addStatementInputConnectorToPath(
                            mDrawPath, xRight, inputLayoutOrigin.y, xOffset, connectorHeight);
                    break;
                }
            }
        }
        mDrawPath.lineTo(xRight, yBottom);

        // Bottom of the block, including "Next" connector.
        if (mBlock.getNextConnection() != null) {
            ConnectorHelper.addNextConnectorToPath(mDrawPath, xLeft, yBottom);
        }
        mDrawPath.lineTo(xLeft, yBottom);

        // Left-hand side of the block, including "Output" connector.
        if (mBlock.getOutputConnection() != null) {
            ConnectorHelper.addOutputConnectorToPath(mDrawPath, xLeft, yTop);
        }
        mDrawPath.lineTo(xLeft, yTop);
        // Draw an additional line segment over again to get a final rounded corner.
        mDrawPath.lineTo(xLeft + ConnectorHelper.OFFSET_FROM_CORNER, yTop);

        // Add cutout paths for "holes" from open inline Value inputs.
        if (getBlock().getInputsInline()) {
            for (int i = 0; i < mInputViews.size(); ++i) {
                InputView inputView = mInputViews.get(i);
                if (inputView.getInput().getType() == Input.TYPE_VALUE) {
                    ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
                    inputView.addInlineCutoutToBlockViewPath(mDrawPath,
                            xLeft + inputLayoutOrigin.x, inputLayoutOrigin.y);
                }
            }
        }

        mDrawPath.close();
    }
}
